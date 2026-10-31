package io.paramnezia.core.amnezia

import io.paramnezia.core.config.ConfigException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.Base64
import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * The AmneziaVPN key format: `vpn://` + base64url (no padding) of Qt's `qCompress` of the server JSON.
 * `qCompress` is a 4-byte big-endian uncompressed length followed by a zlib stream.
 */
object AmneziaKey {
    const val SCHEME = "vpn://"

    internal val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    fun encode(config: JsonObject): String = SCHEME + base64Url(qCompress(json.encodeToString(JsonObject.serializer(), config).toByteArray()))

    /** Accepts `vpn://…`, bare base64url, base64url of uncompressed JSON, or plain JSON text. */
    fun decode(text: String): JsonObject {
        val t = text.trim()
        if (t.startsWith("{")) return parseJson(t.toByteArray())
        val payload = t.removePrefix(SCHEME).filterNot { it.isWhitespace() }
        val bytes = runCatching { Base64.getUrlDecoder().decode(payload.trimEnd('=')) }
            .getOrElse { throw ConfigException("Not a vpn:// key", it) }
        return parseJson(qUncompressOrNull(bytes) ?: bytes)
    }

    /** The qCompress'ed bytes inside a `vpn://` key (what AmneziaVPN's QR series carries). */
    fun compressedPayload(key: String): ByteArray =
        Base64.getUrlDecoder().decode(key.trim().removePrefix(SCHEME).filterNot { it.isWhitespace() }.trimEnd('='))

    fun looksLikeKey(text: String): Boolean = text.trim().startsWith(SCHEME)

    private fun parseJson(bytes: ByteArray): JsonObject = runCatching {
        json.parseToJsonElement(bytes.decodeToString()).jsonObject
    }.getOrElse { throw ConfigException("vpn:// key does not contain a valid config", it) }

    fun base64Url(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    fun qCompress(data: ByteArray, level: Int = 8): ByteArray {
        val deflater = Deflater(level)
        try {
            deflater.setInput(data)
            deflater.finish()
            val out = ByteArrayOutputStream(data.size / 2 + 16)
            out.write(ByteBuffer.allocate(4).putInt(data.size).array())
            val buf = ByteArray(8192)
            while (!deflater.finished()) out.write(buf, 0, deflater.deflate(buf))
            return out.toByteArray()
        } finally {
            deflater.end()
        }
    }

    /** Returns null when [data] is not a qCompress blob (mirrors Qt, which returns an empty array). */
    fun qUncompressOrNull(data: ByteArray): ByteArray? {
        if (data.size < 5) return null
        val expected = ByteBuffer.wrap(data, 0, 4).int
        // Guard against hostile length prefixes before allocating.
        if (expected < 0 || expected > 64 * 1024 * 1024) return null
        val inflater = Inflater()
        try {
            inflater.setInput(data, 4, data.size - 4)
            val out = ByteArray(expected)
            var n = 0
            while (n < expected && !inflater.finished()) {
                val r = inflater.inflate(out, n, expected - n)
                if (r == 0 && (inflater.needsInput() || inflater.needsDictionary())) return null
                n += r
            }
            return if (n == expected && inflater.finished()) out else null
        } catch (_: DataFormatException) {
            return null
        } finally {
            inflater.end()
        }
    }
}
