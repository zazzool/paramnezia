package io.paramnezia.tunnel

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
)

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

    /** Rebuilds the tunnel with a fresh spec from the host (after a manifest or settings change). */
    fun reload() { service?.reload() }

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
