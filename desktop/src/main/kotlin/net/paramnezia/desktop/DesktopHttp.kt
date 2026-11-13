package net.paramnezia.desktop

import net.paramnezia.app.platform.Http
import net.paramnezia.app.platform.readUpTo
import java.net.Authenticator
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.PasswordAuthentication
import java.net.Proxy
import java.net.Socket
import java.net.URL
import javax.net.SocketFactory

/**
 * HTTP the normal way (Windows routes it: tunnel or direct, by the split rules) and straight over
 * the physical network, past the tunnel, through the service's local SOCKS proxy. That proxy is
 * the Windows counterpart of Android's "bind to the physical network".
 */
class DesktopHttp(private val client: ServiceClient) : Http {
    @Volatile private var proxy: Proxy? = null
    @Volatile private var creds: Pair<String, String>? = null

    init {
        // Java asks a global authenticator for SOCKS user/password; answer only for our proxy.
        Authenticator.setDefault(object : Authenticator() {
            override fun getPasswordAuthentication(): PasswordAuthentication? {
                val c = creds ?: return null
                val p = proxy?.address() as? InetSocketAddress ?: return null
                if (requestingHost != p.hostString && requestingSite?.isLoopbackAddress != true) return null
                if (requestingPort != p.port) return null
                return PasswordAuthentication(c.first, c.second.toCharArray())
            }
        })
    }

    /** The physical-network proxy, asked from the service once (it lives as long as the service). */
    fun directProxy(): Proxy? {
        proxy?.let { return it }
        return runCatching {
            val r = client.call(ServiceClient.Request("direct"))
            val (h, p) = r.socks!!.split(':')
            creds = r.socksUser!! to r.socksPass!!
            Proxy(Proxy.Type.SOCKS, InetSocketAddress(h, p.toInt())).also { proxy = it }
        }.getOrNull()
    }

    /** Sockets for SSH over the physical network (server management while our tunnel is up). */
    fun directSockets(): SocketFactory? {
        val p = directProxy() ?: return null
        return object : SocketFactory() {
            override fun createSocket() = Socket(p)
            override fun createSocket(host: String, port: Int) = Socket(p).apply { connect(InetSocketAddress.createUnresolved(host, port)) }
            override fun createSocket(host: String, port: Int, localHost: java.net.InetAddress?, localPort: Int) = createSocket(host, port)
            override fun createSocket(host: java.net.InetAddress, port: Int) = Socket(p).apply { connect(InetSocketAddress(host, port)) }
            override fun createSocket(address: java.net.InetAddress, port: Int, localAddress: java.net.InetAddress?, localPort: Int) = createSocket(address, port)
        }
    }

    override fun hasDirect(): Boolean = directProxy() != null

    override fun get(url: String, maxBytes: Int, fallbackDirect: Boolean, fresh: Boolean, timeoutMs: Int): String? {
        if (fresh && !fallbackDirect) return freshGet(url, maxBytes, timeoutMs)
        for (direct in if (fallbackDirect) listOf(false, true) else listOf(false)) {
            val p = if (direct) directProxy() ?: break else null
            runCatching {
                val c = open(url, p, timeoutMs)
                if (fresh) { c.setRequestProperty("Connection", "close"); c.useCaches = false }
                try {
                    if (c.responseCode == 200) return c.inputStream.use { it.readUpTo(maxBytes) }.decodeToString()
                } finally { c.disconnect() }
            }
        }
        return null
    }

    override fun fetch(url: String, direct: Boolean, maxBytes: Int, timeoutMs: Int, userAgent: String): Http.Fetched {
        val p = if (direct) directProxy() ?: return Http.Fetched(null, "нет связи со службой") else null
        return try {
            val c = open(url, p, timeoutMs)
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
    }

    /**
     * GET on a connection opened just now. Java keeps idle keep-alive connections in a process-wide
     * cache and hands them out regardless of "Connection: close", and on Windows a TCP connection
     * opened before the tunnel keeps leaving through the physical adapter. A client of its own (with
     * an empty pool, closed after the call) makes "how sites see me" follow the current routes.
     */
    private fun freshGet(url: String, maxBytes: Int, timeoutMs: Int): String? = runCatching {
        val client = java.net.http.HttpClient.newBuilder()
            .connectTimeout(java.time.Duration.ofMillis(minOf(timeoutMs, 6_000).toLong()))
            .followRedirects(java.net.http.HttpClient.Redirect.NORMAL)
            .build()
        try {
            val req = java.net.http.HttpRequest.newBuilder(java.net.URI(url))
                .timeout(java.time.Duration.ofMillis(timeoutMs.toLong()))
                .header("User-Agent", "paramnezia")
                .GET().build()
            val resp = client.send(req, java.net.http.HttpResponse.BodyHandlers.ofInputStream())
            resp.body().use { body -> if (resp.statusCode() == 200) body.readUpTo(maxBytes).decodeToString() else null }
        } finally {
            (client as? AutoCloseable)?.close()
        }
    }.getOrNull()

    /** Through a Tor client the user runs: the Tor service (9050) or Tor Browser (9150). No Tor code here. */
    override fun getViaTor(url: String, maxBytes: Int): String? {
        if (!url.startsWith("https://")) return null
        val port = TOR_PORTS.firstOrNull { port -> runCatching { Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 300) }; true }.getOrDefault(false) } ?: return null
        return runCatching {
            val c = URL(url).openConnection(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port))) as HttpURLConnection
            c.connectTimeout = 60_000; c.readTimeout = 60_000
            c.setRequestProperty("User-Agent", "paramnezia")
            try { if (c.responseCode == 200) c.inputStream.use { it.readUpTo(maxBytes) }.decodeToString() else null } finally { c.disconnect() }
        }.getOrNull()
    }

    private fun open(url: String, proxy: Proxy?, readTimeoutMs: Int): HttpURLConnection {
        val u = URL(url)
        val c = (if (proxy != null) u.openConnection(proxy) else u.openConnection()) as HttpURLConnection
        c.connectTimeout = minOf(15_000, readTimeoutMs)
        c.readTimeout = readTimeoutMs
        c.setRequestProperty("User-Agent", "paramnezia")
        return c
    }

    private companion object {
        val TOR_PORTS = listOf(9050, 9150)
    }
}
