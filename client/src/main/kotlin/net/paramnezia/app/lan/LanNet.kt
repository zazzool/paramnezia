package net.paramnezia.app.lan

import net.paramnezia.core.config.Uapi
import net.paramnezia.core.net.IpPrefix
import java.math.BigInteger
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface

/**
 * The local networks this device is in: Wi-Fi, Ethernet, its own hotspot. Never the VPN tunnel,
 * mobile data or virtual adapters, so the share is reachable from the home network only.
 */
object LanNet {
    data class Lan(val interfaceName: String, val address: InetAddress, val prefix: Int) {
        val host: String get() = address.hostAddress.substringBefore('%')
        private val network = IpPrefix.of(address is Inet6Address, BigInteger(1, address.address), prefix)

        fun contains(other: InetAddress): Boolean {
            if (address.address.size != other.address.size) return false
            return network.contains(IpPrefix.of(other is Inet6Address, BigInteger(1, other.address), network.bits))
        }
    }

    private val skipName = Regex("^(tun|ppp|rmnet|ccmni|v4-|clat|dummy|ipsec|wg|utun|docker|veth|br-|virbr|vmnet|vboxnet|lo|ifb|gre|sit|ip6tnl|seth|bond|usbnet)", RegexOption.IGNORE_CASE)
    private val skipDisplay = Regex("virtual|vmware|virtualbox|hyper-v|vethernet|wintun|wireguard|paramnezia|amnezia|tap-|tap |loopback|bluetooth|pseudo|teredo|isatap|vpn|zerotier|tailscale|hamachi", RegexOption.IGNORE_CASE)
    /** Wi-Fi and Ethernet first; then everything else that passed. */
    private val preferName = Regex("^(wlan|swlan|ap|eth|en|wl|wifi|wi-fi)", RegexOption.IGNORE_CASE)

    fun interfaces(): List<Lan> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList().asSequence()
            .filter { runCatching { it.isUp && !it.isLoopback && !it.isPointToPoint && !it.isVirtual }.getOrDefault(false) }
            .filter { !skipName.containsMatchIn(it.name) && !skipDisplay.containsMatchIn(it.displayName.orEmpty()) }
            .flatMap { ni ->
                ni.interfaceAddresses.asSequence().mapNotNull { ia ->
                    val a = ia.address
                    if (!usable(a)) return@mapNotNull null
                    val prefix = ia.networkPrefixLength.toInt()
                    if (a is Inet6Address && prefix !in 64..128) return@mapNotNull null
                    Lan(ni.name, a, if (a is Inet6Address) prefix else prefix.takeIf { it in 8..32 } ?: 24)
                }
            }
            .sortedBy { if (preferName.containsMatchIn(it.interfaceName)) 0 else 1 }
            .distinctBy { it.host }
            .toList()
    }.getOrDefault(emptyList())

    /** Private IPv4 or unscoped IPv6 on a physical network. */
    fun usable(a: InetAddress): Boolean = when (a) {
        is Inet4Address -> a.isSiteLocalAddress && !a.isLoopbackAddress
        is Inet6Address -> !a.isLinkLocalAddress && !a.isLoopbackAddress && !a.isAnyLocalAddress && !a.isMulticastAddress
        else -> false
    }

    /** True for a client in one of our local networks (or this device itself). */
    fun isNeighbour(remote: InetAddress, lans: List<Lan> = interfaces()): Boolean =
        remote.isLoopbackAddress || lans.any { it.contains(remote) }

    fun shortAddress(host: String, port: Int) = if (port == 80) Uapi.urlHost(host) else Uapi.formatEndpoint(host, port)

    /** Accepts IPv4, domains and bare IPv6. With an explicit IPv6 port, brackets are required. */
    fun parseAddress(text: String): Pair<String, Int>? {
        val t = text.trim().removePrefix("http://").removePrefix("https://").substringBefore('/').trim()
        return runCatching {
            if ((t.startsWith("[") && t.substringAfter(']', "").startsWith(":")) ||
                (!t.startsWith("[") && t.count { it == ':' } == 1)
            ) Uapi.splitEndpoint(t) else Uapi.hostAddress(t) to 80
        }.getOrNull()
    }
}
