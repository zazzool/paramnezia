package net.paramnezia.app

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
import net.paramnezia.app.work.SyncWorker
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import net.paramnezia.app.data.Servers
import net.paramnezia.app.fleet.Links
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
    lateinit var fleet: FleetManager; private set
    /** Created on first use, off the launch path (it reads package info and the stored release). */
    val updater: Updater by lazy { Updater(this, repo, net, NativeEd25519, scope) { repo.current.fleet != null } }
    lateinit var servers: Servers; private set
    val links: Links by lazy { Links(repo, fleet, { updater }, scope) }
    /** Buying servers from hosting providers (admin). */
    val cloud: Cloud by lazy { Cloud(repo, scope) }
    private val visibilityCheck by lazy { VisibilityCheck(net) }
    private val _visibility = kotlinx.coroutines.flow.MutableStateFlow<Visibility?>(null)
    /** What foreign and Russian sites last saw; refreshed by the home screen. */
    val visibility: kotlinx.coroutines.flow.StateFlow<Visibility?> = _visibility

    suspend fun refreshVisibility() { _visibility.value = visibilityCheck.run() }
    private val failover = FailoverPolicy()

    override fun onCreate() {
        super.onCreate()
        repo = Repo(this)
        net = Net(this)
        // Server management goes over the phone's own network while our VPN is up, then through it.
        SshConnection.routes = {
            val direct = if (TunnelRuntime.state.value is TunnelState.Down) null else net.direct()
            listOfNotNull(direct?.socketFactory) + listOf(null)
        }
        specs = SpecBuilder(this)
        fleet = FleetManager(repo, net, NativeEd25519, scope) { reconnectIfUp() }
        servers = Servers(repo, fleet)

        // DNS rules follow the settings; loading them is a one-off ~1 ms parse in Go.
        scope.launch(Dispatchers.IO) {
            repo.state.map { it.settings.blockTrackers to it.settings.bypassRussia }.distinctUntilChanged().collect {
                runCatching { specs.applyDnsRules(repo.current.settings) }.onFailure { Log.e(TAG, "dns rules", it) }
            }
        }
        // Nothing below blocks the first frame: WorkManager, the updater and the native library start
        // in the background.
        scope.launch(Dispatchers.IO) {
            SyncWorker.schedule(this@App)
            updater.checkIfDue()
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

    override fun onServerHealthy(spec: TunnelSpec) {
        failover.markHealthy(spec.serverId)
    }

    /**
     * Admin only: checks and repairs the fleet's servers (see [FleetManager.heal]) at most once a
     * day, or now if [force]. Runs only while the admin has the app open (MainActivity), never from
     * background work. Cheap for everyone else: returns at once.
     */
    suspend fun healServers(force: Boolean = false) {
        val f = repo.current.fleet ?: return
        if (f.adminSeed == null) return
        if (!force && System.currentTimeMillis() - f.lastHeal < HEAL_EVERY_MS) return
        runCatching { fleet.heal(updater.shareable()) }.onFailure { Log.w(TAG, "heal", it) }
    }

    private companion object {
        const val TAG = "Paramnezia"
        const val HEAL_EVERY_MS = 24 * 3600_000L
    }
}
