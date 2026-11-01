package net.paramnezia.tunnel

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.IpPrefix as AndroidIpPrefix
import android.net.Network
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.Log
import net.paramnezia.core.config.Uapi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import net.paramnezia.core.config.TunnelConfig

/**
 * The VPN service. Designed to cost nothing while idle:
 *  - a watchdog, not a polling loop: it reads the tunnel's own counters (no network traffic) every
 *    minute with the screen on and every 15 minutes with it off, on the main looper, so it simply
 *    waits while the phone sleeps. Only when the counters look wrong (we send, nothing comes back,
 *    or the session went stale) does it send one small probe through the tunnel; if that fails too,
 *    the tunnel is rebuilt from scratch (fresh sockets fix stuck NAT bindings), and after repeated
 *    failures the host is asked for another server;
 *  - keepalive is lowered while the screen is off;
 */
class ParamneziaVpnService : VpnService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private val main = Handler(Looper.getMainLooper())

    private var handle = -1
    private var spec: TunnelSpec? = null
    private var connectedAt = 0L
    private var lastCheck: TunnelStats? = null
    private var screenOn = true
    /** Failed probes in a row; reset by any sign of life. */
    private var failures = 0

    private val host get() = application as? TunnelHost

    override fun onCreate() {
        super.onCreate()
        TunnelRuntime.service = this
        registerReceiver(screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF)
        })
        getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(networkCallback)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            scope.launch { lock.withLock { down() }; stopSelf() }
            return START_NOT_STICKY
        }
        // ACTION_START from the app, or a null/"android.net.VpnService" intent from Always-on VPN.
        scope.launch { lock.withLock { connect(host?.currentSpec()) } }
        return START_STICKY
    }

    override fun onRevoke() {
        // Another VPN took over or the user revoked us in settings.
        scope.launch { lock.withLock { down() }; stopSelf() }
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(screenReceiver) }
        runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(networkCallback) }
        main.removeCallbacksAndMessages(null)
        val h = handle
        handle = -1
        if (h >= 0) AwgNative.turnOff(h)
        TunnelRuntime.service = null
        TunnelRuntime.set(TunnelState.Down)
        scope.cancel()
        super.onDestroy()
    }

    internal fun reload() {
        scope.launch { lock.withLock { connect(host?.currentSpec()) } }
    }

    internal fun stopFromApp() {
        scope.launch { lock.withLock { down() }; stopSelf() }
    }

    /** Failed starts in a row (no network yet, DNS of the endpoint, a native error); reset on success. */
    private var startFailures = 0
    private val retryStart = Runnable { scope.launch { lock.withLock { if (handle < 0) connect(host?.currentSpec()) } } }

    private fun connect(next: TunnelSpec?) {
        if (next == null) {
            TunnelRuntime.set(TunnelState.Failed("no_profile"))
            down()
            stopSelf()
            return
        }
        TunnelRuntime.set(TunnelState.Connecting(next.serverName))
        try {
            // Resolve before touching the old tunnel, so a DNS failure keeps the current connection.
            val uapi = Uapi.write(next.config.withKeepalive(if (screenOn) next.keepaliveScreenOn else next.keepaliveScreenOff))
            val builder = Builder().setSession(next.serverName).setMtu(next.config.iface.mtu ?: 1280)
            for (a in next.config.iface.addresses) builder.addAddress(a.address, a.length)
            val dns = next.dns
            if (dns != null) {
                builder.addDnsServer(dns.virtual)
                builder.addRoute(dns.virtual, 32)
            } else {
                for (d in next.config.iface.dns) builder.addDnsServer(d)
            }
            for (s in next.config.iface.dnsSearch) builder.addSearchDomain(s)
            for (r in next.routes) builder.addRoute(r.address, r.length)
            if (Build.VERSION.SDK_INT >= 33) {
                for (r in next.excludedRoutes) builder.excludeRoute(AndroidIpPrefix(r.address, r.length))
            }
            for (pkg in next.excludedApps) runCatching { builder.addDisallowedApplication(pkg) }
            // Our own app stays inside the VPN, so the in-tunnel manifest URL works. Channels that
            // must work while the server is blocked (mirrors, update check) bind to
            // TunnelRuntime.directNetwork() instead.
            if (Build.VERSION.SDK_INT >= 29) builder.setMetered(false)
            builder.setBlocking(true)

            down(keepState = true)
            val tun = builder.establish() ?: throw IllegalStateException(PERMISSION_MISSING)
            val h = AwgNative.turnOn("pn0", tun.detachFd(), uapi, dns?.encode().orEmpty(), if (dns != null) directDnsSocket() else -1)
            if (h < 0) throw IllegalStateException("tunnel_start_failed:$h")
            protect(AwgNative.socketV4(h)); protect(AwgNative.socketV6(h))
            handle = h
            spec = next
            connectedAt = System.currentTimeMillis()
            lastCheck = null
            TunnelRuntime.set(TunnelState.Up(next.serverName, next.serverId, connectedAt))
            scheduleCheck(FIRST_CHECK_MS)
            TunnelRuntime.bumpGeneration()
            startFailures = 0
        } catch (e: Exception) {
            Log.e(TAG, "connect failed", e)
            TunnelRuntime.set(TunnelState.Failed(e.message ?: e.javaClass.simpleName))
            // Without permission only the user can help; anything else may pass (a network that is
            // not up yet after boot, a DNS hiccup): try again with backoff, then give up.
            if (e.message != PERMISSION_MISSING && startFailures < MAX_START_RETRIES) {
                spec = next
                main.removeCallbacks(retryStart)
                main.postDelayed(retryStart, minOf(RETRY_BASE_MS shl startFailures, WATCH_SCREEN_OFF_MS))
                startFailures++
            }
        }
    }

    /** A UDP socket outside the VPN for Russian lookups; the native side takes ownership. */
    private fun directDnsSocket(): Int = runCatching {
        val socket = DatagramSocket()
        protect(socket)
        ParcelFileDescriptor.fromDatagramSocket(socket).detachFd().also { socket.close() }
    }.getOrDefault(-1)

    private fun down(keepState: Boolean = false) {
        main.removeCallbacks(healthCheck)
        if (!keepState) { main.removeCallbacks(retryStart); startFailures = 0 }
        val h = handle
        handle = -1
        if (h >= 0) AwgNative.turnOff(h)
        if (!keepState) { spec = null; TunnelRuntime.set(TunnelState.Down) }
    }

    internal fun stats(): TunnelStats? {
        val h = handle
        if (h < 0) return null
        return AwgNative.getConfig(h)?.let(TunnelRuntime::parseStats)
    }

    // --- health and failover ---------------------------------------------------------------

    private val healthCheck = Runnable { scope.launch { lock.withLock { checkHealth() } } }

    private fun scheduleCheck(delayMs: Long) {
        main.removeCallbacks(healthCheck)
        main.postDelayed(healthCheck, delayMs)
    }

    private fun nextInterval() = if (screenOn) WATCH_SCREEN_ON_MS else WATCH_SCREEN_OFF_MS

    private suspend fun checkHealth() {
        val current = spec ?: return
        // A wedged native side would block here: that is a hang, too.
        val now = withTimeoutOrNull(5_000) { runInterruptible { stats() } }
        if (now == null) {
            // Wedged, or a previous rebuild failed: try again, with backoff, once there is a network.
            if (!networkValidated()) { scheduleCheck(nextInterval()); return }
            failures++
            forceReconnect(host?.currentSpec() ?: current, if (handle >= 0) "stats_timeout" else "not_running")
            return
        }
        val prev = lastCheck
        lastCheck = now
        val handshakeAge = if (now.lastHandshakeEpochSec == 0L) Long.MAX_VALUE
        else System.currentTimeMillis() / 1000 - now.lastHandshakeEpochSec
        val sentWithoutReply = prev != null && now.txBytes > prev.txBytes && now.rxBytes == prev.rxBytes
        val stale = handshakeAge > DEAD_AFTER_SEC
        if (!stale && !sentWithoutReply) {
            alive(current)
            return
        }
        // Nothing to fix while the phone itself is offline.
        if (!networkValidated()) { scheduleCheck(nextInterval()); return }
        val rtt = probe(current)
        if (rtt != null) {
            TunnelRuntime.setRtt(rtt)
            alive(current)
            return
        }
        failures++
        Log.w(TAG, "${current.serverName}: no reply (handshake ${handshakeAge}s ago, failure $failures)")
        if (failures >= FAILOVER_AFTER) {
            val replacement = host?.onServerUnreachable(current)
            if (replacement != null && replacement.serverId != current.serverId) { failures = 0; connect(replacement); return }
        }
        forceReconnect(host?.currentSpec() ?: current, "dead")
    }

    private fun alive(current: TunnelSpec) {
        failures = 0
        host?.onServerHealthy(current)
        scheduleCheck(nextInterval())
    }

    /** Tears the tunnel down and builds it again, then looks sooner than usual (with backoff). */
    private fun forceReconnect(next: TunnelSpec, reason: String) {
        Log.i(TAG, "force reconnect to ${next.serverName}: $reason")
        TunnelRuntime.noteReconnect()
        connect(next)
        // connect() schedules its own first look; a failed rebuild needs another try later.
        if (handle < 0) { spec = spec ?: next; scheduleCheck(minOf(RETRY_BASE_MS shl minOf(failures, 4), WATCH_SCREEN_OFF_MS)) }
    }

    /**
     * One DNS query to a foreign resolver, which is routed through the tunnel: a reply proves the
     * whole path (us → server → internet → back). Returns the round trip in ms, or null.
     */
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

    private fun networkValidated(): Boolean = TunnelRuntime.directNetwork(this) != null

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            // A new network (Wi-Fi ↔ mobile): give the tunnel a moment to re-handshake, then look.
            if (handle >= 0) { lastCheck = null; scheduleCheck(NETWORK_CHANGE_CHECK_MS) }
        }
    }

    // --- battery-aware keepalive -----------------------------------------------------------

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            screenOn = intent.action == Intent.ACTION_SCREEN_ON
            val s = spec ?: return
            val h = handle
            if (h < 0) return
            // The user is about to use the phone: make sure the tunnel survived the sleep.
            if (screenOn) { lastCheck = null; scheduleCheck(SCREEN_ON_CHECK_MS) } else scheduleCheck(WATCH_SCREEN_OFF_MS)
            val ka = if (screenOn) s.keepaliveScreenOn else s.keepaliveScreenOff
            scope.launch {
                val peer = s.config.peers.firstOrNull() ?: return@launch
                // A partial UAPI set changes the live peer without a reconnect.
                AwgNative.setConfig(h, "public_key=${peer.publicKey.toHex()}\nupdate_only=true\npersistent_keepalive_interval=$ka\n")
            }
        }
    }

    companion object {
        private const val TAG = "ParamneziaVpn"
        const val ACTION_START = "net.paramnezia.tunnel.START"
        const val ACTION_STOP = "net.paramnezia.tunnel.STOP"
        private const val FIRST_CHECK_MS = 20_000L
        private const val NETWORK_CHANGE_CHECK_MS = 15_000L
        private const val SCREEN_ON_CHECK_MS = 3_000L
        private const val WATCH_SCREEN_ON_MS = 60_000L
        private const val WATCH_SCREEN_OFF_MS = 15 * 60_000L
        private const val RETRY_BASE_MS = 10_000L
        private const val MAX_START_RETRIES = 8
        private const val PERMISSION_MISSING = "VPN permission missing"
        /** WireGuard renews a session every 2 minutes while traffic flows; 3 minutes means stale. */
        private const val DEAD_AFTER_SEC = 180L
        /** Rebuilds of the same server before asking for another one. */
        private const val FAILOVER_AFTER = 2
        private const val PROBE_TIMEOUT_MS = 3_000
        private val PROBE_RESOLVERS = listOf("1.1.1.1", "8.8.8.8", "9.9.9.9")
        /** DNS query: "." IN NS, recursion desired. Tiny, cacheable, answered by every resolver. */
        private val PROBE_QUERY = byteArrayOf(0, 0, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 2, 0, 1)

        /** False if Android refused (a start from the background on Android 8+). */
        fun start(context: Context): Boolean = runCatching {
            context.startService(Intent(context, ParamneziaVpnService::class.java).setAction(ACTION_START)); true
        }.getOrElse { Log.w(TAG, "start refused", it); false }

        fun stop(context: Context) {
            // A running VPN is bound by the system, so this is allowed even from the background;
            // if the service is not running there is nothing to stop.
            val s = TunnelRuntime.service
            if (s != null) s.stopFromApp()
            else runCatching { context.startService(Intent(context, ParamneziaVpnService::class.java).setAction(ACTION_STOP)) }
        }
    }
}

private fun TunnelConfig.withKeepalive(ka: String) =
    copy(peers = peers.map { it.copy(persistentKeepalive = ka) })
