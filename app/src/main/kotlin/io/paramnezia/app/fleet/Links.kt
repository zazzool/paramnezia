package io.paramnezia.app.fleet

import android.content.Context
import android.os.Build
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
        store(links.create(ssh, invite, name, template()), name, server.id, pub, fleetDevice = true)
    }

    /** Without a fleet: a separate account (its own key) on a server we manage. */
    suspend fun forProfile(profile: StoredProfile, name: String): StoredLink = withContext(Dispatchers.IO) {
        val ssh = profile.ssh ?: error("Нет SSH-доступа к этому серверу")
        val protocol = runCatching { Protocol.valueOf(profile.protocol) }.getOrDefault(Protocol.AWG)
        val client = WgKey.generatePrivate()
        val tunnel = manager.addClient(ssh, client, name, protocol)
        val key = AmneziaKey.encode(AmneziaFormat.export(profile.name, tunnel))
        store(links.create(ssh, key, name, template()), name, profile.id, client.publicKey().toBase64())
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
     * This installed APK with an empty settings slot, made once per version. The server writes each
     * invite into its copy, so new devices get exactly the version the admin runs.
     */
    private fun template(): InviteLinks.ApkTemplate? = runCatching {
        val version = context.packageManager.getPackageInfo(context.packageName, 0).let {
            if (Build.VERSION.SDK_INT >= 28) it.longVersionCode else @Suppress("DEPRECATION") it.versionCode.toLong()
        }
        val dir = File(context.cacheDir, "invite").apply { mkdirs() }
        val file = File(dir, "template-$version.apk")
        val offsetFile = File(dir, "template-$version.offset")
        if (!file.exists() || !offsetFile.exists()) {
            dir.listFiles()?.forEach { it.delete() }
            val tmp = File(dir, "template.tmp")
            val offset = ApkSlot.prepare(File(context.applicationInfo.sourceDir), tmp)
            tmp.renameTo(file)
            offsetFile.writeText(offset.toString())
        }
        InviteLinks.ApkTemplate(file, offsetFile.readText().trim().toLong())
    }.getOrNull() // An APK we cannot read (unsigned debug run, say) still leaves the iPhone path.
}
