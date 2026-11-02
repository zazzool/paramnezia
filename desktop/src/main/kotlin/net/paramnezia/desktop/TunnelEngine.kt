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
import net.paramnezia.tunnel.TunnelStats
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

/** The app side of the tunnel: which server to use, and what to do when one stops answering. */
interface TunnelHost {
    suspend fun currentSpec(): TunnelSpec?
    suspend fun onServerUnreachable(spec: TunnelSpec): TunnelSpec?
    fun onServerHealthy(spec: TunnelSpec) {}
}

/**
 * Drives the tunnel in the Paramnezia service, with the same watchdog as the Android VPN service:
 * it reads the tunnel's counters once a minute (no network traffic), and only when they look wrong
 * (stale handshake, sent without reply) sends one DNS probe through the tunnel. No answer: the
 * tunnel is rebuilt with fresh sockets; after two failures in a row the host picks another server.
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
    private var lastCheck: TunnelStats? = null
    private var failures = 0
    private var startFailures = 0
    private var check: Job? = null
    private var retry: Job? = null

    fun start() { scope.launch { lock.withLock { connect(host.currentSpec()) } } }
    fun reload() { scope.launch { lock.withLock { if (running || spec != null) connect(host.currentSpec()) } } }
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

    private suspend fun connect(next: TunnelSpec?) {
        if (next == null) {
            down()
            TunnelRuntime.set(TunnelState.Failed("no_profile"))
            return
        }
        TunnelRuntime.set(TunnelState.Connecting(next.serverName))
        try {
            val req = specs.request(next, next.keepaliveActive)
            client.call(ServiceClient.Request("up", up = req))
            running = true
            spec = next
            lastCheck = null
            startFailures = 0
            retry?.cancel()
            TunnelRuntime.set(TunnelState.Up(next.serverName, next.serverId, System.currentTimeMillis()))
            TunnelRuntime.bumpGeneration()
            scheduleCheck(FIRST_CHECK_MS)
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
        TunnelRuntime.lastStats = null
        TunnelRuntime.set(TunnelState.Down)
    }

    private fun scheduleCheck(delayMs: Long) {
        check?.cancel()
        check = scope.launch { delay(delayMs); if (isActive) lock.withLock { checkHealth() } }
    }

    private suspend fun checkHealth() {
        val current = spec ?: return
        val now = withTimeoutOrNull(5_000) {
            runCatching { client.call(ServiceClient.Request("status")) }.getOrNull()?.takeIf { it.up }?.let { r ->
                TunnelRuntime.lastBlocked = r.blocked
                r.stats?.let(TunnelRuntime::parseStats)?.also { TunnelRuntime.lastStats = it }
            }
        }
        if (now == null) {
            // The service lost the tunnel (restarted, crashed): build it again once there is a network.
            if (!networkUp()) { scheduleCheck(WATCH_MS); return }
            failures++
            forceReconnect(host.currentSpec() ?: current, "not_running")
            return
        }
        val prev = lastCheck
        lastCheck = now
        val handshakeAge = if (now.lastHandshakeEpochSec == 0L) Long.MAX_VALUE else System.currentTimeMillis() / 1000 - now.lastHandshakeEpochSec
        val sentWithoutReply = prev != null && now.txBytes > prev.txBytes && now.rxBytes == prev.rxBytes
        if (handshakeAge <= DEAD_AFTER_SEC && !sentWithoutReply) { alive(current); return }
        if (!networkUp()) { scheduleCheck(WATCH_MS); return }
        val rtt = probe(current)
        if (rtt != null) { TunnelRuntime.setRtt(rtt); alive(current); return }
        failures++
        Log.w(TAG, "${current.serverName}: no reply (handshake ${handshakeAge}s ago, failure $failures)")
        if (failures >= FAILOVER_AFTER) {
            val replacement = host.onServerUnreachable(current)
            if (replacement != null && replacement.serverId != current.serverId) { failures = 0; connect(replacement); return }
        }
        forceReconnect(host.currentSpec() ?: current, "dead")
    }

    private suspend fun alive(current: TunnelSpec) {
        failures = 0
        host.onServerHealthy(current)
        // A cheap probe now and then keeps the ping on the home screen meaningful.
        if (System.currentTimeMillis() - TunnelRuntime.health.value.checkedAt > WATCH_MS * 5) probe(current)?.let(TunnelRuntime::setRtt)
        scheduleCheck(WATCH_MS)
    }

    private suspend fun forceReconnect(next: TunnelSpec, reason: String) {
        Log.i(TAG, "force reconnect to ${next.serverName}: $reason")
        TunnelRuntime.noteReconnect()
        connect(next)
        if (!running) scheduleCheck(minOf(RETRY_BASE_MS shl minOf(failures, 4), WATCH_MAX_MS))
    }

    /** One DNS query to a foreign resolver, routed through the tunnel. Round trip in ms, or null. */
    private fun probe(s: TunnelSpec): Long? {
        val targets = (s.dns?.tunnel.orEmpty() + PROBE_RESOLVERS).distinct().filter { ':' !in it }.take(3)
        for (attempt in 0 until 2) for (t in targets) {
            val rtt = runCatching {
                DatagramSocket().use { socket ->
                    socket.soTimeout = PROBE_TIMEOUT_MS
                    val q = PROBE_QUERY.copyOf().also { val id = (System.nanoTime() and 0xffff).toInt(); it[0] = (id shr 8).toByte(); it[1] = id.toByte() }
                    val started = System.nanoTime()
                    socket.send(DatagramPacket(q, q.size, InetAddress.getByName(t), 53))
                    val buf = ByteArray(512)
                    val reply = DatagramPacket(buf, buf.size)
                    socket.receive(reply)
                    if (reply.length >= 12 && buf[0] == q[0] && buf[1] == q[1]) (System.nanoTime() - started) / 1_000_000 else null
                }
            }.getOrNull()
            if (rtt != null) return rtt
        }
        return null
    }

    companion object {
        private const val TAG = "PnTunnel"
        private const val FIRST_CHECK_MS = 20_000L
        private const val WATCH_MS = 60_000L
        private const val WATCH_MAX_MS = 15 * 60_000L
        private const val RETRY_BASE_MS = 10_000L
        private const val MAX_START_RETRIES = 8
        private const val DEAD_AFTER_SEC = 180L
        private const val FAILOVER_AFTER = 2
        private const val PROBE_TIMEOUT_MS = 3_000
        private val PROBE_RESOLVERS = listOf("1.1.1.1", "8.8.8.8", "9.9.9.9")
        private val PROBE_QUERY = byteArrayOf(0, 0, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 2, 0, 1)

        /** Some physical adapter is up with a usable IPv4 address (ours does not count). */
        fun networkUp(): Boolean = runCatching {
            NetworkInterface.getNetworkInterfaces().toList().any { n ->
                n.isUp && !n.isLoopback && !n.isVirtual && !n.displayName.contains("Paramnezia", true) &&
                    n.inetAddresses.toList().any { it is Inet4Address && !it.isLinkLocalAddress && !it.isLoopbackAddress }
            }
        }.getOrDefault(true)
    }
}
