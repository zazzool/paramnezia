package net.paramnezia.app.lan

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import net.paramnezia.app.platform.Log
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The share's HTTP server: plain HTTP/1.1, one request per connection, local clients only.
 *
 * - `/`            the page for browsers (TV, computer, phone): download buttons and «Получить настройки»;
 * - `/a`, `/w`     the Android app and the Windows installer (short paths, easy to type on a TV);
 * - `/info`        [LanInfo] for apps;
 * - `POST /join`   a device asks for settings ([LanAsk]) → [LanAnswer] with an id;
 * - `/join/<id>`   the answer, waiting up to 20 s for the owner (long poll);
 * - `/k/<id>`      the approved settings as a `.vpn` file (for browsers on a computer).
 *
 * Settings are handed out only after the owner allows the request on their screen ([approve]).
 */
class LanServer(
    private val info: () -> LanInfo,
    private val apk: () -> LanFile?,
    private val exe: () -> LanFile?,
) {
    /** A device waiting for the owner's answer. */
    data class Ask(val id: String, val device: String, val kind: String, val from: String, val at: Long)

    private class Pending(val ask: Ask, @Volatile var answer: LanAnswer, @Volatile var until: Long)

    private val pending = ConcurrentHashMap<String, Pending>()
    private val _asks = MutableStateFlow<List<Ask>>(emptyList())
    /** Requests waiting for the owner, oldest first. */
    val asks: StateFlow<List<Ask>> = _asks
    private val _served = MutableStateFlow<Map<String, Int>>(emptyMap())
    /** Downloads so far: "apk" / "exe" / "settings" → count. */
    val served: StateFlow<Map<String, Int>> = _served

    private var socket: ServerSocket? = null
    private val running = AtomicBoolean(false)
    private val pool = ThreadPoolExecutor(0, MAX_CLIENTS, 30, TimeUnit.SECONDS, java.util.concurrent.SynchronousQueue()) { r ->
        Thread(r, "lan-share").apply { isDaemon = true }
    }
    private val random = SecureRandom()
    @Volatile private var lans: List<LanNet.Lan> = emptyList()
    @Volatile private var lansAt = 0L

    val port: Int get() = socket?.localPort ?: 0

    /** Binds the first free port of [ports]; throws if none is free. */
    fun start(ports: List<Int> = LanProtocol.PORTS) {
        check(running.compareAndSet(false, true)) { "already running" }
        var last: Exception? = null
        for (p in ports + 0) {
            try {
                val s = ServerSocket()
                s.reuseAddress = true
                s.bind(InetSocketAddress(p), 32)
                socket = s
                break
            } catch (e: Exception) { last = e }
        }
        val s = socket ?: run { running.set(false); throw IllegalStateException("Не удалось открыть порт для раздачи", last) }
        Thread({ acceptLoop(s) }, "lan-accept").apply { isDaemon = true }.start()
        Log.i(TAG, "sharing on port ${s.localPort}")
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        runCatching { socket?.close() }
        socket = null
        for (p in pending.values) if (p.answer.state == WAITING) p.answer = p.answer.copy(state = "expired")
        publishAsks()
    }

    /** The owner allows a request: [key] goes to the device that asked. */
    fun approve(id: String, key: String) {
        val p = pending[id] ?: return
        p.answer = p.answer.copy(state = "ok", key = key)
        p.until = System.currentTimeMillis() + LanProtocol.KEY_TTL_MS
        count("settings")
        publishAsks()
    }

    fun deny(id: String, message: String? = null) {
        val p = pending[id] ?: return
        p.answer = p.answer.copy(state = if (message == null) "denied" else "failed", message = message)
        p.until = System.currentTimeMillis() + LanProtocol.KEY_TTL_MS
        publishAsks()
    }

    private fun publishAsks() {
        val now = System.currentTimeMillis()
        pending.values.removeIf { it.until < now }
        _asks.value = pending.values.filter { it.answer.state == WAITING }.map { it.ask }.sortedBy { it.at }
    }

    private fun count(what: String) = _served.update { it + (what to ((it[what] ?: 0) + 1)) }

    private fun acceptLoop(s: ServerSocket) {
        while (running.get()) {
            val c = try { s.accept() } catch (_: SocketException) { break } catch (e: Exception) { Log.w(TAG, "accept", e); continue }
            if (!neighbour(c.inetAddress)) { runCatching { c.close() }; continue }
            try {
                pool.execute { c.use { runCatching { serve(it) }.onFailure { e -> if (e !is SocketException) Log.w(TAG, "serve", e) } } }
            } catch (_: java.util.concurrent.RejectedExecutionException) {
                runCatching { c.close() }
            }
        }
    }

    private fun neighbour(a: InetAddress): Boolean {
        val now = System.currentTimeMillis()
        if (now - lansAt > 10_000) { lans = LanNet.interfaces(); lansAt = now }
        return LanNet.isNeighbour(a, lans)
    }

    // --- HTTP -------------------------------------------------------------------------------------

    private class Request(val method: String, val path: String, val query: Map<String, String>, val headers: Map<String, String>, val body: ByteArray)

    private fun serve(c: Socket) {
        c.soTimeout = 15_000
        c.tcpNoDelay = true
        val input = BufferedInputStream(c.getInputStream())
        val out = c.getOutputStream()
        val req = read(input) ?: return respond(out, 400, "text/plain", "bad request")
        val from = c.inetAddress.hostAddress.orEmpty()
        val get = req.method == "GET" || req.method == "HEAD"
        when {
            req.method == "GET" && (req.path == "/" || req.path == "/index.html") ->
                respond(out, 200, "text/html; charset=utf-8", LanPage.render(info(), apk(), exe(), req.headers["user-agent"].orEmpty()))
            req.method == "GET" && req.path == "/info" -> respondJson(out, 200, LanProtocol.json.encodeToString(LanInfo.serializer(), info()))
            get && (req.path == "/a" || req.path.endsWith(".apk")) -> sendFile(out, req, apk(), "apk")
            get && (req.path == "/w" || req.path.endsWith(".exe")) -> sendFile(out, req, exe(), "exe")
            req.method == "POST" && req.path == "/join" -> join(out, req, from)
            req.method == "GET" && req.path.startsWith("/join/") -> answer(out, req.path.removePrefix("/join/"))
            req.method == "GET" && req.path.startsWith("/k/") -> keyFile(out, req.path.removePrefix("/k/"))
            req.method == "GET" && req.path == "/favicon.ico" -> respond(out, 204, "text/plain", "")
            req.method == "HEAD" -> respond(out, 200, "text/plain", "")
            else -> respond(out, 404, "text/html; charset=utf-8", LanPage.notFound())
        }
    }

    private fun join(out: OutputStream, req: Request, from: String) {
        if (!info().settings) return respondJson(out, 403, answerJson(LanAnswer("", "failed", message = "Здесь раздают только приложение")))
        val ask = runCatching { LanProtocol.json.decodeFromString(LanAsk.serializer(), req.body.decodeToString()) }.getOrNull()
            ?: LanAsk(req.query["device"].orEmpty(), req.query["kind"].orEmpty())
        publishAsks()
        if (pending.values.count { it.answer.state == WAITING } >= MAX_ASKS) {
            return respondJson(out, 429, answerJson(LanAnswer("", "failed", message = "Слишком много запросов, подождите")))
        }
        val id = ByteArray(16).also(random::nextBytes).joinToString("") { "%02x".format(it) }
        val device = ask.device.replace(Regex("\\p{Cntrl}"), "").trim().take(40).ifEmpty { "Устройство" }
        val a = Ask(id, device, ask.kind.take(16), from, System.currentTimeMillis())
        pending[id] = Pending(a, LanAnswer(id, WAITING), System.currentTimeMillis() + LanProtocol.ASK_TTL_MS)
        publishAsks()
        respondJson(out, 200, answerJson(LanAnswer(id, WAITING)))
    }

    private fun answer(out: OutputStream, id: String) {
        val deadline = System.currentTimeMillis() + 20_000
        var p = pending[id]
        while (p != null && p.answer.state == WAITING && running.get() && System.currentTimeMillis() < deadline) {
            Thread.sleep(250)
            p = pending[id]
        }
        val a = p?.answer?.takeIf { p.until >= System.currentTimeMillis() } ?: LanAnswer(id, "expired")
        respondJson(out, 200, answerJson(a))
    }

    private fun keyFile(out: OutputStream, id: String) {
        val key = pending[id]?.takeIf { it.until >= System.currentTimeMillis() }?.answer?.key
            ?: return respond(out, 404, "text/html; charset=utf-8", LanPage.notFound())
        val body = key.toByteArray()
        val name = "paramnezia.vpn"
        out.write(header(200, "application/x-paramnezia", body.size.toLong(), "Content-Disposition: attachment; filename=\"$name\"\r\n"))
        out.write(body)
        out.flush()
    }

    private fun sendFile(out: OutputStream, req: Request, file: LanFile?, what: String) {
        if (file == null) return respond(out, 404, "text/html; charset=utf-8", LanPage.notFound())
        val enc = URLEncoder.encode(file.fileName, "UTF-8").replace("+", "%20")
        val disposition = "Content-Disposition: attachment; filename=\"${file.fileName}\"; filename*=UTF-8''$enc\r\n"
        out.write(header(200, file.mime, file.size, disposition))
        if (req.method == "HEAD") return out.flush()
        count(what)
        file.open().use { it.copyTo(out, 64 * 1024) }
        out.flush()
    }

    private fun answerJson(a: LanAnswer) = LanProtocol.json.encodeToString(LanAnswer.serializer(), a)

    private fun respondJson(out: OutputStream, code: Int, json: String) = respond(out, code, "application/json; charset=utf-8", json)

    private fun respond(out: OutputStream, code: Int, type: String, body: String) {
        val bytes = body.toByteArray()
        out.write(header(code, type, bytes.size.toLong()))
        out.write(bytes)
        out.flush()
    }

    private fun header(code: Int, type: String, length: Long, extra: String = ""): ByteArray =
        ("HTTP/1.1 $code ${reason(code)}\r\nContent-Type: $type\r\nContent-Length: $length\r\n" +
            "Cache-Control: no-store\r\nConnection: close\r\nX-Content-Type-Options: nosniff\r\n$extra\r\n").toByteArray()

    private fun reason(code: Int) = when (code) {
        200 -> "OK"; 204 -> "No Content"; 400 -> "Bad Request"; 403 -> "Forbidden"; 404 -> "Not Found"; 429 -> "Too Many Requests"; else -> "Error"
    }

    private fun read(input: InputStream): Request? {
        val head = ByteArrayOutputStream()
        var last4 = 0
        while (true) {
            val b = input.read()
            if (b < 0) return null
            head.write(b)
            last4 = (last4 shl 8) or b
            if (last4 == 0x0d0a0d0a) break
            if (head.size() > 16 * 1024) return null
        }
        val lines = head.toString(Charsets.ISO_8859_1.name()).split("\r\n")
        val parts = lines.first().split(' ')
        if (parts.size < 2) return null
        val headers = lines.drop(1).filter { ':' in it }.associate { it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim() }
        val target = parts[1]
        val path = target.substringBefore('?')
        val query = target.substringAfter('?', "").split('&').filter { '=' in it }.associate {
            java.net.URLDecoder.decode(it.substringBefore('='), "UTF-8") to java.net.URLDecoder.decode(it.substringAfter('='), "UTF-8")
        }
        val length = headers["content-length"]?.toIntOrNull()?.coerceIn(0, 8192) ?: 0
        val body = ByteArray(length)
        var off = 0
        while (off < length) {
            val n = input.read(body, off, length - off)
            if (n < 0) break
            off += n
        }
        return Request(parts[0].uppercase(), path, query, headers, body)
    }

    companion object {
        private const val TAG = "LanShare"
        private const val WAITING = "waiting"
        private const val MAX_CLIENTS = 24
        private const val MAX_ASKS = 8
    }
}
