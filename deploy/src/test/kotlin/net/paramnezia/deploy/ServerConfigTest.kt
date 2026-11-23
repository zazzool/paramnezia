package net.paramnezia.deploy

import net.paramnezia.core.config.AwgParam
import net.paramnezia.core.crypto.WgKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerConfigTest {
    @Test
    fun clientConfigFromServerInterface() {
        val serverPub = WgKey.generatePrivate().publicKey().toBase64()
        val psk = WgKey.generatePreshared().toBase64()
        val conf = """
            [Interface]
            Address = 10.8.1.1/24
            ListenPort = 41234
            Jc = 5
            Jmin = 10
            Jmax = 50
            H1 = 1
            # I1 = <r 2><b 0x8580>
            HeaderProtectionKey = ${WgKey.generatePrivate().toBase64()}
            ContentPaddingAddition = 0-31
        """.trimIndent()
        val client = WgKey.generatePrivate()
        val t = ServerManager.clientConfig(conf, mapOf("client_ip" to "10.8.1.7", "server_pub" to serverPub, "psk" to psk),
            client, "203.0.113.5", listOf("1.1.1.1"), 1280, null)
        assertEquals("203.0.113.5:41234", t.peers.single().endpoint)
        assertEquals("<r 2><b 0x8580>", t.iface.awg[AwgParam.I1])
        assertEquals("25-35", t.peers.single().persistentKeepalive)
        assertEquals(client, t.iface.privateKey)
        // The server's own padding stays on the server (AmneziaVPN clients keep their trailers).
        assertEquals(null, t.iface.awg[AwgParam.CONTENT_PADDING_ADDITION])
    }

    @Test
    fun dualStackClientConfigUsesHostRoutesAndBracketedEndpoint() {
        val key = WgKey.generatePrivate()
        val ipv6 = net.paramnezia.core.fleet.managedIpv6Address("10.8.1.7/32")!!
        val t = ServerManager.clientConfig(
            "ListenPort = 41234",
            mapOf("client_ip" to "10.8.1.7", "client_ipv6" to ipv6.toString(),
                "server_pub" to key.publicKey().toBase64()),
            key, "2001:db8::5", listOf("1.1.1.1"), 1280, null,
        )
        assertEquals(listOf("10.8.1.7/32", "fd38:706e:1::a08:107/128"), t.iface.addresses.map { it.toString() })
        assertEquals("[2001:db8::5]:41234", t.peers.single().endpoint)
        assertEquals(listOf("0.0.0.0/0", "::/0"), t.peers.single().allowedIps.map { it.toString() })
    }

    @Test
    fun serversNoLongerShareOneSignature() {
        val seen = (1..40).map { ObfuscationProfile.AWG_3.generate() }
        for (p in seen) {
            assertTrue(p[AwgParam.I1] == net.paramnezia.core.config.Camouflage.STUN_BINDING)
            assertEquals("0-31", p[AwgParam.CONTENT_PADDING_ADDITION])
        }
        // Some servers add the DTLS ClientHello after STUN, some do not.
        assertEquals(setOf(true, false), seen.map { it[AwgParam.I2] != null }.toSet())
        assertTrue(seen.none { it[AwgParam.I1] == ObfuscationProfile.DNS_LIKE_I1 })
        val alt = ServerManager.altPortsFor(41000)
        assertEquals(3, alt.size)
        assertTrue(3478 in alt && 443 in alt && 41000 !in alt)
    }

    @Test
    fun profilesProduceDistinctPacketSizes() {
        repeat(50) {
            for (p in ObfuscationProfile.entries) {
                val params = p.generate()
                assertTrue(params[AwgParam.JC]!!.toInt() in 4..6)
                val h = listOf(AwgParam.H1, AwgParam.H2, AwgParam.H3, AwgParam.H4).map { params[it]!! }
                assertEquals(4, h.toSet().size)
            }
        }
        assertEquals("10.8.1.1", ServerManager.firstHost("10.8.1.0"))
    }

    @Test
    fun shellQuoting() {
        assertEquals("'it'\\''s'", SshConnection.shellQuote("it's"))
    }
}
