package io.paramnezia.core

import io.paramnezia.core.fleet.Ed25519
import io.paramnezia.core.fleet.GossipMessage
import io.paramnezia.core.nostr.NostrRelease
import io.paramnezia.core.ota.Release
import io.paramnezia.core.ota.ReleaseCodec
import io.paramnezia.core.ota.UpdateChecker
import io.paramnezia.core.ota.verifyingCopy
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

    private fun release(code: Long, pkg: String = "io.paramnezia", minSdk: Int = 26) =
        Release(pkg, code, "1.$code", minSdk, sha, apk.size.toLong(), blossom = listOf("https://blossom.example/"))

    private fun signed(r: Release, seed: ByteArray = key.first) = ReleaseCodec.encode(ReleaseCodec.sign(r, seed, ed))

    private fun checker(installed: Long = 5) = UpdateChecker("io.paramnezia", installed, 34, pinned, ed)

    @Test fun acceptsNewerSignedRelease() {
        val c = checker()
        assertTrue(c.offer(signed(release(6))))
        assertEquals(6L, c.best!!.first.versionCode)
        assertTrue(c.offer(signed(release(8))))
        assertFalse("older than best", c.offer(signed(release(7))))
        assertEquals("https://blossom.example/$sha", c.best!!.first.downloadUrls().first())
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
        assertEquals(listOf("https://blossom.example/$sha.apk"), c.installed!!.first.publicApkUrls())
        val plain = release(5).copy(blossom = listOf("http://insecure.example"), urls = listOf("https://github.example/a.apk"))
        assertEquals(listOf("https://github.example/a.apk"), plain.publicApkUrls())
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

    @Test fun travelsOverNostrAndGossip() {
        val text = signed(release(7))
        val secret = NostrRelease.secretFromSeed(key.first)
        val ev = NostrRelease.event(text, secret, 7)
        val forged = NostrRelease.event(signed(release(99)), NostrRelease.secretFromSeed(ed.generate().first), 99)
        val got = NostrRelease.candidates(listOf(ev, forged), setOf(NostrRelease.publisherPubkey(key.first)))
        assertEquals(listOf(text), got)
        val g = GossipMessage.decode(GossipMessage.encode(GossipMessage.Release(text))) as GossipMessage.Release
        assertTrue(checker().offer(g.signed))
    }
}
