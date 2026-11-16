package net.paramnezia.app

import net.paramnezia.app.data.ServerReboot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import net.paramnezia.app.data.Cloud
import net.paramnezia.app.data.Repo
import net.paramnezia.app.data.Servers
import net.paramnezia.app.data.StoredProfile
import net.paramnezia.app.fleet.FleetManager
import net.paramnezia.app.fleet.Links
import net.paramnezia.app.lan.LanHub
import net.paramnezia.app.lists.ListUpdates
import net.paramnezia.app.net.Visibility
import net.paramnezia.app.net.VisibilityCheck
import net.paramnezia.app.ota.Updater
import net.paramnezia.app.platform.Log
import net.paramnezia.app.platform.StateStore
import net.paramnezia.core.config.Uapi
import net.paramnezia.core.config.Camouflage
import net.paramnezia.core.config.TunnelConfig
import net.paramnezia.app.tunnel.CamouflagePlanner
import net.paramnezia.desktop.DesktopNetwork
import net.paramnezia.core.config.WgQuick
import net.paramnezia.core.fleet.Ed25519
import net.paramnezia.core.fleet.FailoverPolicy
import net.paramnezia.deploy.SshConnection
import net.paramnezia.desktop.DesktopHttp
import net.paramnezia.desktop.DesktopLan
import net.paramnezia.desktop.ServiceClient
import net.paramnezia.desktop.SpecBuilder
import net.paramnezia.desktop.TunnelEngine
import net.paramnezia.desktop.TunnelHost
import net.paramnezia.tunnel.TunnelRuntime
import net.paramnezia.tunnel.TunnelSpec
import net.paramnezia.tunnel.TunnelState
import java.io.File

/**
 * The Windows app process, with the same members the shared screens use on Android
 * (`net.paramnezia.app.App` there). Also the [TunnelHost]: it decides which server to use and fails
 * over when one is blocked, exactly like the Android app.
 */
class App(
    store: StateStore,
    val client: ServiceClient,
    private val cacheDir: File,
    private val deviceModel: String,
    /** Opens links in the browser (the update button, provider pages). */
    openUrl: (String) -> Unit,
    /** Bypass lists (the Android app's assets/bypass files, bundled as resources). */
    asset: (String) -> String = ::bundledAsset,
) : TunnelHost {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val repo = Repo(store)
    val net = DesktopHttp(client)
    private val ed: Ed25519 = Ed25519.Jca
    /** Bypass lists in use: bundled, or newer signed ones fetched since (once a day, see ListUpdates). */
    val lists = ListUpdates(File(cacheDir, "bypass"), asset, net, BuildConfig.RELEASE_KEY, ed, ListUpdates.urlFor(BuildConfig.ANDROID_UPDATE_URL))
    val specs = SpecBuilder(lists::text, lists::version)
    val tunnel = TunnelEngine(client, specs, this, scope)
    val fleet: FleetManager = FleetManager(repo, net, ed, scope) { reconnectIfUp() }
    val updater = Updater(repo, net, ed, scope, openUrl)
    val links: Links by lazy { Links(repo, fleet, { updater.android }, scope) }
    val cloud: Cloud by lazy { Cloud(repo, scope, cacheDir) }
    /** Reboots a server over SSH, or through its hosting provider when SSH is dead. */
    val reboots: ServerReboot by lazy { ServerReboot(repo) }
    /** Sharing the app and settings with devices in the same network, and finding such shares. */
    val lan: LanHub by lazy { LanHub(DesktopLan(cacheDir), repo, fleet, { links }, { updater.android }, deviceModel, scope) }
    val servers: Servers
    private val visibilityCheck by lazy { VisibilityCheck(net, directLabel = "сеть компьютера", loadGeo = { repo.current.geo }, saveGeo = { m -> repo.update { it.copy(geo = m) } }) }
    private val _visibility = MutableStateFlow<Visibility?>(null)
    val visibility: StateFlow<Visibility?> = _visibility
    private val failover = FailoverPolicy()

    init {
        // Server management goes over the computer's own network while our VPN is up, then through it.
        SshConnection.routes = {
            val direct = if (TunnelRuntime.state.value is TunnelState.Down) null else net.directSockets()
            listOfNotNull(direct) + listOf(null)
        }
        val pageOn: (net.paramnezia.core.profile.SshCredentials) -> Unit = { ssh -> scope.launch { runCatching { links.preparePage(ssh) } } }
        fleet.onServerReady = pageOn
        servers = Servers(repo, fleet, deviceModel, pageOn)
    }

    /** Starts the background parts; separate from the constructor so screenshots can skip it. */
    fun start() {
        // DNS rules follow the settings and the lists (the service keeps them).
        scope.launch(Dispatchers.IO) {
            kotlinx.coroutines.flow.combine(repo.state.map { it.settings.blockTrackers to it.settings.bypassRussia }, lists.taken) { a, n -> a to n }
                .distinctUntilChanged().collect {
                    val (b, d, a) = specs.dnsRules(repo.current.settings)
                    tunnel.applyDnsRules(b, d, a)
                }
        }
        // Newer lists arrived: routes change, so a running tunnel is rebuilt (quietly).
        scope.launch { lists.taken.drop(1).collect { reconnectIfUp() } }
        // Admin: settings changes go to every fleet device (see the Android App).
        scope.launch(Dispatchers.IO) {
            repo.state.map { s -> s.settings to (s.fleet?.let { f -> f.adminSeed != null && f.sharePolicy } == true) }.distinctUntilChanged().drop(1)
                .collectLatest { (settings, share) ->
                    if (!share) return@collectLatest
                    delay(3_000)
                    runCatching { fleet.sharePolicy(settings) }.onFailure { Log.w(TAG, "share policy", it) }
                }
        }
        scope.launch(Dispatchers.IO) {
            updater.checkIfDue()
            lists.checkIfDue()
            // The tunnel was on when the app last closed (or Windows restarted): bring it back.
            if (repo.current.wantConnected) tunnel.start()
        }
        // Fleet devices read the manifest every 12 hours even while the tunnel is off (Android: WorkManager).
        scope.launch(Dispatchers.IO) {
            while (true) {
                delay(12 * 3600_000L)
                runCatching { fleet.sync() }
            }
        }
    }

    fun visibilityKey(): String = when (val s = TunnelRuntime.state.value) {
        is TunnelState.Up -> "up:${s.serverId}:${TunnelRuntime.generation.value}"
        TunnelState.Down, is TunnelState.Failed -> "down"
        is TunnelState.Connecting -> "connecting"
    }

    /** Screenshots only: shows a prepared result instead of asking the network. */
    fun showVisibility(v: Visibility) { _visibility.value = v }

    suspend fun refreshVisibility(again: Boolean = true) {
        val key = visibilityKey()
        val up = TunnelRuntime.state.value as? TunnelState.Up
        val host = up?.let { serverHost(it.serverId) }
        val v = visibilityCheck.run(key, host, vpnUp = up != null, ruDirect = repo.current.settings.bypassRussia)
        // The tunnel may have changed while the check ran; a result for an old state is dropped.
        if (key != visibilityKey()) return
        _visibility.value = v
        // Foreign traffic went around the tunnel: let the watchdog probe and repair right away, then
        // look again by itself, so the warning does not stay up after the tunnel is fine.
        if (v.foreignLeak) {
            TunnelRuntime.recheck()
            if (again) scope.launch { kotlinx.coroutines.delay(8_000); if (key == visibilityKey()) runCatching { refreshVisibility(again = false) } }
        }
    }

    fun serverHost(id: String): String? =
        runCatching { fleet.manifest()?.servers?.firstOrNull { it.id == id }?.host }.getOrNull()
            ?: repo.current.profiles.firstOrNull { it.id == id }?.let { p ->
                runCatching { WgQuick.parse(p.conf).peers.firstOrNull()?.endpoint?.let { Uapi.splitEndpoint(it).first } }.getOrNull()
            }

    fun serverCount(): Int =
        (runCatching { fleet.member()?.tunnels()?.size }.getOrNull() ?: 0) + repo.current.profiles.size

    fun reconnectIfUp() {
        if (TunnelRuntime.state.value !is TunnelState.Down) tunnel.reload()
    }

    fun connect(): Boolean {
        repo.update { it.copy(wantConnected = true) }
        tunnel.start()
        return true
    }

    fun disconnect() {
        repo.update { it.copy(wantConnected = false) }
        tunnel.stop()
    }

    // --- TunnelHost ---------------------------------------------------------------------------

    override suspend fun currentSpec(): TunnelSpec? {
        noteNetwork()
        val state = repo.current
        val member = fleet.member()
        if (member != null) {
            val tunnels = member.tunnels()
            if (tunnels.isNotEmpty()) {
                val chosen = failover.choose(tunnels.map { it.first }, System.currentTimeMillis()) ?: return null
                val config = tunnels.first { it.first.id == chosen.id }.second
                return dressed(chosen.id, chosen.name, camo.dress(chosen.id, config, reachOf(chosen.id)))
            }
        }
        val profile = state.profiles.firstOrNull { it.id == state.activeProfile } ?: state.profiles.firstOrNull() ?: return null
        return profileSpec(profile)
    }

    /**
     * What the service falls back on by itself when the current server stops answering (also while
     * the app is closed), in order: a round of other ways into the same server (signature, ports,
     * IPv6; see Camouflage), the other fleet servers (healthy ones first, in manifest order), then
     * the remaining ways into the first server.
     */
    override suspend fun alternateSpecs(primary: TunnelSpec): List<TunnelSpec> {
        val now = System.currentTimeMillis()
        val raw = rawConfig(primary.serverId)
        // A handful is enough between two looks of the app (each one is a full tunnel spec for the service).
        val variants = raw?.let { camo.rest(primary.serverId, it, reachOf(primary.serverId), primary.variant, max = 6) }.orEmpty()
            .map { dressed(primary.serverId, primary.serverName, it) }
        val member = fleet.member()
        val others = member?.tunnels().orEmpty().filter { it.first.id != primary.serverId }
            .sortedBy { if (failover.isBlocked(it.first.id, now)) 1 else 0 }
            .mapNotNull { (server, config) -> runCatching { dressed(server.id, server.name, camo.dress(server.id, config, reachOf(server.id))) }.getOrNull() }
        val first = Camouflage.ROUND - 1
        return variants.take(first) + others + variants.drop(first)
    }

    private fun profileSpec(p: StoredProfile): TunnelSpec? = runCatching {
        val config = WgQuick.parse(p.conf)
        dressed(p.id, p.name, camo.dress(p.id, config, reachOf(p.id)))
    }.getOrNull()

    /** How each server is dialled on this network (signatures, ports, addresses; see Camouflage). */
    private val camo by lazy { CamouflagePlanner(repo) }

    private fun noteNetwork() = DesktopNetwork.identity().let { (key, v6) -> camo.onNetwork(key, v6) }

    private fun reachOf(serverId: String): CamouflagePlanner.Reach =
        runCatching { fleet.manifest()?.servers?.firstOrNull { it.id == serverId } }.getOrNull()?.let { CamouflagePlanner.Reach(it.altPorts, it.altHosts) }
            ?: repo.current.profiles.firstOrNull { it.id == serverId }?.let { CamouflagePlanner.Reach(it.altPorts, it.altHosts) }
            ?: CamouflagePlanner.Reach()

    /** The service raises the MTU in place after a probe (Windows can), so specs start from the config's own. */
    private fun dressed(id: String, name: String, d: CamouflagePlanner.Dressed): TunnelSpec =
        specs.build(id, name, d.config, repo.current.settings).copy(variant = d.variant, paddingSteady = d.padding?.steady,
            paddingAfterMs = d.padding?.afterMs ?: 0, mtuProbe = net.paramnezia.core.config.PathMtu.probeSpec(d.config))

    private fun rawConfig(serverId: String): TunnelConfig? =
        runCatching { fleet.member()?.tunnels()?.firstOrNull { it.first.id == serverId }?.second }.getOrNull()
            ?: repo.current.profiles.firstOrNull { it.id == serverId }?.let { runCatching { WgQuick.parse(it.conf) }.getOrNull() }

    override suspend fun onServerUnreachable(spec: TunnelSpec): TunnelSpec? {
        failover.markBlocked(spec.serverId, System.currentTimeMillis())
        withTimeoutOrNull(25_000) { runCatching { fleet.sync() } }
        val next = currentSpec() ?: return null
        if (next.serverId == spec.serverId) return null
        Log.i(TAG, "${spec.serverName} unreachable, switching to ${next.serverName}")
        fleet.reportHealth(spec.serverId, reachable = false)
        return next
    }

    @Volatile private var healthyServer: String? = null

    override fun onServerHealthy(spec: TunnelSpec) {
        failover.markHealthy(spec.serverId)
        camo.worked(spec.serverId, spec.variant)
        val fresh = healthyServer != spec.serverId
        healthyServer = spec.serverId
        fleet.syncIfDue(if (fresh) 0 else 3600_000L)
    }

    /** Admin only, while the window is open: daily check and repair of the fleet's servers. */
    suspend fun healServers(force: Boolean = false) {
        // Own standalone servers: a one-off upgrade (spare ports) after an app update.
        runCatching { servers.upgradePorts() }.onFailure { Log.w(TAG, "upgrade", it) }
        val f = repo.current.fleet ?: return
        if (f.adminSeed == null) return
        if (f.sharePolicy) runCatching { fleet.sharePolicy(repo.current.settings) }
        // A pending server upgrade is retried hourly (a server may be down), the rest daily.
        if (!force && System.currentTimeMillis() - f.lastHeal < (if (fleet.needsUpgrade) 3600_000L else HEAL_EVERY_MS)) return
        runCatching { fleet.heal(updater.android.shareable()) }.onFailure { Log.w(TAG, "heal", it) }
    }

    companion object {
        private const val TAG = "Paramnezia"
        private const val HEAL_EVERY_MS = 24 * 3600_000L

        fun bundledAsset(name: String): String =
            App::class.java.getResourceAsStream("/bypass/$name")?.bufferedReader()?.use { it.readText() }
                ?: error("missing bypass list $name")
    }
}
