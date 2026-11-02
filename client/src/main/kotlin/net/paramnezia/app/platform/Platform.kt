package net.paramnezia.app.platform

import net.paramnezia.core.ota.Release

/**
 * What the shared client logic needs from the system it runs on. Android and Windows each provide
 * their own: logging, the encrypted state file, HTTP over the tunnel and past it, release info.
 */

/** Logging with a pluggable sink (Android: `android.util.Log`; Windows: a log file). */
object Log {
    enum class Level { DEBUG, INFO, WARN, ERROR }

    fun interface Sink { fun write(level: Level, tag: String, message: String, error: Throwable?) }

    @Volatile var sink: Sink = Sink { level, tag, message, error ->
        System.err.println("${level.name.first()}/$tag: $message")
        error?.printStackTrace()
    }

    fun d(tag: String, message: String) = sink.write(Level.DEBUG, tag, message, null)
    fun i(tag: String, message: String) = sink.write(Level.INFO, tag, message, null)
    fun w(tag: String, message: String, error: Throwable? = null) = sink.write(Level.WARN, tag, message, error)
    fun e(tag: String, message: String, error: Throwable? = null) = sink.write(Level.ERROR, tag, message, error)
}

/**
 * The encrypted file all app state lives in. Implementations write atomically, keep the previous
 * good state as a backup and move unreadable files aside instead of overwriting them.
 */
interface StateStore {
    /** The stored text, or null when there is none (or nothing readable). */
    fun read(): String?
    fun write(text: String)
    /** Moves the stored files aside (kept, never deleted) so the next write starts fresh. */
    fun quarantine()
    /** Why the last [read] fell back, for logs; null when the main file was fine. */
    val lastProblem: String?
}

/**
 * Two ways out: the normal one (split routing decides: tunnel or direct) and straight over the
 * physical network, past our VPN. Channels that must work while our server is blocked use both.
 */
interface Http {
    /** One answer of [fetch]: the body, or why there is none (for checks that must say what failed). */
    data class Fetched(val body: String?, val error: String?)

    /**
     * GET [url], first the normal way, then, if [fallbackDirect], over the physical network. Null if
     * all fail. [fresh] never reuses a pooled connection (one opened before the tunnel keeps its route).
     */
    fun get(url: String, maxBytes: Int = 1 shl 20, fallbackDirect: Boolean = true, fresh: Boolean = false, timeoutMs: Int = 20_000): String?

    /** One GET on a fresh connection, the normal way or [direct]ly; reports why it failed. */
    fun fetch(url: String, direct: Boolean = false, maxBytes: Int = 4096, timeoutMs: Int = 8_000, userAgent: String = "paramnezia"): Fetched

    /** GET through a local Tor client (Orbot / Tor Browser) when one runs; null otherwise. */
    fun getViaTor(url: String, maxBytes: Int = 1 shl 20): String?

    /** The physical network past the VPN can be used now. */
    fun hasDirect(): Boolean
}

/** The signed app release this device can point others at (invite pages, server copies). */
interface ReleaseSource {
    /** The newest signed release (and its wire form) that others may install, if known. */
    fun shareable(): Pair<Release, String>?
    /** The newest signed Windows installer release, for the family page; null if not known. */
    fun windows(): Release? = null
    /** Reads the release channels once. */
    suspend fun check()
}

/** Reads at most [max] bytes (InputStream.readNBytes needs Android 13). */
fun java.io.InputStream.readUpTo(max: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buf = ByteArray(16 * 1024)
    while (out.size() < max) {
        val n = read(buf, 0, minOf(buf.size, max - out.size()))
        if (n < 0) break
        out.write(buf, 0, n)
    }
    return out.toByteArray()
}
