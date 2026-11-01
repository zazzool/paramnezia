package net.paramnezia.app.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.paramnezia.core.profile.SshCredentials
import net.paramnezia.deploy.ServerManager
import net.paramnezia.deploy.SshException
import net.paramnezia.deploy.cloud.CloudException
import net.paramnezia.deploy.cloud.CloudPlan
import net.paramnezia.deploy.cloud.CloudProvider
import net.paramnezia.deploy.cloud.CloudProviders
import net.paramnezia.deploy.cloud.CloudProvisioner
import net.paramnezia.deploy.cloud.CloudRegion
import net.paramnezia.deploy.cloud.SshKeyPair

/**
 * Buying servers from hosting providers (admin only). Every call here is made on the admin's tap
 * while the app is open; nothing runs in the background (see docs/PLAY-PROTECT.md, rule 11).
 *
 * A purchase runs in the application scope, not the screen's: leaving the screen or turning the
 * phone does not abandon a server that is already being paid for. [job] shows its progress.
 */
class Cloud(private val repo: Repo, private val scope: CoroutineScope) {

    /** Progress of the current (or last) purchase. */
    data class Job(
        val provider: String,
        val label: String,
        val done: Boolean = false,
        val error: String? = null,
        /** The bought server, once the provider has accepted the order: offered for deletion on failure. */
        val server: CloudServerRef? = null,
    )

    private val _job = MutableStateFlow<Job?>(null)
    val job: StateFlow<Job?> = _job

    fun hasToken(provider: String) = repo.current.cloud.tokens[provider] != null

    private fun open(provider: String): CloudProvider =
        CloudProviders.open(provider, repo.current.cloud.tokens[provider] ?: throw CloudException("no_token", "Нет токена"))

    /** Checks [token] with the provider and keeps it. */
    suspend fun saveToken(provider: String, token: String) = withContext(Dispatchers.IO) {
        CloudProviders.open(provider, token).verify()
        repo.update { s -> s.copy(cloud = s.cloud.copy(tokens = s.cloud.tokens + (provider to token.trim()), lastProvider = provider)) }
    }

    fun forgetToken(provider: String) = repo.update { s ->
        s.copy(cloud = s.cloud.copy(tokens = s.cloud.tokens - provider, lastProvider = s.cloud.lastProvider.takeIf { it != provider }))
    }

    suspend fun regions(provider: String): List<CloudRegion> = withContext(Dispatchers.IO) { open(provider).regions() }

    suspend fun plans(provider: String, region: String): List<CloudPlan> = withContext(Dispatchers.IO) { open(provider).plans(region) }

    private fun adminKey(): SshKeyPair {
        val c = repo.current.cloud
        if (c.sshPrivate != null && c.sshPublic != null) return SshKeyPair(c.sshPrivate, c.sshPublic)
        val k = SshKeyPair.generate("paramnezia-admin")
        repo.update { s -> s.copy(cloud = s.cloud.copy(sshPrivate = k.privatePem, sshPublic = k.publicLine)) }
        return k
    }

    /**
     * Buys a server and installs it with [install] (standalone deploy or fleet add). Starts billing.
     * Returns at once; follow [job].
     */
    fun buy(
        provider: String,
        region: CloudRegion,
        plan: CloudPlan,
        install: suspend (name: String, ssh: SshCredentials, onStep: (ServerManager.Step) -> Unit) -> Unit,
    ) {
        if (_job.value?.let { !it.done && it.error == null } == true) return
        val providerName = CloudProviders.info(provider)?.name ?: provider
        _job.value = Job(provider, "Заказываю сервер у $providerName…")
        repo.update { s -> s.copy(cloud = s.cloud.copy(lastProvider = provider, lastRegion = region.id)) }
        scope.launch(Dispatchers.IO) {
            var ref: CloudServerRef? = null
            try {
                val api = open(provider)
                val name = CloudProvisioner.newName()
                val ssh = CloudProvisioner(repo.hostKeys).provision(api, region.id, plan, adminKey(), name, onStage = { st ->
                    update(when (st) {
                        CloudProvisioner.Stage.CREATE -> "Заказываю сервер у $providerName…"
                        CloudProvisioner.Stage.BOOT -> "Хостер запускает сервер…"
                        CloudProvisioner.Stage.SSH -> "Жду, пока сервер загрузится…"
                    })
                }) { id ->
                    ref = CloudServerRef(provider, id, name, createdAt = System.currentTimeMillis(), price = plan.priceText())
                    repo.update { s -> s.copy(cloud = s.cloud.copy(servers = s.cloud.servers + ref!!)) }
                    _job.value = _job.value?.copy(server = ref)
                }
                val located = ref!!.copy(host = ssh.host)
                ref = located
                repo.update { s -> s.copy(cloud = s.cloud.copy(servers = s.cloud.servers.map { if (it.serverId == located.serverId && it.provider == provider) located else it })) }
                _job.value = _job.value?.copy(server = located)
                install("${region.name} · $providerName", ssh) { step -> update(stepLabel(step)) }
                _job.value = _job.value?.copy(label = "Сервер в ${region.name} готов", done = true)
            } catch (e: Throwable) {
                _job.value = (_job.value ?: Job(provider, "")).copy(error = describe(e), server = ref)
            }
        }
    }

    private fun update(label: String) { _job.value = _job.value?.copy(label = label) }

    fun dismissJob() { _job.value = null }

    /** The bought server behind [host], if it was bought from the app. */
    fun serverAt(host: String): CloudServerRef? = repo.current.cloud.servers.lastOrNull { it.host == host }

    /** Destroys a bought server at the provider (billing stops) and forgets it. */
    suspend fun delete(ref: CloudServerRef) = withContext(Dispatchers.IO) {
        open(ref.provider).delete(ref.serverId)
        repo.update { s -> s.copy(cloud = s.cloud.copy(servers = s.cloud.servers.filterNot { it.provider == ref.provider && it.serverId == ref.serverId })) }
        if (_job.value?.server?.serverId == ref.serverId) _job.value = null
    }

    companion object {
        fun stepLabel(s: ServerManager.Step) = when (s) {
            ServerManager.Step.CHECK -> "Проверяю сервер…"
            ServerManager.Step.DOCKER -> "Устанавливаю Docker…"
            ServerManager.Step.IMAGE -> "Собираю AmneziaWG…"
            ServerManager.Step.RUN -> "Запускаю контейнер…"
            ServerManager.Step.CONFIGURE -> "Генерирую ключи и маскировку…"
            ServerManager.Step.START -> "Запускаю туннель…"
            ServerManager.Step.FIREWALL -> "Настраиваю сеть…"
            ServerManager.Step.DONE -> "Готово"
        }

        fun describe(e: Throwable): String = when (e) {
            is CloudException -> when (e.code) {
                "auth_failed" -> "Хостер не принял токен: проверьте его и права (${e.message})"
                "no_money" -> "Недостаточно денег на балансе хостера"
                "network" -> "Хостер не отвечает. Проверьте интернет"
                else -> e.message ?: "Ошибка хостера"
            }
            is SshException -> when (e.code) {
                "host_key_changed" -> "Сервер ответил чужим ключом SSH: возможна подмена. Удалите сервер и попробуйте снова"
                "auth_failed" -> "Сервер не принял ключ SSH (cloud-init не сработал)"
                "unreachable", "connect_failed" -> "Сервер не отвечает по SSH"
                else -> e.message ?: "Ошибка установки"
            }
            else -> e.message ?: e.javaClass.simpleName
        }
    }
}
