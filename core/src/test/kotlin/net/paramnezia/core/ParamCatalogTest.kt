package net.paramnezia.core

import net.paramnezia.core.config.AwgParam
import net.paramnezia.core.config.Camouflage
import net.paramnezia.core.config.ParamCatalog
import net.paramnezia.core.config.ProtocolVersion
import net.paramnezia.core.config.Signatures
import net.paramnezia.core.config.WgQuick
import net.paramnezia.core.crypto.WgKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ParamCatalogTest {
    private fun conf(vararg lines: String) = WgQuick.parse(
        "[Interface]\nPrivateKey = ${WgKey.generatePrivate().toBase64()}\nAddress = 10.8.1.2/32\n" + lines.joinToString("\n") +
            "\n[Peer]\nPublicKey = ${WgKey.generatePrivate().publicKey().toBase64()}\nAllowedIPs = 0.0.0.0/0\nEndpoint = 198.51.100.7:41000\n",
    )

    @Test
    fun detectsTheProtocol() {
        assertEquals(ProtocolVersion.WIREGUARD, ProtocolVersion.of(conf()))
        assertEquals(ProtocolVersion.WIREGUARD, ProtocolVersion.of(conf("H1 = 1", "H2 = 2")))
        assertEquals(ProtocolVersion.AWG_1, ProtocolVersion.of(conf("Jc = 4", "S1 = 50", "H1 = 123456")))
        assertEquals(ProtocolVersion.AWG_1_5, ProtocolVersion.of(conf("Jc = 4", "I1 = <r 2><b 0x8580>")))
        assertEquals(ProtocolVersion.AWG_2, ProtocolVersion.of(conf("Jc = 4", "H1 = 5-100", "S3 = 20")))
        assertEquals(ProtocolVersion.AWG_3, ProtocolVersion.of(conf("Jc = 4", "HeaderProtectionKey = ${WgKey.generatePrivate().toBase64()}")))
        assertEquals(ProtocolVersion.AWG_3, ProtocolVersion.of(conf("RandomTrailers = on")))
    }

    @Test
    fun namesSignatures() {
        assertEquals("STUN Binding Request · 20 Б", Signatures.name(Camouflage.STUN_BINDING))
        assertTrue(Signatures.name(Camouflage.dtlsClientHello()).startsWith("DTLS ClientHello"))
        assertTrue(Signatures.name("<r 2><b 0x858000010001000000000669636c6f756403636f6d0000010001>").startsWith("DNS-ответ"))
        assertTrue(Signatures.name("<r 2><b 0x01000001>").startsWith("DNS-запрос"))
        assertEquals("свои байты · 7 Б", Signatures.name("<b 0xdeadbeef><rc 3>"))
        assertEquals(20, Signatures.length(Camouflage.STUN_BINDING))
        assertEquals(12, Signatures.length("<t><c><rd 4>"))
        assertNull(Signatures.length("garbage"))
        assertNull(Signatures.length("<b 0xabc>"))
        assertEquals("неизвестный формат", Signatures.name("<x 1>"))
    }

    @Test
    fun catalogCoversEveryParameterAndValidates() {
        // Every AmneziaWG parameter has exactly one spec, so the screen and a future editor see them all.
        assertEquals(AwgParam.entries.toSet(), ParamCatalog.all.map { it.param }.toSet())
        assertEquals(AwgParam.entries.size, ParamCatalog.all.size)
        val jc = ParamCatalog.spec(AwgParam.JC).editor
        assertNull(jc.check("4")); assertNotNull(jc.check("x")); assertNotNull(jc.check("1000"))
        val h = ParamCatalog.spec(AwgParam.H1).editor
        assertNull(h.check("5-100")); assertNotNull(h.check("100-5")); assertNotNull(h.check("0"))
        assertNull(ParamCatalog.spec(AwgParam.RANDOM_TRAILERS).editor.check("on"))
        assertNull(ParamCatalog.spec(AwgParam.HEADER_PROTECTION_KEY).editor.check(WgKey.generatePrivate().toBase64()))
        assertNull(ParamCatalog.spec(AwgParam.I1).editor.check(Camouflage.STUN_BINDING))
        assertNotNull(ParamCatalog.spec(AwgParam.I1).editor.check("<b 0xzz>"))
        // Who has to agree: the server checks S/H; signatures and junk are the device's own.
        assertEquals(ParamCatalog.Scope.SERVER, ParamCatalog.spec(AwgParam.S1).scope)
        assertEquals(ParamCatalog.Scope.CLIENT, ParamCatalog.spec(AwgParam.I1).scope)
        val groups = ParamCatalog.of(conf("Jc = 4", "S1 = 12", "I1 = ${Camouflage.STUN_BINDING}", "RandomTrailers = on"))
        assertEquals(listOf("4", "12"), groups.getValue(ParamCatalog.Group.HANDSHAKE).map { it.second })
        assertEquals("вкл.", ParamCatalog.describe(AwgParam.RANDOM_TRAILERS, "on"))
    }
}
