package net.paramnezia.desktop

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import net.paramnezia.app.platform.Log
import net.paramnezia.tunnel.TunnelRuntime
import net.paramnezia.tunnel.TunnelSpec
import net.paramnezia.tunnel.TunnelState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.paramnezia.core.net.LinkProbe
import net.paramnezia.core.net.LinkWatch
import java.net.Inet4Address
import java.net.NetworkInterface

/** The app side of the tunnel: which server to use, and what to do when one stops answering. */
interface TunnelHost {
    suspend fun currentSpec(): TunnelSpec?
    suspend fun onServerUnreachable(spec: TunnelSpec): TunnelSpec?
    fun onServerHealthy(spec: TunnelSpec) {}
}

/**
 * Drives the tunnel in the Paramnezia service with the same watchdog as the Android VPN service
 * ([LinkWatch]): "connected" only after a probe came back through the new tunnel; then the counters
 * are read once a minute (no network traffic), traffic that came back proves the path, an idle
 * tunnel gets one tiny probe. Failures in a row: a second look, fresh sockets in the same tunnel
 * (the adapter, its routes and firewall stay, so nothing leaks), a full rebuild, another server.
 */
class TunnelEngine(
    private val client: ServiceClient,
    private val specs: SpecBuilder,
    private val host: TunnelHost,
    private val scope: CoroutineScope,
) {
    private val lock = Mutex()
    private var spec: TunnelSpec? = null
    private var running = false
    private var sessionSince = 0L
    private val watch = LinkWatch()
    private var startFailures = 0
    private var check: Job? = null
    private var retry: Job? = null

    init {
        TunnelRuntime.onRecheck = { scope.launch { lock.withLock { if (running) { watch.suspect(); scheduleCheck(0) } } } }
    }

    fun start() { scope.launch { lock.withLock { connect(host.currentSpec()) } } }
    fun reload() { scope.launch { lock.withLock { if (running || spec != null) connect(host.currentSpec(), reconnecting = running, quiet = true) } } }
    fun stop() { scope.launch { lock.withLock { down() } } }

    /** Synchronous stop for app exit. */
    suspend fun stopNow() = lock.withLock { down() }

    /** DNS filter lists; the service keeps them across reconnects. */
    fun applyDnsRules(block: String, direct: String, allow: String) {
        runCatching { client.call(ServiceClient.Request("rules", block = block, direct = direct, allow = allow)) }
            .onFailure { Log.w(TAG, "dns rules: ${it.message}") }
    }

    /** Reads counters for the screen (every 2 s while it is open). */
    fun refreshStats() {
        if (!running) return
        runCatching { client.call(ServiceClient.Request("status")) }.onSuccess { r ->
            TunnelRuntime.lastStats = r.stats?.let(TunnelRuntime::parseStats)
            TunnelRuntime.lastBlocked = r.blocked
        }
    }

    /** See the Android service: [quiet] keeps "connected" on screen while a deliberate rebuild is verified. */
    private suspend fun connect(next: TunnelSpec?, reconnecting: Boolean = false, quiet: Boolean = false) {
        if (next == null) {
            down()
            TunnelRuntime.set(TunnelState.Failed("no_profile"))
            return
        }
        val sameServer = spec?.serverId == next.serverId
        val keepUp = quiet && sameServer && (TunnelRuntime.state.value as? TunnelState.Up)?.serverId == next.serverId
        if (!keepUp) TunnelRuntime.set(TunnelState.Connecting(next.serverName, reconnecting = reconnecting && running))
        check?.cancel()
        try {
            val req = specs.request(next, next.keepaliveActive)
            client.call(ServiceClient.Request("up", up = req))
            running = true
            spec = next
            startFailures = 0
            retry?.cancel()
            val now = System.currentTimeMillis()
            if (!(reconnecting && sameServer) || sessionSince == 0L) sessionSince = now
            watch.started(now, sameServer = reconnecting && sameServer)
            TunnelRuntime.setLink(unstable = reconnecting && !keepUp, offline = false)
            scheduleCheck(VERIFY_FIRST_MS)
        } catch (e: Exception) {
            Log.e(TAG, "connect failed", e)
            running = false
            TunnelRuntime.set(TunnelState.Failed(e.message ?: e.javaClass.simpleName))
            // No network yet after boot, a DNS hiccup: try again with backoff, then give up.
            if (startFailures < MAX_START_RETRIES) {
                spec = next
                val wait = minOf(RETRY_BASE_MS shl startFailures, WATCH_MAX_MS)
                startFailures++
                retry?.cancel()
                retry = scope.launch { delay(wait); lock.withLock { if (!running && spec != null) connect(host.currentSpec()) } }
            }
        }
    }

    private fun down() {
        check?.cancel(); retry?.cancel()
        startFailures = 0
        if (running) runCatching { client.call(ServiceClient.Request("down")) }.onFailure { Log.w(TAG, "down: ${it.message}") }
        running = false
        spec = null
        sessionSince = 0
        TunnelRuntime.lastStats = null
        TunnelRuntime.setLink(unstable = false, offline = false)
        TunnelRuntime.set(TunnelState.Down)
    }

    private fun scheduleCheck(delayMs: Long) {
        check?.cancel()
        check = scope.launch { delay(delayMs); if (isActive) lock.withLock { checkHealth() } }
    }

    private suspend fun checkHealth() {
        val current = spec ?: return
        if (!running) {
            if (!networkUp()) { offline(); return }
            rebuild(current)
            return
        }
        val counters = withTimeoutOrNull(5_000) {
            runCatching { client.call(ServiceClient.Request("status")) }.getOrNull()?.takeIf { it.up }?.let { r ->
                TunnelRuntime.lastBlocked = r.blocked
                r.stats?.let(TunnelRuntime::parseStats)?.also { TunnelRuntime.lastStats = it }
            }
        }
        if (counters == null && !networkUp()) { offline(); return }
        // The computer counts as "in use" while the app runs: Windows has no screen-off state for us.
        var action = watch.onCounters(System.currentTimeMillis(), counters?.let { LinkWatch.Counters(it.rxBytes, it.txBytes, it.lastHandshakeEpochSec) }, ACTIVE)
        if (action == LinkWatch.Action.Probe) {
            if (!networkUp()) { offline(); return }
            val rtt = withContext(Dispatchers.IO) { LinkProbe.run(probeTargets(current)) }
            action = watch.onProbe(System.currentTimeMillis(), rtt, ACTIVE)
            watch.rttMs?.takeIf { rtt != null }?.let(TunnelRuntime::setRtt)
            if (rtt == null) Log.w(TAG, "${current.serverName}: no reply (${watch.phase}, failure ${watch.failures}, builds ${watch.failedBuilds})")
        }
        act(current, action)
    }

    private fun offline() {
        TunnelRuntime.setLink(unstable = TunnelRuntime.health.value.unstable, offline = true)
        scheduleCheck(watch.offline(ACTIVE).ms)
    }

    private suspend fun act(current: TunnelSpec, action: LinkWatch.Action) {
        TunnelRuntime.setLink(unstable = watch.phase == LinkWatch.Phase.UNSTABLE, offline = false)
        when (action) {
            is LinkWatch.Action.Wait -> {
                // Fleet devices pick up the admin's changes within the hour while the tunnel works.
                if (watch.phase == LinkWatch.Phase.GOOD) host.onServerHealthy(current)
                scheduleCheck(action.ms)
            }
            LinkWatch.Action.Probe -> scheduleCheck(0)
            is LinkWatch.Action.Verified -> {
                TunnelRuntime.set(TunnelState.Up(current.serverName, current.serverId, sessionSince))
                TunnelRuntime.bumpGeneration()
                host.onServerHealthy(current)
                scheduleCheck(action.ms)
            }
            is LinkWatch.Action.Rebind -> {
                Log.i(TAG, "${current.serverName}: fresh sockets")
                runCatching { client.call(ServiceClient.Request("rebind")) }
                    .onSuccess { TunnelRuntime.noteReconnect() }
                    .onFailure { Log.w(TAG, "rebind: ${it.message}") }
                watch.rebound()
                scheduleCheck(action.ms)
            }
            is LinkWatch.Action.Rebuild -> rebuildAfter(current, action.delayMs)
            is LinkWatch.Action.Failover -> {
                val replacement = host.onServerUnreachable(current)
                if (replacement != null && replacement.serverId != current.serverId) {
                    Log.i(TAG, "switching to ${replacement.serverName}")
                    TunnelRuntime.noteReconnect()
                    connect(replacement, reconnecting = true)
                } else rebuildAfter(current, action.delayMs)
            }
        }
    }

    private suspend fun rebuildAfter(current: TunnelSpec, delayMs: Long) {
        if (delayMs <= 0) { rebuild(current); return }
        // The old tunnel stays meanwhile: programs wait instead of going around it.
        TunnelRuntime.set(TunnelState.Connecting(current.serverName, reconnecting = true))
        check?.cancel()
        check = scope.launch { delay(delayMs); if (isActive) lock.withLock { spec?.let { rebuild(it) } } }
    }

    private suspend fun rebuild(current: TunnelSpec) {
        val next = host.currentSpec() ?: current
        Log.i(TAG, "rebuild ${next.serverName}")
        TunnelRuntime.noteReconnect()
        connect(next, reconnecting = true)
        if (!running) scheduleCheck(minOf(RETRY_BASE_MS shl minOf(watch.failedBuilds, 4), WATCH_MAX_MS))
    }

    private fun probeTargets(s: TunnelSpec): List<String> = (s.dns?.tunnel.orEmpty() + LinkProbe.DNS_TARGETS).distinct()

    companion object {
        private const val TAG = "PnTunnel"
        private const val VERIFY_FIRST_MS = 150L
        private const val ACTIVE = true
        private const val WATCH_MAX_MS = 15 * 60_000L
        private const val RETRY_BASE_MS = 10_000L
        private const val MAX_START_RETRIES = 8

        /** Some physical adapter is up with a usable IPv4 address (ours does not count). */
        fun networkUp(): Boolean = runCatching {
            NetworkInterface.getNetworkInterfaces().toList().any { n ->
                n.isUp && !n.isLoopback && !n.isVirtual && !n.displayName.contains("Paramnezia", true) &&
                    n.inetAddresses.toList().any { it is Inet4Address && !it.isLinkLocalAddress && !it.isLoopbackAddress }
            }
        }.getOrDefault(true)
    }
}
