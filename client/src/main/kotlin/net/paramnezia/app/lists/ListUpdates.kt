package net.paramnezia.app.lists

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import net.paramnezia.app.platform.Http
import net.paramnezia.app.platform.Log
import net.paramnezia.core.fleet.Ed25519
import net.paramnezia.core.routing.ListsBundle
import net.paramnezia.core.routing.ListsCodec
import java.io.File

/**
 * The bypass lists in use: the ones bundled with the app, or a newer signed set fetched since
 * (see [net.paramnezia.core.routing.ListsBundle]). Shared by Android and Windows.
 *
 * Costs: one ~400-byte `lists.json` a day; the ~210 KB bundle only when there is a newer version
 * (weekly at most). The stored set is verified again (signature, size, SHA-256) every time it is
 * loaded, so a damaged file falls back to the bundled lists instead of breaking routing.
 */
class ListUpdates(
    private val dir: File,
    /** The bundled file of that name (Android assets, Windows resources). */
    private val bundled: (String) -> String,
    private val net: Http,
    private val pinnedKey: String,
    private val ed: Ed25519,
    /** HTTPS address of lists.json; null disables updates (builds without a release key). */
    private val url: String?,
) {
    private val bundledVersion: Long by lazy { ListsBundle.version(bundled("meta.json")) ?: 0 }
    @Volatile private var stored: Map<String, String>? = null
    @Volatile private var loaded = false

    private val _version = MutableStateFlow(0L)

    /** The version of the lists in use (loads the stored set on first use). */
    fun version(): Long { load(); return _version.value }

    private val _taken = MutableStateFlow(0)
    /** Counts newer sets taken while running: routes and DNS rules follow each change. */
    val taken: StateFlow<Int> = _taken

    /** The list file [name] in use. */
    fun text(name: String): String {
        load()
        return stored?.get(name) ?: bundled(name)
    }

    private fun load() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            stored = runCatching { read() }.getOrNull()?.takeIf { (v, _) -> v > bundledVersion }?.second
            _version.value = stored?.let { ListsBundle.version(it.getValue("meta.json")) } ?: bundledVersion
            loaded = true
        }
    }

    private fun read(): Pair<Long, Map<String, String>>? {
        val signed = File(dir, SIGNED).takeIf { it.isFile }?.readText()?.let(ListsCodec::decode) ?: return null
        val r = ListsCodec.open(signed, listOf(pinnedKey), ed) ?: return null
        val files = ListsBundle.unpack(File(dir, BUNDLE).readText(), r) ?: return null
        return r.version to files
    }

    /** Looks for newer lists at most once a day (or now if [force]). True if a newer set was taken. */
    suspend fun checkIfDue(force: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        val u = url?.takeIf { it.startsWith("https://") && pinnedKey.isNotEmpty() } ?: return@withContext false
        load()
        val stamp = File(dir, CHECKED)
        if (!force && System.currentTimeMillis() - stamp.lastModified() < DAY_MS) return@withContext false
        dir.mkdirs()
        val taken = runCatching { fetch(u) }.onFailure { Log.w(TAG, "lists: ${it.message}") }.getOrDefault(false)
        runCatching { stamp.writeText(""); stamp.setLastModified(System.currentTimeMillis()) }
        taken
    }

    private fun fetch(u: String): Boolean {
        val signedText = net.get(u, 16_384) ?: return false
        val r = ListsCodec.decode(signedText)?.let { ListsCodec.open(it, listOf(pinnedKey), ed) } ?: return false
        if (r.version <= version()) return false
        val text = r.urls.filter { it.startsWith("https://") }.firstNotNullOfOrNull { net.get(it, ListsCodec.MAX_BYTES.toInt()) } ?: return false
        val files = ListsBundle.unpack(text, r) ?: run { Log.w(TAG, "lists ${r.version}: bundle refused"); return false }
        // Bundle first, signature last: a crash in between leaves the old signature, which no longer
        // matches the new bundle, so the bundled lists are used until the next check fixes it.
        write(BUNDLE, text)
        write(SIGNED, signedText)
        stored = files
        _version.value = r.version
        _taken.value++
        Log.i(TAG, "lists ${r.version} taken")
        return true
    }

    private fun write(name: String, text: String) {
        val tmp = File(dir, "$name.tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(File(dir, name))) { File(dir, name).delete(); tmp.renameTo(File(dir, name)) }
    }

    companion object {
        private const val TAG = "PnLists"
        private const val SIGNED = "lists.json"
        private const val BUNDLE = "lists.txt"
        private const val CHECKED = "checked"
        private const val DAY_MS = 24 * 3600_000L

        /** lists.json next to the app releases: `…/releases/latest/download/release.json` → `…/releases/download/bypass-lists/lists.json`. */
        fun urlFor(updateUrl: String): String? =
            updateUrl.takeIf { it.startsWith("https://") && it.endsWith("/releases/latest/download/release.json") }
                ?.replace("/releases/latest/download/release.json", "/releases/download/$TAG_NAME/lists.json")

        const val TAG_NAME = "bypass-lists"
    }
}
