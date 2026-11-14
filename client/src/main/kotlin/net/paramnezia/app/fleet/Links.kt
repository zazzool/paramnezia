package net.paramnezia.app.fleet

import kotlinx.coroutines.sync.withLock
import net.paramnezia.core.config.WgQuick
import net.paramnezia.app.data.PooledClient
import net.paramnezia.app.platform.Log
import net.paramnezia.app.data.Repo
import net.paramnezia.app.data.StoredLink
import net.paramnezia.app.data.StoredProfile
import net.paramnezia.core.amnezia.AmneziaFormat
import net.paramnezia.core.amnezia.AmneziaKey
import net.paramnezia.core.crypto.WgKey
import net.paramnezia.core.profile.Protocol
import net.paramnezia.core.profile.SshCredentials
import net.paramnezia.app.platform.ReleaseSource
import net.paramnezia.deploy.FleetPublisher
import net.paramnezia.deploy.InviteLinks
import net.paramnezia.deploy.ServerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.paramnezia.core.fleet.Invite
import net.paramnezia.deploy.SshException

/**
 * One-time links for new devices and people. The link page runs on our own server. Android gets the
 * official release APK (the same file for everyone, from public HTTPS mirrors or the server's own
 * verified copy) and then opens the invite in the installed app; iPhone gets a key for AmneziaVPN.
 * The app never reads, rewrites or uploads its own APK (see docs/PLAY-PROTECT.md).
 */
class Links(
    private val repo: Repo,
    private val fleet: FleetManager,
    private val updater: () -> ReleaseSource,
    private val scope: CoroutineScope,
) {
    private val links = InviteLinks(repo.hostKeys)
    private val manager = ServerManager(repo.hostKeys)
    private val publisher = FleetPublisher(repo.hostKeys)

    /** How a new person gets their settings. */
    enum class Delivery {
        /** One-time link to a page on our server (installs the app, then opens the invite in it). */
        LINK,
        /** A settings file sent straight from the app; needs no link page, but works more than once. */
        FILE,
    }

    /** A person just added: their link (for [Delivery.LINK]) and the settings themselves. */
    data class Created(val name: String, val stored: StoredLink, val key: String)

    /**
     * Several people at once. Each name gets its own key; failures do not stop the rest and come
     * back as (name, error). Fleet devices when [profile] is null, else accounts on that server.
     */
    suspend fun createMany(names: List<String>, profile: StoredProfile?, delivery: Delivery): Pair<List<Created>, List<Pair<String, String>>> {
        val ok = ArrayList<Created>()
        val failed = ArrayList<Pair<String, String>>()
        for (name in names.map { it.trim() }.filter { it.isNotEmpty() }.distinct()) {
            runCatching { if (profile == null) forFleetDevice(name, delivery) else forProfile(profile, name, delivery) }
                .onSuccess { ok += it }
                .onFailure { failed += name to (it.message ?: it.toString()) }
        }
        return ok to failed
    }

    /** Fleet admin: a new trusted device, joined through a link or a file. */
    suspend fun forFleetDevice(name: String, delivery: Delivery = Delivery.LINK): Created = withContext(Dispatchers.IO) {
        val (server, ssh) = fleet.managedServer() ?: error("Нет SSH-доступа ни к одному серверу семьи")
        val invite = fleet.addDevice(name)
        val pub = Invite.read(invite)?.let { WgKey.fromBase64(it.privateKey).publicKey().toBase64() }
        if (delivery == Delivery.FILE) return@withContext Created(name, storeFile(name, server.id, pub, fleetDevice = true), invite)
        val link = try {
            links.create(ssh, invite, name, appDownload(), fast = isPrepared(ssh))
        } catch (e: Exception) {
            // No link, no device: do not leave a member nobody can use.
            pub?.let { runCatching { fleet.removeDevice(it) } }
            throw friendly(e)
        }
        mirrorForInvites(ssh)
        Created(name, store(link, name, server.id, pub, fleetDevice = true), invite)
    }

    /** Without a fleet: a separate account (its own key) on a server we manage. */
    suspend fun forProfile(profile: StoredProfile, name: String, delivery: Delivery = Delivery.LINK): Created = withContext(Dispatchers.IO) {
        val ssh = profile.ssh ?: error("Нет SSH-доступа к этому серверу")
        val protocol = runCatching { Protocol.valueOf(profile.protocol) }.getOrDefault(Protocol.AWG)
        // A prepared account is instant; without one the server is asked now.
        val pooled = takeClient(profile.id)
        val client = pooled?.let { WgKey.fromBase64(it.privateKey) } ?: WgKey.generatePrivate()
        val tunnel = pooled?.let { runCatching { WgQuick.parse(it.conf) }.getOrNull() }
            ?: try { manager.addClient(ssh, client, name, protocol) } catch (e: Exception) { throw friendly(e) }
        scope.launch { runCatching { fillClientPool(profile) } }
        val key = AmneziaFormat.export(profile.name, tunnel).let(AmneziaKey::encode)
        if (delivery == Delivery.FILE) return@withContext Created(name, storeFile(name, profile.id, client.publicKey().toBase64()), key)
        val link = try {
            links.create(ssh, key, name, appDownload(), fast = isPrepared(ssh))
        } catch (e: Exception) {
            runCatching { manager.removeClient(ssh, client.publicKey(), protocol) }
            throw friendly(e)
        }
        mirrorForInvites(ssh)
        Created(name, store(link, name, profile.id, client.publicKey().toBase64()), key)
    }

    /** What went wrong, in words the admin can act on. */
    private fun friendly(e: Exception): Exception {
        val code = (e as? SshException)?.code
        val text = when (code) {
            "unreachable", "connect_failed" -> "Не удалось подключиться к серверу по SSH (порт 22). Проверьте, что сервер включён и SSH доступен."
            "auth_failed" -> "Сервер не принял логин или пароль SSH."
            "host_key_changed" -> "Ключ сервера изменился с первого подключения. Если сервер переустанавливали, удалите и добавьте его заново."
            "invite_failed" -> "Сервер не смог сохранить ссылку: ${e.message}"
            else -> e.message ?: e.toString()
        }
        return IllegalStateException(text, e)
    }

    private val poolLock = kotlinx.coroutines.sync.Mutex()

    private suspend fun takeClient(profileId: String): PooledClient? = poolLock.withLock {
        val c = repo.current.clientPool.firstOrNull { it.profileId == profileId } ?: return null
        repo.update { s -> s.copy(clientPool = s.clientPool - c) }
        c
    }

    /**
     * Prepares up to [CLIENT_POOL] accounts on [profile]'s server in the background (one SSH session),
     * so that the next "add a person" without a fleet is instant too.
     */
    suspend fun fillClientPool(profile: StoredProfile) = withContext(Dispatchers.IO) {
        val ssh = profile.ssh ?: return@withContext
        val protocol = runCatching { Protocol.valueOf(profile.protocol) }.getOrDefault(Protocol.AWG)
        poolLock.withLock {
            val have = repo.current.clientPool.count { it.profileId == profile.id }
            repeat(CLIENT_POOL - have) {
                val key = WgKey.generatePrivate()
                val conf = runCatching { manager.addClient(ssh, key, POOL_PEER_NAME, protocol) }.getOrElse { e ->
                    Log.w(TAG, "prepare an account on ${ssh.host}: ${e.message}"); return@withLock
                }
                val c = PooledClient(profile.id, key.toBase64(), WgQuick.write(conf), System.currentTimeMillis())
                repo.update { s -> s.copy(clientPool = s.clientPool + c) }
            }
        }
    }

    private val prepared = java.util.Collections.synchronizedSet(HashSet<String>())

    /** The page on [ssh] was set up in this run of the app (with the current app download info). */
    private fun isPrepared(ssh: SshCredentials): Boolean = prepared.any { it.startsWith("${ssh.host}:${ssh.port}:") }

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
                val url = links.prepare(ssh, app)
                repo.update { s -> s.copy(pages = s.pages + (ssh.host to url)) }
                app?.let { publisher.mirrorRelease(ssh, it.sha256, it.urls, null, forInvites = true, protocol = null, windows = it.windows) }
            }.onSuccess { prepared += key }.onFailure { Log.w(TAG, "prepare ${ssh.host}: ${it.message}") }
        }
        // Accounts prepared for servers that are gone from the app are dropped (their peers are harmless).
        repo.update { s -> s.copy(clientPool = s.clientPool.filter { c -> s.profiles.any { it.id == c.profileId && it.ssh != null } }) }
        // Ready slots, so that adding a person is instant (they refill after each use).
        runCatching { fleet.fillPool() }.onFailure { Log.w(TAG, "device slots: ${it.message}") }
        for (p in repo.current.profiles.filter { it.ssh != null }) runCatching { fillClientPool(p) }
    }

    /**
     * Where the invite page sends people for the app: the signed release this admin runs (or a newer
     * one it knows). Looked up once on the release channels if nothing is known yet.
     */
    private suspend fun appDownload(): InviteLinks.AppDownload? {
        val u = updater()
        val now = System.currentTimeMillis()
        if ((u.shareable() == null || u.windows() == null) && now - lastCheck > 10 * 60_000L) {
            lastCheck = now
            runCatching { u.check() }
        }
        val (release, _) = u.shareable() ?: return null
        val windows = u.windows()?.let { InviteLinks.AppDownload(it.versionName, it.sha256, it.publicApkUrls()) }
        return InviteLinks.AppDownload(release.versionName, release.sha256, release.publicApkUrls(), windows)
    }

    @Volatile private var lastCheck = 0L

    /** The server's own verified copies of the apps (Android, Windows) for the page, in the background. */
    private fun mirrorForInvites(ssh: SshCredentials, protocol: Protocol? = null) = scope.launch(Dispatchers.IO) {
        val app = appDownload() ?: return@launch
        val shared = updater().shareable()?.second
        runCatching { publisher.mirrorRelease(ssh, app.sha256, app.urls, shared, forInvites = true, protocol = protocol, windows = app.windows) }
            .onFailure { Log.w(TAG, "mirror to ${ssh.host}: ${it.message}") }
    }

    /**
     * The page to share with family and friends (download, screenshots, how to connect) on [ssh];
     * the same small web server as the invite links. Remembers its address for the fleet screen.
     */
    suspend fun preparePage(ssh: SshCredentials): String = withContext(Dispatchers.IO) {
        val url = try { links.prepare(ssh, appDownload()) } catch (e: Exception) { throw friendly(e) }
        repo.update { s -> s.copy(pages = s.pages + (ssh.host to url)) }
        mirrorForInvites(ssh)
        url
    }

    /** The family page of the main server (fleet master, else the first own server); deploys it if needed. */
    suspend fun familyPage(): String = withContext(Dispatchers.IO) {
        val ssh = fleet.managedServer()?.second ?: repo.current.profiles.firstOrNull { it.ssh != null }?.ssh
            ?: error("Сначала разверните свой сервер")
        repo.current.pages[ssh.host] ?: preparePage(ssh)
    }

    /** Asks the servers which links have been used. */
    suspend fun refresh() = withContext(Dispatchers.IO) {
        val open = repo.current.links.filter { !it.file && !it.used && it.expiresAt > System.currentTimeMillis() }
        val used = open.groupBy { it.serverRef }.flatMap { (ref, list) ->
            val ssh = access(ref) ?: return@flatMap emptyList()
            runCatching { links.claimed(ssh, list.map { it.token }) }.getOrDefault(emptySet())
        }.toSet()
        if (used.isNotEmpty()) repo.update { s -> s.copy(links = s.links.map { if (it.token in used) it.copy(used = true) else it }) }
    }

    /** Takes back an unused link and the access it carried. */
    suspend fun revoke(link: StoredLink) = withContext(Dispatchers.IO) {
        val ssh = access(link.serverRef)
        if (!link.file) ssh?.let { links.revoke(it, link.token) }
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

    /** A person who got a file: kept so their access can be taken back like a link's. */
    private fun storeFile(name: String, ref: String, peer: String?, fleetDevice: Boolean = false): StoredLink {
        val now = System.currentTimeMillis()
        val stored = StoredLink("file-" + java.util.UUID.randomUUID(), "", name, ref, now, Long.MAX_VALUE,
            used = true, peerPublicKey = peer, fleetDevice = fleetDevice, file = true)
        repo.update { s -> s.copy(links = (listOf(stored) + s.links).take(50)) }
        return stored
    }

    private companion object {
        const val TAG = "PnLinks"
        const val CLIENT_POOL = 2
        const val POOL_PEER_NAME = "Paramnezia"
    }
}
