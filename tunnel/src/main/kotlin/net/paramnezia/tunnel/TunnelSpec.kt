package net.paramnezia.tunnel

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import net.paramnezia.core.config.TunnelConfig
import net.paramnezia.core.net.IpPrefix
import net.paramnezia.core.routing.RoutePlanner
import java.net.Inet4Address
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
    /** Split DNS with tracker blocking; null leaves DNS to the config. */
    val dns: DnsPolicy? = null,
    /**
     * Networks of the current Wi-Fi/Ethernet that went direct on top of the private ranges
     * ([LocalNetwork.prefixes] when the spec was built). The service rebuilds the tunnel when they change.
     */
    val lan: List<IpPrefix> = emptyList(),
)

/**
 * The Wi-Fi or Ethernet the phone is on: [prefixes] are its own networks that the always-direct
 * private ranges do not cover ([RoutePlanner.extraLan]), [dns] its IPv4 resolvers (normally the
 * router), which answer local names such as nas.lan. Empty on mobile data.
 */
data class LocalNetwork(val prefixes: List<IpPrefix>, val dns: List<String>)

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

/** The app side of the service: supplies configs and hears about server health. */
interface TunnelHost {
    /** Spec to connect with now (also used when Always-on VPN starts the service). */
    suspend fun currentSpec(): TunnelSpec?

    /** The current server looks blocked or dead; return the spec to switch to, or null to stay. */
    suspend fun onServerUnreachable(spec: TunnelSpec): TunnelSpec?

    /** Handshakes work again on [spec]'s server. */
    fun onServerHealthy(spec: TunnelSpec) {}
}

/**
 * What the user should be told. [Up] means proven: a probe came back through the tunnel after it was
 * built, never merely "the interface exists". While a fresh or rebuilt tunnel is being verified the
 * state is [Connecting]; [Connecting.reconnecting] says it is a repair of a session that worked.
 */
sealed interface TunnelState {
    data object Down : TunnelState
    data class Connecting(val serverName: String, val reconnecting: Boolean = false) : TunnelState
    /** [since]: start of the session; automatic repairs of the same server keep it. */
    data class Up(val serverName: String, val serverId: String, val since: Long) : TunnelState
    data class Failed(val reason: String) : TunnelState
}

data class TunnelStats(val rxBytes: Long, val txBytes: Long, val lastHandshakeEpochSec: Long)

/** Process-wide tunnel state for the UI and the app's workers. */
data class TunnelHealth(
    val rttMs: Long? = null,
    val checkedAt: Long = 0,
    val reconnects: Int = 0,
    val lastReconnectAt: Long = 0,
    /** The last probe got no answer; the watchdog is repairing (traffic stays in the tunnel meanwhile). */
    val unstable: Boolean = false,
    /** The device itself has no working network: nothing to repair until it comes back. */
    val offline: Boolean = false,
)

object TunnelRuntime {
    private val _state = MutableStateFlow<TunnelState>(TunnelState.Down)
    val state: StateFlow<TunnelState> = _state.asStateFlow()

    @Volatile internal var service: ParamneziaVpnService? = null

    internal fun set(s: TunnelState) { _state.value = s }

    private val _health = MutableStateFlow(TunnelHealth())
    /** What the watchdog last saw: probe round trip and how often it had to rebuild the tunnel. */
    val health: StateFlow<TunnelHealth> = _health.asStateFlow()
    internal fun setRtt(ms: Long) { _health.value = _health.value.copy(rttMs = ms, checkedAt = System.currentTimeMillis()) }
    internal fun noteReconnect() { _health.value = _health.value.copy(reconnects = _health.value.reconnects + 1, lastReconnectAt = System.currentTimeMillis()) }
    internal fun setLink(unstable: Boolean, offline: Boolean) {
        val h = _health.value
        if (h.unstable != unstable || h.offline != offline) _health.value = h.copy(unstable = unstable, offline = offline)
    }

    private val _generation = MutableStateFlow(0)
    /** Changes on every successful (re)connect: a signal to refresh what depends on the route. */
    val generation: StateFlow<Int> = _generation.asStateFlow()
    internal fun bumpGeneration() { _generation.value++ }

    /** Reads counters on demand (call it only while something is on screen). */
    fun stats(): TunnelStats? = service?.stats()

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
     * Something outside the watchdog saw a problem (e.g. a site saw the real address): probe now and
     * repair if needed. Costs one tiny probe when all is well.
     */
    fun recheck() { service?.recheck() }

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

    /**
     * Our own VPN network, once Android has it connected (up to a second or two after the tunnel is
     * built), or null. Sockets bound to it ([Network.bindSocket], [Network.openConnection]) go through
     * the tunnel whatever the app's default network is at that moment; right after a (first) connect
     * the default network is still the physical one, and unbound traffic would leave past the tunnel.
     */
    @Suppress("DEPRECATION")
    fun vpnNetwork(context: Context): Network? {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val me = android.os.Process.myUid()
        return cm.allNetworks.firstOrNull { n ->
            val c = cm.getNetworkCapabilities(n) ?: return@firstOrNull false
            c.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                (android.os.Build.VERSION.SDK_INT < 29 || c.ownerUid == me || c.ownerUid == android.os.Process.INVALID_UID)
        }
    }

    /** The current [LocalNetwork]; reads link properties only, no network traffic. */
    @Suppress("DEPRECATION")
    fun localNetwork(context: Context): LocalNetwork = runCatching {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val prefixes = ArrayList<IpPrefix>()
        val dns = ArrayList<String>()
        for (n in cm.allNetworks) {
            val c = cm.getNetworkCapabilities(n) ?: continue
            if (c.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
            if (!c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && !c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) continue
            val lp = cm.getLinkProperties(n) ?: continue
            for (la in lp.linkAddresses) {
                IpPrefix.parseOrNull("${la.address.hostAddress?.substringBefore('%')}/${la.prefixLength}")?.let(prefixes::add)
            }
            for (d in lp.dnsServers) if (d is Inet4Address) d.hostAddress?.let(dns::add)
        }
        LocalNetwork(RoutePlanner.extraLan(prefixes), dns.distinct())
    }.getOrDefault(LocalNetwork(emptyList(), emptyList()))

    /** Tells the DNS filter which resolvers answer local names (see [LocalNetwork.dns]). */
    internal fun setLocalDns(servers: List<String>) { runCatching { AwgNative.setLocalDns(servers.joinToString(",")) } }

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
