package io.paramnezia.core.apk

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Settings carried inside a signed APK.
 *
 * APK Signature Scheme v2/v3 signs everything except the APK Signing Block itself, and verifiers
 * must ignore pairs with unknown IDs (the same trick channel-packaging tools use). So one extra
 * pair of fixed size, a "slot", can be overwritten per download without re-signing: the server
 * streams `apk[0, offset) + slot + apk[offset + SIZE, end)`. The installed app reads its own APK
 * on first launch and finds its invite there.
 *
 * The pair is sized so the block grows by exactly 16 KiB, which keeps whatever page alignment the
 * signer chose for the central directory.
 */
object ApkSlot {
    const val ID = 0x706e7a31 // "pnz1"
    /** Value bytes of the pair; 12 bytes of pair header make the block grow by 16 KiB. */
    const val SIZE = 16384 - 12
    private val MAGIC = "PNI1".toByteArray()
    private const val HEADER = 8
    const val MAX_PAYLOAD = SIZE - HEADER
    private val BLOCK_MAGIC = "APK Sig Block 42".toByteArray()

    /** Slot bytes for [payload]: magic, big-endian length, UTF-8, zero padding. */
    fun encode(payload: String): ByteArray {
        val data = payload.toByteArray()
        require(data.size <= MAX_PAYLOAD) { "payload of ${data.size} bytes does not fit the slot" }
        val out = ByteArray(SIZE)
        MAGIC.copyInto(out)
        ByteBuffer.wrap(out, 4, 4).order(ByteOrder.BIG_ENDIAN).putInt(data.size)
        data.copyInto(out, HEADER)
        return out
    }

    fun decode(slot: ByteArray): String? {
        if (slot.size < HEADER || !slot.copyOfRange(0, 4).contentEquals(MAGIC)) return null
        val n = ByteBuffer.wrap(slot, 4, 4).order(ByteOrder.BIG_ENDIAN).int
        if (n <= 0 || n > slot.size - HEADER) return null
        return String(slot, HEADER, n)
    }

    /**
     * Copies [src] to [dst] with an empty slot and returns the slot's offset in [dst]. If [src]
     * already has a slot (an APK installed from an invite), it is cleared and reused.
     */
    fun prepare(src: File, dst: File): Long {
        val layout = RandomAccessFile(src, "r").use { Layout.read(it) }
        layout.slotOffset?.let { off ->
            src.copyTo(dst, overwrite = true)
            RandomAccessFile(dst, "rw").use { it.seek(off); it.write(ByteArray(SIZE)) }
            return off
        }
        val growth = 12L + SIZE
        RandomAccessFile(src, "r").use { input ->
            dst.outputStream().buffered(1 shl 16).use { out ->
                // Everything before the block, then the block with its old pairs and our slot.
                val size = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(layout.blockSize + growth).array()
                copy(input, 0, layout.blockStart, out)
                out.write(size)
                copy(input, layout.blockStart + 8, layout.pairsSize, out)
                val pair = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).putLong(4L + SIZE).putInt(ID).array()
                out.write(pair)
                out.write(ByteArray(SIZE))
                out.write(size)
                out.write(BLOCK_MAGIC)
                // Central directory, then the end record pointing at its new place.
                copy(input, layout.cdOffset, layout.eocdOffset - layout.cdOffset, out)
                val eocd = ByteArray((input.length() - layout.eocdOffset).toInt())
                input.seek(layout.eocdOffset); input.readFully(eocd)
                ByteBuffer.wrap(eocd).order(ByteOrder.LITTLE_ENDIAN).putInt(16, (layout.cdOffset + growth).toInt())
                out.write(eocd)
            }
        }
        // The block starts where it did; our value follows the old pairs and its 12-byte header.
        return layout.blockStart + 8 + layout.pairsSize + 12
    }

    /** Where the slot's value starts in an APK that has one (release builds get an empty one in CI), or null. */
    fun offset(apk: File): Long? = runCatching { RandomAccessFile(apk, "r").use { Layout.read(it).slotOffset } }.getOrNull()

    /** Reads the invite in an APK's slot, or null. Touches only the last few KiB and the block. */
    fun read(apk: File): String? = runCatching {
        RandomAccessFile(apk, "r").use { f ->
            val off = Layout.read(f).slotOffset ?: return null
            val slot = ByteArray(SIZE)
            f.seek(off); f.readFully(slot)
            decode(slot)
        }
    }.getOrNull()

    private fun copy(input: RandomAccessFile, from: Long, length: Long, out: java.io.OutputStream) {
        input.seek(from)
        val buf = ByteArray(1 shl 16)
        var left = length
        while (left > 0) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (n < 0) throw IOException("unexpected end of APK")
            out.write(buf, 0, n)
            left -= n
        }
    }

    /** Where the signing block, central directory and end record are; where our slot is, if any. */
    private class Layout(
        val blockStart: Long,
        val blockSize: Long,
        val pairsSize: Long,
        val cdOffset: Long,
        val eocdOffset: Long,
        val slotOffset: Long?,
    ) {
        companion object {
            fun read(f: RandomAccessFile): Layout {
                val len = f.length()
                val tailLen = minOf(len, 22L + 65535).toInt()
                val tail = ByteArray(tailLen)
                f.seek(len - tailLen); f.readFully(tail)
                val tb = ByteBuffer.wrap(tail).order(ByteOrder.LITTLE_ENDIAN)
                var eocdRel = -1
                for (i in tailLen - 22 downTo 0) {
                    if (tb.getInt(i) == 0x06054b50 && (tb.getShort(i + 20).toInt() and 0xffff) == tailLen - 22 - i) { eocdRel = i; break }
                }
                if (eocdRel < 0) throw IOException("not a zip file")
                val eocdOffset = len - tailLen + eocdRel
                val cdOffset = tb.getInt(eocdRel + 16).toLong() and 0xffffffffL
                if (cdOffset < 32) throw IOException("APK is not signed (no signing block)")

                val footer = ByteArray(24)
                f.seek(cdOffset - 24); f.readFully(footer)
                if (!footer.copyOfRange(8, 24).contentEquals(BLOCK_MAGIC)) throw IOException("APK is not signed with scheme v2+")
                val blockSize = ByteBuffer.wrap(footer).order(ByteOrder.LITTLE_ENDIAN).getLong(0)
                val blockStart = cdOffset - blockSize - 8
                if (blockStart < 0) throw IOException("bad signing block")
                val pairsSize = blockSize - 24 // minus the trailing size field and magic

                var slot: Long? = null
                val head = ByteArray(12)
                var p = blockStart + 8
                val end = p + pairsSize
                while (p + 12 <= end) {
                    f.seek(p); f.readFully(head)
                    val hb = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN)
                    val pairLen = hb.getLong(0)
                    if (pairLen < 4 || p + 8 + pairLen > end) throw IOException("bad signing block pair")
                    if (hb.getInt(8) == ID && pairLen == 4L + SIZE) slot = p + 12
                    p += 8 + pairLen
                }
                return Layout(blockStart, blockSize, pairsSize, cdOffset, eocdOffset, slot)
            }
        }
    }
}
