package io.paramnezia.tunnel

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import io.paramnezia.core.config.TunnelConfig
import io.paramnezia.core.net.IpPrefix
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Everything the VPN service needs to bring a tunnel up. Built by the app, never persisted here. */
data class TunnelSpec(
    /** Stable id of the server, used for failover bookkeeping. */
    val serverId: String,
    val serverName: String,
    val config: TunnelConfig,
    /** Routes into the tunnel. */
    val routes: List<IpPrefix>,
    /** Routes kept out of the tunnel (Android 13+ only). */
    val excludedRoutes: List<IpPrefix>,
    val excludedApps: List<String>,
    /** Keepalive while the screen is on / off, UAPI syntax (`"25"`, `"25-35"`, `"0"` = off). */
    val keepaliveScreenOn: String,
    val keepaliveScreenOff: String,
    /** Tunnel addresses of other trusted devices, for in-tunnel gossip. */
    val gossipPeers: List<String> = emptyList(),
    /** Split DNS with tracker blocking; null leaves DNS to the config. */
    val dns: DnsPolicy? = null,
)

/**
 * Apps resolve through [virtual], an address answered inside the tun device: blocked names get an
 * empty answer, Russian names go to [direct] resolvers outside the tunnel, the rest to [tunnel]
 * resolvers through it. The rules themselves are set once with [TunnelRuntime.setDnsRules].
 */
data class DnsPolicy(
    val tunnel: List<String>,
    val direct: List<String> = listOf("77.88.8.8", "77.88.8.1"),
    val virtual: String = "198.18.0.53",
) {
    internal fun encode() = "virtual=$virtual\ntunnel=${tunnel.joinToString(",")}\ndirect=${direct.joinToString(",")}"
}

/** The app side of the service: supplies configs and hears about health and gossip. */
interface TunnelHost {
    /** Spec to connect with now (also used when Always-on VPN starts the service). */
    suspend fun currentSpec(): TunnelSpec?

    /** The current server looks blocked or dead; return the spec to switch to, or null to stay. */
    suspend fun onServerUnreachable(spec: TunnelSpec): TunnelSpec?

    /** Handshakes work again on [spec]'s server. */
    fun onServerHealthy(spec: TunnelSpec) {}

    /** A gossip datagram from another trusted device (source is its tunnel address). */
    fun onGossip(from: String, payload: ByteArray) {}
}

sealed interface TunnelState {
    data object Down : TunnelState
    data class Connecting(val serverName: String) : TunnelState
    data class Up(val serverName: String, val serverId: String, val since: Long) : TunnelState
    data class Failed(val reason: String) : TunnelState
}

data class TunnelStats(val rxBytes: Long, val txBytes: Long, val lastHandshakeEpochSec: Long)

/** Process-wide tunnel state for the UI and the app's workers. */
object TunnelRuntime {
    private val _state = MutableStateFlow<TunnelState>(TunnelState.Down)
    val state: StateFlow<TunnelState> = _state.asStateFlow()

    @Volatile internal var service: ParamneziaVpnService? = null

    internal fun set(s: TunnelState) { _state.value = s }

    /** Reads counters on demand (call it only while something is on screen). */
    fun stats(): TunnelStats? = service?.stats()

    /** Sends a gossip datagram to other devices through the tunnel; no-op when down. */
    fun gossip(payload: ByteArray, to: List<String>) { service?.sendGossip(payload, to) }

    /**
     * Domain rules for the DNS filter, one per line (`full:` for an exact name): [block] gets empty
     * answers, [direct] is resolved outside the tunnel, [allow] overrides [block]. Takes effect at once.
     */
    fun setDnsRules(block: String, direct: String, allow: String = "") = AwgNative.setDnsRules(block, direct, allow)

    /** Ad and tracker lookups blocked since the app started. */
    fun blockedQueries(): Long = runCatching { AwgNative.dnsBlocked() }.getOrDefault(0)

    /** Rebuilds the tunnel with a fresh spec from the host (after a manifest or settings change). */
    fun reload() { service?.reload() }

    /**
     * The validated physical network, bypassing our VPN. Bind sockets to it (OkHttp
     * `socketFactory(network.socketFactory)`) for channels that must work while the tunnel is dead.
     * A VPN app may use the underlying network even in lockdown mode.
     */
    @Suppress("DEPRECATION")
    fun directNetwork(context: Context): Network? {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        return cm.allNetworks.firstOrNull { n ->
            val c = cm.getNetworkCapabilities(n) ?: return@firstOrNull false
            !c.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }
    }

    internal fun parseStats(uapi: String): TunnelStats {
        var rx = 0L; var tx = 0L; var hs = 0L
        for (line in uapi.lineSequence()) {
            val k = line.substringBefore('='); val v = line.substringAfter('=', "")
            when (k) {
                "rx_bytes" -> rx += v.toLongOrNull() ?: 0
                "tx_bytes" -> tx += v.toLongOrNull() ?: 0
                "last_handshake_time_sec" -> hs = maxOf(hs, v.toLongOrNull() ?: 0)
            }
        }
        return TunnelStats(rx, tx, hs)
    }
}
