package net.paramnezia.core

import net.paramnezia.core.amnezia.AmneziaFormat
import net.paramnezia.core.amnezia.AmneziaKey
import net.paramnezia.core.amnezia.AmneziaQr
import net.paramnezia.core.config.AwgParam
import net.paramnezia.core.config.WgQuick
import net.paramnezia.core.crypto.WgKey
import net.paramnezia.core.profile.Protocol
import net.paramnezia.core.profile.SshCredentials
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class AmneziaTest {
    @Test
    fun decodesKeyMadeByQtCompatibleZlib() {
        // Produced with Python: b">I" length + zlib.compress(json, 8), base64url without padding.
        val key = "vpn://AAAAlHjaq1bKyC8u8UvMTVWyUjIyMNYz0DM0NNYzV9JRSkktTi7KLCjJzM8DyoWkFpcABZPz80oSM_NSi4qVrKKrEVygisTcvNSqzETdxPJ0I6BKIKVkVa1UkF9UApQ0NTUxMlGqrY0FmZuWWJpT4oxLay0AsR4wRA"
        val json = AmneziaKey.decode(key)
        assertEquals("203.0.113.7", json["hostName"]!!.jsonPrimitive.content)
        assertEquals("amnezia-awg2", json["defaultContainer"]!!.jsonPrimitive.content)
    }

    @Test
    fun encodeDecodeRoundTrip() {
        val json = buildJsonObject { put("hostName", "example.org"); put("description", "Мой сервер") }
        val key = AmneziaKey.encode(json)
        assert(key.startsWith("vpn://"))
        assertEquals(json, AmneziaKey.decode(key))
    }

    @Test
    fun qUncompressRejectsGarbage() {
        assertNull(AmneziaKey.qUncompressOrNull(byteArrayOf(0, 0, 0, 5, 1, 2, 3)))
        assertNull(AmneziaKey.qUncompressOrNull(byteArrayOf(0x7f, 0, 0, 0, 1, 2, 3)))
    }

    /** A user (connection-only) key as AmneziaVPN's ExportController produces it. */
    private fun amneziaUserKey(priv: WgKey, serverPub: WgKey, psk: WgKey): JsonObject {
        val native = """
            [Interface]
            Address = 10.8.1.5/32
            DNS = ${'$'}PRIMARY_DNS, ${'$'}SECONDARY_DNS
            PrivateKey = ${priv.toBase64()}
            Jc = 4
            Jmin = 10
            Jmax = 50
            S1 = 12
            S2 = 12
            H1 = 1
            H2 = 2
            H3 = 3
            H4 = 4

            [Peer]
            PublicKey = ${serverPub.toBase64()}
            PresharedKey = ${psk.toBase64()}
            AllowedIPs = 0.0.0.0/0, ::/0
            Endpoint = 198.51.100.10:55424
            PersistentKeepalive = 25
        """.trimIndent()
        val last = buildJsonObject {
            put("config", native)
            put("hostName", "198.51.100.10")
            put("port", 55424)
            put("client_ip", "10.8.1.5")
            put("client_priv_key", priv.toBase64())
            put("server_pub_key", serverPub.toBase64())
            put("psk_key", psk.toBase64())
            put("mtu", "1280")
            put("persistent_keep_alive", "25")
            put("Jc", "4")
        }
        return buildJsonObject {
            put("format_version", 1)
            put("description", "Netherlands")
            put("hostName", "198.51.100.10")
            put("containers", buildJsonArray {
                add(buildJsonObject {
                    put("container", "amnezia-awg2")
                    put("awg", buildJsonObject {
                        put("port", "55424")
                        put("transport_proto", "udp")
                        put("last_config", last.toString())
                    })
                })
            })
            put("defaultContainer", "amnezia-awg2")
            put("dns1", "9.9.9.9")
            put("dns2", "149.112.112.112")
        }
    }

    @Test
    fun importsAmneziaUserKey() {
        val priv = WgKey.generatePrivate(); val serverPub = WgKey.generatePrivate().publicKey(); val psk = WgKey.generatePreshared()
        val profiles = AmneziaFormat.import(AmneziaKey.decode(AmneziaKey.encode(amneziaUserKey(priv, serverPub, psk))))
        val p = profiles.single()
        assertEquals(Protocol.AWG, p.protocol)
        assertEquals("Netherlands", p.name)
        val t = assertNotNullT(p.tunnel)
        assertEquals(priv, t.iface.privateKey)
        assertEquals(listOf("9.9.9.9", "149.112.112.112"), t.iface.dns)
        assertEquals(1280, t.iface.mtu)
        assertEquals("4", t.iface.awg[AwgParam.JC])
        assertEquals("198.51.100.10:55424", t.peers.single().endpoint)
        assertEquals(psk, t.peers.single().presharedKey)
        assertNull(p.ssh)
    }

    @Test
    fun importsFullAccessKeyWithoutClientConfig() {
        val json = buildJsonObject {
            put("hostName", "198.51.100.10"); put("userName", "root"); put("password", "secret"); put("port", 2222)
            put("description", "VPS")
        }
        val p = AmneziaFormat.import(json).single()
        assertEquals(SshCredentials("198.51.100.10", 2222, "root", "secret"), p.ssh)
        assertNull(p.tunnel)
    }

    @Test
    fun exportIsReadableByOurImportAndKeepsAmneziaShape() {
        val priv = WgKey.generatePrivate(); val serverPub = WgKey.generatePrivate().publicKey(); val psk = WgKey.generatePreshared()
        val original = AmneziaFormat.import(amneziaUserKey(priv, serverPub, psk)).single().tunnel!!
        val exported = AmneziaFormat.export("Exported", original)
        val c = exported["containers"]!!.jsonArray.single().jsonObject
        assertEquals("amnezia-awg2", c["container"]!!.jsonPrimitive.content)
        val last = AmneziaKey.decode(c["awg"]!!.jsonObject["last_config"]!!.jsonPrimitive.content)
        assertEquals(priv.toBase64(), last["client_priv_key"]!!.jsonPrimitive.content)
        assertEquals(JsonPrimitive(55424), last["port"])
        val again = AmneziaFormat.import(exported).single().tunnel!!
        assertEquals(WgQuick.write(original), WgQuick.write(again))
    }

    @Test
    fun qrSeriesRoundTrip() {
        val payload = AmneziaKey.qCompress(ByteArray(3000) { (it * 7).toByte() })
        val codes = AmneziaQr.encode(payload)
        val asm = AmneziaQr.Assembler()
        var result: ByteArray? = null
        for (code in codes.reversed()) result = asm.offer(AmneziaQr.parseChunk(code)!!)
        assertArrayEquals(payload, result)
        assertNull(AmneziaQr.parseChunk("vpn://notaqr"))
    }

    private fun <T> assertNotNullT(v: T?): T { assertNotNull(v); return v!! }
}
