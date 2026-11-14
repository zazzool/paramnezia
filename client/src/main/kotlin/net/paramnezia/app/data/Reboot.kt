package net.paramnezia.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import net.paramnezia.app.platform.Log
import net.paramnezia.core.profile.SshCredentials
import net.paramnezia.deploy.ServerManager
import net.paramnezia.deploy.SshConnection
import net.paramnezia.deploy.SshException
import net.paramnezia.deploy.cloud.CloudProviders
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Reboots a whole server, whatever state it is in, and waits until it is back:
 *  1. over SSH, on the physical network (never through our own VPN, which may be what is broken):
 *     a forced reboot, so a hung container or service cannot hold it up;
 *  2. if SSH does not answer (the system hangs, its network is down, sshd died): a hard reset
 *     through the hosting provider's API, like the reset button in its panel. That needs the
 *     provider's token in the app: for servers bought from the app it is there, and a server bought
 *     by hand is found by its address on every account the app has a token for;
 *  3. then it watches the server go down and come back (its SSH port, over the physical network)
 *     and checks that the VPN container is running again.
 */
class ServerReboot(private val repo: Repo) {
    enum class Stage(val label: String) {
        ASKING("Отправляю команду перезагрузки…"),
        HOSTER("Сервер не отвечает по SSH: перезагружаю через хостера…"),
        GOING_DOWN("Сервер выключается…"),
        COMING_UP("Сервер загружается…"),
        CHECKING("Проверяю VPN на сервере…"),
    }

    class Outcome(val viaHoster: Boolean, val seconds: Int, val vpnRunning: Boolean?)

    class RebootException(message: String, cause: Throwable? = null) : Exception(message, cause)

    private val manager = ServerManager(repo.hostKeys)

    suspend fun reboot(host: String, ssh: SshCredentials?, onStage: (Stage) -> Unit): Outcome = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        val port = ssh?.port ?: 22
        var viaHoster = false
        var sshError: Throwable? = null
        if (ssh != null) {
            onStage(Stage.ASKING)
            // A server that hangs may accept the connection and then say nothing: do not wait for long.
            val sent = withTimeoutOrNull(SSH_TIMEOUT_MS) { runCatching { manager.reboot(ssh) } }
            sshError = when {
                sent == null -> SshException("unreachable", "SSH did not answer in time")
                sent.isFailure -> sent.exceptionOrNull()
                else -> null
            }
            // Wrong password, no sudo: the hoster's reset would work, but the problem is ours to say.
            (sshError as? SshException)?.takeIf { it.code == "sudo_password_required" }?.let {
                throw RebootException("Пользователю на сервере нужен sudo без пароля, иначе перезагрузить нельзя", it)
            }
        }
        if (ssh == null || sshError != null) {
            Log.w(TAG, "reboot $host over SSH: ${sshError?.message ?: "no SSH access"}; trying the provider")
            onStage(Stage.HOSTER)
            val done = hardReset(host)
            if (!done) {
                val tokens = repo.current.cloud.tokens.keys
                throw RebootException(
                    "Сервер не отвечает по SSH" + (sshError?.let { " (${describe(it)})" } ?: "") + ". " +
                        if (tokens.isEmpty()) "Перезагрузите его в панели хостера или добавьте в приложении токен хостера: тогда Paramnezia сможет перезагрузить его сама."
                        else "У хостеров, чьи токены есть в приложении, сервера с адресом $host нет: перезагрузите его в панели хостера.",
                    sshError,
                )
            }
            viaHoster = true
        }
        if (!viaHoster) {
            onStage(Stage.GOING_DOWN)
            // A forced reboot drops SSH within seconds; a server that never goes down still counts
            // (it may have rebooted between two looks).
            withTimeoutOrNull(DOWN_TIMEOUT_MS) { while (portOpen(host, port)) delay(1_500) }
        }
        onStage(Stage.COMING_UP)
        delay(5_000)
        val up = withTimeoutOrNull(UP_TIMEOUT_MS) { while (!portOpen(host, port)) delay(3_000); true } ?: false
        if (!up) throw RebootException("Сервер не загрузился за ${UP_TIMEOUT_MS / 60_000} минут. Посмотрите его консоль в панели хостера.")
        var vpn: Boolean? = null
        if (ssh != null) {
            onStage(Stage.CHECKING)
            // sshd answers a little before Docker has started the containers.
            vpn = withTimeoutOrNull(CHECK_TIMEOUT_MS) {
                var running = false
                while (!running) {
                    running = runCatching { manager.inspect(ssh).containers.any { (name, status) -> name.startsWith("amnezia-") && status.equals("running", true) } }.getOrDefault(false)
                    if (!running) delay(5_000)
                }
                true
            } ?: false
        }
        Outcome(viaHoster, ((System.currentTimeMillis() - started) / 1000).toInt(), vpn)
    }

    /** The provider's hard reset: the server bought from the app, or the one at [host] on any saved account. */
    private suspend fun hardReset(host: String): Boolean = coroutineScope {
        val cloud = repo.current.cloud
        cloud.servers.lastOrNull { it.host == host }?.let { ref ->
            val token = cloud.tokens[ref.provider]
            if (token != null && runCatching { CloudProviders.open(ref.provider, token).reboot(ref.serverId) }.isSuccess) return@coroutineScope true
        }
        val found = cloud.tokens.map { (provider, token) ->
            async {
                runCatching {
                    val p = CloudProviders.open(provider, token)
                    p.findByAddress(host)?.let { p to it.id }
                }.getOrNull()
            }
        }.awaitAll().filterNotNull().firstOrNull() ?: return@coroutineScope false
        runCatching { found.first.reboot(found.second) }.isSuccess
    }

    /** Whether [host]:[port] accepts a TCP connection, tried over the physical network first. */
    private fun portOpen(host: String, port: Int): Boolean {
        for (way in SshConnection.routes().ifEmpty { listOf(null) }) {
            val ok = runCatching {
                (way?.createSocket() ?: Socket()).use { it.connect(InetSocketAddress(host, port), 3_000) }
            }.isSuccess
            if (ok) return true
        }
        return false
    }

    private fun describe(e: Throwable): String = when ((e as? SshException)?.code) {
        "unreachable", "connect_failed", "connection_lost" -> "нет ответа"
        "auth_failed" -> "не принят ключ или пароль"
        "host_key_changed" -> "ключ сервера изменился"
        else -> e.message ?: e.javaClass.simpleName
    }

    private companion object {
        const val TAG = "PnReboot"
        const val SSH_TIMEOUT_MS = 25_000L
        const val DOWN_TIMEOUT_MS = 45_000L
        const val UP_TIMEOUT_MS = 6 * 60_000L
        const val CHECK_TIMEOUT_MS = 90_000L
    }
}
