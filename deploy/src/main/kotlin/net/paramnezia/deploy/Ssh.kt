package net.paramnezia.deploy

import net.paramnezia.core.profile.SshCredentials
import net.paramnezia.deploy.ssh.SshAuthException
import net.paramnezia.deploy.ssh.SshClient
import net.paramnezia.deploy.ssh.SshConnectionLostException
import net.paramnezia.deploy.ssh.SshHostKeyException
import net.paramnezia.deploy.ssh.SshKeyException
import net.paramnezia.deploy.ssh.SshKeys
import net.paramnezia.deploy.ssh.SshNegotiationException
import net.paramnezia.deploy.ssh.SshPrivateKey
import net.paramnezia.deploy.ssh.SshTimeoutException
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.NoRouteToHostException
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * [code]: unreachable, connect_failed, connection_lost, timeout, auth_failed, password_change_required,
 * host_key_changed, bad_key, bad_passphrase, or a code from our scripts.
 */
class SshException(val code: String, message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Trust-on-first-use host keys: the first fingerprint seen for a host is remembered and any later
 * change is refused, which is what protects the SSH password from a man in the middle.
 */
interface HostKeyStore {
    fun get(host: String, port: Int): String?
    fun put(host: String, port: Int, fingerprint: String)
}

class MemoryHostKeyStore : HostKeyStore {
    private val keys = HashMap<String, String>()
    override fun get(host: String, port: Int) = keys["$host:$port"]
    override fun put(host: String, port: Int, fingerprint: String) { keys["$host:$port"] = fingerprint }
}

data class ExecResult(val exitCode: Int, val stdout: String, val stderr: String) {
    /** `::result::key=value` lines printed by our scripts. */
    val results: Map<String, String> by lazy {
        stdout.lineSequence().filter { it.startsWith("::result::") }
            .map { it.removePrefix("::result::") }
            .associate { it.substringBefore('=') to it.substringAfter('=', "") }
    }
    val error: String? get() = stdout.lineSequence().firstOrNull { it.startsWith("::error::") }?.removePrefix("::error::")
}

/** One SSH connection; run several scripts over it, then close. Blocking: call from a worker thread. */
class SshConnection private constructor(private val client: SshClient) : Closeable {

    /**
     * Runs [script] with `bash -s`, passing [env] as exported variables ahead of the script body so
     * values never pass through command-line quoting. [onLine] receives stdout lines as they arrive
     * (decoded as whole UTF-8 lines, so a letter split between two packets stays intact).
     */
    fun run(script: String, env: Map<String, String> = emptyMap(), timeoutMs: Long = 15 * 60_000, onLine: (String) -> Unit = {}): ExecResult {
        val input = buildString {
            for ((k, v) in env) {
                require(k.matches(Regex("[A-Z_][A-Z0-9_]*"))) { "Bad variable name $k" }
                append("export ").append(k).append('=').append(shellQuote(v)).append('\n')
            }
            append(script)
            if (!script.endsWith("\n")) append('\n')
        }
        val line = ByteArrayOutputStream()
        val out = try {
            client.exec("bash -s", input.toByteArray(Charsets.UTF_8), System.currentTimeMillis() + timeoutMs) { b, off, len ->
                for (i in off until off + len) {
                    if (b[i] == '\n'.code.toByte()) { onLine(line.toString(Charsets.UTF_8.name())); line.reset() } else line.write(b[i].toInt())
                }
            }
        } catch (e: Exception) {
            client.close()
            throw translate(e)
        }
        if (line.size() > 0) onLine(line.toString(Charsets.UTF_8.name()))
        return ExecResult(out.exitCode, String(out.stdout, Charsets.UTF_8), String(out.stderr, Charsets.UTF_8))
    }

    override fun close() = client.close()

    companion object {
        private const val CONNECT_TIMEOUT_MS = 20_000

        /**
         * Ways to reach a server, tried in order; null is the default route. The Android app puts the
         * physical network first while its own VPN is up: SSH to the VPS through the tunnel that ends
         * on that same VPS does not work reliably.
         */
        @Volatile var routes: () -> List<javax.net.SocketFactory?> = { listOf(null) }

        /**
         * Connects over every route in turn, twice: mobile networks drop the odd TCP handshake, and
         * one quiet retry is cheaper than a failed operation the admin has to start again.
         */
        fun open(creds: SshCredentials, hostKeys: HostKeyStore): SshConnection {
            // A key that cannot be read fails at once, before any network traffic.
            val key = creds.privateKey.takeIf { it.isNotBlank() }?.let {
                try { SshKeys.parse(it, creds.passphrase) } catch (e: SshKeyException) { throw SshException(e.code, e.message ?: "Bad key", e) }
            }
            var last: SshException? = null
            for (attempt in 0 until 2) {
                if (attempt > 0) Thread.sleep(2_000)
                val ways = routes().ifEmpty { listOf(null) }
                for ((i, way) in ways.withIndex()) {
                    try {
                        return open(creds, key, hostKeys, way, if (ways.size > 1 && i < ways.size - 1) 10_000 else CONNECT_TIMEOUT_MS)
                    } catch (e: SshException) {
                        // Wrong password or host key: another route or try will not help.
                        if (e.code != "unreachable" && e.code != "connect_failed") throw e
                        last = e
                    }
                }
            }
            throw last!!
        }

        /**
         * One connection over [way]. The remembered host key is checked here (trust on first use).
         * The server may hold several keys and choose another type than the one remembered (an older
         * app version, or another platform, negotiated differently): before calling the key changed,
         * each other key type is asked for in turn, and any match is the same server.
         */
        private fun open(creds: SshCredentials, key: SshPrivateKey?, hostKeys: HostKeyStore, way: javax.net.SocketFactory?, timeoutMs: Int): SshConnection {
            val known = hostKeys.get(creds.host, creds.port)
            var left = SshKeys.HOST_KEY_FAMILIES
            while (true) {
                try {
                    return connect(creds, key, way, timeoutMs, left.flatten()) { alg, blob ->
                        val fp = SshKeys.fingerprint(blob)
                        if (known == null) hostKeys.put(creds.host, creds.port, fp)
                        else if (fp != known) throw SshHostKeyException(alg, fp)
                    }
                } catch (e: SshHostKeyException) {
                    left = left.filter { e.algorithm !in it }
                    if (left.isEmpty()) throw SshException("host_key_changed", "Server key changed since the first connection")
                } catch (e: SshException) {
                    // None of the remaining key types: the server has no key matching the remembered one.
                    if (e.code != "no_host_key_type") throw e
                    if (left.size == SshKeys.HOST_KEY_FAMILIES.size) throw SshException("connect_failed", e.message.orEmpty(), e)
                    throw SshException("host_key_changed", "Server key changed since the first connection", e)
                }
            }
        }

        private fun connect(
            creds: SshCredentials, key: SshPrivateKey?, way: javax.net.SocketFactory?, timeoutMs: Int,
            hostKeyAlgorithms: List<String>, accept: (String, ByteArray) -> Unit,
        ): SshConnection {
            val socket = try {
                (way?.createSocket() ?: Socket()).apply {
                    tcpNoDelay = true
                    keepAlive = true
                    connect(InetSocketAddress(creds.host, creds.port), timeoutMs)
                }
            } catch (e: Exception) { throw translate(e) }
            val client = SshClient(socket, hostKeyAlgorithms, accept)
            try {
                client.handshake(timeoutMs)
                client.authenticate(creds.user, key, creds.password)
                return SshConnection(client)
            } catch (e: Exception) {
                client.close()
                if (e is SshHostKeyException) throw e
                throw translate(e)
            }
        }

        private fun translate(e: Exception): SshException = when (e) {
            is SshException -> e
            is SshAuthException -> SshException(e.code, if (e.code == "auth_failed") "Wrong login or password" else e.message.orEmpty(), e)
            is SshHostKeyException -> SshException("host_key_changed", "Server key changed since the first connection", e)
            is SshConnectionLostException -> SshException("connection_lost", "Connection to the server was lost", e)
            is SshTimeoutException -> if (e.message == "remote command timed out") SshException("timeout", "Remote command timed out", e)
                else SshException("unreachable", "Server did not answer", e)
            is SocketTimeoutException -> SshException("unreachable", "Server did not answer", e)
            is SshNegotiationException -> SshException(if (e.what == "host key type") "no_host_key_type" else "connect_failed", e.message.orEmpty(), e)
            is UnknownHostException -> SshException("connect_failed", "Unknown host ${e.message}", e)
            is ConnectException, is NoRouteToHostException -> SshException("connect_failed", e.message ?: "Connection refused", e)
            is IOException -> SshException("connect_failed", e.message ?: e.toString(), e)
            else -> SshException("connect_failed", e.message ?: e.toString(), e)
        }

        fun shellQuote(v: String) = "'" + v.replace("'", "'\\''") + "'"
    }
}
