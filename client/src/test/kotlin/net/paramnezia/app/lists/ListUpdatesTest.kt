package net.paramnezia.app.lists

import kotlinx.coroutines.runBlocking
import net.paramnezia.app.platform.Http
import net.paramnezia.core.fleet.Ed25519
import net.paramnezia.core.routing.ListsBundle
import net.paramnezia.core.routing.ListsCodec
import net.paramnezia.core.routing.ListsRelease
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.Base64

class ListUpdatesTest {
    private val ed = Ed25519.Jca
    private val key = ed.generate()
    private val pub = Base64.getEncoder().encodeToString(key.second)
    private val bundled = ListsBundle.FILES.associateWith { File("../app/src/main/assets/bypass/$it").readText() }
    private val oldVersion = ListsBundle.version(bundled.getValue("meta.json"))!!

    private fun newer(): Map<String, String> = bundled +
        ("meta.json" to bundled.getValue("meta.json").replace("\"$oldVersion\"", "\"${oldVersion + 7}\"")) +
        ("domains.txt" to bundled.getValue("domains.txt") + "full:new-service.example.ru\n")

    private class Net(val pages: Map<String, String>) : Http {
        var asked = 0
        override fun get(url: String, maxBytes: Int, fallbackDirect: Boolean, fresh: Boolean, timeoutMs: Int): String? { asked++; return pages[url] }
        override fun fetch(url: String, direct: Boolean, maxBytes: Int, timeoutMs: Int, userAgent: String) = Http.Fetched(null, "no")
        override fun getViaTor(url: String, maxBytes: Int): String? = null
        override fun hasDirect() = false
    }

    private fun publish(files: Map<String, String>, seed: ByteArray = key.first): Map<String, String> {
        val text = ListsBundle.pack(files)
        val v = ListsBundle.version(files.getValue("meta.json"))!!
        val r = ListsRelease(v, ListsBundle.sha256(text), text.toByteArray().size.toLong(), listOf("https://x.example/lists-$v.txt"))
        return mapOf("https://x.example/lists.json" to ListsCodec.encode(ListsCodec.sign(r, seed, ed)), "https://x.example/lists-$v.txt" to text)
    }

    @Test
    fun takesNewerSignedListsKeepsThemAndFallsBackWhenDamaged() = runBlocking {
        val dir = Files.createTempDirectory("lists").toFile()
        val net = Net(publish(newer()))
        val u = ListUpdates(dir, bundled::getValue, net, pub, ed, "https://x.example/lists.json")
        assertEquals(oldVersion, u.version())
        assertTrue(u.checkIfDue())
        assertEquals(oldVersion + 7, u.version())
        assertTrue(u.text("domains.txt").contains("new-service.example.ru"))
        assertEquals(1, u.taken.value)
        // Once a day: a second check right away asks nothing.
        val asked = net.asked
        assertFalse(u.checkIfDue())
        assertEquals(asked, net.asked)

        // A restart loads the stored set (verified again).
        val again = ListUpdates(dir, bundled::getValue, Net(emptyMap()), pub, ed, "https://x.example/lists.json")
        assertEquals(oldVersion + 7, again.version())
        // A damaged file: back to the bundled lists, never broken routing.
        File(dir, "lists.txt").appendText("x")
        val damaged = ListUpdates(dir, bundled::getValue, Net(emptyMap()), pub, ed, "https://x.example/lists.json")
        assertEquals(oldVersion, damaged.version())
        assertFalse(damaged.text("domains.txt").contains("new-service.example.ru"))
    }

    @Test
    fun refusesForeignSignaturesAndOlderLists() = runBlocking {
        val dir = Files.createTempDirectory("lists").toFile()
        val foreign = ListUpdates(dir, bundled::getValue, Net(publish(newer(), ed.generate().first)), pub, ed, "https://x.example/lists.json")
        assertFalse(foreign.checkIfDue(force = true))
        val same = ListUpdates(dir, bundled::getValue, Net(publish(bundled)), pub, ed, "https://x.example/lists.json")
        assertFalse(same.checkIfDue(force = true))
        assertEquals(oldVersion, same.version())
    }

    @Test
    fun urlNextToTheReleases() {
        assertEquals("https://github.com/o/r/releases/download/bypass-lists/lists.json",
            ListUpdates.urlFor("https://github.com/o/r/releases/latest/download/release.json"))
    }
}
