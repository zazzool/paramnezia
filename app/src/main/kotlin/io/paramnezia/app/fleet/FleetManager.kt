package io.paramnezia.app.fleet

import android.util.Log
import io.paramnezia.app.data.Repo
import io.paramnezia.app.data.StoredFleet
import io.paramnezia.app.tunnel.Net
import io.paramnezia.core.config.TunnelConfig
import io.paramnezia.core.crypto.WgKey
import io.paramnezia.core.fleet.Ed25519
import io.paramnezia.core.fleet.FleetAdmin
import io.paramnezia.core.fleet.FleetCrypto
import io.paramnezia.core.fleet.FleetManifest
import io.paramnezia.core.fleet.FleetMember
import io.paramnezia.core.fleet.FleetServer
import io.paramnezia.core.fleet.GossipMessage
import io.paramnezia.core.fleet.HealthBook
import io.paramnezia.core.fleet.Invite
import io.paramnezia.core.fleet.fleetServerOf
import io.paramnezia.core.nostr.NostrClient
import io.paramnezia.core.nostr.NostrFleet
import io.paramnezia.core.profile.SshCredentials
import io.paramnezia.deploy.FleetPublisher
import io.paramnezia.deploy.ServerManager
import io.paramnezia.tunnel.TunnelRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.util.Base64
import java.util.UUID

/**
 * Fleet membership on this device and, on the admin device, fleet management.
 *
 * Manifests travel over every channel at once and the newest valid one wins: the in-tunnel URL on
 * each server, HTTPS mirrors, Nostr relays (every device keeps its own slot) and UDP gossip between
 * devices inside the tunnel. None of them is trusted; the admin signature is the only authority.
 */
class FleetManager(
    private val repo: Repo,
    private val net: Net,
    private val ed: Ed25519,
    private val scope: CoroutineScope,
    /** Called when a newer manifest arrived, so the tunnel can switch servers at once. */
    private val onManifestChanged: () -> Unit,
) {
    private val servers = ServerManager(repo.hostKeys)
    private val publisher = FleetPublisher(repo.hostKeys)
    private val health = HealthBook()

    val stored: StoredFleet? get() = repo.current.fleet

    fun member(): FleetMember? {
        val f = stored ?: return null
        val signed = FleetCrypto.decode(f.manifest) ?: return null
        return FleetMember(f.trust, WgKey.fromBase64(f.deviceKey), ed, FleetCrypto.open(signed, f.trust, ed))
    }

    fun manifest(): FleetManifest? = member()?.manifest

    private fun admin(): FleetAdmin? {
        val f = stored ?: return null
        val seed = f.adminSeed ?: return null
        return FleetAdmin(f.trust, Base64.getDecoder().decode(seed), ed)
    }

    val isAdmin: Boolean get() = stored?.adminSeed != null

    private fun nostr() = stored?.let { NostrFleet(it.trust) }

    private fun relays(): List<String> = repo.current.settings.relays.ifEmpty { NostrFleet.DEFAULT_RELAYS }

    /** Tunnel addresses of the other devices, for gossip. */
    fun peers(): List<String> {
        val f = stored ?: return emptyList()
        val me = WgKey.fromBase64(f.deviceKey).publicKey().toBase64()
        return manifest()?.devices.orEmpty().filter { it.publicKey != me }.map { it.address.substringBefore('/') }
    }

    // --- receiving ----------------------------------------------------------------------------

    /** Offers a manifest from any channel. Returns true if it was newer and is now in use. */
    fun offer(text: String, gossipOnward: Boolean = true): Boolean {
        val m = member() ?: return false
        if (!m.offer(text)) return false
        repo.update { s -> s.copy(fleet = s.fleet?.copy(manifest = text.trim())) }
        if (gossipOnward) TunnelRuntime.gossip(GossipMessage.encode(GossipMessage.Manifest(text.trim())), peers())
        onManifestChanged()
        return true
    }

    /** Joins a fleet from an invite. Returns false for an ordinary key (not an invite). */
    fun join(invite: String, deviceName: String): Boolean {
        val m = FleetMember.fromInvite(invite.trim(), ed) ?: return false
        val inv = Invite.read(invite) ?: return false
        repo.update { s ->
            s.copy(fleet = StoredFleet(m.trust, m.deviceKey.toBase64(), deviceName.ifBlank { inv.deviceName }, inv.manifest), activeProfile = null)
        }
        onManifestChanged()
        return true
    }

    /** Pulls from every channel in parallel; true if a newer manifest was found. */
    suspend fun sync(): Boolean = withContext(Dispatchers.IO) {
        val f = stored ?: return@withContext false
        val manifest = manifest()
        val nf = NostrFleet(f.trust)
        val client = NostrClient(net.directSockets())
        val texts: List<String> = coroutineScope {
            val http = (listOf(publisher.tunnelUrl) + manifest?.mirrors.orEmpty()).distinct().map { url ->
                async { listOfNotNull(net.get(url)) }
            }
            val relayJobs = relays().map { relay ->
                async {
                    runCatching {
                        nf.candidates(client.fetch(relay, manifest?.let(nf::authors) ?: listOf(nf.pubkeyFor(NostrFleet.ADMIN)), nf.tag), manifest)
                    }.getOrDefault(emptyList())
                }
            }
            (http + relayJobs).awaitAll().flatten()
        }
        var changed = false
        for (t in texts.distinct()) if (offer(t)) changed = true
        repo.update { s -> s.copy(fleet = s.fleet?.copy(lastSync = System.currentTimeMillis())) }
        // Keep our own slot current, so devices that missed the admin's event still find this version.
        if (changed || texts.none { FleetCrypto.decode(it)?.version == member()?.manifest?.version }) publishOwnSlot()
        changed
    }

    private suspend fun publishOwnSlot() = withContext(Dispatchers.IO) {
        val f = stored ?: return@withContext
        val nf = nostr() ?: return@withContext
        val version = manifest()?.version ?: return@withContext
        val me = if (f.adminSeed != null) NostrFleet.ADMIN else WgKey.fromBase64(f.deviceKey).publicKey().toBase64()
        val event = nf.manifestEvent(f.manifest, me, version)
        val client = NostrClient(net.directSockets())
        coroutineScope { relays().map { r -> async { runCatching { client.publish(r, event) }.getOrDefault(false) } }.awaitAll() }
    }

    // --- gossip ---------------------------------------------------------------------------------

    fun onGossip(from: String, message: GossipMessage) {
        when (message) {
            is GossipMessage.Manifest -> offer(message.signed)
            is GossipMessage.Version -> {
                val mine = manifest() ?: return
                if (message.fleetId == mine.fleetId && message.version < mine.version) {
                    stored?.let { TunnelRuntime.gossip(GossipMessage.encode(GossipMessage.Manifest(it.manifest)), listOf(from)) }
                }
            }
            is GossipMessage.Health -> if (isAdmin) {
                health.record(from, message)
                maybePromote()
            }
            is GossipMessage.Release -> {}
        }
    }

    /** Announces our version to the other devices (after connecting). */
    fun announce() {
        val m = manifest() ?: return
        TunnelRuntime.gossip(GossipMessage.encode(GossipMessage.Version(m.fleetId, m.version)), peers())
    }

    fun reportHealth(serverId: String, reachable: Boolean) {
        val msg = GossipMessage.Health(serverId, reachable, System.currentTimeMillis())
        if (isAdmin) { health.record("self", msg); maybePromote() }
        TunnelRuntime.gossip(GossipMessage.encode(msg), peers())
    }

    /** Admin: when the master is down for the quorum of devices, the first live standby becomes master. */
    private fun maybePromote() {
        val m = manifest() ?: return
        val master = m.servers.firstOrNull() ?: return
        if (!health.isDown(master.id, System.currentTimeMillis(), m.devices.size)) return
        val next = m.servers.drop(1).firstOrNull { !health.isDown(it.id, System.currentTimeMillis(), m.devices.size) } ?: return
        Log.i(TAG, "master ${master.name} is down for the fleet, promoting ${next.name}")
        scope.launch(Dispatchers.IO) { runCatching { promote(next.id) } }
    }

    // --- admin ----------------------------------------------------------------------------------

    /**
     * Creates a fleet around a server this device can manage over SSH. This device becomes the admin
     * and its first member.
     */
    suspend fun create(serverName: String, serverTunnel: TunnelConfig, ssh: SshCredentials, deviceName: String) = withContext(Dispatchers.IO) {
        val (admin, empty) = FleetAdmin.create(serverName, ed)
        val serverId = UUID.randomUUID().toString()
        val (device, key) = admin.newDevice(empty, deviceName)
        val config = servers.addClient(ssh, key, deviceName, clientIp = device.address.substringBefore('/'),
            dns = serverTunnel.iface.dns.ifEmpty { listOf("1.1.1.1", "1.0.0.1") })
        val server = fleetServerOf(serverId, serverName, config)
        val manifest = admin.next(empty) { it.copy(servers = listOf(server), devices = listOf(device), mirrors = listOf(publisher.tunnelUrl)) }
        val signed = FleetCrypto.encode(admin.seal(manifest))
        repo.update { s ->
            s.copy(
                fleet = StoredFleet(admin.trust, key.toBase64(), deviceName, signed,
                    adminSeed = Base64.getEncoder().encodeToString(admin.signingSeed), serverAccess = mapOf(serverId to ssh)),
                activeProfile = null,
            )
        }
        publish(signed)
        onManifestChanged()
    }

    /** Admin: a new trusted device. Returns its invite (a vpn:// key, also readable by AmneziaVPN). */
    suspend fun addDevice(name: String): String = withContext(Dispatchers.IO) {
        val admin = admin() ?: error("not admin")
        val current = manifest() ?: error("no manifest")
        val (device, key) = admin.newDevice(current, name)
        registerEverywhere(current.servers, key.publicKey(), name, device.address)
        val next = admin.next(current) { it.copy(devices = it.devices + device) }
        val signed = FleetCrypto.encode(admin.seal(next))
        accept(signed)
        admin.invite(next, device, key)
    }

    /** Admin: revokes a device everywhere. */
    suspend fun removeDevice(publicKey: String) = withContext(Dispatchers.IO) {
        val admin = admin() ?: error("not admin")
        val current = manifest() ?: return@withContext
        for (s in current.servers) access(s)?.let { runCatching { servers.removeClient(it, WgKey.fromBase64(publicKey)) } }
        accept(FleetCrypto.encode(admin.seal(admin.next(current) { m -> m.copy(devices = m.devices.filter { it.publicKey != publicKey }) })))
    }

    /**
     * Admin: deploys a fresh VPS as a standby (or master) and registers every device on it with its
     * fixed address. Devices switch to it automatically when they need to.
     */
    suspend fun addServer(name: String, ssh: SshCredentials, makeMaster: Boolean, onStep: (ServerManager.Step) -> Unit) = withContext(Dispatchers.IO) {
        val admin = admin() ?: error("not admin")
        val current = manifest() ?: error("no manifest")
        val f = stored!!
        val inspection = servers.inspect(ssh)
        if (inspection.awgContainer == null) servers.install(ssh, ServerManager.InstallOptions(), onStep)
        val myKey = WgKey.fromBase64(f.deviceKey)
        val me = current.deviceFor(myKey.publicKey()) ?: error("admin is not a fleet member")
        val config = servers.addClient(ssh, myKey, me.name, clientIp = me.address.substringBefore('/'))
        val server = fleetServerOf(UUID.randomUUID().toString(), name, config)
        for (d in current.devices) if (d.publicKey != me.publicKey) {
            servers.registerPeer(ssh, WgKey.fromBase64(d.publicKey), d.name, clientIp = d.address.substringBefore('/'))
        }
        repo.update { s -> s.copy(fleet = s.fleet?.let { it.copy(serverAccess = it.serverAccess + (server.id to ssh)) }) }
        accept(FleetCrypto.encode(admin.seal(admin.next(current) {
            it.copy(servers = if (makeMaster) listOf(server) + it.servers else it.servers + server)
        })))
    }

    /** Admin: makes [serverId] the master. Devices follow within seconds (gossip) or hours (sync). */
    suspend fun promote(serverId: String) = withContext(Dispatchers.IO) {
        val admin = admin() ?: return@withContext
        val current = manifest() ?: return@withContext
        val target = current.servers.firstOrNull { it.id == serverId } ?: return@withContext
        if (current.servers.first().id == serverId) return@withContext
        accept(FleetCrypto.encode(admin.seal(admin.next(current) { m -> m.copy(servers = listOf(target) + m.servers.filter { it.id != serverId }) })))
    }

    /** Admin: drops a server (for example one banned for good). */
    suspend fun removeServer(serverId: String) = withContext(Dispatchers.IO) {
        val admin = admin() ?: return@withContext
        val current = manifest() ?: return@withContext
        if (current.servers.size <= 1) return@withContext
        accept(FleetCrypto.encode(admin.seal(admin.next(current) { m -> m.copy(servers = m.servers.filter { it.id != serverId }) })))
        repo.update { s -> s.copy(fleet = s.fleet?.let { it.copy(serverAccess = it.serverAccess - serverId) }) }
    }

    /** Admin: sets the gist mirror (devices without a working tunnel read it over plain HTTPS). */
    suspend fun setGist(gistId: String, token: String) = withContext(Dispatchers.IO) {
        val admin = admin() ?: return@withContext
        val f = stored ?: return@withContext
        val url = publisher.publishToGist(token, gistId, f.manifest)
        repo.update { s -> s.copy(fleet = s.fleet?.copy(gistId = gistId, gistToken = token)) }
        val current = manifest() ?: return@withContext
        if (url !in current.mirrors) accept(FleetCrypto.encode(admin.seal(admin.next(current) { it.copy(mirrors = it.mirrors + url) })))
    }

    private suspend fun accept(signed: String) {
        offer(signed)
        publish(signed)
    }

    /** Admin: pushes a manifest to every channel. Failures are fine as long as one channel works. */
    private suspend fun publish(signed: String) = withContext(Dispatchers.IO) {
        val f = stored ?: return@withContext
        val m = manifest() ?: return@withContext
        coroutineScope {
            val jobs = m.servers.mapNotNull { s -> access(s)?.let { a -> async { runCatching { publisher.publishToServer(a, signed) } } } } +
                listOfNotNull(f.gistId?.let { id -> f.gistToken?.let { t -> async { runCatching { publisher.publishToGist(t, id, signed) } } } })
            jobs.awaitAll()
        }
        publishOwnSlot()
    }

    private fun registerEverywhere(list: List<FleetServer>, publicKey: WgKey, name: String, address: String) {
        for (s in list) {
            val a = access(s) ?: continue
            runCatching { servers.registerPeer(a, publicKey, name, clientIp = address.substringBefore('/')) }
                .onFailure { Log.w(TAG, "could not register $name on ${s.name}: ${it.message}") }
        }
    }

    private fun access(s: FleetServer): SshCredentials? = stored?.serverAccess?.get(s.id)

    /** Admin: the first server (master first) this device can reach over SSH. */
    fun managedServer(): Pair<FleetServer, SshCredentials>? =
        manifest()?.servers?.firstNotNullOfOrNull { s -> access(s)?.let { s to it } }

    /** Admin: SSH access to a fleet server by id. */
    fun accessTo(serverId: String): SshCredentials? = stored?.serverAccess?.get(serverId)

    private companion object {
        const val TAG = "PnFleet"
    }
}

