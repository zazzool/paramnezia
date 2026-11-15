package net.paramnezia.desktop

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.NetworkInterface
import java.security.MessageDigest

/**
 * Which network the computer is on, as an opaque key that stays the same when it comes back (a
 * digest of the subnets of its physical adapters), and whether it has a global IPv6 address. Our
 * own adapter and other virtual ones (VPNs, Hyper-V, WSL) are left out.
 */
internal object DesktopNetwork {
    private val virtual = Regex("(?i)paramnezia|wintun|wireguard|tap|tun|vpn|hyper-v|vethernet|virtualbox|vmware|wsl|loopback|bluetooth")

    fun identity(): Pair<String, Boolean> = runCatching {
        val parts = ArrayList<String>()
        var v6 = false
        for (ni in NetworkInterface.getNetworkInterfaces().toList()) {
            if (!ni.isUp || ni.isLoopback || ni.isVirtual || ni.isPointToPoint) continue
            if (virtual.containsMatchIn(ni.name + " " + (ni.displayName ?: ""))) continue
            for (ia in ni.interfaceAddresses) {
                when (val a = ia.address) {
                    is Inet4Address -> {
                        val bits = ia.networkPrefixLength.toInt().coerceIn(0, 32)
                        val raw = a.address.fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xFF) }
                        val net = if (bits == 0) 0L else raw and (0xFFFFFFFFL shl (32 - bits)) and 0xFFFFFFFFL
                        parts += "%d.%d.%d.%d/%d".format(net shr 24 and 0xFF, net shr 16 and 0xFF, net shr 8 and 0xFF, net and 0xFF, bits)
                    }
                    is Inet6Address -> if (!a.isLinkLocalAddress && !a.isLoopbackAddress && (a.address[0].toInt() and 0xfe) != 0xfc) v6 = true
                }
            }
        }
        if (parts.isEmpty()) return "offline" to false
        val d = MessageDigest.getInstance("SHA-256").digest(parts.sorted().joinToString("|").toByteArray())
        ("pc:" + d.take(8).joinToString("") { "%02x".format(it) }) to v6
    }.getOrDefault("unknown" to false)
}
