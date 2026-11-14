package net.paramnezia.app.tunnel

import android.content.Context
import android.net.Network
import net.paramnezia.app.platform.Http
import net.paramnezia.app.platform.readUpTo
import net.paramnezia.tunnel.TunnelRuntime
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import javax.net.SocketFactory

/**
 * Two ways out: through whatever the system routes (the tunnel, when it is up) and straight over the
 * physical network. Channels that must work while our server is blocked use both.
 */
class Net(private val context: Context) : Http {
    fun direct(): Network? = TunnelRuntime.directNetwork(context)

    /** Our VPN network; Android connects it a moment after the tunnel is built, so wait a little. */
    private fun vpnNetwork(waitMs: Long = 3_000): Network? {
        val until = System.currentTimeMillis() + waitMs
        while (true) {
            TunnelRuntime.vpnNetwork(context)?.let { return it }
            if (System.currentTimeMillis() >= until) return null
            Thread.sleep(150)
        }
    }

    override fun getThroughTunnel(url: String, maxBytes: Int, timeoutMs: Int): String? {
        // Bound to the VPN network (a new Network object, so its own empty connection pool too): the
        // answer is what foreign sites see through the tunnel, never the physical network's address.
        val vpn = vpnNetwork() ?: return null
        return runCatching {
            val c = open(url, vpn, timeoutMs)
            c.setRequestProperty("Connection", "close"); c.useCaches = false
            try { if (c.responseCode == 200) c.inputStream.use { it.readUpTo(maxBytes) }.decodeToString() else null } finally { c.disconnect() }
        }.getOrNull()
    }

    fun directSockets(): SocketFactory = direct()?.socketFactory ?: SocketFactory.getDefault()

    /**
     * GET [url], first the normal way (split routing decides: tunnel or direct), then, if
     * [fallbackDirect], over the physical network. Null if all fail.
     */
    override fun hasDirect(): Boolean = direct() != null

    override fun get(url: String, maxBytes: Int, fallbackDirect: Boolean, fresh: Boolean, timeoutMs: Int): String? {
        for (network in if (fallbackDirect) listOf(null, direct()) else listOf(null)) {
            runCatching {
                // A pooled keep-alive socket remembers the route it was opened on: "fresh" requests never
                // reuse one ("Connection: close", no cache). Checks that must go through the tunnel use
                // getThroughTunnel, bound to the VPN network.
                val c = open(url, network, timeoutMs)
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

    override fun fetch(url: String, direct: Boolean, maxBytes: Int, timeoutMs: Int, userAgent: String): Http.Fetched =
        if (!direct) fetch(url, null as Network?, maxBytes, timeoutMs, userAgent)
        else this.direct()?.let { fetch(url, it, maxBytes, timeoutMs, userAgent) } ?: Http.Fetched(null, "нет сети")

    /**
     * One GET on a fresh connection (never a pooled one: a socket opened before the tunnel came up
     * keeps its old route), over [network] or, when null, the way the system routes it. Unlike
     * [get] it reports the failure: HTTP code, timeout, DNS, TLS, no network.
     */
    fun fetch(url: String, network: Network?, maxBytes: Int = 4096, timeoutMs: Int = 8_000, userAgent: String = "paramnezia"): Http.Fetched =
        try {
            val c = open(url, network, timeoutMs)
            c.connectTimeout = minOf(timeoutMs, 6_000)
            c.setRequestProperty("User-Agent", userAgent)
            c.setRequestProperty("Connection", "close"); c.useCaches = false
            try {
                val code = c.responseCode
                if (code == 200) Http.Fetched(c.inputStream.use { it.readUpTo(maxBytes) }.decodeToString(), null)
                else Http.Fetched(null, "HTTP $code")
            } finally { c.disconnect() }
        } catch (e: java.net.SocketTimeoutException) { Http.Fetched(null, "нет ответа")
        } catch (e: java.net.UnknownHostException) { Http.Fetched(null, "имя не найдено")
        } catch (e: javax.net.ssl.SSLException) { Http.Fetched(null, "ошибка TLS")
        } catch (e: java.net.ConnectException) { Http.Fetched(null, "соединение отклонено")
        } catch (e: Exception) { Http.Fetched(null, e.javaClass.simpleName) }

    /**
     * GET [url] through Orbot's SOCKS port, when Orbot is running. The last way to reach the fleet's
     * HTTPS mirrors when both our server and the direct path are blocked. No Tor code in our app:
     * nothing happens unless the user installed and started Orbot.
     */
    override fun getViaTor(url: String, maxBytes: Int): String? {
        if (!url.startsWith("https://") || !torAvailable()) return null
        return runCatching {
            val proxy = java.net.Proxy(java.net.Proxy.Type.SOCKS, java.net.InetSocketAddress(TOR_HOST, TOR_PORT))
            val c = URL(url).openConnection(proxy) as HttpURLConnection
            c.connectTimeout = 60_000; c.readTimeout = 60_000
            c.setRequestProperty("User-Agent", "paramnezia")
            try {
                if (c.responseCode == 200) c.inputStream.use { it.readUpTo(maxBytes) }.decodeToString() else null
            } finally { c.disconnect() }
        }.getOrNull()
    }

    /** Orbot listens on 127.0.0.1:9050 while it runs; a refused local connect costs nothing. */
    fun torAvailable(): Boolean = runCatching {
        java.net.Socket().use { it.connect(java.net.InetSocketAddress(TOR_HOST, TOR_PORT), 300) }
        true
    }.getOrDefault(false)

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

    private companion object {
        const val TOR_HOST = "127.0.0.1"
        const val TOR_PORT = 9050
    }
}
