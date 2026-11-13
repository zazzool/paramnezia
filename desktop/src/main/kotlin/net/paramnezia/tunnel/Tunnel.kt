package net.paramnezia.tunnel

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import net.paramnezia.core.config.TunnelConfig
import net.paramnezia.core.net.IpPrefix

/*
 * The Windows counterpart of the Android `tunnel` module's public types. The shared screens
 * (app/src/main/kotlin/net/paramnezia/app/ui) read the tunnel through exactly these names, so they
 * compile unchanged on both systems. Here the tunnel itself runs in the Paramnezia service
 * (tunnel/src/main/go/windows); [net.paramnezia.desktop.TunnelEngine] drives it.
 */

/** Everything the service needs to bring a tunnel up. Built by the app, never persisted. */
data class TunnelSpec(
    val serverId: String,
    val serverName: String,
    val config: TunnelConfig,
    /** Routes into the tunnel: everything except Russian and local networks (and the server itself). */
    val routes: List<IpPrefix>,
    /** Keepalive while the computer is in use / idle, UAPI syntax (`"25"`, `"25-35"`, `"0"`). */
    val keepaliveActive: String,
    val keepaliveIdle: String,
    /** Split DNS with tracker blocking; null leaves DNS to the config. */
    val dns: DnsPolicy? = null,
    /** Programs past the VPN, by file name ("telegram.exe"). */
    val excludedApps: List<String> = emptyList(),
)

/** Same meaning as on Android: blocked names, Russian names outside, the rest through the tunnel. */
data class DnsPolicy(
    val tunnel: List<String>,
    val direct: List<String> = listOf("77.88.8.8", "77.88.8.1"),
    val virtual: String = "198.18.0.53",
) {
    fun encode() = "virtual=$virtual\ntunnel=${tunnel.joinToString(",")}\ndirect=${direct.joinToString(",")}"
}

/** Same meaning as on Android: [Up] only once a probe came back through the tunnel. */
sealed interface TunnelState {
    data object Down : TunnelState
    data class Connecting(val serverName: String, val reconnecting: Boolean = false) : TunnelState
    data class Up(val serverName: String, val serverId: String, val since: Long) : TunnelState
    data class Failed(val reason: String) : TunnelState
}

data class TunnelStats(val rxBytes: Long, val txBytes: Long, val lastHandshakeEpochSec: Long)

data class TunnelHealth(
    val rttMs: Long? = null,
    val checkedAt: Long = 0,
    val reconnects: Int = 0,
    val lastReconnectAt: Long = 0,
    val unstable: Boolean = false,
    val offline: Boolean = false,
)

object TunnelRuntime {
    private val _state = MutableStateFlow<TunnelState>(TunnelState.Down)
    val state: StateFlow<TunnelState> = _state.asStateFlow()
    fun set(s: TunnelState) { _state.value = s }

    private val _health = MutableStateFlow(TunnelHealth())
    val health: StateFlow<TunnelHealth> = _health.asStateFlow()
    fun setRtt(ms: Long) { _health.value = _health.value.copy(rttMs = ms, checkedAt = System.currentTimeMillis()) }
    fun noteReconnect() { _health.value = _health.value.copy(reconnects = _health.value.reconnects + 1, lastReconnectAt = System.currentTimeMillis()) }
    fun setLink(unstable: Boolean, offline: Boolean) {
        val h = _health.value
        if (h.unstable != unstable || h.offline != offline) _health.value = h.copy(unstable = unstable, offline = offline)
    }

    /** Set by the engine: probe now and repair if needed (see the Android TunnelRuntime.recheck). */
    @Volatile var onRecheck: (() -> Unit)? = null
    fun recheck() { onRecheck?.invoke() }

    private val _generation = MutableStateFlow(0)
    val generation: StateFlow<Int> = _generation.asStateFlow()
    fun bumpGeneration() { _generation.value++ }

    /** Last counters the engine read from the service (refreshed every 2 s while the window is open). */
    @Volatile var lastStats: TunnelStats? = null
    @Volatile var lastBlocked: Long = 0

    /** Never blocks the UI thread: the pipe is read by the engine, here only the latest answer. */
    fun stats(): TunnelStats? = lastStats.takeIf { _state.value is TunnelState.Up }

    fun blockedQueries(): Long = lastBlocked

    fun parseStats(uapi: String): TunnelStats {
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
