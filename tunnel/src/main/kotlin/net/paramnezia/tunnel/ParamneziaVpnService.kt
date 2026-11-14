package net.paramnezia.tunnel

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.IpPrefix as AndroidIpPrefix
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.Log
import net.paramnezia.core.config.Uapi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicInteger
import net.paramnezia.core.config.TunnelConfig
import net.paramnezia.core.net.LinkProbe
import net.paramnezia.core.net.LinkWatch

/**
 * The VPN service. Designed to cost nothing while idle and to tell the truth about the connection:
 *  - "connected" is shown only after a probe came back through the freshly built tunnel (a WireGuard
 *    device that is up says nothing about the server answering); until then it is "connecting";
 *  - a watchdog, not a polling loop: it reads the tunnel's own counters (no network traffic) every
 *    minute with the screen on and every 15 minutes with it off, on the main looper, so it simply
 *    waits while the phone sleeps. Traffic that came back proves the path by itself; otherwise one
 *    tiny probe goes out. Failures in a row climb a ladder ([LinkWatch]): a second look, fresh
 *    sockets in the same tunnel, a full rebuild, another server;
 *  - a rebuild establishes the new interface before the old one is dropped, so apps never see a
 *    moment without the VPN (that moment is when traffic used to leave with the real address);
 *  - keepalive is lowered while the screen is off;
 *  - "off" means off at once, at any stage: a stop does not queue behind a connect, a probe or a
 *    failover that is still running. It cancels them, shows "off" immediately and tears down
 *    whatever they had built; the server's address is resolved cancellably for the same reason.
 */
class ParamneziaVpnService : VpnService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private val main = Handler(Looper.getMainLooper())

    private var handle = -1
    private var spec: TunnelSpec? = null
    /** Start of the session shown to the user; automatic repairs of the same server keep it. */
    private var sessionSince = 0L
    /** When the current tunnel was built (Android connects its network a moment later). */
    private var builtAt = 0L
    private var screenOn = true
    private val watch = LinkWatch()

    private val host get() = application as? TunnelHost

    /**
     * Bumped by every stop: work queued before it (a connect, a scheduled check, a retry) finds a
     * newer epoch and does nothing, so nothing can bring the tunnel back after the user turned it off.
     */
    private val epoch = AtomicInteger()
    /** Start requests seen; a stop that finishes after a newer start leaves the service running. */
    private val starts = AtomicInteger()

    /** Runs [block] under the lock unless a stop came in since it was asked for. */
    private fun work(block: suspend () -> Unit) {
        val e = epoch.get()
        scope.launch { lock.withLock { if (e == epoch.get()) block() } }
    }

    override fun onCreate() {
        super.onCreate()
        TunnelRuntime.service = this
        registerReceiver(screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF)
        })
        val cm = getSystemService(ConnectivityManager::class.java)
        // The physical network under the tunnel (our own app's default network is the VPN itself).
        val physical = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        runCatching {
            if (Build.VERSION.SDK_INT >= 31) cm.registerBestMatchingNetworkCallback(physical, networkCallback, main)
            else cm.registerNetworkCallback(physical, networkCallback)
        }
        runCatching {
            cm.registerNetworkCallback(NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
                .build(), lanCallback)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopNow()
            return START_NOT_STICKY
        }
        // ACTION_START from the app, or a null/"android.net.VpnService" intent from Always-on VPN.
        starts.incrementAndGet()
        work { connect(host?.currentSpec()) }
        return START_STICKY
    }

    override fun onRevoke() {
        // Another VPN took over or the user revoked us in settings.
        stopNow()
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(screenReceiver) }
        runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(networkCallback) }
        runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(lanCallback) }
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
        work { connect(host?.currentSpec(), reconnecting = handle >= 0, quiet = true) }
    }

    internal fun stopFromApp() = stopNow()

    /**
     * Off, now, whatever is going on. Whatever holds the lock (resolving the server, a probe, a
     * failover waiting for the manifest) is cancelled, scheduled work is dropped, the screen says
     * "off" at once, and the teardown follows as soon as the lock is free (milliseconds).
     */
    private fun stopNow() {
        epoch.incrementAndGet()
        main.removeCallbacks(healthCheck); main.removeCallbacks(rebuildLater); main.removeCallbacks(retryStart); main.removeCallbacks(lanCheck)
        scope.coroutineContext.cancelChildren()
        TunnelRuntime.set(TunnelState.Down)
        val startsAtStop = starts.get()
        scope.launch {
            lock.withLock { down() }
            if (starts.get() == startsAtStop) stopSelf()
        }
    }

    /** Failed starts in a row (no network yet, DNS of the endpoint, a native error); reset on success. */
    private var startFailures = 0
    private val retryStart = Runnable { work { if (handle < 0) connect(host?.currentSpec()) } }

    /**
     * The server's address, resolved on a side thread: a stop does not wait for a slow resolver
     * (the lookup finishes in the background and is dropped).
     */
    private suspend fun resolve(name: String): InetAddress = withTimeout(RESOLVE_TIMEOUT_MS) {
        suspendCancellableCoroutine<InetAddress> { c ->
            Thread({ val r = runCatching { InetAddress.getByName(name) }; if (c.isActive) c.resumeWith(r) }, "pn-resolve")
                .apply { isDaemon = true; start() }
        }
    }

    private suspend fun resolveEndpoints(config: TunnelConfig): Map<String, InetAddress> {
        val out = HashMap<String, InetAddress>()
        for (p in config.peers) {
            val host = p.endpoint?.let { runCatching { Uapi.splitEndpoint(it).first }.getOrNull() } ?: continue
            if (host !in out) out[host] = resolve(host)
        }
        return out
    }

    /**
     * Builds (or rebuilds) the tunnel and starts verifying it. [reconnecting]: a repair or reload of a
     * running session. [quiet]: a deliberate rebuild of a working tunnel to the same server (new
     * settings, new local network) keeps showing "connected" while it is verified, instead of
     * flashing "connecting" for a second; if the verification fails the watchdog says so.
     */
    private suspend fun connect(next: TunnelSpec?, reconnecting: Boolean = false, quiet: Boolean = false) {
        currentCoroutineContext().ensureActive()
        val e = epoch.get()
        if (next == null) {
            TunnelRuntime.set(TunnelState.Failed("no_profile"))
            down()
            stopSelf()
            return
        }
        val sameServer = spec?.serverId == next.serverId
        val repair = reconnecting && handle >= 0
        val keepUp = quiet && sameServer && (TunnelRuntime.state.value as? TunnelState.Up)?.serverId == next.serverId
        if (!keepUp) TunnelRuntime.set(TunnelState.Connecting(next.serverName, reconnecting = repair))
        try {
            // Resolve before touching the old tunnel, so a DNS failure keeps the current connection.
            val resolved = resolveEndpoints(next.config)
            val uapi = Uapi.write(next.config.withKeepalive(if (screenOn) next.keepaliveScreenOn else next.keepaliveScreenOff)) { resolved[it] ?: InetAddress.getByName(it) }
            currentCoroutineContext().ensureActive()
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
            // The local network is never in the tunnel (see RoutePlanner.LOCAL_V4); its router
            // also answers local names (nas.lan, printer.local) behind our DNS filter.
            TunnelRuntime.setLocalDns(TunnelRuntime.localNetwork(this).dns)
            // Our own app stays inside the VPN, so the in-tunnel manifest URL works. Channels that
            // must work while the server is blocked (mirrors, update check) bind to
            // TunnelRuntime.directNetwork() instead.
            if (Build.VERSION.SDK_INT >= 29) builder.setMetered(false)
            builder.setBlocking(true)

            main.removeCallbacks(healthCheck); main.removeCallbacks(rebuildLater)
            // The new interface first: Android moves the VPN onto it in place. Dropping the old tunnel
            // first would leave a gap without any VPN, and apps' traffic would leave directly.
            val tun = builder.establish() ?: throw IllegalStateException(PERMISSION_MISSING)
            val old = handle
            handle = -1
            if (old >= 0) AwgNative.turnOff(old)
            val h = AwgNative.turnOn("pn0", tun.detachFd(), uapi, dns?.encode().orEmpty(), if (dns != null) directDnsSocket() else -1)
            if (h < 0) throw IllegalStateException("tunnel_start_failed:$h")
            // Turned off while the native side was starting: undo it rather than leave it running.
            if (e != epoch.get()) { AwgNative.turnOff(h); return }
            protect(AwgNative.socketV4(h)); protect(AwgNative.socketV6(h))
            handle = h
            spec = next
            val now = System.currentTimeMillis()
            builtAt = now
            if (!(reconnecting && sameServer) || sessionSince == 0L) sessionSince = now
            watch.started(now, sameServer = reconnecting && sameServer)
            TunnelRuntime.setLink(unstable = repair, offline = false)
            // Verified (and shown as connected) by the first probe that comes back.
            scheduleCheck(VERIFY_FIRST_MS)
            startFailures = 0
        } catch (c: CancellationException) {
            throw c
        } catch (x: Exception) {
            if (e != epoch.get()) return
            Log.e(TAG, "connect failed", x)
            // Without permission only the user can help; anything else may pass (a network that is
            // not up yet after boot, a DNS hiccup): try again with backoff, then give up.
            if (x.message != PERMISSION_MISSING && startFailures < MAX_START_RETRIES) {
                val wait = minOf(RETRY_BASE_MS shl startFailures, WATCH_SCREEN_OFF_MS)
                TunnelRuntime.set(TunnelState.Failed(x.message ?: x.javaClass.simpleName, retryAt = System.currentTimeMillis() + wait))
                spec = next
                main.removeCallbacks(retryStart)
                main.postDelayed(retryStart, wait)
                startFailures++
            } else TunnelRuntime.set(TunnelState.Failed(x.message ?: x.javaClass.simpleName))
        }
    }

    /** A UDP socket outside the VPN for Russian lookups; the native side takes ownership. */
    private fun directDnsSocket(): Int = runCatching {
        val socket = DatagramSocket()
        protect(socket)
        ParcelFileDescriptor.fromDatagramSocket(socket).detachFd().also { socket.close() }
    }.getOrDefault(-1)

    private fun down() {
        main.removeCallbacks(healthCheck); main.removeCallbacks(rebuildLater); main.removeCallbacks(retryStart)
        startFailures = 0
        val h = handle
        handle = -1
        if (h >= 0) AwgNative.turnOff(h)
        spec = null
        sessionSince = 0
        TunnelRuntime.setLink(unstable = false, offline = false)
        TunnelRuntime.set(TunnelState.Down)
    }

    internal fun stats(): TunnelStats? {
        val h = handle
        if (h < 0) return null
        return AwgNative.getConfig(h)?.let(TunnelRuntime::parseStats)
    }

    // --- health and failover ---------------------------------------------------------------

    private val healthCheck = Runnable { work { checkHealth() } }
    private val rebuildLater = Runnable { work { spec?.let { rebuild(it) } } }

    private fun scheduleCheck(delayMs: Long) {
        main.removeCallbacks(healthCheck)
        main.postDelayed(healthCheck, delayMs)
    }

    internal fun recheck() {
        work { if (handle >= 0) { watch.suspect(); scheduleCheck(0) } }
    }

    private suspend fun checkHealth() {
        val current = spec ?: return
        if (handle < 0) {
            // A previous rebuild failed: try again once there is a network.
            if (!networkValidated()) { offline(); return }
            rebuild(current)
            return
        }
        // A wedged native side would block here: that is a failure, too.
        val counters = withTimeoutOrNull(5_000) { runInterruptible { stats() } }
        val now = System.currentTimeMillis()
        if (counters == null && !networkValidated()) { offline(); return }
        var action = watch.onCounters(now, counters?.let { LinkWatch.Counters(it.rxBytes, it.txBytes, it.lastHandshakeEpochSec) }, screenOn)
        if (action == LinkWatch.Action.Probe) {
            // Nothing to fix (and nothing to count) while the phone itself is offline.
            if (!networkValidated()) { offline(); return }
            // The probe must go through the tunnel, so it is bound to the VPN network. Android connects
            // that network a moment after the tunnel is built: until then there is nothing to probe yet.
            val vpn = TunnelRuntime.vpnNetwork(this)
            if (vpn == null && System.currentTimeMillis() - builtAt < VPN_WAIT_MS) { scheduleCheck(VPN_NOT_READY_MS); return }
            // Never connected by Android: counts as a failed probe (the ladder rebuilds the tunnel).
            val rtt = if (vpn == null) null else runInterruptible { LinkProbe.run(probeTargets(current), sockets = boundTo(vpn)) }
            action = watch.onProbe(System.currentTimeMillis(), rtt, screenOn)
            watch.rttMs?.takeIf { rtt != null }?.let(TunnelRuntime::setRtt)
            if (rtt == null) Log.w(TAG, "${current.serverName}: no reply (${watch.phase}, failure ${watch.failures}, builds ${watch.failedBuilds})")
        }
        act(current, action)
    }

    private fun offline() {
        TunnelRuntime.setLink(unstable = TunnelRuntime.health.value.unstable, offline = true)
        scheduleCheck(watch.offline(screenOn).ms)
    }

    private suspend fun act(current: TunnelSpec, action: LinkWatch.Action) {
        TunnelRuntime.setLink(unstable = watch.phase == LinkWatch.Phase.UNSTABLE, offline = false)
        when (action) {
            is LinkWatch.Action.Wait -> {
                // Fleet devices pick up the admin's changes within the hour while the tunnel works.
                if (watch.phase == LinkWatch.Phase.GOOD) host?.onServerHealthy(current)
                scheduleCheck(action.ms)
            }
            LinkWatch.Action.Probe -> scheduleCheck(0)
            is LinkWatch.Action.Verified -> {
                TunnelRuntime.set(TunnelState.Up(current.serverName, current.serverId, sessionSince))
                TunnelRuntime.bumpGeneration()
                host?.onServerHealthy(current)
                scheduleCheck(action.ms)
            }
            is LinkWatch.Action.Rebind -> {
                // Fresh sockets in the same tunnel: routes stay, nothing leaves past the VPN.
                val h = handle
                Log.i(TAG, "${current.serverName}: fresh sockets")
                if (h >= 0 && AwgNative.rebind(h) == 0) {
                    protect(AwgNative.socketV4(h)); protect(AwgNative.socketV6(h))
                    TunnelRuntime.noteReconnect()
                }
                watch.rebound()
                scheduleCheck(action.ms)
            }
            is LinkWatch.Action.Rebuild -> rebuildAfter(current, action.delayMs)
            is LinkWatch.Action.Failover -> {
                val replacement = host?.onServerUnreachable(current)
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
        // The old tunnel stays in place meanwhile: apps wait instead of leaking past it.
        TunnelRuntime.set(TunnelState.Connecting(current.serverName, reconnecting = true))
        main.removeCallbacks(rebuildLater)
        main.postDelayed(rebuildLater, delayMs)
    }

    /** Builds the tunnel again with a fresh spec (fresh sockets, new handshake) and verifies it. */
    private suspend fun rebuild(current: TunnelSpec) {
        val next = host?.currentSpec() ?: current
        Log.i(TAG, "rebuild ${next.serverName}")
        TunnelRuntime.noteReconnect()
        connect(next, reconnecting = true)
        // connect() schedules its own first look; a failed build needs another try later.
        if (handle < 0) { spec = spec ?: next; scheduleCheck(minOf(RETRY_BASE_MS shl minOf(watch.failedBuilds, 4), WATCH_SCREEN_OFF_MS)) }
    }

    private fun boundTo(network: Network) = object : LinkProbe.Sockets() {
        override fun udp(): DatagramSocket = DatagramSocket().also { network.bindSocket(it) }
        override fun tcp(): java.net.Socket = network.socketFactory.createSocket()
    }

    /** The tunnel's own resolvers first (they answer only through it), then public ones. */
    private fun probeTargets(s: TunnelSpec): List<String> = (s.dns?.tunnel.orEmpty() + LinkProbe.DNS_TARGETS).distinct()

    private fun networkValidated(): Boolean = TunnelRuntime.directNetwork(this) != null

    /** The physical network last seen under the tunnel. */
    @Volatile private var underlying: Network? = null

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            val changed = underlying != null && underlying != network
            underlying = network
            // Wi-Fi ↔ mobile under a working tunnel: give it a moment to re-handshake, then probe. A
            // fresh tunnel is being verified anyway; its quick verification is not pushed back.
            if (changed) work { if (handle >= 0 && TunnelRuntime.state.value is TunnelState.Up) { watch.suspect(); scheduleCheck(NETWORK_CHANGE_CHECK_MS) } }
        }
    }

    // --- local network ----------------------------------------------------------------------

    /**
     * Wi-Fi or Ethernet came, went or changed. The router's address for local names is updated in
     * place; only when the network itself sits on addresses outside the private ranges (public
     * addresses at some providers and hotels, the provider's IPv6 /64) do the routes change, and
     * then the tunnel is rebuilt so those addresses stay reachable directly too.
     */
    private val lanCheck = Runnable {
        work {
            val current = spec ?: return@work
            if (handle < 0) return@work
            val local = TunnelRuntime.localNetwork(this@ParamneziaVpnService)
            TunnelRuntime.setLocalDns(local.dns)
            if (local.prefixes != current.lan) {
                Log.i(TAG, "local network changed: ${local.prefixes}, rebuilding routes")
                connect(host?.currentSpec() ?: return@work, reconnecting = true, quiet = true)
            }
        }
    }

    private val lanCallback = object : ConnectivityManager.NetworkCallback() {
        private fun changed() { main.removeCallbacks(lanCheck); main.postDelayed(lanCheck, LAN_SETTLE_MS) }
        override fun onAvailable(network: Network) = changed()
        override fun onLost(network: Network) = changed()
        override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) = changed()
    }

    // --- battery-aware keepalive -----------------------------------------------------------

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            screenOn = intent.action == Intent.ACTION_SCREEN_ON
            val s = spec ?: return
            val h = handle
            if (h < 0) return
            // The user is about to use the phone: make sure the tunnel survived the sleep.
            // A repair in progress keeps its quick pace either way.
            if (TunnelRuntime.state.value is TunnelState.Up && watch.phase == LinkWatch.Phase.GOOD)
                scheduleCheck(if (screenOn) SCREEN_ON_CHECK_MS else watch.interval(false))
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
        /** First verification probe of a fresh tunnel: right after its first handshake could finish. */
        private const val VERIFY_FIRST_MS = 150L
        private const val NETWORK_CHANGE_CHECK_MS = 3_000L
        /** Look again this soon while Android is still connecting the VPN network. */
        private const val VPN_NOT_READY_MS = 200L
        private const val VPN_WAIT_MS = 8_000L
        private const val SCREEN_ON_CHECK_MS = 3_000L
        /** Link properties arrive in bursts while Wi-Fi connects; look once they settle. */
        private const val LAN_SETTLE_MS = 2_000L
        private const val WATCH_SCREEN_OFF_MS = 15 * 60_000L
        private const val RETRY_BASE_MS = 10_000L
        private const val MAX_START_RETRIES = 8
        private const val PERMISSION_MISSING = "VPN permission missing"
        private const val RESOLVE_TIMEOUT_MS = 15_000L

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
