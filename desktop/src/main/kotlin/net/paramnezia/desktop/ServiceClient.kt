package net.paramnezia.desktop

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.RandomAccessFile

/**
 * Talks to the Paramnezia service (tunnel/src/main/go/windows) over its named pipe: one JSON request,
 * one JSON answer per connection. The pipe accepts only local SYSTEM, administrators and users who
 * are logged on at this computer.
 */
class ServiceClient(private val pipe: String = PIPE) {
    @Serializable
    data class UpSpec(
        val name: String,
        val uapi: String,
        val addresses: List<String>,
        val routes: List<String>,
        val dnsConfig: String = "",
        val dnsServers: List<String> = emptyList(),
        val dnsSearch: List<String> = emptyList(),
        val mtu: Int = 1280,
        /** Programs past the VPN, by file name; the service matches them per connection. */
        val excludedApps: List<String> = emptyList(),
    )

    @Serializable
    data class Request(
        val op: String,
        val up: UpSpec? = null,
        /** Servers to fall back on, preferred first; the service switches by itself when one stays dead. */
        val alternates: List<UpSpec>? = null,
        val uapi: String? = null,
        val block: String? = null,
        val direct: String? = null,
        val allow: String? = null,
    )

    @Serializable
    data class Response(
        val ok: Boolean = false,
        val error: String? = null,
        val up: Boolean = false,
        val name: String? = null,
        val since: Long = 0,
        val stats: String? = null,
        val blocked: Long = 0,
        val socks: String? = null,
        val socksUser: String? = null,
        val socksPass: String? = null,
        val version: String? = null,
        val watch: Watch? = null,
    )

    /** What the service's watchdog knows (tunnel/src/main/go/windows/watch.go). */
    @Serializable
    data class Watch(
        val verified: Boolean = false,
        val repairing: Boolean = false,
        val phase: String? = null,
        val offline: Boolean = false,
        val rttMs: Long = 0,
        val reconnects: Int = 0,
        val active: Int = 0,
        val server: String? = null,
        val session: Long = 0,
    )

    class ServiceException(message: String) : IOException(message)

    /** Sends [req]; throws [ServiceException] when the service is missing or says no. */
    fun call(req: Request): Response {
        val raw = try {
            exchange(json.encodeToString(Request.serializer(), req))
        } catch (e: IOException) {
            throw ServiceException(NOT_RUNNING)
        }
        val resp = runCatching { json.decodeFromString(Response.serializer(), raw) }.getOrElse { throw ServiceException("Служба ответила непонятно") }
        if (!resp.ok) throw ServiceException(resp.error ?: "Служба отказала")
        return resp
    }

    fun available(): Boolean = runCatching { call(Request("version")) }.isSuccess

    @Synchronized
    private fun exchange(body: String): String {
        // Only Windows has the pipe; elsewhere (development, screenshots) there is no service.
        if (!isWindows) throw IOException("no service on this system")
        // Windows opens a pipe like a file; "rw" gives one duplex handle.
        RandomAccessFile(pipe, "rw").use { f ->
            f.write((body + "\n").toByteArray(Charsets.UTF_8))
            val out = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            while (true) {
                val n = f.read(buf)
                if (n < 0) break
                val nl = (0 until n).firstOrNull { buf[it] == '\n'.code.toByte() }
                out.write(buf, 0, nl ?: n)
                if (nl != null) break
                if (out.size() > 4 shl 20) throw IOException("answer too long")
            }
            return out.toString(Charsets.UTF_8)
        }
    }

    companion object {
        const val PIPE = """\\.\pipe\paramnezia"""
        private val isWindows = System.getProperty("os.name").orEmpty().startsWith("Windows")
        const val NOT_RUNNING = "Служба Paramnezia не запущена. Переустановите приложение или запустите службу «Paramnezia VPN»."
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false; explicitNulls = false }
    }
}
