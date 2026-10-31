package io.paramnezia.tunnel

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
import io.paramnezia.core.config.Uapi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress

/**
 * The VPN service. Designed to cost nothing while idle:
 *  - no polling loop: one delayed health check after (re)connect or a network change, then a check
 *    every 15 minutes, both on the main looper so they simply wait while the phone sleeps;
 *  - keepalive is lowered while the screen is off;
 *  - the gossip socket blocks in receive and wakes only for incoming packets.
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
    private var gossipSocket: DatagramSocket? = null

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
        gossipSocket?.close()
        TunnelRuntime.service = null
        TunnelRuntime.set(TunnelState.Down)
        scope.cancel()
        super.onDestroy()
    }

    internal fun reload() {
        scope.launch { lock.withLock { connect(host?.currentSpec()) } }
    }

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
            // Our own app stays inside the VPN, so gossip and the in-tunnel mirror work. Channels that
            // must work while the server is blocked (relays, mirrors, updates) bind to
            // TunnelRuntime.directNetwork() instead.
            if (Build.VERSION.SDK_INT >= 29) builder.setMetered(false)
            builder.setBlocking(true)

            down(keepState = true)
            val tun = builder.establish() ?: throw IllegalStateException("VPN permission missing")
            val h = AwgNative.turnOn("pn0", tun.detachFd(), uapi, dns?.encode().orEmpty(), if (dns != null) directDnsSocket() else -1)
            if (h < 0) throw IllegalStateException("tunnel_start_failed:$h")
            protect(AwgNative.socketV4(h)); protect(AwgNative.socketV6(h))
            handle = h
            spec = next
            connectedAt = System.currentTimeMillis()
            lastCheck = null
            TunnelRuntime.set(TunnelState.Up(next.serverName, next.serverId, connectedAt))
            openGossip(next)
            scheduleCheck(FIRST_CHECK_MS)
        } catch (e: Exception) {
            Log.e(TAG, "connect failed", e)
            TunnelRuntime.set(TunnelState.Failed(e.message ?: e.javaClass.simpleName))
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
        gossipSocket?.close(); gossipSocket = null
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

    private suspend fun checkHealth() {
        val current = spec ?: return
        val now = stats() ?: return
        val prev = lastCheck
        lastCheck = now
        val handshakeAge = if (now.lastHandshakeEpochSec == 0L) Long.MAX_VALUE
        else System.currentTimeMillis() / 1000 - now.lastHandshakeEpochSec
        // Dead = we kept sending, nothing came back and the session is stale. Idle tunnels are fine.
        val sentWithoutReply = prev == null || (now.txBytes > prev.txBytes && now.rxBytes == prev.rxBytes)
        if (handshakeAge > DEAD_AFTER_SEC && sentWithoutReply && networkValidated()) {
            val replacement = host?.onServerUnreachable(current)
            if (replacement != null) { connect(replacement); return }
        } else if (handshakeAge <= DEAD_AFTER_SEC) {
            host?.onServerHealthy(current)
        }
        scheduleCheck(PERIODIC_CHECK_MS)
    }

    private fun networkValidated(): Boolean = TunnelRuntime.directNetwork(this) != null

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            // A new network: give the tunnel time to re-handshake, then look once.
            if (handle >= 0) { lastCheck = null; scheduleCheck(FIRST_CHECK_MS) }
        }
    }

    // --- battery-aware keepalive -----------------------------------------------------------

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            screenOn = intent.action == Intent.ACTION_SCREEN_ON
            val s = spec ?: return
            val h = handle
            if (h < 0) return
            val ka = if (screenOn) s.keepaliveScreenOn else s.keepaliveScreenOff
            scope.launch {
                val peer = s.config.peers.firstOrNull() ?: return@launch
                // A partial UAPI set changes the live peer without a reconnect.
                AwgNative.setConfig(h, "public_key=${peer.publicKey.toHex()}\nupdate_only=true\npersistent_keepalive_interval=$ka\n")
            }
        }
    }

    // --- in-tunnel gossip -------------------------------------------------------------------

    private fun openGossip(s: TunnelSpec) {
        if (s.gossipPeers.isEmpty()) return
        val local = s.config.iface.addresses.firstOrNull { !it.isV6 }?.address ?: return
        val socket = runCatching { DatagramSocket(InetSocketAddress(local, GOSSIP_PORT)) }.getOrNull() ?: return
        gossipSocket = socket
        Thread({
            val buf = ByteArray(65_507)
            while (!socket.isClosed) {
                val p = DatagramPacket(buf, buf.size)
                try { socket.receive(p) } catch (_: Exception) { break }
                val from = p.address.hostAddress ?: continue
                if (from in s.gossipPeers) host?.onGossip(from, p.data.copyOfRange(p.offset, p.offset + p.length))
            }
        }, "pn-gossip").apply { isDaemon = true }.start()
    }

    internal fun sendGossip(payload: ByteArray, to: List<String>) {
        val socket = gossipSocket ?: return
        scope.launch {
            for (addr in to) runCatching {
                socket.send(DatagramPacket(payload, payload.size, InetAddress.getByName(addr), GOSSIP_PORT))
            }
        }
    }

    companion object {
        private const val TAG = "ParamneziaVpn"
        const val ACTION_START = "io.paramnezia.tunnel.START"
        const val ACTION_STOP = "io.paramnezia.tunnel.STOP"
        const val GOSSIP_PORT = io.paramnezia.core.fleet.GossipMessage.PORT
        private const val FIRST_CHECK_MS = 150_000L
        private const val PERIODIC_CHECK_MS = 15 * 60_000L
        private const val DEAD_AFTER_SEC = 180L

        fun start(context: Context) {
            context.startService(Intent(context, ParamneziaVpnService::class.java).setAction(ACTION_START))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, ParamneziaVpnService::class.java).setAction(ACTION_STOP))
        }
    }
}

private fun io.paramnezia.core.config.TunnelConfig.withKeepalive(ka: String) =
    copy(peers = peers.map { it.copy(persistentKeepalive = ka) })
