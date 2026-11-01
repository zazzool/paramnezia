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
        """.trimIndent()
        val client = WgKey.generatePrivate()
        val t = ServerManager.clientConfig(conf, mapOf("client_ip" to "10.8.1.7", "server_pub" to serverPub, "psk" to psk),
            client, "203.0.113.5", listOf("1.1.1.1"), 1280, null)
        assertEquals("203.0.113.5:41234", t.peers.single().endpoint)
        assertEquals("<r 2><b 0x8580>", t.iface.awg[AwgParam.I1])
        assertEquals("25-35", t.peers.single().persistentKeepalive)
        assertEquals(client, t.iface.privateKey)
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
