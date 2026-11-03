package net.paramnezia.app.lan

import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

/**
 * The local networks this device is in: Wi-Fi, Ethernet, its own hotspot. Never the VPN tunnel,
 * mobile data or virtual adapters, so the share is reachable from the home network only.
 */
object LanNet {
    data class Lan(val interfaceName: String, val address: Inet4Address, val prefix: Int) {
        val host: String get() = address.hostAddress
        fun contains(other: InetAddress): Boolean {
            if (other !is Inet4Address) return false
            val p = prefix.coerceIn(8, 32)
            val mask = if (p == 32) -1 else (-1 shl (32 - p))
            return (toInt(address) and mask) == (toInt(other) and mask)
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
                    val a = ia.address as? Inet4Address ?: return@mapNotNull null
                    if (!usable(a)) return@mapNotNull null
                    Lan(ni.name, a, ia.networkPrefixLength.toInt().takeIf { it in 1..32 } ?: 24)
                }
            }
            .sortedBy { if (preferName.containsMatchIn(it.interfaceName)) 0 else 1 }
            .distinctBy { it.host }
            .toList()
    }.getOrDefault(emptyList())

    /** A private IPv4 address a neighbour can reach: 10/8, 172.16/12, 192.168/16 (not link-local, not CGNAT). */
    fun usable(a: Inet4Address): Boolean = a.isSiteLocalAddress && !a.isLoopbackAddress

    /** True for a client in one of our local networks (or this device itself). */
    fun isNeighbour(remote: InetAddress, lans: List<Lan> = interfaces()): Boolean =
        remote.isLoopbackAddress || lans.any { it.contains(remote) }

    fun shortAddress(host: String, port: Int) = if (port == 80) host else "$host:$port"

    /** "192.168.1.5", "192.168.1.5:8080", "http://192.168.1.5:8080/" → host and port; null if not an address. */
    fun parseAddress(text: String): Pair<String, Int>? {
        val t = text.trim().removePrefix("http://").removePrefix("https://").substringBefore('/').trim()
        if (t.isEmpty()) return null
        val host = t.substringBefore(':')
        val port = t.substringAfter(':', "").let { if (it.isEmpty()) 80 else it.toIntOrNull() } ?: return null
        if (port !in 1..65535) return null
        if (!host.matches(Regex("[0-9]{1,3}(\\.[0-9]{1,3}){3}")) && !host.matches(Regex("[A-Za-z0-9.-]+"))) return null
        if (host.split('.').let { parts -> parts.size == 4 && parts.all { it.toIntOrNull() != null } && parts.any { it.toInt() > 255 } }) return null
        return host to port
    }

    private fun toInt(a: Inet4Address): Int = a.address.fold(0) { acc, b -> (acc shl 8) or (b.toInt() and 0xff) }
}
