package net.paramnezia.core.crypto

import java.security.SecureRandom
import java.util.Base64

/** A 32-byte Curve25519 key (private, public or preshared) as used by WireGuard. */
class WgKey(bytes: ByteArray) {
    private val raw: ByteArray = bytes.copyOf()

    init {
        require(raw.size == 32) { "Key must be 32 bytes, got ${raw.size}" }
    }

    val bytes: ByteArray get() = raw.copyOf()

    fun toBase64(): String = Base64.getEncoder().encodeToString(raw)

    fun toHex(): String = buildString(64) {
        for (b in raw) append(HEX[(b.toInt() shr 4) and 0xF]).append(HEX[b.toInt() and 0xF])
    }

    /** Treats this key as a private key and derives the matching public key. */
    fun publicKey(): WgKey {
        val out = ByteArray(32)
        Curve25519.eval(out, 0, raw, null)
        return WgKey(out)
    }

    override fun equals(other: Any?) = other is WgKey && raw.contentEquals(other.raw)
    override fun hashCode() = raw.contentHashCode()

    /** Never print key material by accident. */
    override fun toString() = "WgKey(${toBase64().take(4)}…)"

    companion object {
        private const val HEX = "0123456789abcdef"
        private val random by lazy { SecureRandom() }

        fun fromBase64(text: String): WgKey = WgKey(Base64.getDecoder().decode(text.trim()))

        fun fromHex(text: String): WgKey {
            val s = text.trim()
            require(s.length == 64) { "Hex key must be 64 chars" }
            return WgKey(ByteArray(32) { i -> s.substring(i * 2, i * 2 + 2).toInt(16).toByte() })
        }

        fun generatePrivate(): WgKey {
            val k = ByteArray(32).also(random::nextBytes)
            // RFC 7748 clamping, as `wg genkey` does.
            k[0] = (k[0].toInt() and 248).toByte()
            k[31] = ((k[31].toInt() and 127) or 64).toByte()
            return WgKey(k)
        }

        fun generatePreshared(): WgKey = WgKey(ByteArray(32).also(random::nextBytes))
    }
}
