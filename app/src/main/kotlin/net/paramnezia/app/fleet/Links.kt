package net.paramnezia.app.fleet

import android.util.Log
import net.paramnezia.app.data.Repo
import net.paramnezia.app.data.StoredLink
import net.paramnezia.app.data.StoredProfile
import net.paramnezia.core.amnezia.AmneziaFormat
import net.paramnezia.core.amnezia.AmneziaKey
import net.paramnezia.core.crypto.WgKey
import net.paramnezia.core.profile.Protocol
import net.paramnezia.core.profile.SshCredentials
import net.paramnezia.app.ota.Updater
import net.paramnezia.deploy.FleetPublisher
import net.paramnezia.deploy.InviteLinks
import net.paramnezia.deploy.ServerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One-time links for new devices and people. The link page runs on our own server. Android gets the
 * official release APK (the same file for everyone, from public HTTPS mirrors or the server's own
 * verified copy) and then opens the invite in the installed app; iPhone gets a key for AmneziaVPN.
 * The app never reads, rewrites or uploads its own APK (see docs/PLAY-PROTECT.md).
 */
class Links(
    private val repo: Repo,
    private val fleet: FleetManager,
    private val updater: () -> Updater,
    private val scope: CoroutineScope,
) {
    private val links = InviteLinks(repo.hostKeys)
    private val manager = ServerManager(repo.hostKeys)
    private val publisher = FleetPublisher(repo.hostKeys)

    /** Fleet admin: a new trusted device, joined through a link. */
    suspend fun forFleetDevice(name: String): StoredLink = withContext(Dispatchers.IO) {
        val (server, ssh) = fleet.managedServer() ?: error("Нет SSH-доступа ни к одному серверу флота")
        val invite = fleet.addDevice(name)
        val pub = net.paramnezia.core.fleet.Invite.read(invite)?.let { WgKey.fromBase64(it.privateKey).publicKey().toBase64() }
        val link = try {
            links.create(ssh, invite, name, appDownload())
        } catch (e: Exception) {
            // No link, no device: do not leave a member nobody can use.
            pub?.let { runCatching { fleet.removeDevice(it) } }
            throw friendly(e)
        }
        mirrorForInvites(ssh)
        store(link, name, server.id, pub, fleetDevice = true)
    }

    /** Without a fleet: a separate account (its own key) on a server we manage. */
    suspend fun forProfile(profile: StoredProfile, name: String): StoredLink = withContext(Dispatchers.IO) {
        val ssh = profile.ssh ?: error("Нет SSH-доступа к этому серверу")
        val protocol = runCatching { Protocol.valueOf(profile.protocol) }.getOrDefault(Protocol.AWG)
        val client = WgKey.generatePrivate()
        val tunnel = try { manager.addClient(ssh, client, name, protocol) } catch (e: Exception) { throw friendly(e) }
        val key = AmneziaFormat.export(profile.name, tunnel).let(AmneziaKey::encode)
        val link = try {
            links.create(ssh, key, name, appDownload())
        } catch (e: Exception) {
            runCatching { manager.removeClient(ssh, client.publicKey(), protocol) }
            throw friendly(e)
        }
        mirrorForInvites(ssh)
        store(link, name, profile.id, client.publicKey().toBase64())
    }

    /** What went wrong, in words the admin can act on. */
    private fun friendly(e: Exception): Exception {
        val code = (e as? net.paramnezia.deploy.SshException)?.code
        val text = when (code) {
            "unreachable", "connect_failed" -> "Не удалось подключиться к серверу по SSH (порт 22). Проверьте, что сервер включён и SSH доступен."
            "auth_failed" -> "Сервер не принял логин или пароль SSH."
            "host_key_changed" -> "Ключ сервера изменился с первого подключения. Если сервер переустанавливали, удалите и добавьте его заново."
            "invite_failed" -> "Сервер не смог сохранить ссылку: ${e.message}"
            else -> e.message ?: e.toString()
        }
        return IllegalStateException(text, e)
    }

    private val prepared = java.util.Collections.synchronizedSet(HashSet<String>())

    /**
     * Readies the servers this admin can hand out links from (page container, image pull, the app's
     * download info and the server's own copy of it) in the background, so that pressing "create
     * link" later takes a second or two.
     */
    suspend fun prewarm() = withContext(Dispatchers.IO) {
        val targets = listOfNotNull(fleet.managedServer()?.second) + repo.current.profiles.mapNotNull { it.ssh }
        val app = appDownload()
        for (ssh in targets.distinctBy { "${it.host}:${it.port}" }) {
            val key = "${ssh.host}:${ssh.port}:${app?.sha256}"
            if (key in prepared) continue
            runCatching {
                links.prepare(ssh, app)
                app?.let { publisher.mirrorRelease(ssh, it.sha256, it.urls, null, forInvites = true, protocol = null) }
            }.onSuccess { prepared += key }.onFailure { Log.w(TAG, "prepare ${ssh.host}: ${it.message}") }
        }
    }

    /**
     * Where the invite page sends people for the app: the signed release this admin runs (or a newer
     * one it knows). Looked up once on the release channels if nothing is known yet.
     */
    private suspend fun appDownload(): InviteLinks.AppDownload? {
        val u = updater()
        if (u.shareable() == null) runCatching { u.check() }
        val (release, _) = u.shareable() ?: return null
        val urls = release.publicApkUrls()
        return InviteLinks.AppDownload(release.versionName, release.sha256, urls)
    }

    /** The server's own verified copy of the app for the invite page, in the background. */
    private fun mirrorForInvites(ssh: SshCredentials, protocol: Protocol? = null) = scope.launch(Dispatchers.IO) {
        val app = appDownload() ?: return@launch
        val shared = updater().shareable()?.second
        runCatching { publisher.mirrorRelease(ssh, app.sha256, app.urls, shared, forInvites = true, protocol = protocol) }
            .onFailure { Log.w(TAG, "mirror to ${ssh.host}: ${it.message}") }
    }

    /** Asks the servers which links have been used. */
    suspend fun refresh() = withContext(Dispatchers.IO) {
        val open = repo.current.links.filter { !it.used && it.expiresAt > System.currentTimeMillis() }
        val used = open.groupBy { it.serverRef }.flatMap { (ref, list) ->
            val ssh = access(ref) ?: return@flatMap emptyList()
            runCatching { links.claimed(ssh, list.map { it.token }) }.getOrDefault(emptySet())
        }.toSet()
        if (used.isNotEmpty()) repo.update { s -> s.copy(links = s.links.map { if (it.token in used) it.copy(used = true) else it }) }
    }

    /** Takes back an unused link and the access it carried. */
    suspend fun revoke(link: StoredLink) = withContext(Dispatchers.IO) {
        val ssh = access(link.serverRef)
        ssh?.let { links.revoke(it, link.token) }
        link.peerPublicKey?.let { pub ->
            if (link.fleetDevice) fleet.removeDevice(pub)
            else if (ssh != null) {
                val protocol = repo.current.profiles.firstOrNull { it.id == link.serverRef }?.protocol
                    ?.let { runCatching { Protocol.valueOf(it) }.getOrNull() } ?: Protocol.AWG
                runCatching { manager.removeClient(ssh, WgKey.fromBase64(pub), protocol) }
            }
        }
        repo.update { s -> s.copy(links = s.links.filter { it.token != link.token }) }
    }

    private fun access(ref: String): SshCredentials? =
        fleet.accessTo(ref) ?: repo.current.profiles.firstOrNull { it.id == ref }?.ssh

    private fun store(link: InviteLinks.Link, name: String, ref: String, peer: String?, fleetDevice: Boolean = false): StoredLink {
        val stored = StoredLink(link.token, link.url, name, ref, System.currentTimeMillis(), link.expiresAt, peerPublicKey = peer, fleetDevice = fleetDevice)
        repo.update { s -> s.copy(links = (listOf(stored) + s.links).take(50)) }
        return stored
    }

    private companion object {
        const val TAG = "PnLinks"
    }
}
