package net.paramnezia.core.amnezia

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.Base64

/**
 * AmneziaVPN's multi-QR format. Each code is base64url of a QDataStream:
 * `qint16 magic=1984, quint8 count, quint8 index, QByteArray chunk` (QByteArray = quint32 length + bytes).
 * The concatenated chunks are the qCompress'ed server JSON.
 */
object AmneziaQr {
    private const val MAGIC: Short = 1984
    private const val CHUNK = 850

    fun encode(compressed: ByteArray): List<String> {
        val count = (compressed.size + CHUNK - 1) / CHUNK
        require(count in 1..255) { "Config too large for QR series" }
        return (0 until count).map { i ->
            val part = compressed.copyOfRange(i * CHUNK, minOf(compressed.size, (i + 1) * CHUNK))
            val bos = ByteArrayOutputStream()
            DataOutputStream(bos).use {
                it.writeShort(MAGIC.toInt()); it.writeByte(count); it.writeByte(i); it.writeInt(part.size); it.write(part)
            }
            AmneziaKey.base64Url(bos.toByteArray())
        }
    }

    data class Chunk(val count: Int, val index: Int, val data: ByteArray)

    /** Returns null when the scanned text is not an AmneziaVPN QR chunk. */
    fun parseChunk(text: String): Chunk? = runCatching {
        val bytes = Base64.getUrlDecoder().decode(text.trim().trimEnd('='))
        DataInputStream(bytes.inputStream()).use { s ->
            if (s.readShort() != MAGIC) return null
            val count = s.readUnsignedByte()
            val index = s.readUnsignedByte()
            val len = s.readInt()
            if (len < 0 || len > bytes.size) return null
            Chunk(count, index, ByteArray(len).also(s::readFully))
        }
    }.getOrNull()

    /** Collects chunks from a camera stream; [offer] returns the assembled payload once complete. */
    class Assembler {
        private val parts = HashMap<Int, ByteArray>()
        private var total = 0
        val received get() = parts.size
        val expected get() = total

        fun offer(chunk: Chunk): ByteArray? {
            if (chunk.count != total) { parts.clear(); total = chunk.count }
            parts[chunk.index] = chunk.data
            if (parts.size < total) return null
            val out = ByteArrayOutputStream()
            for (i in 0 until total) out.write(parts[i] ?: return null)
            return out.toByteArray()
        }
    }
}
