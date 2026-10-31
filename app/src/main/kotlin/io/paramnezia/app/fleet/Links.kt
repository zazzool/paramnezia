package io.paramnezia.app.fleet

import android.content.Context
import io.paramnezia.app.data.Repo
import io.paramnezia.app.data.StoredLink
import io.paramnezia.app.data.StoredProfile
import io.paramnezia.core.amnezia.AmneziaFormat
import io.paramnezia.core.amnezia.AmneziaKey
import io.paramnezia.core.apk.ApkSlot
import io.paramnezia.core.crypto.WgKey
import io.paramnezia.core.profile.Protocol
import io.paramnezia.core.profile.SshCredentials
import io.paramnezia.deploy.InviteLinks
import io.paramnezia.deploy.ServerManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * One-time links for new devices and people. The link page runs on our own server; Android gets
 * this very app with the invite written into it, iPhone gets a key for AmneziaVPN.
 */
class Links(private val context: Context, private val repo: Repo, private val fleet: FleetManager) {
    private val links = InviteLinks(repo.hostKeys)
    private val manager = ServerManager(repo.hostKeys)

    /** Fleet admin: a new trusted device, joined through a link. */
    suspend fun forFleetDevice(name: String): StoredLink = withContext(Dispatchers.IO) {
        val (server, ssh) = fleet.managedServer() ?: error("Нет SSH-доступа ни к одному серверу флота")
        val invite = fleet.addDevice(name)
        val pub = io.paramnezia.core.fleet.Invite.read(invite)?.let { WgKey.fromBase64(it.privateKey).publicKey().toBase64() }
        val link = try {
            links.create(ssh, invite, name, template())
        } catch (e: Exception) {
            // No link, no device: do not leave a member nobody can use.
            pub?.let { runCatching { fleet.removeDevice(it) } }
            throw friendly(e)
        }
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
            links.create(ssh, key, name, template())
        } catch (e: Exception) {
            runCatching { manager.removeClient(ssh, client.publicKey(), protocol) }
            throw friendly(e)
        }
        store(link, name, profile.id, client.publicKey().toBase64())
    }

    /** What went wrong, in words the admin can act on. */
    private fun friendly(e: Exception): Exception {
        val code = (e as? io.paramnezia.deploy.SshException)?.code
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
     * Readies the servers this admin can hand out links from (page, image, this app version) in the
     * background, so that pressing "create link" later takes a second or two instead of minutes.
     */
    suspend fun prewarm() = withContext(Dispatchers.IO) {
        val targets = listOfNotNull(fleet.managedServer()?.second) + repo.current.profiles.mapNotNull { it.ssh }
        val apk = template()
        for (ssh in targets.distinctBy { "${it.host}:${it.port}" }) {
            val key = "${ssh.host}:${ssh.port}"
            if (key in prepared) continue
            runCatching { links.prepare(ssh, apk) }.onSuccess { prepared += key }
        }
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

    /**
     * The installed APK itself: release builds carry an empty settings slot, so the app never has to
     * rewrite an APK. The server writes each invite into its copy, so new devices get exactly the
     * version the admin runs. Builds without a slot (local debug runs) leave only the iPhone path.
     */
    private fun template(): InviteLinks.ApkTemplate? {
        val apk = File(context.applicationInfo.sourceDir)
        return ApkSlot.offset(apk)?.let { InviteLinks.ApkTemplate(apk, it) }
    }
}
