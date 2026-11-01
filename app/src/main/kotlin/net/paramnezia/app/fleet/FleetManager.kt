package net.paramnezia.app.fleet

import android.util.Log
import net.paramnezia.app.data.Repo
import net.paramnezia.app.data.Settings
import net.paramnezia.app.data.StoredFleet
import net.paramnezia.core.fleet.FleetPolicy
import net.paramnezia.app.tunnel.Net
import net.paramnezia.core.config.TunnelConfig
import net.paramnezia.core.crypto.WgKey
import net.paramnezia.core.fleet.Ed25519
import net.paramnezia.core.fleet.FleetAdmin
import net.paramnezia.core.fleet.FleetCrypto
import net.paramnezia.core.fleet.FleetManifest
import net.paramnezia.core.fleet.FleetMember
import net.paramnezia.core.fleet.FleetServer
import net.paramnezia.core.fleet.HealthBook
import net.paramnezia.core.fleet.HealthReport
import net.paramnezia.core.fleet.Invite
import net.paramnezia.core.fleet.fleetServerOf
import net.paramnezia.core.profile.SshCredentials
import net.paramnezia.deploy.FleetPublisher
import net.paramnezia.deploy.ServerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.util.Base64
import java.util.UUID
import net.paramnezia.core.ota.Release

/**
 * Fleet membership on this device and, on the admin device, fleet management.
 *
 * Devices read the signed manifest over plain HTTP(S), like a VPN subscription URL: from the
 * server inside the tunnel and from the HTTPS mirrors the manifest lists (a gist, any static host).
 * The newest valid one wins. No mirror is trusted; the admin signature is the only authority. The
 * manifest lists several servers, so when the current one is banned a device moves to the next on
 * its own and reads the newest manifest through it.
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

    // --- receiving ----------------------------------------------------------------------------

    /** Offers a manifest from any channel. Returns true if it was newer and is now in use. */
    fun offer(text: String): Boolean {
        val m = member() ?: return false
        val wire = FleetCrypto.unwrap(text)
        if (!m.offer(wire)) return false
        val policy = m.manifest?.policy
        repo.update { s ->
            val applied = if (policy != null && s.fleet?.adminSeed == null) s.settings.applying(policy) else s.settings
            s.copy(fleet = s.fleet?.copy(manifest = wire), settings = applied)
        }
        onManifestChanged()
        return true
    }

    /** Joins a fleet from an invite. Returns false for an ordinary key (not an invite). */
    fun join(invite: String, deviceName: String): Boolean {
        val m = FleetMember.fromInvite(invite.trim(), ed) ?: return false
        val inv = Invite.read(invite) ?: return false
        val policy = m.manifest?.policy
        repo.update { s ->
            s.copy(
                fleet = StoredFleet(m.trust, m.deviceKey.toBase64(), deviceName.ifBlank { inv.deviceName }, inv.manifest),
                activeProfile = null,
                settings = policy?.let(s.settings::applying) ?: s.settings,
            )
        }
        onManifestChanged()
        return true
    }

    @Volatile private var lastAttempt = 0L

    /**
     * Reads every channel in parallel: the server inside the tunnel and the HTTPS mirrors (the
     * GitHub reserve channel), each the normal way and then over the physical network. When none
     * answers, tries the HTTPS mirrors through Orbot if it runs. True if a newer manifest arrived.
     * With [tunnelFirst] the mirrors are asked only when the server inside the tunnel does not answer
     * (the hourly check: one small request that never leaves the tunnel).
     */
    suspend fun sync(tunnelFirst: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        stored ?: return@withContext false
        lastAttempt = System.currentTimeMillis()
        val mirrors = manifest()?.mirrors.orEmpty().filter { it != publisher.tunnelUrl }.distinct()
        fun valid(u: String, t: String?) = t?.takeIf { FleetCrypto.decode(it) != null }?.let { u to it }
        var answers = if (tunnelFirst) listOfNotNull(valid(publisher.tunnelUrl, net.get(publisher.tunnelUrl, fallbackDirect = false))) else emptyList()
        if (answers.isEmpty()) {
            val urls = listOf(publisher.tunnelUrl) + mirrors
            answers = coroutineScope { urls.map { url -> async { url to net.get(url) } }.awaitAll() }.mapNotNull { (u, t) -> valid(u, t) }
        }
        var via = answers.firstOrNull()?.first?.let(::channelName)
        if (answers.isEmpty() && mirrors.isNotEmpty()) {
            answers = listOfNotNull(mirrors.firstNotNullOfOrNull { u -> valid(u, net.getViaTor(u)) })
            if (answers.isNotEmpty()) via = "tor"
        }
        // Newest first, so a stale mirror never triggers an extra reconnect.
        var changed = false
        for (t in answers.map { it.second }.distinct().sortedByDescending { FleetCrypto.decode(it)?.version ?: 0L }) if (offer(t)) changed = true
        if (answers.isNotEmpty()) repo.update { s -> s.copy(fleet = s.fleet?.copy(lastSync = System.currentTimeMillis(), lastSyncVia = via)) }
        changed
    }

    /**
     * Cheap background check while the tunnel works: at most once per [minIntervalMs], and at once
     * after a switch to another server. One small HTTP request inside the tunnel.
     */
    fun syncIfDue(minIntervalMs: Long = SYNC_EVERY_MS) {
        if (stored == null || System.currentTimeMillis() - lastAttempt < minIntervalMs) return
        lastAttempt = System.currentTimeMillis()
        scope.launch { runCatching { sync(tunnelFirst = true) } }
    }

    private fun channelName(url: String) = when {
        url == publisher.tunnelUrl -> "tunnel"
        "github" in url -> "github"
        else -> "mirror"
    }

    /**
     * This device could not reach [serverId]. On the admin device, a master that stays down makes the
     * first live standby the master, and the new manifest goes to the servers and mirrors.
     */
    fun reportHealth(serverId: String, reachable: Boolean) {
        if (!isAdmin) return
        health.record("self", HealthReport(serverId, reachable, System.currentTimeMillis()))
        maybePromote()
    }

    /** Admin: when the master is down, the first live standby becomes master. */
    private fun maybePromote() {
        val m = manifest() ?: return
        val master = m.servers.firstOrNull() ?: return
        if (!health.isDown(master.id, System.currentTimeMillis(), 1)) return
        val next = m.servers.drop(1).firstOrNull { !health.isDown(it.id, System.currentTimeMillis(), 1) } ?: return
        Log.i(TAG, "master ${master.name} is down, promoting ${next.name}")
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
        val (device, key) = admin.newDevice(empty, deviceName, reserved = servers.usedAddresses(ssh))
        val config = servers.addClient(ssh, key, deviceName, clientIp = device.address.substringBefore('/'),
            dns = serverTunnel.iface.dns.ifEmpty { listOf("1.1.1.1", "1.0.0.1") })
        val server = fleetServerOf(serverId, serverName, config)
        val manifest = admin.next(empty) {
            it.copy(servers = listOf(server), devices = listOf(device), mirrors = listOf(publisher.tunnelUrl), policy = repo.current.settings.toPolicy())
        }
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
        val reserved = current.servers.mapNotNull(::access).flatMap { a -> runCatching { servers.usedAddresses(a) }.getOrDefault(emptySet()) }
        val (device, key) = admin.newDevice(current, name, reserved = reserved)
        val failed = registerEverywhere(current.servers, key.publicKey(), name, device.address)
        // The master must take the device now; a standby that is down gets it at the next self-check.
        current.servers.firstOrNull()?.let { master ->
            if (access(master) != null && master.id in failed) throw failed.getValue(master.id)
        }
        val next = admin.next(current) { it.copy(devices = it.devices + device) }
        val signed = FleetCrypto.encode(admin.seal(next))
        accept(signed)
        admin.invite(next, device, key)
    }

    /**
     * Admin: revokes a device everywhere. A server that cannot be reached now is cleaned up by the
     * next self-check ([heal]): the key stays on the revoked list until every server dropped it.
     */
    suspend fun removeDevice(publicKey: String) = withContext(Dispatchers.IO) {
        val admin = admin() ?: error("not admin")
        val current = manifest() ?: return@withContext
        repo.update { s -> s.copy(fleet = s.fleet?.let { it.copy(revoked = it.revoked + publicKey) }) }
        var everywhere = true
        for (s in current.servers) {
            val a = access(s) ?: continue
            runCatching { servers.removeClient(a, WgKey.fromBase64(publicKey)) }.onFailure { everywhere = false }
        }
        if (everywhere) repo.update { s -> s.copy(fleet = s.fleet?.let { it.copy(revoked = it.revoked - publicKey) }) }
        accept(FleetCrypto.encode(admin.seal(admin.next(current) { m -> m.copy(devices = m.devices.filter { it.publicKey != publicKey }) })))
    }

    /**
     * Admin: checks and repairs every server it can reach, so that the fleet converges even after
     * failures (a server that was down while a device was added or removed, a VPS reboot that took
     * the in-tunnel web server with it, an AmneziaVPN-made container):
     *  - every device of the manifest is a peer with its fixed address (idempotent);
     *  - revoked devices are gone;
     *  - the current signed manifest is stored and served inside the tunnel;
     *  - the server holds the current app release (downloaded and verified by the server itself).
     * Each server and each step fails on its own; the next run tries again. Returns servers fixed.
     */
    suspend fun heal(release: Pair<Release, String>?): Int = withContext(Dispatchers.IO) {
        val f = stored ?: return@withContext 0
        if (f.adminSeed == null) return@withContext 0
        val m = manifest() ?: return@withContext 0
        val peers = m.devices.map { ServerManager.PeerSpec(WgKey.fromBase64(it.publicKey), it.name, it.address.substringBefore('/')) }
        var ok = 0
        val stillRevoked = HashSet<String>()
        for (s in m.servers) {
            val a = access(s) ?: continue
            val steps = listOf(
                "peers" to { servers.ensurePeers(a, peers).forEach { (k, why) -> Log.w(TAG, "${s.name}: peer $k: $why") } },
                "revoked" to {
                    runCatching { servers.removeClients(a, f.revoked.map(WgKey::fromBase64)) }.onFailure { stillRevoked += f.revoked }.getOrThrow()
                },
                "manifest" to { if (!publisher.publishToServer(a, f.manifest)) Log.w(TAG, "${s.name}: in-tunnel web server is not answering") },
                "release" to { release?.let { (r, wire) -> publisher.mirrorRelease(a, r.sha256, r.publicApkUrls(), wire, forInvites = false) } },
            )
            var serverOk = true
            for ((name, step) in steps) runCatching { step() }.onFailure { serverOk = false; Log.w(TAG, "heal ${s.name}/$name: ${it.message}") }
            if (serverOk) ok++
        }
        repo.update { st -> st.copy(fleet = st.fleet?.let { it.copy(revoked = it.revoked.filter { k -> k in stillRevoked }.toSet(), lastHeal = System.currentTimeMillis()) }) }
        ok
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

    /** Admin: makes [serverId] the master. Devices follow at their next manifest read. */
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

    /**
     * Admin: turns on the reserve channel. With only a GitHub token (scope `gist`) the app creates a
     * secret gist, stores the manifest there and lists it in the manifest, so every device can read
     * it when no fleet server is reachable. The gist holds only the encrypted, signed manifest.
     */
    suspend fun setReserveChannel(token: String) = withContext(Dispatchers.IO) {
        val admin = admin() ?: error("not admin")
        val f = stored ?: return@withContext
        val gist = publisher.publishToGist(token.trim(), f.gistId, f.manifest)
        repo.update { s -> s.copy(fleet = s.fleet?.copy(gistId = gist.id, gistToken = token.trim(), gistError = null)) }
        val current = manifest() ?: return@withContext
        val mirrors = current.mirrors.filter { "github" !in it } + gist.mirrors
        if (mirrors != current.mirrors) accept(FleetCrypto.encode(admin.seal(admin.next(current) { it.copy(mirrors = mirrors) })))
    }

    /** Admin: turns the reserve channel off (devices stop looking there; the gist itself stays). */
    suspend fun clearReserveChannel() = withContext(Dispatchers.IO) {
        val admin = admin() ?: return@withContext
        repo.update { s -> s.copy(fleet = s.fleet?.copy(gistId = null, gistToken = null, gistError = null)) }
        val current = manifest() ?: return@withContext
        val mirrors = current.mirrors.filter { "github" !in it }
        if (mirrors != current.mirrors) accept(FleetCrypto.encode(admin.seal(admin.next(current) { it.copy(mirrors = mirrors) })))
    }

    /**
     * Admin: shares [settings] with every device (or stops sharing when [share] is false). Called
     * when the admin changes settings; a no-op when nothing changed, so no new version is made.
     */
    suspend fun sharePolicy(settings: Settings, share: Boolean = stored?.sharePolicy ?: true) = withContext(Dispatchers.IO) {
        val admin = admin() ?: return@withContext
        if (stored?.sharePolicy != share) repo.update { s -> s.copy(fleet = s.fleet?.copy(sharePolicy = share)) }
        val current = manifest() ?: return@withContext
        val policy = if (share) settings.toPolicy() else null
        if (policy != current.policy) accept(FleetCrypto.encode(admin.seal(admin.next(current) { it.copy(policy = policy) })))
    }

    private fun accept(signed: String) {
        offer(signed)
        // Spreading the new manifest (servers, gist) can take a while on a slow network;
        // the admin's screen does not wait for it.
        scope.launch { runCatching { publish(signed) }.onFailure { Log.w(TAG, "publish failed", it) } }
    }

    /** Admin: pushes a manifest to every channel. Failures are fine as long as one channel works. */
    private suspend fun publish(signed: String) = withContext(Dispatchers.IO) {
        val f = stored ?: return@withContext
        val m = manifest() ?: return@withContext
        val gist = coroutineScope {
            val jobs = m.servers.mapNotNull { s -> access(s)?.let { a -> async { runCatching { publisher.publishToServer(a, signed) } } } }
            val gistJob = f.gistToken?.let { t -> async { runCatching { publisher.publishToGist(t, f.gistId, signed) } } }
            jobs.awaitAll()
            gistJob?.await()?.also { r ->
                val error = r.exceptionOrNull()?.let { it.message ?: it.toString() }
                if (error != stored?.gistError) repo.update { s -> s.copy(fleet = s.fleet?.copy(gistError = error)) }
            }?.getOrNull()
        }
        // The gist was deleted on GitHub and a new one was made: point the devices at it.
        if (gist != null && gist.id != f.gistId) {
            repo.update { s -> s.copy(fleet = s.fleet?.copy(gistId = gist.id)) }
            f.gistToken?.let { setReserveChannel(it) }
        }
    }

    /** Registers a device on every server; returns the failures by server id. */
    private fun registerEverywhere(list: List<FleetServer>, publicKey: WgKey, name: String, address: String): Map<String, Exception> {
        val failed = HashMap<String, Exception>()
        for (s in list) {
            val a = access(s) ?: continue
            try {
                servers.registerPeer(a, publicKey, name, clientIp = address.substringBefore('/'))
            } catch (e: Exception) {
                Log.w(TAG, "could not register $name on ${s.name}: ${e.message}")
                failed[s.id] = e
            }
        }
        return failed
    }

    private fun access(s: FleetServer): SshCredentials? = stored?.serverAccess?.get(s.id)

    /** Admin: the first server (master first) this device can reach over SSH. */
    fun managedServer(): Pair<FleetServer, SshCredentials>? =
        manifest()?.servers?.firstNotNullOfOrNull { s -> access(s)?.let { s to it } }

    /** Admin: SSH access to a fleet server by id. */
    fun accessTo(serverId: String): SshCredentials? = stored?.serverAccess?.get(serverId)

    private companion object {
        const val TAG = "PnFleet"
        /** While the tunnel works, look for a newer manifest at most this often. */
        const val SYNC_EVERY_MS = 3600_000L
    }
}

/** The admin's settings as the fleet's shared policy (battery mode stays per device). */
fun Settings.toPolicy() = FleetPolicy(bypassRussia, bypassLan, blockTrackers, forceTunnel, forceDirect, directDns)

/** This device's settings with the fleet's policy applied. */
fun Settings.applying(p: FleetPolicy) = copy(
    bypassRussia = p.bypassRussia, bypassLan = p.bypassLan, blockTrackers = p.blockTrackers,
    forceTunnel = p.forceTunnel, forceDirect = p.forceDirect, directDns = p.directDns,
)

