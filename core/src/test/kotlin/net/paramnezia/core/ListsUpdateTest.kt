package net.paramnezia.core

import net.paramnezia.core.fleet.Ed25519
import net.paramnezia.core.ota.Release
import net.paramnezia.core.ota.ReleaseCodec
import net.paramnezia.core.routing.ListsBundle
import net.paramnezia.core.routing.ListsCodec
import net.paramnezia.core.routing.ListsRelease
import net.paramnezia.core.routing.SignedLists
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.util.Base64

class ListsUpdateTest {
    private val ed = Ed25519.Jca
    private val key = ed.generate()
    private val pinned = listOf(Base64.getEncoder().encodeToString(key.second))

    /** The real bundled lists (app/src/main/assets/bypass). */
    private val files = ListsBundle.FILES.associateWith { File("../app/src/main/assets/bypass/$it").readText() }
    private val version = ListsBundle.version(files.getValue("meta.json"))!!

    private fun release(text: String) = ListsRelease(version, ListsBundle.sha256(text), text.toByteArray().size.toLong(), listOf("https://example.org/l.txt"))

    @Test
    fun roundTripOfTheRealLists() {
        val text = ListsBundle.pack(files)
        val signed = ListsCodec.encode(ListsCodec.sign(release(text), key.first, ed))
        val r = ListsCodec.open(ListsCodec.decode(signed)!!, pinned, ed)!!
        assertEquals(version, r.version)
        assertEquals(files, ListsBundle.unpack(text, r))
    }

    @Test
    fun foreignKeyOrAReleaseSignatureIsRefused() {
        val text = ListsBundle.pack(files)
        val other = ed.generate()
        assertNull(ListsCodec.open(ListsCodec.sign(release(text), other.first, ed), pinned, ed))
        // An app release signed by the same key cannot pass for lists (separate signature domains).
        val rel = ReleaseCodec.sign(Release("net.paramnezia", 1, "1", sha256 = "0".repeat(64), size = 1), key.first, ed)
        assertNull(ListsCodec.open(SignedLists(rel.body, rel.sig), pinned, ed))
    }

    @Test
    fun damagedOrImplausibleBundlesAreRefused() {
        val text = ListsBundle.pack(files)
        val r = release(text)
        assertNull("truncated", ListsBundle.unpack(text.dropLast(10), r))
        val tampered = text.replace("77.88.0.0/18", "8.8.8.0/24")
        assertNull("not what was signed", ListsBundle.unpack(tampered, r))
        // Correctly signed but nearly empty: refused, the lists in use stay.
        val tiny = ListsBundle.pack(files + ("ipv4.txt" to "77.88.0.0/18\n"))
        assertNull("too small to be real", ListsBundle.unpack(tiny, release(tiny)))
        val wrongVersion = ListsBundle.pack(files)
        assertNull(ListsBundle.unpack(wrongVersion, release(wrongVersion).copy(version = version + 1)))
        assertNotNull(ListsBundle.unpack(text, r))
    }
}
