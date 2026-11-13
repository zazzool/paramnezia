package net.paramnezia.app

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
import net.paramnezia.app.net.Visibility
import net.paramnezia.app.net.VisibilityCheck
import net.paramnezia.app.ota.Updater
import net.paramnezia.app.platform.Log
import net.paramnezia.app.platform.StateStore
import net.paramnezia.core.config.Uapi
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
    val specs = SpecBuilder(asset)
    val tunnel = TunnelEngine(client, specs, this, scope)
    val fleet: FleetManager = FleetManager(repo, net, ed, scope) { reconnectIfUp() }
    val updater = Updater(repo, net, ed, scope, openUrl)
    val links: Links by lazy { Links(repo, fleet, { updater.android }, scope) }
    val cloud: Cloud by lazy { Cloud(repo, scope, cacheDir) }
    /** Sharing the app and settings with devices in the same network, and finding such shares. */
    val lan: LanHub by lazy { LanHub(DesktopLan(cacheDir), repo, fleet, { links }, { updater.android }, deviceModel, scope) }
    val servers: Servers
    private val visibilityCheck by lazy { VisibilityCheck(net, directLabel = "сеть компьютера") }
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
        // DNS rules follow the settings (the service keeps them).
        scope.launch(Dispatchers.IO) {
            repo.state.map { it.settings.blockTrackers to it.settings.bypassRussia }.distinctUntilChanged().collect {
                val (b, d, a) = specs.dnsRules(repo.current.settings)
                tunnel.applyDnsRules(b, d, a)
            }
        }
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

    suspend fun refreshVisibility() {
        val key = visibilityKey()
        val up = TunnelRuntime.state.value as? TunnelState.Up
        val host = up?.let { serverHost(it.serverId) }
        val v = visibilityCheck.run(key, host, vpnUp = up != null, ruDirect = repo.current.settings.bypassRussia)
        // The tunnel may have changed while the check ran; a result for an old state is dropped.
        if (key != visibilityKey()) return
        _visibility.value = v
        // Foreign traffic went around the tunnel: let the watchdog probe and repair right away.
        if (v.foreignLeak) TunnelRuntime.recheck()
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
        val state = repo.current
        val member = fleet.member()
        if (member != null) {
            val tunnels = member.tunnels()
            if (tunnels.isNotEmpty()) {
                val chosen = failover.choose(tunnels.map { it.first }, System.currentTimeMillis()) ?: return null
                val config = tunnels.first { it.first.id == chosen.id }.second
                return specs.build(chosen.id, chosen.name, config, state.settings)
            }
        }
        val profile = state.profiles.firstOrNull { it.id == state.activeProfile } ?: state.profiles.firstOrNull() ?: return null
        return profileSpec(profile)
    }

    private fun profileSpec(p: StoredProfile): TunnelSpec? = runCatching {
        specs.build(p.id, p.name, WgQuick.parse(p.conf), repo.current.settings)
    }.getOrNull()

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
        val fresh = healthyServer != spec.serverId
        healthyServer = spec.serverId
        fleet.syncIfDue(if (fresh) 0 else 3600_000L)
    }

    /** Admin only, while the window is open: daily check and repair of the fleet's servers. */
    suspend fun healServers(force: Boolean = false) {
        val f = repo.current.fleet ?: return
        if (f.adminSeed == null) return
        if (f.sharePolicy) runCatching { fleet.sharePolicy(repo.current.settings) }
        if (!force && System.currentTimeMillis() - f.lastHeal < HEAL_EVERY_MS) return
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
