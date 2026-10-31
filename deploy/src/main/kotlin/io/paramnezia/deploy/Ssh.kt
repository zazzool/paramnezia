package io.paramnezia.deploy

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.Session
import com.jcraft.jsch.UserInfo
import io.paramnezia.core.profile.SshCredentials
import java.io.Closeable
import java.security.MessageDigest
import java.util.Base64

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
class SshConnection private constructor(private val session: Session) : Closeable {

    /**
     * Runs [script] with `bash -s`, passing [env] as exported variables ahead of the script body so
     * values never pass through command-line quoting. [onLine] receives stdout lines as they arrive.
     */
    fun run(script: String, env: Map<String, String> = emptyMap(), timeoutMs: Long = 15 * 60_000, onLine: (String) -> Unit = {}): ExecResult {
        val channel = session.openChannel("exec") as ChannelExec
        channel.setCommand("bash -s")
        val input = buildString {
            for ((k, v) in env) {
                require(k.matches(Regex("[A-Z_][A-Z0-9_]*"))) { "Bad variable name $k" }
                append("export ").append(k).append('=').append(shellQuote(v)).append('\n')
            }
            append(script)
            if (!script.endsWith("\n")) append('\n')
        }
        val stdout = StringBuilder(); val stderr = StringBuilder()
        val out = channel.inputStream
        val err = channel.errStream
        val stdin = channel.outputStream
        channel.connect(CONNECT_TIMEOUT_MS)
        stdin.write(input.toByteArray())
        stdin.flush()
        stdin.close()

        val deadline = System.currentTimeMillis() + timeoutMs
        val line = StringBuilder()
        val buf = ByteArray(8192)
        while (true) {
            var progressed = false
            while (out.available() > 0) {
                val n = out.read(buf); if (n < 0) break
                progressed = true
                val s = String(buf, 0, n)
                stdout.append(s)
                for (ch in s) if (ch == '\n') { onLine(line.toString()); line.setLength(0) } else line.append(ch)
            }
            while (err.available() > 0) {
                val n = err.read(buf); if (n < 0) break
                progressed = true
                stderr.append(String(buf, 0, n))
            }
            if (channel.isClosed && out.available() == 0 && err.available() == 0) break
            if (System.currentTimeMillis() > deadline) {
                channel.disconnect()
                throw SshException("timeout", "Remote command timed out")
            }
            if (!progressed) Thread.sleep(50)
        }
        if (line.isNotEmpty()) onLine(line.toString())
        val code = channel.exitStatus
        channel.disconnect()
        return ExecResult(code, stdout.toString(), stderr.toString())
    }

    /** Runs [command] with [input] streamed to its stdin (for binary uploads). Returns stdout. */
    fun pipe(command: String, input: java.io.InputStream, timeoutMs: Long = 10 * 60_000): ExecResult {
        val channel = session.openChannel("exec") as ChannelExec
        channel.setCommand(command)
        val out = channel.inputStream
        val err = channel.errStream
        val stdin = channel.outputStream
        channel.connect(CONNECT_TIMEOUT_MS)
        stdin.use { input.copyTo(it, 64 * 1024) }
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!channel.isClosed) {
            if (System.currentTimeMillis() > deadline) { channel.disconnect(); throw SshException("timeout", "Upload timed out") }
            Thread.sleep(50)
        }
        val result = ExecResult(channel.exitStatus, out.readBytes().decodeToString(), err.readBytes().decodeToString())
        channel.disconnect()
        return result
    }

    override fun close() = session.disconnect()

    companion object {
        private const val CONNECT_TIMEOUT_MS = 20_000

        fun open(creds: SshCredentials, hostKeys: HostKeyStore): SshConnection {
            val jsch = JSch()
            if (creds.privateKey.isNotBlank()) {
                jsch.addIdentity("key", creds.privateKey.toByteArray(), null, creds.passphrase.takeIf { it.isNotEmpty() }?.toByteArray())
            }
            jsch.hostKeyRepository = TofuRepository(hostKeys, creds.port)
            val session = jsch.getSession(creds.user, creds.host, creds.port)
            if (creds.password.isNotEmpty()) session.setPassword(creds.password)
            session.userInfo = object : UserInfo {
                override fun getPassphrase() = creds.passphrase
                override fun getPassword() = creds.password
                override fun promptPassword(message: String?) = creds.password.isNotEmpty()
                override fun promptPassphrase(message: String?) = creds.passphrase.isNotEmpty()
                override fun promptYesNo(message: String?) = false
                override fun showMessage(message: String?) {}
            }
            session.setConfig("PreferredAuthentications", "publickey,keyboard-interactive,password")
            session.setServerAliveInterval(15_000)
            try {
                session.connect(CONNECT_TIMEOUT_MS)
            } catch (e: JSchException) {
                val msg = e.message.orEmpty()
                throw when {
                    "HostKey has been changed" in msg || "reject HostKey" in msg ->
                        SshException("host_key_changed", "Server key changed since the first connection", e)
                    "Auth fail" in msg || "Auth cancel" in msg -> SshException("auth_failed", "Wrong login or password", e)
                    "timeout" in msg.lowercase() -> SshException("unreachable", "Server did not answer", e)
                    else -> SshException("connect_failed", msg, e)
                }
            }
            return SshConnection(session)
        }

        fun shellQuote(v: String) = "'" + v.replace("'", "'\\''") + "'"
    }

    private class TofuRepository(private val store: HostKeyStore, private val port: Int) : HostKeyRepository {
        override fun check(host: String, key: ByteArray): Int {
            val fp = fingerprint(key)
            val h = host.removePrefix("[").substringBefore("]")
            val known = store.get(h, port)
            return when {
                known == null -> { store.put(h, port, fp); HostKeyRepository.OK }
                known == fp -> HostKeyRepository.OK
                else -> HostKeyRepository.CHANGED
            }
        }

        override fun add(hostkey: HostKey?, ui: UserInfo?) {}
        override fun remove(host: String?, type: String?) {}
        override fun remove(host: String?, type: String?, key: ByteArray?) {}
        override fun getKnownHostsRepositoryID() = "paramnezia"
        override fun getHostKey(): Array<HostKey> = emptyArray()
        override fun getHostKey(host: String?, type: String?): Array<HostKey> = emptyArray()

        private fun fingerprint(key: ByteArray) =
            "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(key))
    }
}
