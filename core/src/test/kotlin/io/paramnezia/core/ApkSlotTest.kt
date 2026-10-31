package io.paramnezia.core

import io.paramnezia.core.apk.ApkSlot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class ApkSlotTest {
    /** A zip laid out like a v2-signed APK: entries, signing block (one fake signature pair), CD, EOCD. */
    private fun fakeApk(): File {
        val zip = ByteArrayOutputStream()
        ZipOutputStream(zip).use { z ->
            z.setComment("apk comment")
            for (i in 0 until 5) {
                z.putNextEntry(ZipEntry("res/file$i.txt")); z.write("content $i ".repeat(500).toByteArray()); z.closeEntry()
            }
        }
        val bytes = zip.toByteArray()
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var eocd = bytes.size - 22
        while (bb.getInt(eocd) != 0x06054b50) eocd--
        val cd = bb.getInt(eocd + 16)

        val sig = ByteArray(300) { it.toByte() }
        val pairs = ByteBuffer.allocate(12 + sig.size).order(ByteOrder.LITTLE_ENDIAN).putLong(4L + sig.size).putInt(0x7109871a).put(sig).array()
        val blockSize = pairs.size + 24L
        val block = ByteBuffer.allocate((blockSize + 8).toInt()).order(ByteOrder.LITTLE_ENDIAN)
            .putLong(blockSize).put(pairs).putLong(blockSize).put("APK Sig Block 42".toByteArray()).array()

        val out = ByteArrayOutputStream()
        out.write(bytes, 0, cd)
        out.write(block)
        out.write(bytes, cd, eocd - cd)
        val end = bytes.copyOfRange(eocd, bytes.size)
        ByteBuffer.wrap(end).order(ByteOrder.LITTLE_ENDIAN).putInt(16, cd + block.size)
        out.write(end)
        return File.createTempFile("fake", ".apk").apply { writeBytes(out.toByteArray()); deleteOnExit() }
    }

    private fun entries(f: File) = ZipFile(f).use { z -> z.entries().toList().associate { it.name to z.getInputStream(it).readBytes().decodeToString() } }

    @Test
    fun slotRoundTripKeepsZipIntact() {
        val apk = fakeApk()
        assertNull(ApkSlot.read(apk))
        val tmpl = File.createTempFile("tmpl", ".apk").apply { deleteOnExit() }
        val off = ApkSlot.prepare(apk, tmpl)
        assertEquals(apk.length() + 16384, tmpl.length())
        assertEquals(entries(apk), entries(tmpl))
        assertNull(ApkSlot.read(tmpl)) // empty slot

        // What the server does: stream the template with the slot bytes replaced.
        val invite = "vpn://" + "x".repeat(5000)
        val personal = File.createTempFile("personal", ".apk").apply { deleteOnExit() }
        val t = tmpl.readBytes()
        personal.writeBytes(t.copyOfRange(0, off.toInt()) + ApkSlot.encode(invite) + t.copyOfRange(off.toInt() + ApkSlot.SIZE, t.size))
        assertEquals(invite, ApkSlot.read(personal))
        assertEquals(entries(apk), entries(personal))

        // An APK that came from an invite becomes a template again, slot cleared, same layout.
        val again = File.createTempFile("again", ".apk").apply { deleteOnExit() }
        assertEquals(off, ApkSlot.prepare(personal, again))
        assertNull(ApkSlot.read(again))
        assertEquals(tmpl.readBytes().toList(), again.readBytes().toList())
    }

    @Test
    fun signedPartsAreUntouched() {
        val apk = fakeApk()
        val tmpl = File.createTempFile("tmpl", ".apk").apply { deleteOnExit() }
        ApkSlot.prepare(apk, tmpl)
        val a = apk.readBytes(); val b = tmpl.readBytes()
        // v2 digests cover the entries, the central directory and the end record (with the CD offset
        // replaced by the block's offset). All three must be byte-identical apart from that field.
        val ab = ByteBuffer.wrap(a).order(ByteOrder.LITTLE_ENDIAN)
        var eocd = a.size - 22
        while (ab.getInt(eocd) != 0x06054b50) eocd--
        val cd = ab.getInt(eocd + 16)
        val blockSize = ab.getLong(cd - 24)
        val blockStart = (cd - blockSize - 8).toInt()
        assertEquals(a.copyOfRange(0, blockStart).toList(), b.copyOfRange(0, blockStart).toList())
        assertEquals(a.copyOfRange(cd, a.size).toList().drop(0).size, b.size - (cd + 16384))
        assertEquals(a.copyOfRange(cd, eocd).toList(), b.copyOfRange(cd + 16384, eocd + 16384).toList())
        RandomAccessFile(tmpl, "r").use { f ->
            val end = ByteArray(a.size - eocd); f.seek((eocd + 16384).toLong()); f.readFully(end)
            val eb = ByteBuffer.wrap(end).order(ByteOrder.LITTLE_ENDIAN)
            assertEquals(cd + 16384, eb.getInt(16))
            assertEquals(a.copyOfRange(eocd, eocd + 16).toList(), end.copyOfRange(0, 16).toList())
            assertEquals(a.copyOfRange(eocd + 20, a.size).toList(), end.copyOfRange(20, end.size).toList())
        }
    }

    @Test
    fun encodeRejectsOversizedPayload() {
        assertEquals("abc", ApkSlot.decode(ApkSlot.encode("abc")))
        runCatching { ApkSlot.encode("x".repeat(ApkSlot.MAX_PAYLOAD + 1)) }.let { assert(it.isFailure) }
    }
}
