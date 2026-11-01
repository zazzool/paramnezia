package net.paramnezia.app.tunnel

import android.content.Context
import android.net.Network
import net.paramnezia.tunnel.TunnelRuntime
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import javax.net.SocketFactory

/**
 * Two ways out: through whatever the system routes (the tunnel, when it is up) and straight over the
 * physical network. Channels that must work while our server is blocked use both.
 */
class Net(private val context: Context) {
    fun direct(): Network? = TunnelRuntime.directNetwork(context)

    fun directSockets(): SocketFactory = direct()?.socketFactory ?: SocketFactory.getDefault()

    /**
     * GET [url], first the normal way (split routing decides: tunnel or direct), then, if
     * [fallbackDirect], over the physical network. Null if all fail.
     */
    fun get(url: String, maxBytes: Int = 1 shl 20, fallbackDirect: Boolean = true, fresh: Boolean = false, timeoutMs: Int = 20_000): String? {
        for (network in if (fallbackDirect) listOf(null, direct()) else listOf(null)) {
            runCatching {
                val c = open(url, network, timeoutMs)
                // A pooled keep-alive socket remembers the route it was opened on: a connection made
                // before the tunnel came up would still go direct. Checks of "how sites see me" must not reuse one.
                if (fresh) { c.setRequestProperty("Connection", "close"); c.useCaches = false }
                try {
                    if (c.responseCode == 200) {
                        return c.inputStream.use { it.readUpTo(maxBytes) }.decodeToString()
                    }
                } finally { c.disconnect() }
            }
            if (network == null && direct() == null) break
        }
        return null
    }

    /** Streams [url] through [consume]; returns its result or null on any failure. */
    fun <T> download(url: String, network: Network?, consume: (java.io.InputStream) -> T): T? = runCatching {
        val c = open(url, network, readTimeoutMs = 60_000)
        try {
            if (c.responseCode != 200) null else c.inputStream.use(consume)
        } finally { c.disconnect() }
    }.getOrNull()

    private fun open(url: String, network: Network?, readTimeoutMs: Int = 20_000): HttpURLConnection {
        val u = URL(url)
        val c = (network?.openConnection(u) ?: u.openConnection()) as HttpURLConnection
        c.connectTimeout = minOf(15_000, readTimeoutMs)
        c.readTimeout = readTimeoutMs
        c.setRequestProperty("User-Agent", "paramnezia")
        return c
    }

    fun cacheFile(name: String) = File(context.cacheDir, name)
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
