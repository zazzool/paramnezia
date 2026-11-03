package net.paramnezia.app.lan

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import net.paramnezia.app.platform.readUpTo
import java.io.BufferedInputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * The asking side: plain HTTP over a socket to a neighbour. Not HttpURLConnection, which on Android
 * refuses cleartext to anything but our tunnel server (network_security_config) and may go through
 * a proxy; a LAN address must be reached directly.
 */
object LanClient {
    class Reply(val code: Int, val body: String)

    fun request(host: String, port: Int, method: String, path: String, body: String? = null, timeoutMs: Int = 8_000, readTimeoutMs: Int = timeoutMs): Reply {
        Socket().use { s ->
            s.connect(InetSocketAddress(host, port), timeoutMs)
            s.soTimeout = readTimeoutMs
            val bytes = body?.toByteArray() ?: ByteArray(0)
            val head = buildString {
                append("$method $path HTTP/1.1\r\nHost: ${LanNet.shortAddress(host, port)}\r\nUser-Agent: paramnezia\r\nConnection: close\r\n")
                if (body != null) append("Content-Type: application/json\r\nContent-Length: ${bytes.size}\r\n")
                append("\r\n")
            }
            val out = s.getOutputStream()
            out.write(head.toByteArray())
            out.write(bytes)
            out.flush()
            val raw = BufferedInputStream(s.getInputStream()).readUpTo(256 * 1024).toString(Charsets.UTF_8)
            val status = raw.substringBefore("\r\n").split(' ').getOrNull(1)?.toIntOrNull() ?: 0
            return Reply(status, raw.substringAfter("\r\n\r\n", ""))
        }
    }

    /** The sharer's [LanInfo]; null if it is not a Paramnezia share or does not answer. */
    suspend fun info(host: String, port: Int): LanInfo? = withContext(Dispatchers.IO) {
        runCatching {
            val r = request(host, port, "GET", "/info", timeoutMs = 4_000)
            if (r.code != 200) null else LanProtocol.json.decodeFromString(LanInfo.serializer(), r.body).takeIf { it.app == "paramnezia" }
        }.getOrNull()
    }

    /**
     * Asks [peer] for settings and waits until its owner answers (up to [LanProtocol.ASK_TTL_MS]).
     * Returns the key; throws with a message for the person otherwise.
     */
    suspend fun askSettings(peer: LanPeer, device: String, kind: String): String = withContext(Dispatchers.IO) {
        val askBody = LanProtocol.json.encodeToString(LanAsk.serializer(), LanAsk(device, kind))
        val first = try {
            request(peer.host, peer.port, "POST", "/join", askBody)
        } catch (e: Exception) {
            throw IllegalStateException("${peer.name} не отвечает. Проверьте, что раздача ещё открыта и оба устройства в одной Wi-Fi.", e)
        }
        val started = decode(first.body) ?: throw IllegalStateException("${peer.name} ответил непонятно (код ${first.code})")
        if (started.state != "waiting") throw IllegalStateException(started.message ?: "Настройки не выдали")
        val deadline = System.currentTimeMillis() + LanProtocol.ASK_TTL_MS
        var failures = 0
        while (System.currentTimeMillis() < deadline) {
            val a = runCatching { decode(request(peer.host, peer.port, "GET", "/join/${started.id}", readTimeoutMs = 30_000).body) }.getOrNull()
            if (a == null) {
                if (++failures >= 10) throw IllegalStateException("Связь с ${peer.name} пропала")
                delay(1_500)
                continue
            }
            failures = 0
            when (a.state) {
                "waiting" -> continue
                "ok" -> return@withContext a.key ?: throw IllegalStateException("Пустой ответ")
                "denied" -> throw IllegalStateException("На ${peer.name} запрос отклонили")
                "expired" -> throw IllegalStateException("Запрос устарел: попросите ещё раз")
                else -> throw IllegalStateException(a.message ?: "Настройки не выдали")
            }
        }
        throw IllegalStateException("На ${peer.name} так и не ответили")
    }

    private fun decode(body: String): LanAnswer? = runCatching { LanProtocol.json.decodeFromString(LanAnswer.serializer(), body) }.getOrNull()
}
