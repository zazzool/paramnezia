package io.paramnezia.app

import android.app.Application
import android.net.VpnService
import android.os.PowerManager
import android.util.Log
import io.paramnezia.app.data.Repo
import io.paramnezia.app.data.StoredProfile
import io.paramnezia.app.fleet.FleetManager
import io.paramnezia.app.ota.Updater
import io.paramnezia.app.tunnel.Net
import io.paramnezia.app.tunnel.SpecBuilder
import io.paramnezia.app.ui.WidgetProvider
import io.paramnezia.app.work.SyncWorker
import io.paramnezia.core.config.WgQuick
import io.paramnezia.core.fleet.FailoverPolicy
import io.paramnezia.core.fleet.GossipMessage
import io.paramnezia.tunnel.NativeEd25519
import io.paramnezia.tunnel.ParamneziaVpnService
import io.paramnezia.tunnel.TunnelHost
import io.paramnezia.tunnel.TunnelRuntime
import io.paramnezia.tunnel.TunnelSpec
import io.paramnezia.tunnel.TunnelState
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

/**
 * The app process. Also the [TunnelHost] the VPN service asks for configs: it decides which server
 * to use, fails over when one is blocked and feeds what devices gossip into the fleet and updater.
 */
class App : Application(), TunnelHost {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    lateinit var repo: Repo; private set
    lateinit var net: Net; private set
    lateinit var specs: SpecBuilder; private set
    lateinit var fleet: FleetManager; private set
    /** Created on first use, off the launch path (it reads package info and the stored release). */
    val updater: Updater by lazy { Updater(this, repo, net, fleet, NativeEd25519, scope) }
    lateinit var servers: io.paramnezia.app.data.Servers; private set
    val links: io.paramnezia.app.fleet.Links by lazy { io.paramnezia.app.fleet.Links(this, repo, fleet) }
    private val failover = FailoverPolicy()

    override fun onCreate() {
        super.onCreate()
        repo = Repo(this)
        net = Net(this)
        specs = SpecBuilder(this)
        fleet = FleetManager(repo, net, NativeEd25519, scope) { reconnectIfUp() }
        servers = io.paramnezia.app.data.Servers(repo, fleet)
        applyEmbeddedInvite()

        // DNS rules follow the settings; loading them is a one-off ~1 ms parse in Go.
        scope.launch(Dispatchers.IO) {
            repo.state.map { it.settings.blockTrackers to it.settings.bypassRussia }.distinctUntilChanged().collect {
                runCatching { specs.applyDnsRules(repo.current.settings) }.onFailure { Log.e(TAG, "dns rules", it) }
            }
        }
        // After each connect: tell the other devices our manifest version (they answer if theirs is newer).
        scope.launch {
            TunnelRuntime.state.collect { s -> if (s is TunnelState.Up) { delay(3_000); fleet.announce() } }
        }
        // Nothing below blocks the first frame: WorkManager, the updater and the native library start
        // in the background.
        scope.launch(Dispatchers.IO) {
            SyncWorker.schedule(this@App)
            updater
        }
        // Widget: redraw on every state change; while up, refresh the counters every 30 s with the
        // screen on (the loop simply does not run while the phone sleeps).
        scope.launch {
            TunnelRuntime.state.collectLatest { s ->
                WidgetProvider.refresh(this@App)
                android.service.quicksettings.TileService.requestListeningState(
                    this@App, android.content.ComponentName(this@App, io.paramnezia.app.ui.QuickTile::class.java))
                val power = getSystemService(PowerManager::class.java)
                while (s is TunnelState.Up && WidgetProvider.hasWidgets(this@App)) {
                    delay(30_000)
                    if (power.isInteractive) WidgetProvider.refresh(this@App)
                }
            }
        }
    }

    /**
     * Installed from a one-time link: the invite is inside our own APK. Applied once, on a device
     * that has nothing set up yet; the check reads a few KiB at the end of the APK.
     */
    private fun applyEmbeddedInvite() = scope.launch(Dispatchers.IO) {
        repo.ready.first { it }
        val s = repo.current
        if (s.embeddedInviteChecked) return@launch
        if (s.profiles.isEmpty() && s.fleet == null) {
            io.paramnezia.core.apk.ApkSlot.read(java.io.File(applicationInfo.sourceDir))?.let { invite ->
                runCatching { servers.import(invite) }.onFailure { Log.w(TAG, "embedded invite", it) }
            }
        }
        repo.update { it.copy(embeddedInviteChecked = true) }
    }

    /** Reconnects with a fresh spec when the tunnel is running (new manifest, new settings). */
    fun reconnectIfUp() {
        if (TunnelRuntime.state.value !is TunnelState.Down) TunnelRuntime.reload()
    }

    fun connect() {
        repo.update { it.copy(wantConnected = true) }
        ParamneziaVpnService.start(this)
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
                return specs.build(chosen.id, chosen.name, config, state.settings, fleet.peers())
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
        scope.launch { delay(10_000); fleet.reportHealth(spec.serverId, reachable = false) }
        return next
    }

    override fun onServerHealthy(spec: TunnelSpec) {
        failover.markHealthy(spec.serverId)
    }

    override fun onGossip(from: String, payload: ByteArray) {
        val msg = GossipMessage.decode(payload) ?: return
        scope.launch {
            when (msg) {
                is GossipMessage.Release -> updater.offer(msg.signed)
                else -> fleet.onGossip(from, msg)
            }
        }
    }

    suspend fun afterUpdate() {
        if (repo.current.wantConnected && VpnService.prepare(this) == null) ParamneziaVpnService.start(this)
        updater.mirrorInstalledToServers()
    }

    private companion object {
        const val TAG = "Paramnezia"
    }
}
