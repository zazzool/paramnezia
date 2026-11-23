package net.paramnezia.app.fleet

import net.paramnezia.app.platform.Log
import net.paramnezia.app.data.Repo
import net.paramnezia.app.data.Settings
import net.paramnezia.app.data.StoredFleet
import net.paramnezia.app.data.PooledDevice
import net.paramnezia.app.data.CoAdminRecord
import net.paramnezia.core.fleet.AdminGrant
import net.paramnezia.core.fleet.DevicePsk
import net.paramnezia.core.fleet.FleetCoAdmin
import net.paramnezia.core.fleet.GrantBox
import net.paramnezia.deploy.cloud.SshKeyPair
import net.paramnezia.core.fleet.FleetDevice
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import net.paramnezia.core.fleet.FleetPolicy
import net.paramnezia.app.platform.Http
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
import net.paramnezia.core.net.IpPrefix
import net.paramnezia.core.profile.Protocol
import net.paramnezia.core.config.Uapi
import net.paramnezia.core.fleet.FleetTrust
import kotlinx.coroutines.sync.Mutex
import net.paramnezia.deploy.FleetPublisher
import net.paramnezia.deploy.MemoryHostKeyStore
import net.paramnezia.deploy.ServerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
    private val net: Http,
    private val ed: Ed25519,
    private val scope: CoroutineScope,
    /** Called when a newer manifest arrived, so the tunnel can switch servers at once. */
    private val onManifestChanged: () -> Unit,
) {
    private val servers = ServerManager(repo.hostKeys)
    private val publisher = FleetPublisher(repo.hostKeys)
    private val health = HealthBook()

    /** A server joined the fleet (deployed or bought): put the family page on it. Set by App. */
    var onServerReady: (SshCredentials) -> Unit = {}
    var onEndpointChanged: (String) -> Unit = {}

    val stored: StoredFleet? get() = repo.current.fleet

    fun member(): FleetMember? {
        val f = stored ?: return null
        val signed = FleetCrypto.decode(f.manifest) ?: return null
        // The stored manifest was checked when it was accepted: open it with whoever signed it.
        val manifest = FleetCrypto.open(signed, f.trust, ed, listOfNotNull(signed.signer))
        val ownerVersion = if (f.ownerVersion == 0L && signed.signer == null) signed.version else f.ownerVersion
        val key = WgKey.fromBase64(f.deviceKey)
        return FleetMember(f.trust, key, ed, manifest, ownerVersion, signed.signer, devicePsk(f, key))
    }

    /** This device's own preshared key: from its invite, or (the owner) derived like every other device's. */
    private fun devicePsk(f: StoredFleet, key: WgKey): WgKey? =
        f.devicePsk?.let(WgKey::fromBase64)
            ?: f.adminSeed?.takeIf { !f.coAdmin }?.let { DevicePsk.of(DevicePsk.seed(Base64.getDecoder().decode(it)), key.publicKey().toBase64()) }

    fun manifest(): FleetManifest? = member()?.manifest

    private fun admin(): FleetAdmin? {
        val f = stored ?: return null
        val seed = f.adminSeed ?: return null
        val raw = Base64.getDecoder().decode(seed)
        return if (f.coAdmin) FleetAdmin(f.trust, raw, ed, signer = f.signerKey, pskSeed = f.pskSeed?.let(Base64.getDecoder()::decode))
        else FleetAdmin(f.trust, raw, ed)
    }

    /** The owner or a co-admin: may manage people and servers. */
    val isAdmin: Boolean get() = stored?.adminSeed != null
    /** The owner: also appoints co-admins, adds and removes servers, shares settings. */
    val isOwner: Boolean get() = stored?.let { it.adminSeed != null && !it.coAdmin } == true

    /** Admin: the servers are due a one-off upgrade at the next self-check (see [heal]). */
    val needsUpgrade: Boolean get() = stored?.let { it.adminSeed != null && it.portsChecked < SERVER_UPGRADE } == true

    private fun owner(): FleetAdmin = admin()?.takeIf { it.isOwner } ?: error("Это может только владелец семьи")

    /** The next version; on the owner it also carries the co-admins and their freshly sealed grants. */
    private fun FleetAdmin.nextFor(current: FleetManifest, change: (FleetManifest) -> FleetManifest): FleetManifest =
        next(current) { m -> change(m).let { if (isOwner) withAdmins(it) else it } }

    /** Owner: the co-admin list and grants, built from what the owner holds (server access follows the owner's own). */
    private fun withAdmins(m: FleetManifest, access: Map<String, SshCredentials>? = null): FleetManifest {
        val f = stored ?: return m
        val records = f.coAdmins.filter { r -> m.devices.any { it.publicKey == r.device } }
        val serverAccess = access ?: f.serverAccess
        val pins = repo.current.hostKeys.filterKeys { k -> serverAccess.values.any { k == "${it.host}:${it.port}" } }
        val now = System.currentTimeMillis()
        val pskSeed = f.adminSeed?.let { Base64.getEncoder().encodeToString(DevicePsk.seed(Base64.getDecoder().decode(it))) }
        return m.copy(
            ownerDevice = WgKey.fromBase64(f.deviceKey).publicKey().toBase64(),
            admins = records.map { FleetCoAdmin(it.name, it.device, it.verifyKey, it.sshPublic, it.since) },
            grants = records.map { r ->
                val grantAccess = serverAccess.filterKeys { id -> m.servers.any { it.id == id } }
                    .mapValues { (_, c) -> c.copy(password = "", privateKey = r.sshPrivate, passphrase = "") }
                GrantBox.seal(AdminGrant(r.signingSeed, r.verifyKey, grantAccess, pins, f.gistId, f.gistToken, now, pskSeed), WgKey.fromBase64(r.device))
            },
        )
    }

    // --- receiving ----------------------------------------------------------------------------

    /** Offers a manifest from any channel. Returns true if it was newer and is now in use. */
    fun offer(text: String): Boolean = receive(text)

    @Synchronized
    private fun receive(
        text: String,
        accessChange: (Map<String, SshCredentials>) -> Map<String, SshCredentials> = { it },
        useAutomaticSelection: Boolean = false,
    ): Boolean {
        val m = member() ?: return false
        val wire = FleetCrypto.unwrap(text)
        val previousServers = m.manifest?.servers.orEmpty().associateBy { it.id }
        val previousRotation = m.manifest?.endpointRotation
        if (!m.offer(wire)) return false
        val policy = m.manifest?.policy
        val rotation = m.manifest?.endpointRotation?.takeIf { it != previousRotation }
        val switchedServer = m.manifest?.servers?.firstOrNull()?.takeIf { rotation != null }
        repo.update { s ->
            val follows = s.fleet?.let { it.adminSeed == null || it.coAdmin } == true
            val applied = if (policy != null && follows) s.settings.applying(policy) else s.settings
            val selected = s.activeProfile?.takeIf { id ->
                s.profiles.any { it.id == id } || m.manifest?.servers?.any { it.id == id } == true
            }
            s.copy(
                fleet = s.fleet?.let { it.copy(manifest = wire, ownerVersion = m.ownerVersion, serverAccess = accessChange(it.serverAccess)) },
                settings = applied,
                activeProfile = if (useAutomaticSelection || switchedServer != null) null else selected,
            )
        }
        applyGrant(m)
        // A co-admin's change, seen by the owner: signed again with the owner's key, so devices with an
        // app older than 2.16 (which trust only the owner's key) get it too.
        if (m.signer != null && isOwner) scope.launch(Dispatchers.IO) {
            runCatching { val a = owner(); manifest()?.let { cur -> accept(FleetCrypto.encode(a.seal(a.nextFor(cur) { it }))) } }
        }
        for (server in m.manifest?.servers.orEmpty()) {
            val previous = previousServers[server.id]
            if (server.id == switchedServer?.id || (previous != null &&
                    (previous.host != server.host || previous.port != server.port || previous.altHosts != server.altHosts))) {
                onEndpointChanged(server.id)
            }
        }
        onManifestChanged()
        return true
    }

    /**
     * The owner made this device a co-admin (its grant is in the manifest), or took the rights back:
     * the device's own admin key, SSH access and reserve channel follow. Nothing changes on the owner.
     */
    private fun applyGrant(m: FleetMember) {
        val f = stored ?: return
        if (f.adminSeed != null && !f.coAdmin) return
        val grant = m.grant()
        if (grant != null) {
            if (f.coAdmin && f.adminSeed == grant.signingSeed && f.serverAccess == grant.servers && f.gistToken == grant.gistToken &&
                f.pskSeed == grant.pskSeed) return
            repo.update { s ->
                s.copy(
                    fleet = s.fleet?.copy(adminSeed = grant.signingSeed, coAdmin = true, signerKey = grant.verifyKey,
                        serverAccess = grant.servers, gistId = grant.gistId, gistToken = grant.gistToken, pskSeed = grant.pskSeed),
                    hostKeys = s.hostKeys + grant.hostKeys,
                )
            }
            Log.i(TAG, "this device is a co-admin now (${grant.servers.size} servers)")
        } else if (f.coAdmin) {
            repo.update { s ->
                s.copy(fleet = s.fleet?.copy(adminSeed = null, coAdmin = false, signerKey = null, serverAccess = emptyMap(),
                    gistId = null, gistToken = null, gistError = null, pool = emptyList(), pskSeed = null))
            }
            Log.i(TAG, "administrator rights were taken back")
        }
    }

    /** Joins a fleet from an invite. Returns false for an ordinary key (not an invite). */
    @Synchronized
    fun join(invite: String, deviceName: String): Boolean {
        val m = FleetMember.fromInvite(invite.trim(), ed) ?: return false
        val inv = Invite.read(invite) ?: return false
        val policy = m.manifest?.policy
        repo.update { s ->
            s.copy(
                fleet = StoredFleet(m.trust, m.deviceKey.toBase64(), deviceName.ifBlank { inv.deviceName }, inv.manifest, devicePsk = inv.psk),
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
    @Synchronized
    fun syncIfDue(minIntervalMs: Long = SYNC_EVERY_MS, tunnelFirst: Boolean = true) {
        if (stored == null || System.currentTimeMillis() - lastAttempt < minIntervalMs) return
        lastAttempt = System.currentTimeMillis()
        scope.launch { runCatching { sync(tunnelFirst = tunnelFirst) } }
    }

    /**
     * Watch for rotations while VPN is requested, including while it is trying to recover.
     * Poll the fast in-tunnel channel separately so a dead server cannot delay the reserve read.
     */
    fun watchEndpointUpdates() = scope.launch {
        repo.state.map { state -> state.wantConnected to state.fleet?.trust }
            .distinctUntilChanged().collectLatest { (wanted, trust) ->
                if (!wanted || trust == null) return@collectLatest
                coroutineScope {
                    launch(Dispatchers.IO) {
                        while (true) {
                            coroutineScope {
                                launch { net.getThroughTunnel(publisher.tunnelUrl, timeoutMs = 3_000)?.let(::offer) }
                                launch { syncRecoveryChannel(customOnly = true) }
                            }
                            delay(5_000)
                        }
                    }
                    launch(Dispatchers.IO) {
                        while (true) {
                            syncRecoveryChannel(gitHubOnly = true)
                            delay(65_000)
                        }
                    }
                }
            }
    }

    suspend fun syncRecoveryChannel(): Boolean = syncRecoveryChannel(customOnly = false, gitHubOnly = false)

    private suspend fun syncRecoveryChannel(customOnly: Boolean = false, gitHubOnly: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        val mirrors = manifest()?.mirrors.orEmpty().filter {
            it.startsWith("https://") && (!customOnly || !isGitHubMirror(it)) && (!gitHubOnly || isGitHubMirror(it))
        }.sortedBy { it.startsWith("https://gist.githubusercontent.com/") }
        if (mirrors.isEmpty()) return@withContext false
        fun acceptFrom(text: String?, via: String): Boolean {
            if (text?.let(::offer) != true) return false
            repo.update { s -> s.copy(fleet = s.fleet?.copy(lastSync = System.currentTimeMillis(), lastSyncVia = via)) }
            return true
        }
        val results = coroutineScope {
            mirrors.map { url -> async {
                val body = net.fetch(url, direct = true, maxBytes = 2 shl 20, timeoutMs = 5_000).body
                val accepted = acceptFrom(body, channelName(url))
                accepted to (FleetCrypto.decode(body.orEmpty()) != null)
            } }.awaitAll()
        }
        results.any { it.first } ||
            (!customOnly && results.none { it.second } && acceptFrom(net.getViaTor(mirrors.first()), "tor"))
    }

    /** Keep the server picker current while the app is visible, including when disconnected. */
    suspend fun syncWhileVisible(checkAddresses: suspend () -> Unit = {}) {
        val interval = 5 * 60_000L
        while (true) {
            syncIfDue(interval, tunnelFirst = false)
            checkAddresses()
            val family = stored
            if (family != null && isOwner && (family.recoveryMirrors.isNotEmpty() || family.gistToken != null)) {
                val version = FleetCrypto.decode(family.manifest)?.version
                if (family.gistError != null || (version != null && family.recoveryMirrors.keys.any { family.deliveredMirrorVersions[it] != version })) {
                    withContext(Dispatchers.IO) { publishReserve(family.trust) }
                }
            }
            delay(interval)
        }
    }

    private fun isGitHubMirror(url: String): Boolean =
        url.startsWith("https://api.github.com/gists/") || url.startsWith("https://gist.githubusercontent.com/")

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
        val psk = admin.pskFor(device)
        val config = servers.addClient(ssh, key, deviceName, clientIp = device.address.substringBefore('/'),
            dns = serverTunnel.iface.dns.ifEmpty { listOf("1.1.1.1", "1.0.0.1") }, psk = psk)
        val server = withPorts(fleetServerOf(serverId, serverName, config), ssh)
        val manifest = admin.nextFor(empty) {
            it.copy(servers = listOf(server), devices = listOf(device), mirrors = listOf(publisher.tunnelUrl), policy = repo.current.settings.toPolicy())
        }
        val signed = FleetCrypto.encode(admin.seal(manifest))
        repo.update { s ->
            s.copy(
                fleet = StoredFleet(admin.trust, key.toBase64(), deviceName, signed,
                    adminSeed = Base64.getEncoder().encodeToString(admin.signingSeed), serverAccess = mapOf(serverId to ssh),
                    devicePsk = psk?.toBase64(), portsChecked = SERVER_UPGRADE),
                activeProfile = null,
            )
        }
        publish(admin.trust)
        onManifestChanged()
        onServerReady(ssh)
    }

    /**
     * Admin: a new trusted device. Returns its invite (a vpn:// key, also readable by AmneziaVPN).
     *
     * Instant when a prepared slot is there ([fillPool]): the key, the address and the peer on the
     * servers are ready, so only the manifest is signed and the invite built, both locally; the new
     * manifest spreads in the background as always. Without a slot the servers are asked, all at once.
     */
    suspend fun addDevice(name: String): String = withContext(Dispatchers.IO) {
        val admin = admin() ?: error("not admin")
        val current = manifest() ?: error("no manifest")
        takeSlot(current)?.let { (slot, key) ->
            val device = FleetDevice(name, key.publicKey().toBase64(), slot.address, ownPsk = slot.ownPsk && admin.pskSeed != null)
            val next = admin.nextFor(current) { it.copy(devices = it.devices + device) }
            accept(FleetCrypto.encode(admin.seal(next)))
            // A server the slot missed (added since, or down when the slot was made) and the next slot.
            scope.launch {
                val missing = current.servers.filter { it.id !in slot.servers }
                if (missing.isNotEmpty()) registerEverywhere(missing, key.publicKey(), name, slot.address, admin.pskFor(device))
                runCatching { fillPool() }
            }
            Log.i(TAG, "device $name from a prepared slot")
            return@withContext admin.invite(next, device, key)
        }
        val reserved = coroutineScope {
            current.servers.mapNotNull(::access).map { a -> async { runCatching { servers.usedAddresses(a) }.getOrDefault(emptySet()) } }.awaitAll().flatten()
        } + (stored?.pool?.map { it.address } ?: emptyList())
        val (device, key) = admin.newDevice(current, name, reserved = reserved)
        val failed = registerEverywhere(current.servers, key.publicKey(), name, device.address, admin.pskFor(device))
        // The master must take the device now; a standby that is down gets it at the next self-check.
        current.servers.firstOrNull()?.let { master ->
            if (access(master) != null && master.id in failed) throw failed.getValue(master.id)
        }
        val next = admin.nextFor(current) { it.copy(devices = it.devices + device) }
        val signed = FleetCrypto.encode(admin.seal(next))
        accept(signed)
        scope.launch { runCatching { fillPool() } }
        admin.invite(next, device, key)
    }

    private val poolLock = kotlinx.coroutines.sync.Mutex()

    /** Takes a prepared slot the master already knows, or null. */
    private suspend fun takeSlot(current: FleetManifest): Pair<PooledDevice, WgKey>? = poolLock.withLock {
        val master = current.servers.firstOrNull() ?: return null
        val taken = current.devices.mapTo(HashSet()) { it.address.substringBefore('/') }
        val slot = stored?.pool?.firstOrNull { it.address.substringBefore('/') !in taken && (master.id in it.servers || access(master) == null) }
            ?: return null
        repo.update { s -> s.copy(fleet = s.fleet?.let { it.copy(pool = it.pool - slot) }) }
        slot to WgKey.fromBase64(slot.privateKey)
    }

    /**
     * Admin: prepares up to [target] device slots in the background: keys made here, free addresses
     * found and the peers registered on every reachable server, each server over one SSH session and
     * all servers at once. Called when the family screen opens and after a slot is used, so that the
     * next "add a person" is instant. Unused slots are harmless: a peer whose key nobody holds.
     */
    suspend fun fillPool(target: Int = POOL_SIZE): Unit = withContext(Dispatchers.IO) {
        poolLock.withLock {
            val admin = admin() ?: return@withLock
            val current = manifest() ?: return@withLock
            val reachable = current.servers.mapNotNull { s -> access(s)?.let { s to it } }
            if (reachable.isEmpty()) return@withLock
            val taken = current.devices.mapTo(HashSet()) { it.address.substringBefore('/') }
            val masterId = current.servers.first().id
            val now = System.currentTimeMillis()
            // A slot the master still refuses after a day (its address taken there meanwhile) is dropped.
            var pool = (stored?.pool ?: emptyList()).filter {
                it.address.substringBefore('/') !in taken && (masterId in it.servers || now - it.createdAt < DAY_MS)
            }
            val complete = pool.count { slot -> reachable.all { it.first.id in slot.servers } }
            if (pool.size >= target && complete == pool.size) return@withLock
            if (pool.size < target) {
                val used = coroutineScope {
                    reachable.map { (_, a) -> async { runCatching { servers.usedAddresses(a) }.getOrDefault(emptySet()) } }.awaitAll().flatten()
                }
                val reserved = (used + pool.map { it.address }).toMutableList()
                repeat(target - pool.size) {
                    val (d, key) = admin.newDevice(current, "", reserved = reserved)
                    reserved += d.address
                    pool = pool + PooledDevice(key.toBase64(), d.address, createdAt = System.currentTimeMillis(), ownPsk = d.ownPsk)
                }
            }
            val registered = coroutineScope {
                reachable.map { (s, a) ->
                    async {
                        val todo = pool.filter { s.id !in it.servers }
                        val specs = todo.map { slot ->
                            val pub = WgKey.fromBase64(slot.privateKey).publicKey()
                            ServerManager.PeerSpec(pub, POOL_PEER_NAME, slot.address.substringBefore('/'), if (slot.ownPsk) admin.pskFor(pub) else null)
                        }
                        val failed = runCatching { servers.ensurePeers(a, specs) }.getOrElse { e -> specs.associate { it.publicKey.toBase64() to (e.message ?: "error") } }
                        s.id to todo.filter { WgKey.fromBase64(it.privateKey).publicKey().toBase64() !in failed }.map { it.privateKey }.toSet()
                    }
                }.awaitAll()
            }
            pool = pool.map { slot -> slot.copy(servers = slot.servers + registered.filter { (_, ok) -> slot.privateKey in ok }.map { it.first }) }
            val master = current.servers.first().id
            // Only slots the master knows can be handed out; the others are tried again next time.
            val ready = pool.count { master in it.servers }
            repo.update { s -> s.copy(fleet = s.fleet?.let { it.copy(pool = pool) }) }
            Log.i(TAG, "device slots: $ready ready of ${pool.size}")
        }
    }

    /**
     * Admin: revokes a device everywhere. A server that cannot be reached now is cleaned up by the
     * next self-check ([heal]): the key stays on the revoked list until every server dropped it.
     */
    suspend fun removeDevice(publicKey: String) = withContext(Dispatchers.IO) {
        val admin = admin() ?: error("not admin")
        var current = manifest() ?: return@withContext
        if (!admin.isOwner && (publicKey == current.ownerDevice || current.admins.any { it.device == publicKey }))
            error("Устройство владельца и администраторов может отключить только владелец")
        // An administrator's device: its rights and its SSH key go too.
        if (admin.isOwner && stored?.coAdmins?.any { it.device == publicKey } == true) {
            revokeAdmin(publicKey)
            current = manifest() ?: return@withContext
        }
        repo.update { s -> s.copy(fleet = s.fleet?.let { it.copy(revoked = it.revoked + publicKey) }) }
        var everywhere = true
        for (s in current.servers) {
            val a = access(s) ?: continue
            runCatching { servers.removeClient(a, WgKey.fromBase64(publicKey)) }.onFailure { everywhere = false }
        }
        if (everywhere) repo.update { s -> s.copy(fleet = s.fleet?.let { it.copy(revoked = it.revoked - publicKey) }) }
        accept(FleetCrypto.encode(admin.seal(admin.nextFor(current) { m -> m.copy(devices = m.devices.filter { it.publicKey != publicKey }) })))
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
        val admin = admin() ?: return@withContext 0
        // A device with its own key that this admin cannot derive (a co-admin with a grant from before
        // 2.16) is left to the owner: adding it with the shared key would lock it out of that server.
        val managedDevices = m.devices.filter { !it.ownPsk || admin.pskSeed != null }
        val upgrade = f.portsChecked < SERVER_UPGRADE
        val updated = HashMap<String, Pair<FleetServer, FleetServer>>()
        var upgradeFailed = false
        var ok = 0
        val stillRevoked = HashSet<String>()
        val stillRevokedKeys = HashSet<String>()
        for (s in m.servers) {
            val a = access(s) ?: continue
            val peers = managedDevices.map {
                val address = s.peerAddresses[it.publicKey] ?: it.address
                ServerManager.PeerSpec(WgKey.fromBase64(it.publicKey), it.name, address.substringBefore('/'), admin.pskFor(it))
            }
            val steps = listOf(
                "peers" to { servers.ensurePeers(a, peers).forEach { (k, why) -> Log.w(TAG, "${s.name}: peer $k: $why") } },
                "revoked" to {
                    runCatching { servers.removeClients(a, f.revoked.map(WgKey::fromBase64)) }.onFailure { stillRevoked += f.revoked }.getOrThrow()
                },
                "admins" to {
                    if (!f.coAdmin && (f.coAdmins.isNotEmpty() || f.revokedAdminKeys.isNotEmpty())) {
                        runCatching { servers.updateAdminKeys(a, f.coAdmins.map { it.sshPublic }, f.revokedAdminKeys) }
                            .onFailure { stillRevokedKeys += f.revokedAdminKeys }.getOrThrow()
                    }
                },
                "release" to { release?.let { (r, wire) -> publisher.mirrorRelease(a, r.sha256, r.publicApkUrls(), wire, forInvites = false) } },
                // Spare ports (once per server upgrade): a server without any gets them, and the
                // manifest learns what each server answers on.
                // The server's engine to the latest AmneziaWG build (2.17), before the tuning below.
                "engine" to {
                    if (upgrade) runCatching { if (servers.refresh(a)) Log.i(TAG, "${s.name}: AmneziaWG engine updated") }.onFailure { e ->
                        if ((e as? net.paramnezia.deploy.SshException)?.code != "no_container") upgradeFailed = true
                    }.getOrThrow()
                },
                // Throughput tuning (2.17): MSS clamping, buffers, BBR, kept across reboots.
                "tune" to {
                    if (upgrade) runCatching { servers.tune(a) }.onFailure { e ->
                        if ((e as? net.paramnezia.deploy.SshException)?.code != "no_container") upgradeFailed = true
                    }.getOrThrow()
                },
                "ipv6" to {
                    if (!s.tunnelIpv6) servers.enableIpv6(a)
                },
                "ports" to {
                    if (upgrade || s.altPorts.isEmpty() || !s.tunnelIpv6 || s.id in f.followServerAddress) {
                        runCatching {
                            val p = servers.ports(a, if (s.altPorts.isEmpty()) ServerManager.altPortsFor(s.port) else emptyList())
                            val observed = if (!f.coAdmin && s.id in f.followServerAddress && p.publicKey == s.publicKey) {
                                val currentIp = IpPrefix.parseOrNull(s.host)
                                if (currentIp?.isV6 == true) p.ipv6 else p.ipv4
                            } else null
                            val host = observed ?: s.host
                            val next = s.copy(host = host, altPorts = p.alt, altHosts = alternateHosts(host, p),
                                tunnelIpv6 = p.tunnelIpv6)
                            if (next != s && p.main == s.port) updated[s.id] = s to next
                        }.onFailure { e ->
                            // Not an AmneziaWG 2 container (an older AmneziaVPN protocol): nothing to upgrade there.
                            if ((e as? net.paramnezia.deploy.SshException)?.code != "no_container") upgradeFailed = true
                        }.getOrThrow()
                    }
                },
            )
            var serverOk = true
            for ((name, step) in steps) runCatching { step() }.onFailure { serverOk = false; Log.w(TAG, "heal ${s.name}/$name: ${it.message}") }
            runCatching {
                publishLock.withLock {
                    val latest = stored?.takeIf { it.trust == f.trust && s.id in it.serverAccess }
                    if (latest != null && !publisher.publishToServer(a, latest.manifest)) {
                        Log.w(TAG, "${s.name}: in-tunnel web server is not answering")
                    }
                }
            }.onFailure { serverOk = false; Log.w(TAG, "heal ${s.name}/manifest: ${it.message}") }
            if (serverOk) ok++
        }
        repo.update { st ->
            st.copy(fleet = st.fleet?.let {
                it.copy(revoked = it.revoked.filter { k -> k in stillRevoked }.toSet(),
                    revokedAdminKeys = it.revokedAdminKeys.filter { k -> k in stillRevokedKeys }.toSet(),
                    lastHeal = System.currentTimeMillis(),
                    portsChecked = if (upgrade && !upgradeFailed) SERVER_UPGRADE else it.portsChecked)
            })
        }
        if (updated.isNotEmpty()) {
            Log.i(TAG, "spare ports on ${updated.values.joinToString { (_, server) -> "${server.name}: ${server.altPorts}" }}")
            manifest()?.let { cur ->
                accept(FleetCrypto.encode(admin.seal(admin.nextFor(cur) { mm -> mm.copy(servers = mm.servers.map { server ->
                    val (before, after) = updated[server.id] ?: return@map server
                    if (server.host == before.host && server.port == before.port && stored?.serverAccess?.get(server.id) == f.serverAccess[server.id]) {
                        server.copy(host = if (server.id in stored?.followServerAddress.orEmpty()) after.host else server.host,
                            altPorts = after.altPorts, altHosts = after.altHosts, tunnelIpv6 = after.tunnelIpv6)
                    } else server
                }) })))
            }
        }
        ok
    }

    /**
     * Admin: deploys a fresh VPS as a standby (or master) and registers every device on it with its
     * fixed address. Devices switch to it automatically when they need to.
     */
    suspend fun addServer(
        name: String,
        ssh: SshCredentials,
        makeMaster: Boolean,
        vpnEndpoint: String? = null,
        expectedPublicKey: String? = null,
        onStep: (ServerManager.Step) -> Unit,
    ): String = withContext(Dispatchers.IO) {
        val admin = owner()
        val current = manifest() ?: error("no manifest")
        val f = stored ?: error("Семья не найдена")
        fun checkNewServer() {
            val latest = stored?.takeIf { it.trust == f.trust } ?: error("Семья изменилась")
            require(latest.serverAccess.values.none { it.host.equals(ssh.host, ignoreCase = true) && it.port == ssh.port }) {
                "Этот сервер уже добавлен в семью"
            }
        }
        checkNewServer()
        val endpoint = vpnEndpoint?.let(Uapi::splitEndpoint)
        val inspection = servers.inspect(ssh)
        val protocol = inspection.awgContainer ?: run {
            check(expectedPublicKey == null) { "На SSH-сервере не найден AmneziaWG из локального профиля" }
            servers.install(ssh, ServerManager.InstallOptions(), onStep)
            Protocol.AWG
        }
        require(protocol == Protocol.AWG) { "Для семейного сервера нужен AmneziaWG 2" }
        val existingPorts = expectedPublicKey?.let { expected ->
            servers.ports(ssh).also { ports ->
                check(ports.publicKey == expected) { "Ключ VPN на SSH-сервере отличается от локального профиля" }
            }
        }
        val myKey = WgKey.fromBase64(f.deviceKey)
        val me = current.deviceFor(myKey.publicKey()) ?: error("admin is not a fleet member")
        val assigned = linkedMapOf<String, String>()
        fun <T> assign(device: FleetDevice, register: (String) -> T): T =
            try {
                register(device.address.substringBefore('/'))
            } catch (e: net.paramnezia.deploy.SshException) {
                if (e.code != "address_taken") throw e
                val reserved = servers.usedAddresses(ssh) + current.devices.map { it.address.substringBefore('/') }
                val subnet = FleetAdmin.DEFAULT_SUBNET.network
                val free = (2..254).asSequence().map { i ->
                    IpPrefix.of(false, subnet + i.toBigInteger(), 32).address.hostAddress
                }.firstOrNull { it !in reserved } ?: error("На сервере нет свободного адреса в подсети семьи")
                register(free)
            }

        fun rememberAddress(device: FleetDevice, actual: String) {
            val ip = IpPrefix.parse(actual)
            require(FleetAdmin.DEFAULT_SUBNET.contains(ip) && !ip.isV6) {
                "На сервере нет свободного адреса в подсети семьи"
            }
            if (ip != IpPrefix.parse(device.address)) assigned[device.publicKey] = ip.toString()
        }

        val config = assign(me) { ip ->
            servers.addClient(ssh, myKey, me.name, clientIp = ip, psk = admin.pskFor(me))
        }
        rememberAddress(me, config.iface.addresses.first { !it.isV6 }.address.hostAddress)
        val raw = fleetServerOf(UUID.randomUUID().toString(), name, config)
        val advertised = endpoint?.let { (host, port) -> raw.copy(host = host, port = port) } ?: raw
        val server = when {
            existingPorts == null -> withPorts(advertised, ssh)
            existingPorts.main != advertised.port -> advertised
            else -> advertised.copy(altPorts = existingPorts.alt, altHosts = alternateHosts(advertised.host, existingPorts),
                tunnelIpv6 = existingPorts.tunnelIpv6)
        }
        for (d in current.devices) if (d.publicKey != me.publicKey) {
            val peer = assign(d) { ip ->
                servers.registerPeer(ssh, WgKey.fromBase64(d.publicKey), d.name, clientIp = ip, psk = admin.pskFor(d))
            }
            rememberAddress(d, peer.clientIp)
        }
        val target = server.copy(peerAddresses = assigned.toMap())
        val occupied = servers.usedAddresses(ssh)
        // The co-admins reach the new server with their own keys too.
        f.coAdmins.takeIf { it.isNotEmpty() }?.let { list -> runCatching { servers.updateAdminKeys(ssh, list.map { it.sshPublic }) } }
        poolLock.withLock {
            changeServers(accessChange = { it + (target.id to ssh) }) { latest ->
                checkNewServer()
                check(latest.devices == current.devices && latest.admins == current.admins) {
                    "Состав семьи изменился. Повторите добавление сервера"
                }
                latest.copy(servers = if (makeMaster) listOf(target) + latest.servers else latest.servers + target)
            }
            repo.update { state -> state.copy(fleet = state.fleet?.let { f ->
                f.copy(pool = f.pool.filterNot { it.address.substringBefore('/') in occupied })
            }) }
        }
        onStep(ServerManager.Step.DONE)
        onServerReady(ssh)
        target.id
    }

    /** Admin: makes [serverId] the master. Devices follow at their next manifest read. */
    suspend fun promote(serverId: String, useAutomaticSelection: Boolean = false) = withContext(Dispatchers.IO) {
        synchronized(this@FleetManager) {
            val admin = admin() ?: error("Эта команда доступна администратору семьи")
            val current = manifest() ?: error("Семья не найдена")
            val target = current.servers.firstOrNull { it.id == serverId } ?: error("Сервер не найден")
            if (current.servers.first().id == serverId) {
                if (useAutomaticSelection && repo.current.activeProfile != null) {
                    repo.update { it.copy(activeProfile = null) }
                    onManifestChanged()
                }
                return@synchronized
            }
            val next = admin.nextFor(current) { m -> m.copy(servers = listOf(target) + m.servers.filter { it.id != serverId }) }
            accept(FleetCrypto.encode(admin.seal(next)), useAutomaticSelection = useAutomaticSelection)
        }
    }

    /** The same signed, encrypted manifest that members accept from the mirrors. */
    fun addressUpdate(): String? = stored?.takeIf { isAdmin }?.manifest

    fun followsAddress(serverId: String): Boolean = serverId in stored?.followServerAddress.orEmpty()

    /** Owner: updates the name and endpoint advertised to every member. */
    suspend fun editServer(
        serverId: String,
        name: String,
        endpoint: String,
        followAddress: Boolean? = null,
        sshHost: String? = null,
        confirmRecovery: Boolean = false,
        expectedServer: FleetServer? = null,
    ) = withContext(Dispatchers.IO) {
        val label = name.trim()
        require(label.isNotEmpty()) { "Введите название сервера" }
        val (rawHost, port) = Uapi.splitEndpoint(endpoint)
        val literal = IpPrefix.parseOrNull(rawHost)
        require(rawHost.isNotEmpty() && rawHost.none { it.isWhitespace() || it in "/[]@?#%" } &&
            (':' !in rawHost || literal?.isV6 == true)) {
            "Введите адрес сервера и порт"
        }
        val host = literal?.toString()?.substringBefore('/') ?: rawHost
        owner()
        val current = manifest()?.servers?.firstOrNull { it.id == serverId } ?: error("Сервер не найден")
        val recovery = stored ?: error("Семья не найдена")
        val endpointChanged = current.host != host || current.port != port
        val hasRecovery = recovery.recoveryMirrors.isNotEmpty() || (recovery.gistId != null && recovery.gistToken != null)
        val rotate = confirmRecovery && hasRecovery && (endpointChanged || recovery.gistError != null)
        val ssh = accessTo(serverId)
        val newSsh = sshHost?.trim()?.removeSurrounding("[", "]")?.takeIf { it.isNotEmpty() && it != ssh?.host }?.let { address ->
            val old = requireNotNull(ssh) { "Нет SSH-доступа к серверу" }
            val pin = requireNotNull(repo.hostKeys.get(old.host, old.port)) { "Сначала подключитесь по текущему SSH-адресу" }
            val known = repo.hostKeys.get(address, old.port)
            require(known == null || known == pin) { "Ключ SSH нового адреса не совпадает" }
            val candidate = old.copy(host = address)
            val pinned = MemoryHostKeyStore().apply { put(address, old.port, pin) }
            val actual = ServerManager(pinned).ports(candidate)
            require(actual.publicKey != null && actual.publicKey == manifest()?.servers?.firstOrNull { it.id == serverId }?.publicKey) {
                "Ключ VPN нового адреса не совпадает"
            }
            repo.hostKeys.put(address, old.port, pin)
            candidate
        }
        if (followAddress == true) {
            require((newSsh ?: ssh) != null) { "Для автообновления нужен SSH-доступ" }
            require(literal != null) { "Для автообновления укажите IP-адрес VPN" }
        }
        changeServers(publishInBackground = !rotate, accessChange = {
            if (newSsh == null) it else {
                check(it[serverId] == ssh) { "SSH-адрес уже изменился" }
                it + (serverId to newSsh)
            }
        }) { current ->
            require(current.servers.any { it.id == serverId }) { "Сервер не найден" }
            if (expectedServer != null) {
                check(current.servers.firstOrNull { it.id == serverId } == expectedServer && followsAddress(serverId))
            }
            val updated = current.servers.map { server ->
                when {
                    server.id != serverId -> server
                    server.host == host && server.port == port -> server.copy(name = label)
                    else -> server.copy(name = label, host = host, port = port, altPorts = emptyList(), altHosts = emptyList())
                }
            }
            if (confirmRecovery && endpointChanged) {
                current.copy(servers = updated.sortedByDescending { it.id == serverId }, endpointRotation = UUID.randomUUID().toString())
            } else current.copy(servers = updated)
        }
        if (followAddress != null) repo.update { s ->
            s.copy(fleet = s.fleet?.let {
                it.copy(followServerAddress = if (followAddress) it.followServerAddress + serverId else it.followServerAddress - serverId)
            })
        }
        if (rotate) {
            scope.launch { runCatching { publish(recovery.trust, includeRecovery = false) } }
            val result = publishReserve(recovery.trust)
            result.getOrElse { error("Адрес сохранён. Резервный канал: ${it.message}. Повторите сохранение для отправки.") }
        }
    }

    /** Owner: drops a server and publishes the removal there for devices still connected to it. */
    suspend fun removeServer(serverId: String) = withContext(Dispatchers.IO) {
        synchronized(this@FleetManager) {
            val removed = accessTo(serverId)
            changeServers(listOfNotNull(removed), accessChange = { it - serverId }) { current ->
                require(current.servers.any { it.id == serverId }) { "Сервер не найден" }
                require(current.servers.size > 1) { "В семье должен остаться хотя бы один сервер" }
                current.copy(servers = current.servers.filter { it.id != serverId })
            }
            if (serverId in stored?.followServerAddress.orEmpty()) repo.update { state ->
                state.copy(fleet = state.fleet?.let { it.copy(followServerAddress = it.followServerAddress - serverId) })
            }
        }
    }

    @Synchronized
    private fun changeServers(
        extraTargets: List<SshCredentials> = emptyList(),
        publishInBackground: Boolean = true,
        accessChange: (Map<String, SshCredentials>) -> Map<String, SshCredentials> = { it },
        change: (FleetManifest) -> FleetManifest,
    ) {
        val admin = owner()
        val current = manifest() ?: error("Семья не найдена")
        val next = change(current)
        val previousAccess = stored?.serverAccess.orEmpty()
        val serverAccess = accessChange(previousAccess)
        if (next == current && serverAccess == previousAccess) return
        val signed = FleetCrypto.encode(admin.seal(admin.next(current) { withAdmins(next, serverAccess) }))
        if (publishInBackground) accept(signed, extraTargets, accessChange = { serverAccess })
        else check(receive(signed, accessChange = { serverAccess })) { "Настройки семьи изменились. Повторите сохранение" }
    }

    /**
     * Admin: turns on the reserve channel. With only a GitHub token (scope `gist`) the app creates a
     * secret gist, stores the manifest there and lists it in the manifest, so every device can read
     * it when no fleet server is reachable. The gist holds only the encrypted, signed manifest.
     */
    suspend fun setReserveChannel(token: String) = withContext(Dispatchers.IO) {
        val admin = owner()
        val f = stored ?: return@withContext
        val gist = recoveryPublishLock.withLock {
            val latest = stored?.takeIf { it.trust == f.trust } ?: error("Семья изменилась")
            publisher.publishToGist(token.trim(), latest.gistId, latest.manifest).also { channel ->
                repo.update { s -> s.copy(fleet = s.fleet?.copy(gistId = channel.id, gistToken = token.trim(), gistError = null)) }
            }
        }
        val current = manifest() ?: return@withContext
        val mirrors = current.mirrors.filter { !isGitHubMirror(it) } + gist.mirrors
        if (mirrors != current.mirrors) accept(FleetCrypto.encode(admin.seal(admin.nextFor(current) { it.copy(mirrors = mirrors) })))
    }

    suspend fun selectAmneziaDnsServer(id: String) = withContext(Dispatchers.IO) {
        recoveryPublishLock.withLock {
            owner()
            val f = requireNotNull(stored)
            val server = manifest()?.servers?.firstOrNull { it.id == id } ?: error("Сервер отсутствует")
            val url = psyncMirrorUrl(f.trust.fleetId)
            val token = f.recoveryMirrors[url] ?: error("Сначала подключите зеркало psync.ru")
            publisher.publishToHttpsMirror(url, token, f.manifest, server.host)
            repo.update { s -> s.copy(fleet = s.fleet?.let {
                if (it.trust == f.trust) it.copy(psyncDnsServerId = id) else it
            }) }
        }
    }

    /** Readable without credentials, writable by the owner with a bearer token. */
    suspend fun addRecoveryMirror(address: String, token: String) = withContext(Dispatchers.IO) {
        val url = address.trim()
        val uri = runCatching { java.net.URI(url) }.getOrNull()
        require(uri?.scheme == "https" && uri.host != null && uri.userInfo == null &&
            uri.fragment == null && uri.rawQuery == null && !uri.path.isNullOrBlank()) { "Введите HTTPS-адрес файла" }
        require(token.isNotBlank()) { "Введите токен записи" }
        owner()
        recoveryPublishLock.withLock {
            synchronized(this@FleetManager) {
                check(stored?.recoveryMirrors.orEmpty().size < 4 || url in stored?.recoveryMirrors.orEmpty()) {
                    "Можно настроить до четырёх HTTPS-зеркал"
                }
            }
            val initial = stored ?: error("Семья не найдена")
            publisher.publishToHttpsMirror(url, token.trim(), initial.manifest)
            synchronized(this@FleetManager) {
                check(stored?.trust == initial.trust) { "Семья изменилась" }
                val admin = owner()
                val current = manifest() ?: error("Семья не найдена")
                repo.update { s -> s.copy(fleet = s.fleet?.let {
                    it.copy(recoveryMirrors = it.recoveryMirrors + (url to token.trim()))
                }) }
                if (url !in current.mirrors) accept(FleetCrypto.encode(admin.seal(admin.nextFor(current) {
                    it.copy(mirrors = it.mirrors + url)
                })))
            }
        }
    }

    suspend fun removeRecoveryMirror(url: String) = withContext(Dispatchers.IO) {
        recoveryPublishLock.withLock {
            synchronized(this@FleetManager) {
                val admin = owner()
                val current = manifest() ?: error("Семья не найдена")
                repo.update { s -> s.copy(fleet = s.fleet?.let { it.copy(recoveryMirrors = it.recoveryMirrors - url, deliveredMirrorVersions = it.deliveredMirrorVersions - url) }) }
                accept(FleetCrypto.encode(admin.seal(admin.nextFor(current) { it.copy(mirrors = it.mirrors - url) })))
            }
        }
    }

    /** Admin: turns the reserve channel off (devices stop looking there; the gist itself stays). */
    suspend fun clearReserveChannel() = withContext(Dispatchers.IO) {
        val admin = owner()
        repo.update { s -> s.copy(fleet = s.fleet?.copy(gistId = null, gistToken = null, gistError = null)) }
        val current = manifest() ?: return@withContext
        val mirrors = current.mirrors.filter { !isGitHubMirror(it) }
        if (mirrors != current.mirrors) accept(FleetCrypto.encode(admin.seal(admin.nextFor(current) { it.copy(mirrors = mirrors) })))
    }

    /**
     * Admin: shares [settings] with every device (or stops sharing when [share] is false). Called
     * when the admin changes settings; a no-op when nothing changed, so no new version is made.
     */
    suspend fun sharePolicy(settings: Settings, share: Boolean = stored?.sharePolicy ?: true) = withContext(Dispatchers.IO) {
        val admin = admin()?.takeIf { it.isOwner } ?: return@withContext
        if (stored?.sharePolicy != share) repo.update { s -> s.copy(fleet = s.fleet?.copy(sharePolicy = share)) }
        val current = manifest() ?: return@withContext
        val policy = if (share) settings.toPolicy() else null
        if (policy != current.policy) accept(FleetCrypto.encode(admin.seal(admin.nextFor(current) { it.copy(policy = policy) })))
    }

    /**
     * Owner: makes a family device a co-admin. It gets its own signing key and its own SSH key (put on
     * every server now; a server that is down gets it at the next self-check), sealed to that device
     * alone inside the next manifest. The device picks it up at its next manifest read, within the
     * hour or at once when the app opens; nothing has to be sent to it by hand. Returns the servers
     * that could not take the key right now.
     */
    suspend fun grantAdmin(devicePublicKey: String): Int = withContext(Dispatchers.IO) {
        val admin = owner()
        val f = stored ?: error("no fleet")
        val current = manifest() ?: error("no manifest")
        val device = current.devices.firstOrNull { it.publicKey == devicePublicKey } ?: error("Устройство не найдено")
        if (device.publicKey == WgKey.fromBase64(f.deviceKey).publicKey().toBase64()) error("Это устройство и так владелец")
        if (f.coAdmins.any { it.device == devicePublicKey }) return@withContext 0
        val (seed, pub) = ed.generate()
        val ssh = SshKeyPair.generate("paramnezia-admin:" + device.name.filter { it.isLetterOrDigit() }.take(24).ifEmpty { "device" })
        val missed = coroutineScope {
            current.servers.mapNotNull(::access).map { a -> async { runCatching { servers.updateAdminKeys(a, listOf(ssh.publicLine)) }.isFailure } }.awaitAll().count { it }
        }
        val b64 = Base64.getEncoder()
        val record = CoAdminRecord(devicePublicKey, device.name, b64.encodeToString(seed), b64.encodeToString(pub), ssh.privatePem, ssh.publicLine, System.currentTimeMillis())
        repo.update { s -> s.copy(fleet = s.fleet?.let { it.copy(coAdmins = it.coAdmins + record, revokedAdminKeys = it.revokedAdminKeys - ssh.publicLine) }) }
        accept(FleetCrypto.encode(admin.seal(admin.nextFor(current) { it })))
        Log.i(TAG, "${device.name} is a co-admin (key on ${current.servers.size - missed} of ${current.servers.size} servers)")
        missed
    }

    /**
     * Owner: takes a co-admin's rights back. Its key is dropped from the manifest at once (devices stop
     * trusting it with the next version they read) and its SSH key from every server; a server that
     * is down is cleaned up at the next self-check.
     */
    suspend fun revokeAdmin(devicePublicKey: String) = withContext(Dispatchers.IO) {
        val admin = owner()
        val record = stored?.coAdmins?.firstOrNull { it.device == devicePublicKey } ?: return@withContext
        repo.update { s -> s.copy(fleet = s.fleet?.let { it.copy(coAdmins = it.coAdmins - record, revokedAdminKeys = it.revokedAdminKeys + record.sshPublic) }) }
        manifest()?.let { current -> accept(FleetCrypto.encode(admin.seal(admin.nextFor(current) { it }))) }
        val current = manifest() ?: return@withContext
        val everywhere = coroutineScope {
            current.servers.mapNotNull(::access).map { a -> async { runCatching { servers.updateAdminKeys(a, emptyList(), listOf(record.sshPublic)) }.isSuccess } }.awaitAll().all { it }
        }
        if (everywhere) repo.update { s -> s.copy(fleet = s.fleet?.let { it.copy(revokedAdminKeys = it.revokedAdminKeys - record.sshPublic) }) }
    }

    private fun accept(
        signed: String,
        extraTargets: List<SshCredentials> = emptyList(),
        accessChange: (Map<String, SshCredentials>) -> Map<String, SshCredentials> = { it },
        useAutomaticSelection: Boolean = false,
    ) {
        if (!receive(signed, accessChange, useAutomaticSelection)) return
        val trust = stored?.trust ?: return
        // Spreading the new manifest (servers, gist) can take a while on a slow network;
        // the admin's screen does not wait for it.
        scope.launch { runCatching { publish(trust, extraTargets) }.onFailure { Log.w(TAG, "publish failed", it) } }
    }

    private val publishLock = Mutex()
    private val recoveryPublishLock = Mutex()

    private suspend fun publishReserve(trust: FleetTrust): Result<Unit> = recoveryPublishLock.withLock {
        val saved = stored?.takeIf { it.trust == trust } ?: return@withLock Result.failure(IllegalStateException("Семья изменилась"))
        val dnsUrl = psyncMirrorUrl(trust.fleetId)
        val dnsAddress = saved.psyncDnsServerId?.let { id ->
            val envelope = requireNotNull(FleetCrypto.decode(saved.manifest))
            FleetCrypto.open(envelope, trust, ed, listOfNotNull(envelope.signer))
                ?.servers?.firstOrNull { it.id == id }?.host
        }
        val result = coroutineScope {
            val writes = saved.recoveryMirrors.map { (url, token) -> async {
                runCatching<Unit> {
                    publisher.publishToHttpsMirror(url, token, saved.manifest, dnsAddress.takeIf { url == dnsUrl })
                    val version = requireNotNull(FleetCrypto.decode(saved.manifest)).version
                    repo.update { state -> state.copy(fleet = state.fleet?.let {
                        if (it.trust == trust && it.recoveryMirrors[url] == token)
                            it.copy(deliveredMirrorVersions = it.deliveredMirrorVersions + (url to version))
                        else it
                    }) }
                }
            } }.toMutableList()
            if (saved.gistToken != null && saved.gistId != null) writes += async {
                runCatching { publisher.publishToGist(saved.gistToken, saved.gistId, saved.manifest, createIfMissing = false); Unit }
            }
            val results = writes.awaitAll()
            if (results.any { it.isSuccess }) Result.success(Unit)
            else Result.failure(results.firstNotNullOfOrNull { it.exceptionOrNull() } ?: IllegalStateException("Резервный канал выключен"))
        }
        val failure = result.exceptionOrNull()?.let { it.message ?: "Ошибка доставки" }
        repo.update { state -> state.copy(fleet = state.fleet?.let {
            if (it.trust == trust && it.gistId == saved.gistId && it.gistToken == saved.gistToken) it.copy(gistError = failure) else it
        }) }
        result
    }

    /** Publish independently so a dead SSH server cannot delay the recovery channel. */
    private suspend fun publish(trust: FleetTrust, extraTargets: List<SshCredentials> = emptyList(), includeRecovery: Boolean = true) =
        withContext(Dispatchers.IO) {
            coroutineScope {
                if (includeRecovery && stored?.takeIf { it.trust == trust }?.let {
                    it.gistToken != null || it.recoveryMirrors.isNotEmpty()
                } == true) launch { publishReserve(trust) }
                publishLock.withLock {
                    val f = stored?.takeIf { it.trust == trust } ?: return@withLock
                    val m = manifest() ?: return@withLock
                    val targets = (m.servers.mapNotNull { f.serverAccess[it.id] } + extraTargets).distinct()
                    targets.map { a -> async {
                        runCatching {
                            if (!publisher.publishToServer(a, f.manifest)) Log.w(TAG, "${a.host}: in-tunnel web server is not answering")
                        }.onFailure { Log.w(TAG, "${a.host}: manifest publication failed", it) }
                    } }.awaitAll()
                }
            }
        }

    /**
     * The ports [server] answers on, asked from the server itself (spare ones are added if it has
     * none yet). Best effort: without an answer the server is used with its main port only.
     */
    private fun withPorts(server: FleetServer, ssh: SshCredentials): FleetServer = runCatching {
        val p = servers.ports(ssh, ServerManager.altPortsFor(server.port))
        if (p.main == server.port) server.copy(altPorts = p.alt, altHosts = alternateHosts(server.host, p), tunnelIpv6 = p.tunnelIpv6) else server
    }.onFailure { Log.w(TAG, "ports of ${server.name}: ${it.message}") }.getOrDefault(server)

    private fun alternateHosts(host: String, ports: ServerManager.Ports): List<String> =
        if (':' in host) listOfNotNull(ports.ipv4) else listOfNotNull(ports.ipv6)

    /** Registers a device on every server, all at once; returns the failures by server id. */
    private suspend fun registerEverywhere(list: List<FleetServer>, publicKey: WgKey, name: String, address: String, psk: WgKey? = null): Map<String, Exception> =
        coroutineScope {
            list.mapNotNull { s -> access(s)?.let { a -> s to a } }.map { (s, a) ->
                async(Dispatchers.IO) {
                    try {
                        servers.registerPeer(a, publicKey, name, clientIp = address.substringBefore('/'), psk = psk)
                        null
                    } catch (e: Exception) {
                        Log.w(TAG, "could not register $name on ${s.name}: ${e.message}")
                        s.id to e
                    }
                }
            }.awaitAll().filterNotNull().toMap()
        }

    private fun access(s: FleetServer): SshCredentials? = stored?.serverAccess?.get(s.id)

    /** Admin: the first server (master first) this device can reach over SSH. */
    fun managedServer(): Pair<FleetServer, SshCredentials>? =
        manifest()?.servers?.firstNotNullOfOrNull { s -> access(s)?.let { s to it } }

    /** Admin: SSH access to a fleet server by id. */
    fun accessTo(serverId: String): SshCredentials? = stored?.serverAccess?.get(serverId)

    private companion object {
        const val TAG = "PnFleet"
        /** Prepared device slots: enough for a couple of people in a row without waiting. */
        const val POOL_SIZE = 2
        /** How a prepared slot is called on the server until someone gets it. */
        const val POOL_PEER_NAME = "Paramnezia"
        const val DAY_MS = 24 * 3600_000L
        /** Bumped when every server needs a one-off upgrade at the next self-check (1: spare ports, 2: tuning, 3: engine). */
        const val SERVER_UPGRADE = 3
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

