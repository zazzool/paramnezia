package io.paramnezia.deploy

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** A zip laid out like a v2-signed APK (same as core's ApkSlotTest fixture). */
internal fun fakeSignedApk(): ByteArray {
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
    return out.toByteArray()
}
