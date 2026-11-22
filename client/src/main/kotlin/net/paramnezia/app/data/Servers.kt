package net.paramnezia.app.data

import net.paramnezia.app.fleet.FleetManager
import net.paramnezia.core.amnezia.AmneziaFormat
import net.paramnezia.core.amnezia.AmneziaKey
import net.paramnezia.core.config.ConfigException
import net.paramnezia.core.config.WgQuick
import net.paramnezia.core.config.Uapi
import net.paramnezia.core.net.IpPrefix
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
    /** This device's model, for client names on servers ("Paramnezia <model>"). */
    private val deviceModel: String,
    /** A server was just deployed: put the family page on it (see Links.preparePage). */
    private val onServerReady: (SshCredentials) -> Unit = {},
) {
    private val manager = ServerManager(repo.hostKeys)

    companion object {
        /** Bumped when own standalone servers need a one-off upgrade (1: spare ports, 2: tuning, 3: engine). */
        const val PORTS_UPGRADE = 4
    }

    sealed interface Imported {
        data class Profiles(val names: List<String>) : Imported
        data class FamilyServer(val name: String) : Imported
        data object JoinedFleet : Imported
        data object UpdatedFleet : Imported
    }

    /**
     * Accepts everything AmneziaVPN and WireGuard clients share: `vpn://` keys (connection-only or
     * full access), fleet invites, wg-quick/awg-quick configs and assembled multi-QR payloads.
     */
    suspend fun import(text: String): Imported = withContext(Dispatchers.IO) {
        val t = net.paramnezia.core.amnezia.KeyText.extract(text)
        if (net.paramnezia.core.fleet.FleetCrypto.decode(t) != null) {
            if (!fleet.offer(t)) throw ConfigException("Обновление семьи уже применено или подпись не подтверждена")
            return@withContext Imported.UpdatedFleet
        }
        if (Invite.read(t) != null) {
            if (!fleet.join(t, deviceModel)) throw ConfigException("Приглашение повреждено или устарело")
            return@withContext Imported.JoinedFleet
        }
        val added = if (t.contains("[Interface]", ignoreCase = true)) {
            val cfg = WgQuick.parse(t)
            val host = cfg.peers.firstOrNull()?.endpoint?.substringBeforeLast(':') ?: "server"
            listOf(StoredProfile(UUID.randomUUID().toString(), host, WgQuick.write(cfg), if (cfg.isAmnezia) "AWG" else "WIREGUARD"))
        } else {
            val profiles = AmneziaFormat.import(AmneziaKey.decode(t))
            val withAccess = profiles.firstOrNull { it.ssh != null }
            if (fleet.isOwner && withAccess != null) {
                val ssh = checkNotNull(withAccess.ssh)
                val awg = profiles.firstOrNull { it.protocol == Protocol.AWG }
                val name = withAccess.name.ifBlank { ssh.host }
                val peer = awg?.tunnel?.peers?.firstOrNull { it.endpoint != null }
                addFamilyServer(name, ssh, peer?.endpoint, peer?.publicKey?.toBase64())
                return@withContext Imported.FamilyServer(name)
            }
            profiles.mapNotNull { p ->
                val tunnel = p.tunnel ?: p.ssh?.let { ssh ->
                    // Full-access key without a client config: make ourselves a client on the server.
                    if (p.protocol == Protocol.XRAY) null
                    else manager.addClient(ssh, WgKey.generatePrivate(), "Paramnezia ${deviceModel}", p.protocol,
                        dns = p.dns.ifEmpty { listOf(AmneziaFormat.DEFAULT_DNS1, AmneziaFormat.DEFAULT_DNS2) }, psk = WgKey.generatePreshared())
                } ?: return@mapNotNull null
                StoredProfile(UUID.randomUUID().toString(), p.name, WgQuick.write(tunnel), p.protocol.name, p.ssh)
            }
        }
        if (added.isEmpty()) throw ConfigException("В ключе нет поддерживаемых протоколов (AmneziaWG, WireGuard)")
        repo.update { s -> s.copy(profiles = s.profiles + added, activeProfile = added.first().id) }
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
        val tunnel = manager.addClient(ssh, WgKey.generatePrivate(), "Paramnezia ${deviceModel}", protocol, psk = WgKey.generatePreshared())
        val ports = if (protocol == Protocol.AWG) runCatching { manager.ports(ssh, ServerManager.altPortsFor(portOf(tunnel))) }.getOrNull() else null
        onStep(ServerManager.Step.DONE)
        val profile = StoredProfile(UUID.randomUUID().toString(), name.ifBlank { ssh.host }, WgQuick.write(tunnel), protocol.name, ssh,
            altPorts = ports?.alt.orEmpty(), altHosts = listOfNotNull(ports?.ipv6), portsChecked = PORTS_UPGRADE)
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

    /**
     * Once per upgrade: own standalone servers (with SSH) are tuned for throughput (tune.sh) and get
     * spare ports, and the app learns what each answers on. A server whose container must be re-created for it is down for a few seconds.
     * Call from the open app, never from background work. Each server fails on its own.
     */
    suspend fun upgradePorts() = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        for (p in repo.current.profiles) {
            val ssh = p.ssh ?: continue
            if (p.portsChecked >= PORTS_UPGRADE || p.protocol != Protocol.AWG.name) continue
            // A server that did not answer is asked again in an hour, not at every start of the app.
            if (now - (upgradeTried[p.id] ?: 0L) < 3600_000L) continue
            upgradeTried[p.id] = now
            val main = runCatching { portOf(WgQuick.parse(p.conf)) }.getOrNull() ?: continue
            runCatching {
                manager.refresh(ssh)
                manager.tune(ssh)
                manager.enableIpv6(ssh)
                manager.ports(ssh, if (p.altPorts.isEmpty()) ServerManager.altPortsFor(main) else emptyList())
            }
                .onSuccess { r ->
                    repo.update { s -> s.copy(profiles = s.profiles.map {
                        if (it.id != p.id || it.conf != p.conf || it.ssh != ssh || r.main != main) it
                        else {
                            val config = WgQuick.parse(p.conf)
                            val address = config.iface.addresses.firstOrNull { !it.isV6 }
                            val ipv6 = if (r.tunnelIpv6 && config.iface.addresses.none { it.isV6 }) {
                                address?.let { net.paramnezia.core.fleet.managedIpv6Address(it.toString()) }
                            } else null
                            val updated = if (ipv6 == null) it.conf else WgQuick.write(config.copy(
                                iface = config.iface.copy(addresses = config.iface.addresses + ipv6),
                            ))
                            val host = net.paramnezia.core.config.Uapi.splitEndpoint(config.peers.first().endpoint.orEmpty()).first
                            val alternate = if (':' in host) r.ipv4 else r.ipv6
                            it.copy(conf = updated, altPorts = r.alt, altHosts = listOfNotNull(alternate), portsChecked = PORTS_UPGRADE)
                        }
                    }) }
                }
                .onFailure { net.paramnezia.app.platform.Log.w("PnServers", "ports of ${p.name}: ${it.message}") }
        }
    }

    private val upgradeTried = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private fun portOf(t: net.paramnezia.core.config.TunnelConfig): Int =
        net.paramnezia.core.config.Uapi.splitEndpoint(t.peers.first().endpoint ?: throw ConfigException("no endpoint")).second

    /** The owner's VPS is provisioned for every family device. */
    suspend fun addVps(name: String, ssh: SshCredentials, onStep: (ServerManager.Step) -> Unit): String {
        val label = name.trim().ifEmpty { ssh.host }
        if (fleet.isOwner) fleet.addServer(label, ssh, makeMaster = false, onStep = onStep)
        else deploy(label, ssh, onStep)
        return label
    }

    /** Moves the existing profile only after its server is available to all family devices. */
    suspend fun moveToFamily(id: String, credentials: SshCredentials? = null) = withContext(Dispatchers.IO) {
        check(fleet.isOwner) { "Перенести сервер может владелец семьи" }
        val family = fleet.stored?.trust ?: error("Семья не найдена")
        val profile = repo.current.profiles.firstOrNull { it.id == id } ?: error("Сервер не найден")
        val config = WgQuick.parse(profile.conf)
        val peer = config.peers.firstOrNull { it.endpoint != null } ?: error("В профиле нет адреса VPN")
        val endpoint = checkNotNull(peer.endpoint)
        val key = peer.publicKey.toBase64()
        val familyId = existingFamilyServer(credentials ?: profile.ssh, endpoint, key) ?: run {
            val ssh = credentials ?: profile.ssh ?: error("Укажите SSH-доступ к серверу")
            check(profile.protocol == Protocol.AWG.name) { "Для семейного сервера нужен AmneziaWG 2" }
            addFamilyServer(profile.name, ssh, endpoint, key)
        }
        check(fleet.manifest()?.servers?.any { it.id == familyId } == true) { "Семейный сервер не сохранился" }
        repo.update { state ->
            check(state.fleet?.trust == family) { "Семья изменилась во время переноса" }
            val current = state.profiles.firstOrNull { it.id == id } ?: return@update state
            check(current == profile) { "Профиль изменился во время переноса" }
            state.copy(
                profiles = state.profiles.filterNot { it.id == id },
                activeProfile = state.activeProfile.takeUnless { it == id },
                links = state.links.map { if (it.serverRef == id) it.copy(serverRef = familyId) else it },
                clientPool = state.clientPool.filterNot { it.profileId == id },
            )
        }
    }

    data class FamilyMove(val moved: Int, val failures: List<String>)

    suspend fun moveLocalServersToFamily(): FamilyMove = withContext(Dispatchers.IO) {
        val profiles = repo.current.profiles
        val failures = mutableListOf<String>()
        var moved = 0
        for (profile in profiles) {
            try {
                moveToFamily(profile.id)
                if (repo.current.profiles.none { it.id == profile.id }) moved++
                else failures += "${profile.name}: профиль изменился во время переноса"
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                failures += "${profile.name}: ${e.message ?: "не удалось подключиться"}"
            }
        }
        FamilyMove(moved, failures)
    }

    private fun existingFamilyServer(ssh: SshCredentials?, endpoint: String?, key: String?): String? {
        val address = endpoint?.let(Uapi::splitEndpoint)
        return fleet.manifest()?.servers.orEmpty().firstOrNull { server ->
            val sameSsh = ssh != null && fleet.accessTo(server.id)?.let {
                it.host.equals(ssh.host, ignoreCase = true) && it.port == ssh.port
            } == true
            val sameEndpoint = address?.let { (host, port) -> host.equals(server.host, ignoreCase = true) && port == server.port } == true
            (sameSsh || (key != null && sameEndpoint)) && (key == null || key == server.publicKey)
        }?.id
    }

    private suspend fun addFamilyServer(name: String, ssh: SshCredentials, endpoint: String?, key: String?): String =
        existingFamilyServer(ssh, endpoint, key) ?: fleet.addServer(
            name, ssh, makeMaster = false, vpnEndpoint = endpoint, expectedPublicKey = key,
        ) {}

    fun makePrimary(id: String) = repo.update { s ->
        val server = s.profiles.firstOrNull { it.id == id } ?: error("Сервер не найден")
        s.copy(profiles = listOf(server) + s.profiles.filterNot { it.id == id }, activeProfile = id)
    }

    fun edit(id: String, name: String, conf: String) = editProfile(id, name, updateProtocol = true) { conf }

    fun editEndpoint(id: String, name: String, endpoint: String) = editProfile(id, name, updateProtocol = false) { profile ->
        val (host, port) = Uapi.splitEndpoint(endpoint)
        require(host.isNotEmpty() && host.none { it.isWhitespace() || it in "/[]@?#%" } &&
            (':' !in host || IpPrefix.parseOrNull(host)?.isV6 == true)) { "Введите адрес сервера и порт" }
        val config = WgQuick.parse(profile.conf)
        val index = config.peers.indexOfFirst { it.endpoint != null }
        require(index >= 0) { "Укажите Endpoint в конфигурации" }
        val address = if (':' in host) "[$host]:$port" else "$host:$port"
        if (config.peers[index].endpoint == address) profile.conf
        else WgQuick.write(config.copy(peers = config.peers.mapIndexed { i, peer ->
            if (i == index) peer.copy(endpoint = address) else peer
        }))
    }

    private fun editProfile(id: String, name: String, updateProtocol: Boolean, edit: (StoredProfile) -> String) {
        val label = name.trim()
        require(label.isNotEmpty()) { "Введите название сервера" }
        repo.update { s ->
            val profile = s.profiles.firstOrNull { it.id == id } ?: error("Сервер не найден")
            val conf = edit(profile)
            val updated = if (conf == profile.conf) profile.copy(name = label) else {
                val config = WgQuick.parse(conf)
                require(config.peers.any { it.endpoint != null }) { "Укажите Endpoint в конфигурации" }
                val protocol = if (!updateProtocol) profile.protocol else if (config.isAmnezia) "AWG" else "WIREGUARD"
                profile.copy(name = label, conf = WgQuick.write(config), protocol = protocol,
                    altPorts = emptyList(), altHosts = emptyList())
            }
            s.copy(
                profiles = s.profiles.map { if (it.id == id) updated else it },
                clientPool = if (conf == profile.conf) s.clientPool else s.clientPool.filter { it.profileId != id },
            )
        }
    }

    fun remove(id: String) = repo.update { s ->
        val rest = s.profiles.filter { it.id != id }
        s.copy(
            profiles = rest,
            activeProfile = s.activeProfile.takeUnless { it == id },
            clientPool = s.clientPool.filter { it.profileId != id },
        )
    }
}
