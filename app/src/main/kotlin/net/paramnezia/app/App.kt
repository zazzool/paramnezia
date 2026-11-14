package net.paramnezia.app

import net.paramnezia.app.platform.Log as PLog
import net.paramnezia.app.data.Vault
import net.paramnezia.app.data.Cloud
import android.app.Application
import android.net.VpnService
import android.os.PowerManager
import android.util.Log
import net.paramnezia.app.data.Repo
import net.paramnezia.app.data.StoredProfile
import net.paramnezia.app.fleet.FleetManager
import net.paramnezia.app.ota.Updater
import net.paramnezia.app.tunnel.Net
import net.paramnezia.app.tunnel.SpecBuilder
import net.paramnezia.app.ui.WidgetProvider
import net.paramnezia.app.work.SyncJob
import net.paramnezia.core.config.Uapi
import net.paramnezia.core.config.WgQuick
import net.paramnezia.core.fleet.FailoverPolicy
import net.paramnezia.tunnel.NativeEd25519
import net.paramnezia.tunnel.ParamneziaVpnService
import net.paramnezia.tunnel.TunnelHost
import net.paramnezia.tunnel.TunnelRuntime
import net.paramnezia.tunnel.TunnelSpec
import net.paramnezia.tunnel.TunnelState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import net.paramnezia.app.data.Servers
import net.paramnezia.app.fleet.Links
import net.paramnezia.app.lan.AndroidLan
import net.paramnezia.app.lan.LanHub
import net.paramnezia.app.lists.ListUpdates
import net.paramnezia.app.net.Visibility
import net.paramnezia.app.net.VisibilityCheck
import net.paramnezia.app.ui.QuickTile
import net.paramnezia.deploy.SshConnection

/**
 * The app process. Also the [TunnelHost] the VPN service asks for configs: it decides which server
 * to use and fails over when one is blocked.
 */
class App : Application(), TunnelHost {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    lateinit var repo: Repo; private set
    lateinit var net: Net; private set
    lateinit var specs: SpecBuilder; private set
    /** Bypass lists in use: bundled, or newer signed ones fetched since (once a day, see ListUpdates). */
    val lists: ListUpdates by lazy {
        ListUpdates(java.io.File(filesDir, "bypass"), { name -> assets.open("bypass/$name").bufferedReader().use { it.readText() } },
            net, BuildConfig.RELEASE_KEY, NativeEd25519, ListUpdates.urlFor(BuildConfig.UPDATE_URL))
    }
    lateinit var fleet: FleetManager; private set
    /** Created on first use, off the launch path (it reads package info and the stored release). */
    val updater: Updater by lazy { Updater(this, repo, net, NativeEd25519, scope) { repo.current.fleet != null } }
    lateinit var servers: Servers; private set
    val links: Links by lazy { Links(repo, fleet, { updater }, scope) }
    /** Buying servers from hosting providers (admin). */
    val cloud: Cloud by lazy { Cloud(repo, scope, cacheDir) }
    /** Reboots a server over SSH, or through its hosting provider when SSH is dead. */
    val reboots: net.paramnezia.app.data.ServerReboot by lazy { net.paramnezia.app.data.ServerReboot(repo) }
    /** Sharing the app and settings with devices in the same Wi-Fi, and finding such shares. */
    val lan: LanHub by lazy { LanHub(AndroidLan(this), repo, fleet, { links }, { updater }, android.os.Build.MODEL, scope) }
    private val visibilityCheck by lazy { VisibilityCheck(net, loadGeo = { repo.current.geo }, saveGeo = { m -> repo.update { it.copy(geo = m) } }) }
    private val _visibility = kotlinx.coroutines.flow.MutableStateFlow<Visibility?>(null)
    /** What foreign and Russian sites last saw; refreshed by the home screen. */
    val visibility: kotlinx.coroutines.flow.StateFlow<Visibility?> = _visibility

    /** Which tunnel state a visibility check belongs to (a check made in another state is stale). */
    fun visibilityKey(): String = when (val s = TunnelRuntime.state.value) {
        is TunnelState.Up -> "up:${s.serverId}:${TunnelRuntime.generation.value}"
        TunnelState.Down, is TunnelState.Failed -> "down"
        is TunnelState.Connecting -> "connecting"
    }

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

    /** Host (name or address) of a fleet server or a standalone profile. */
    fun serverHost(id: String): String? =
        runCatching { fleet.manifest()?.servers?.firstOrNull { it.id == id }?.host }.getOrNull()
            ?: repo.current.profiles.firstOrNull { it.id == id }?.let { p ->
                runCatching { WgQuick.parse(p.conf).peers.firstOrNull()?.endpoint?.let { Uapi.splitEndpoint(it).first } }.getOrNull()
            }

    /** Servers this device can connect to: fleet servers it has a config for, plus standalone ones. */
    fun serverCount(): Int =
        (runCatching { fleet.member()?.tunnels()?.size }.getOrNull() ?: 0) + repo.current.profiles.size
    private val failover = FailoverPolicy()

    override fun onCreate() {
        super.onCreate()
        // Shared client code logs through the platform logger; here that is logcat.
        PLog.sink = PLog.Sink { level, tag, msg, e ->
            when (level) {
                PLog.Level.DEBUG -> Log.d(tag, msg, e)
                PLog.Level.INFO -> Log.i(tag, msg, e)
                PLog.Level.WARN -> Log.w(tag, msg, e)
                PLog.Level.ERROR -> Log.e(tag, msg, e)
            }
        }
        repo = Repo(Vault(this))
        net = Net(this)
        // Server management goes over the phone's own network while our VPN is up, then through it.
        SshConnection.routes = {
            val direct = if (TunnelRuntime.state.value is TunnelState.Down) null else net.direct()
            listOfNotNull(direct?.socketFactory) + listOf(null)
        }
        specs = SpecBuilder(this, lists)
        fleet = FleetManager(repo, net, NativeEd25519, scope) { reconnectIfUp() }
        // Every new server gets the page to share with family and friends, in the background.
        val pageOn: (net.paramnezia.core.profile.SshCredentials) -> Unit = { ssh -> scope.launch { runCatching { links.preparePage(ssh) } } }
        fleet.onServerReady = pageOn
        servers = Servers(repo, fleet, android.os.Build.MODEL, pageOn)

        // DNS rules follow the settings and the lists; loading them is a one-off ~1 ms parse in Go.
        scope.launch(Dispatchers.IO) {
            kotlinx.coroutines.flow.combine(repo.state.map { it.settings.blockTrackers to it.settings.bypassRussia }, lists.taken) { a, v -> a to v }
                .distinctUntilChanged().collect {
                    runCatching { specs.applyDnsRules(repo.current.settings) }.onFailure { Log.e(TAG, "dns rules", it) }
                }
        }
        // Newer lists arrived: routes change, so a running tunnel is rebuilt (quietly, see the service).
        scope.launch { lists.taken.drop(1).collect { reconnectIfUp() } }
        // Admin: settings changes go to every fleet device (no new manifest version when nothing changed).
        // Only real changes (drop the current value): settings change on the admin's screen, so the
        // SSH publishing this causes never starts from the background. The open app catches up in
        // healServers.
        scope.launch(Dispatchers.IO) {
            repo.state.map { s -> s.settings to (s.fleet?.let { f -> f.adminSeed != null && f.sharePolicy } == true) }.distinctUntilChanged().drop(1)
                .collectLatest { (settings, share) ->
                    if (!share) return@collectLatest
                    delay(3_000) // a burst of toggles becomes one version
                    runCatching { fleet.sharePolicy(settings) }.onFailure { Log.w(TAG, "share policy", it) }
                }
        }
        // Nothing below blocks the first frame: the sync job, the updater and the native library start
        // in the background.
        scope.launch(Dispatchers.IO) {
            SyncJob.schedule(this@App)
            updater.checkIfDue()
            lists.checkIfDue()
        }
        // Widget: redraw on every state change; while up, refresh the counters every 30 s with the
        // screen on (the loop simply does not run while the phone sleeps).
        scope.launch {
            TunnelRuntime.state.collectLatest { s ->
                WidgetProvider.refresh(this@App)
                android.service.quicksettings.TileService.requestListeningState(
                    this@App, android.content.ComponentName(this@App, QuickTile::class.java))
                val power = getSystemService(PowerManager::class.java)
                while (s is TunnelState.Up && WidgetProvider.hasWidgets(this@App)) {
                    delay(30_000)
                    if (power.isInteractive) WidgetProvider.refresh(this@App)
                }
            }
        }
    }

    /** Reconnects with a fresh spec when the tunnel is running (new manifest, new settings). */
    fun reconnectIfUp() {
        if (TunnelRuntime.state.value !is TunnelState.Down) TunnelRuntime.reload()
    }

    /**
     * Turns the tunnel on. Returns false if Android refused to start the service from the background
     * (only possible when called without a visible screen); callers then open the app.
     */
    fun connect(): Boolean {
        repo.update { it.copy(wantConnected = true) }
        return ParamneziaVpnService.start(this)
    }

    fun disconnect() {
        repo.update { it.copy(wantConnected = false) }
        ParamneziaVpnService.stop(this)
    }
    fun vpnPermissionIntent() = VpnService.prepare(this)

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
        val now = System.currentTimeMillis()
        failover.markBlocked(spec.serverId, now)
        // The admin may already have moved the fleet elsewhere: look before guessing.
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
        // Fleet devices pick up the admin's changes within the hour while the tunnel works, and at
        // once on a fresh connection or a switch to another server.
        val fresh = healthyServer != spec.serverId
        healthyServer = spec.serverId
        fleet.syncIfDue(if (fresh) 0 else 3600_000L)
    }

    /**
     * Admin only: checks and repairs the fleet's servers (see [FleetManager.heal]) at most once a
     * day, or now if [force]. Runs only while the admin has the app open (MainActivity), never from
     * background work. Cheap for everyone else: returns at once.
     */
    suspend fun healServers(force: Boolean = false) {
        val f = repo.current.fleet ?: return
        if (f.adminSeed == null) return
        // Cheap and local when nothing changed; brings a manifest from before 2.2 up to date.
        if (f.sharePolicy) runCatching { fleet.sharePolicy(repo.current.settings) }
        if (!force && System.currentTimeMillis() - f.lastHeal < HEAL_EVERY_MS) return
        runCatching { fleet.heal(updater.shareable()) }.onFailure { Log.w(TAG, "heal", it) }
    }

    private companion object {
        const val TAG = "Paramnezia"
        const val HEAL_EVERY_MS = 24 * 3600_000L
    }
}
