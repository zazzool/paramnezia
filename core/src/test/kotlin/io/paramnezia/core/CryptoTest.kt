package io.paramnezia.core

import io.paramnezia.core.crypto.WgKey
import org.junit.Assert.assertEquals
import org.junit.Test

class CryptoTest {
    @Test
    fun knownVectorsRfc7748() {
        // RFC 7748 section 6.1.
        val alice = WgKey.fromHex("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a")
        assertEquals("8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a", alice.publicKey().toHex())
        val bob = WgKey.fromHex("5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb")
        assertEquals("de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f", bob.publicKey().toHex())
    }

    @Test
    fun generatedKeysAreClamped() {
        val k = WgKey.generatePrivate().bytes
        assertEquals(0, k[0].toInt() and 7)
        assertEquals(64, k[31].toInt() and 0xC0)
    }

    @Test
    fun base64HexRoundTrip() {
        val k = WgKey.generatePreshared()
        assertEquals(k, WgKey.fromBase64(k.toBase64()))
        assertEquals(k, WgKey.fromHex(k.toHex()))
    }
}
