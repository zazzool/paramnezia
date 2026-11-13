package net.paramnezia.desktop

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.paramnezia.app.platform.Log
import net.paramnezia.tunnel.TunnelRuntime
import net.paramnezia.tunnel.TunnelSpec
import net.paramnezia.tunnel.TunnelState

/** The app side of the tunnel: which servers to use, and what to do when one stops answering. */
interface TunnelHost {
    suspend fun currentSpec(): TunnelSpec?
    /** The other servers to fall back on, preferred first (the service switches by itself). */
    suspend fun alternateSpecs(primary: TunnelSpec): List<TunnelSpec> = emptyList()
    suspend fun onServerUnreachable(spec: TunnelSpec): TunnelSpec?
    fun onServerHealthy(spec: TunnelSpec) {}
}

/**
 * The app's view of the tunnel in the Paramnezia service. The watchdog itself runs in the service
 * (tunnel/src/main/go/windows/watch.go, the same rules as Android's LinkWatch): it verifies a new
 * tunnel before it counts as connected, repairs it (fresh sockets, rebuild) and switches to the
 * next server of the list the app gave it, also while the app is closed or has crashed. This class
 * only asks for a tunnel, hands over the server list and mirrors the service's status every
 * [POLL_MS] for the screen (one small pipe call).
 */
class TunnelEngine(
    private val client: ServiceClient,
    private val specs: SpecBuilder,
    private val host: TunnelHost,
    private val scope: CoroutineScope,
) {
    private val lock = Mutex()
    /** The servers the service was given: [0] preferred, the rest to fall back on. */
    private var servers: List<TunnelSpec> = emptyList()
    private var running = false
    private var startFailures = 0
    private var poll: Job? = null
    private var retry: Job? = null

    // What the last status said, to notice changes.
    private var lastActive = 0
    private var lastVerified = false
    private var lastReconnects = 0
    private var lastHealthyCall = 0L
    /** A quiet reload of a working tunnel keeps "connected" on screen until the service has verified it. */
    private var quietUntil = 0L

    init {
        TunnelRuntime.onRecheck = { scope.launch(Dispatchers.IO) { runCatching { client.call(ServiceClient.Request("recheck")) } } }
    }

    fun start() { scope.launch { lock.withLock { adoptOrConnect() } } }
    fun reload() { scope.launch { lock.withLock { if (running || servers.isNotEmpty()) connect(host.currentSpec(), quiet = true) } } }
    fun stop() { scope.launch { lock.withLock { down() } } }

    /** Synchronous stop for "turn the VPN off and quit". */
    suspend fun stopNow() = lock.withLock { down() }

    /** DNS filter lists; the service keeps them across reconnects. */
    fun applyDnsRules(block: String, direct: String, allow: String) {
        runCatching { client.call(ServiceClient.Request("rules", block = block, direct = direct, allow = allow)) }
            .onFailure { Log.w(TAG, "dns rules: ${it.message}") }
    }

    /** Counters for the screen (the poll keeps them fresh; kept for the screen's own 2 s refresh). */
    fun refreshStats() {
        if (!running) return
        runCatching { client.call(ServiceClient.Request("status")) }.onSuccess { r ->
            TunnelRuntime.lastStats = r.stats?.let(TunnelRuntime::parseStats)
            TunnelRuntime.lastBlocked = r.blocked
        }
    }

    /**
     * After the app starts: if the service still runs our tunnel (the app was closed with the VPN
     * on), take it over as it is, without a reconnect; otherwise connect.
     */
    private suspend fun adoptOrConnect() {
        val next = host.currentSpec()
        val st = runCatching { client.call(ServiceClient.Request("status")) }.getOrNull()
        if (next != null && st != null && st.up && st.watch != null) {
            servers = listOf(next) + host.alternateSpecs(next)
            if (servers.any { it.serverName == st.watch.server }) {
                running = true
                lastActive = st.watch.active; lastVerified = false; lastReconnects = st.watch.reconnects
                Log.i(TAG, "took over the running tunnel to ${st.watch.server}")
                apply(st)
                startPolling()
                return
            }
        }
        connect(next)
    }

    private suspend fun connect(next: TunnelSpec?, quiet: Boolean = false) {
        if (next == null) {
            down()
            TunnelRuntime.set(TunnelState.Failed("no_profile"))
            return
        }
        val alternates = host.alternateSpecs(next)
        val keepUp = quiet && (TunnelRuntime.state.value as? TunnelState.Up)?.serverId == next.serverId
        if (!keepUp) TunnelRuntime.set(TunnelState.Connecting(next.serverName))
        quietUntil = if (keepUp) System.currentTimeMillis() + QUIET_MS else 0
        try {
            val req = ServiceClient.Request("up", up = specs.request(next, next.keepaliveActive),
                alternates = alternates.map { specs.request(it, it.keepaliveActive) }.ifEmpty { null })
            withContext(Dispatchers.IO) { client.call(req) }
            servers = listOf(next) + alternates
            running = true
            startFailures = 0
            lastActive = 0; lastVerified = false; lastReconnects = 0
            retry?.cancel()
            startPolling()
        } catch (e: Exception) {
            Log.e(TAG, "connect failed", e)
            running = false
            TunnelRuntime.set(TunnelState.Failed(e.message ?: e.javaClass.simpleName))
            // No service yet after boot, a DNS hiccup building the spec: try again with backoff.
            if (startFailures < MAX_START_RETRIES) {
                servers = listOf(next)
                val wait = minOf(RETRY_BASE_MS shl startFailures, RETRY_MAX_MS)
                startFailures++
                retry?.cancel()
                retry = scope.launch { delay(wait); lock.withLock { if (!running && servers.isNotEmpty()) connect(host.currentSpec()) } }
            }
        }
    }

    private fun down() {
        poll?.cancel(); retry?.cancel()
        startFailures = 0
        if (running || servers.isNotEmpty()) runCatching { client.call(ServiceClient.Request("down")) }.onFailure { Log.w(TAG, "down: ${it.message}") }
        running = false
        servers = emptyList()
        TunnelRuntime.lastStats = null
        TunnelRuntime.setLink(unstable = false, offline = false)
        TunnelRuntime.set(TunnelState.Down)
    }

    private fun startPolling() {
        poll?.cancel()
        poll = scope.launch {
            while (isActive) {
                delay(POLL_MS)
                lock.withLock { if (running) pollOnce() }
            }
        }
    }

    private suspend fun pollOnce() {
        val st = withContext(Dispatchers.IO) { runCatching { client.call(ServiceClient.Request("status")) }.getOrNull() }
        if (st == null || !st.up || st.watch == null) {
            // The service lost the tunnel (it was restarted or updated): ask for it again.
            Log.w(TAG, "the service has no tunnel; reconnecting")
            running = false
            connect(host.currentSpec())
            return
        }
        apply(st)
    }

    /** Mirrors one status of the service into the state the screens show. */
    private suspend fun apply(st: ServiceClient.Response) {
        val w = st.watch ?: return
        TunnelRuntime.lastStats = st.stats?.let(TunnelRuntime::parseStats)
        TunnelRuntime.lastBlocked = st.blocked
        val spec = servers.getOrNull(w.active) ?: servers.firstOrNull() ?: return
        // The service switched servers: tell the app's bookkeeping (blocked list, fleet health).
        if (w.active != lastActive) {
            servers.getOrNull(lastActive)?.let { old ->
                Log.i(TAG, "the service switched from ${old.serverName} to ${spec.serverName}")
                scope.launch { runCatching { host.onServerUnreachable(old) } }
            }
            lastActive = w.active
        }
        repeat((w.reconnects - lastReconnects).coerceAtLeast(0)) { TunnelRuntime.noteReconnect() }
        lastReconnects = w.reconnects
        if (w.rttMs > 0) TunnelRuntime.setRtt(w.rttMs)
        TunnelRuntime.setLink(unstable = w.phase == "unstable", offline = w.offline)
        val now = System.currentTimeMillis()
        val since = w.session.takeIf { it > 0 } ?: now
        when {
            w.verified -> {
                TunnelRuntime.set(TunnelState.Up(spec.serverName, spec.serverId, since))
                if (!lastVerified) TunnelRuntime.bumpGeneration()
                quietUntil = 0
                if (now - lastHealthyCall > HEALTHY_EVERY_MS) { lastHealthyCall = now; host.onServerHealthy(spec) }
            }
            now < quietUntil && (TunnelRuntime.state.value as? TunnelState.Up)?.serverId == spec.serverId -> Unit
            else -> TunnelRuntime.set(TunnelState.Connecting(spec.serverName, reconnecting = w.repairing))
        }
        lastVerified = w.verified
    }

    companion object {
        private const val TAG = "PnTunnel"
        private const val POLL_MS = 2_000L
        private const val QUIET_MS = 20_000L
        private const val HEALTHY_EVERY_MS = 60_000L
        private const val RETRY_BASE_MS = 10_000L
        private const val RETRY_MAX_MS = 15 * 60_000L
        private const val MAX_START_RETRIES = 8
    }
}
