package net.paramnezia.core

import net.paramnezia.core.fleet.Ed25519
import net.paramnezia.core.ota.Release
import net.paramnezia.core.ota.ReleaseCodec
import net.paramnezia.core.ota.UpdateChecker
import net.paramnezia.core.ota.verifyingCopy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.Base64

class OtaTest {
    private val ed = Ed25519.Jca
    private val apk = ByteArray(200_000) { (it * 31).toByte() }
    private val sha = MessageDigest.getInstance("SHA-256").digest(apk).joinToString("") { "%02x".format(it) }
    private val key = ed.generate()
    private val pinned = listOf(Base64.getEncoder().encodeToString(key.second))

    private fun release(code: Long, pkg: String = "net.paramnezia", minSdk: Int = 26) =
        Release(pkg, code, "1.$code", minSdk, sha, apk.size.toLong(), urls = listOf("https://psync.ru/paramnezia/a.apk"))

    private fun signed(r: Release, seed: ByteArray = key.first) = ReleaseCodec.encode(ReleaseCodec.sign(r, seed, ed))

    private fun checker(installed: Long = 5) = UpdateChecker("net.paramnezia", installed, 34, pinned, ed)

    @Test fun acceptsNewerSignedRelease() {
        val c = checker()
        assertTrue(c.offer(signed(release(6))))
        assertEquals(6L, c.best!!.first.versionCode)
        assertTrue(c.offer(signed(release(8))))
        assertFalse("older than best", c.offer(signed(release(7))))
        assertEquals("https://psync.ru/paramnezia/a.apk", c.best!!.first.publicApkUrls().first())
    }

    @Test fun rejectsDowngradesForeignKeysAndTampering() {
        val c = checker()
        assertFalse(c.offer(signed(release(5))))
        assertFalse(c.offer(signed(release(4))))
        assertFalse(c.offer(signed(release(9), ed.generate().first)))
        assertFalse(c.offer(signed(release(9, pkg = "evil.app"))))
        assertFalse(c.offer(signed(release(9, minSdk = 99))))
        val good = ReleaseCodec.sign(release(9), key.first, ed)
        val tampered = good.copy(body = good.body.replace(sha, "0".repeat(64)))
        assertNull(ReleaseCodec.open(tampered, pinned, ed))
        assertFalse(c.offer(ReleaseCodec.encode(tampered)))
        assertFalse(c.offer("garbage"))
    }

    @Test fun remembersTheInstalledRelease() {
        val c = checker()
        assertFalse("same version is not an update", c.offer(signed(release(5))))
        assertEquals(5L, c.installed!!.first.versionCode)
        assertNull(c.best)
        assertEquals(listOf("https://psync.ru/paramnezia/a.apk"), c.installed!!.first.publicApkUrls())
    }

    @Test fun verifiesDownloadedBytes() {
        val r = release(6)
        val out = ByteArrayOutputStream()
        assertTrue(verifyingCopy(ByteArrayInputStream(apk), { b, n -> out.write(b, 0, n) }, r))
        assertEquals(apk.size, out.size())
        val bad = apk.copyOf().also { it[100] = (it[100] + 1).toByte() }
        assertFalse(verifyingCopy(ByteArrayInputStream(bad), { _, _ -> }, r))
        assertFalse(verifyingCopy(ByteArrayInputStream(apk + 1), { _, _ -> }, r))
    }

    @Test fun publicUrlsAreHttpsOnly() {
        val r = release(7).copy(urls = listOf("https://psync.ru/paramnezia/paramnezia-v7.apk", "http://10.8.1.1:8080/apk/x"))
        assertEquals(listOf("https://psync.ru/paramnezia/paramnezia-v7.apk"), r.publicApkUrls())
    }
}
