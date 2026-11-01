package net.paramnezia.app.data

import android.os.Build
import net.paramnezia.app.fleet.FleetManager
import net.paramnezia.core.amnezia.AmneziaFormat
import net.paramnezia.core.amnezia.AmneziaKey
import net.paramnezia.core.config.ConfigException
import net.paramnezia.core.config.WgQuick
import net.paramnezia.core.crypto.WgKey
import net.paramnezia.core.fleet.Invite
import net.paramnezia.core.profile.Protocol
import net.paramnezia.core.profile.SshCredentials
import net.paramnezia.deploy.ServerManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/** Importing keys and deploying servers: the two ways a server gets into the app. */
class Servers(
    private val repo: Repo,
    private val fleet: FleetManager,
    /** A server was just deployed: put the family page on it (see Links.preparePage). */
    private val onServerReady: (SshCredentials) -> Unit = {},
) {
    private val manager = ServerManager(repo.hostKeys)

    sealed interface Imported {
        data class Profiles(val names: List<String>) : Imported
        data object JoinedFleet : Imported
    }

    /**
     * Accepts everything AmneziaVPN and WireGuard clients share: `vpn://` keys (connection-only or
     * full access), fleet invites, wg-quick/awg-quick configs and assembled multi-QR payloads.
     */
    suspend fun import(text: String): Imported = withContext(Dispatchers.IO) {
        val t = net.paramnezia.core.amnezia.KeyText.extract(text)
        if (Invite.read(t) != null) {
            if (!fleet.join(t, Build.MODEL)) throw ConfigException("Приглашение повреждено или устарело")
            return@withContext Imported.JoinedFleet
        }
        val added = if (t.contains("[Interface]", ignoreCase = true)) {
            val cfg = WgQuick.parse(t)
            val host = cfg.peers.firstOrNull()?.endpoint?.substringBeforeLast(':') ?: "server"
            listOf(StoredProfile(UUID.randomUUID().toString(), host, WgQuick.write(cfg), if (cfg.isAmnezia) "AWG" else "WIREGUARD"))
        } else {
            AmneziaFormat.import(AmneziaKey.decode(t)).mapNotNull { p ->
                val tunnel = p.tunnel ?: p.ssh?.let { ssh ->
                    // Full-access key without a client config: make ourselves a client on the server.
                    if (p.protocol == Protocol.XRAY) null
                    else manager.addClient(ssh, WgKey.generatePrivate(), "Paramnezia ${Build.MODEL}", p.protocol,
                        dns = p.dns.ifEmpty { listOf(AmneziaFormat.DEFAULT_DNS1, AmneziaFormat.DEFAULT_DNS2) })
                } ?: return@mapNotNull null
                StoredProfile(UUID.randomUUID().toString(), p.name, WgQuick.write(tunnel), p.protocol.name, p.ssh)
            }
        }
        if (added.isEmpty()) throw ConfigException("В ключе нет поддерживаемых протоколов (AmneziaWG, WireGuard)")
        repo.update { s -> s.copy(profiles = s.profiles + added, activeProfile = s.activeProfile ?: added.first().id) }
        Imported.Profiles(added.map { it.name })
    }

    /** Multi-QR from AmneziaVPN, assembled: the same as a `vpn://` key. */
    suspend fun importQrPayload(compressed: ByteArray) = import("vpn://" + AmneziaKey.base64Url(compressed))

    /**
     * One-click server: installs AmneziaWG on a clean VPS (or reuses an AmneziaVPN install), adds
     * this phone as a client and makes it the active server.
     */
    suspend fun deploy(name: String, ssh: SshCredentials, onStep: (ServerManager.Step) -> Unit): StoredProfile = withContext(Dispatchers.IO) {
        val inspection = manager.inspect(ssh)
        val protocol = inspection.awgContainer ?: run {
            manager.install(ssh, ServerManager.InstallOptions(), onStep)
            Protocol.AWG
        }
        val tunnel = manager.addClient(ssh, WgKey.generatePrivate(), "Paramnezia ${Build.MODEL}", protocol)
        onStep(ServerManager.Step.DONE)
        val profile = StoredProfile(UUID.randomUUID().toString(), name.ifBlank { ssh.host }, WgQuick.write(tunnel), protocol.name, ssh)
        repo.update { s -> s.copy(profiles = s.profiles + profile, activeProfile = profile.id) }
        onServerReady(ssh)
        profile
    }

    /** A `vpn://` key for a profile, importable by AmneziaVPN (with server management if [withSsh]). */
    fun export(p: StoredProfile, withSsh: Boolean): String =
        AmneziaKey.encode(AmneziaFormat.export(p.name, WgQuick.parse(p.conf), if (withSsh) p.ssh else null))

    /**
     * Every server this device can manage, as `vpn://` keys with full access (SSH included), one per
     * line: the backup to keep before reinstalling, also importable by AmneziaVPN. Empty if none.
     */
    fun exportAllWithAccess(): String {
        val keys = ArrayList<String>()
        for (p in repo.current.profiles) if (p.ssh != null) runCatching { keys += export(p, withSsh = true) }
        val member = fleet.member()
        if (member != null) for ((server, tunnel) in member.tunnels()) {
            val ssh = fleet.accessTo(server.id) ?: continue
            runCatching { keys += AmneziaKey.encode(AmneziaFormat.export(server.name, tunnel, ssh)) }
        }
        return keys.distinct().joinToString("\n\n")
    }

    fun remove(id: String) = repo.update { s ->
        val rest = s.profiles.filter { it.id != id }
        s.copy(profiles = rest, activeProfile = if (s.activeProfile == id) rest.firstOrNull()?.id else s.activeProfile)
    }
}
