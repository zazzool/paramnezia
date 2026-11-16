package net.paramnezia.core

import net.paramnezia.core.config.PathMtu
import net.paramnezia.core.config.WgQuick
import net.paramnezia.core.crypto.WgKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PathMtuTest {
    private fun conf(s4: Int? = 12, mtu: Int? = null, endpoint: String = "198.51.100.7:41000", address: String = "10.8.1.7/32") = WgQuick.parse(
        """
        [Interface]
        PrivateKey = ${WgKey.generatePrivate().toBase64()}
        Address = $address
        ${mtu?.let { "MTU = $it" } ?: ""}
        Jc = 4
        ${s4?.let { "S4 = $it" } ?: ""}
        [Peer]
        PublicKey = ${WgKey.generatePrivate().publicKey().toBase64()}
        AllowedIPs = 0.0.0.0/0
        Endpoint = $endpoint
        """.trimIndent(),
    )

    @Test
    fun candidatesFollowTheOverhead() {
        // AmneziaWG 3 (S4 = 12) over IPv4: 1500 - 20 - 8 - 32 - 12 = 1428, capped at WireGuard's 1420.
        assertEquals(listOf(1420, 1400, 1360, 1320), PathMtu.candidates(conf()))
        // A large transport padding (AmneziaWG 2) leaves less room.
        assertEquals(listOf(1380, 1360, 1320), PathMtu.candidates(conf(s4 = 60)))
        // An IPv6 endpoint costs 20 bytes more.
        assertEquals(listOf(1400, 1360, 1320), PathMtu.candidates(conf(s4 = 20, endpoint = "[2001:db8::7]:41000")))
        assertEquals(listOf(1420, 1400, 1360, 1320), PathMtu.candidates(conf(s4 = null)))
    }

    @Test
    fun probesTheServerAtTheFirstHost() {
        assertEquals("10.8.1.7" to "10.8.1.1", PathMtu.addresses(conf()))
        assertEquals("10.8.1.7 10.8.1.1 1420,1400,1360,1320", PathMtu.probeSpec(conf()))
        assertNull(PathMtu.addresses(conf(address = "fd00::7/128")))
    }

    @Test
    fun choosesAndLearns() {
        // Nothing known: the config's own, at least the safe minimum.
        assertEquals(1280, PathMtu.choose(conf(), null))
        assertEquals(1376, PathMtu.choose(conf(mtu = 1376), null))
        assertEquals(1420, PathMtu.choose(conf(), 1420))
        // A probe that found something wins; none back keeps what the config came with.
        assertEquals(1360, PathMtu.fromProbe(conf(), 1360))
        assertEquals(1280, PathMtu.fromProbe(conf(), 0))
        assertEquals(1420, PathMtu.fromProbe(conf(mtu = 1420), 0))
        assertEquals(1360, PathMtu.fromProbe(conf(mtu = 1420), 1360))
    }
}
