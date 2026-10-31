package io.paramnezia.deploy

import io.paramnezia.core.apk.ApkSlot
import java.io.File
import java.io.RandomAccessFile
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ApkTemplateTest {
    @Test
    fun theInviteThisDeviceCameWithIsBlankedOnTheWayOut() {
        val src = File.createTempFile("src", ".apk").apply { writeBytes(fakeSignedApk()); deleteOnExit() }
        val installed = File.createTempFile("installed", ".apk").apply { deleteOnExit() }
        val off = ApkSlot.prepare(src, installed)
        RandomAccessFile(installed, "rw").use { it.seek(off); it.write(ApkSlot.encode("vpn://admin-secret")) }
        val before = installed.readBytes()

        val template = InviteLinks.ApkTemplate(installed, ApkSlot.offset(installed)!!)
        val served = template.open().use { it.readBytes() }

        assertEquals(before.size, served.size)
        assertArrayEquals(before.copyOfRange(0, off.toInt()), served.copyOfRange(0, off.toInt()))
        assertArrayEquals(ByteArray(ApkSlot.SIZE), served.copyOfRange(off.toInt(), off.toInt() + ApkSlot.SIZE))
        assertArrayEquals(before.copyOfRange(off.toInt() + ApkSlot.SIZE, before.size), served.copyOfRange(off.toInt() + ApkSlot.SIZE, served.size))
        assertNull(ApkSlot.read(File.createTempFile("served", ".apk").apply { writeBytes(served); deleteOnExit() }))
        assertArrayEquals(before, installed.readBytes()) // the installed APK is never touched
        assertEquals(64, template.sha256.length)
    }
}
