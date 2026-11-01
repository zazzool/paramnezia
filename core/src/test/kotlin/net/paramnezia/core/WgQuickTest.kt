package net.paramnezia.core

import net.paramnezia.core.config.AwgParam
import net.paramnezia.core.config.Uapi
import net.paramnezia.core.config.WgQuick
import net.paramnezia.core.crypto.WgKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class WgQuickTest {
    private val priv = WgKey.generatePrivate()
    private val serverPub = WgKey.generatePrivate().publicKey()
    private val psk = WgKey.generatePreshared()
    private val hpk = WgKey.generatePrivate()

    /** Shape of the client config AmneziaVPN generates from server_scripts/awg/template.conf. */
    private val amneziaConf = """
        [Interface]
        Address = 10.8.1.2/32
        DNS = 1.1.1.1, 1.0.0.1
        PrivateKey = ${priv.toBase64()}
        Jc = 5
        Jmin = 10
        Jmax = 50
        S1 = 12
        S2 = 12
        S3 = 12
        S4 = 12
        H1 = 1
        H2 = 2
        H3 = 3
        H4 = 4
        I1 = <r 2><b 0x858000010001000000000669636c6f756403636f6d0000010001c00c000100010000105a00044d583737>
        HeaderProtectionKey = ${hpk.toBase64()}
        RekeyAfterTime = 100-120
        RandomTrailers = on
        DisableCookies = on

        [Peer]
        PublicKey = ${serverPub.toBase64()}
        PresharedKey = ${psk.toBase64()}
        AllowedIPs = 0.0.0.0/0, ::/0
        Endpoint = 203.0.113.7:55424
        PersistentKeepalive = 25-35
    """.trimIndent()

    @Test
    fun parsesAmneziaClientConfig() {
        val c = WgQuick.parse(amneziaConf)
        assertEquals(priv, c.iface.privateKey)
        assertEquals("10.8.1.2/32", c.iface.addresses.single().toString())
        assertEquals(listOf("1.1.1.1", "1.0.0.1"), c.iface.dns)
        assertEquals("5", c.iface.awg[AwgParam.JC])
        assertTrue(c.iface.awg[AwgParam.I1]!!.startsWith("<r 2><b 0x8580"))
        assertEquals("25-35", c.peers.single().persistentKeepalive)
        assertEquals(listOf("0.0.0.0/0", "::/0"), c.peers.single().allowedIps.map { it.toString() })
        assertTrue(c.isAmnezia)
    }

    @Test
    fun roundTripIsStable() {
        val c = WgQuick.parse(amneziaConf)
        assertEquals(c, WgQuick.parse(WgQuick.write(c)))
    }

    @Test
    fun plainWireGuardStaysPlain() {
        val c = WgQuick.parse(
            """
            [Interface]
            PrivateKey = ${priv.toBase64()}
            Address = 10.0.0.2/24, fd00::2/64
            PostUp = iptables -A FORWARD
            [Peer]
            PublicKey = ${serverPub.toBase64()}
            AllowedIPs = 0.0.0.0/0
            Endpoint = [2001:db8::1]:51820
            """.trimIndent(),
        )
        assertTrue(!c.isAmnezia)
        assertEquals(listOf("PostUp" to "iptables -A FORWARD"), c.iface.extra)
        assertTrue(WgQuick.write(c).contains("PostUp = iptables -A FORWARD"))
    }

    @Test
    fun uapiUsesHexKeysAndGoBools() {
        val uapi = Uapi.write(WgQuick.parse(amneziaConf)) { InetAddress.getByName(it) }
        assertTrue(uapi.startsWith("private_key=${priv.toHex()}\n"))
        assertTrue("header_protection_key=${hpk.toHex()}\n" in uapi)
        assertTrue("random_trailers=true\n" in uapi)
        assertTrue("rekey_after_time=100-120\n" in uapi)
        assertTrue("endpoint=203.0.113.7:55424\n" in uapi)
        assertTrue("persistent_keepalive_interval=25-35\n" in uapi)
        assertTrue("allowed_ip=::/0\n" in uapi)
        assertTrue(uapi.indexOf("replace_peers=true") < uapi.indexOf("public_key="))
    }
}
