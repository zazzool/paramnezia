package net.paramnezia.core.routing

import net.paramnezia.core.net.IpPrefix
import net.paramnezia.core.net.PrefixSet

/**
 * Decides which routes the VPN interface gets.
 *
 * Android caps the size of a VPN configuration (it crosses Binder in one transaction), so a few
 * thousand routes is the practical ceiling. The bypass list is ordered by importance, and we take as
 * many blocks as fit the budget. Blocks that do not fit simply stay in the tunnel: that is the safe
 * failure mode, because they still work, only not directly.
 */
object RoutePlanner {
    /**
     * Networks that never belong in the tunnel, always (Vladimir: the local network must stay
     * reachable whatever the settings): private LANs, link-local, CGNAT, multicast and broadcast.
     */
    val LOCAL_V4 = listOf(
        "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "169.254.0.0/16", "100.64.0.0/10",
        "224.0.0.0/4", "255.255.255.255/32",
    ).map(IpPrefix::parse)
    val LOCAL_V6 = listOf("fc00::/7", "fe80::/10", "ff00::/8").map(IpPrefix::parse)

    /**
     * The networks of the current Wi-Fi or Ethernet that [LOCAL_V4]/[LOCAL_V6] do not cover: a
     * home network on public addresses (some providers and hotels do that) and the provider's IPv6
     * /64, through which home devices are often reached. Implausibly wide networks are ignored, so
     * a broken DHCP lease cannot pull half the internet out of the tunnel.
     */
    fun extraLan(onLink: List<IpPrefix>): List<IpPrefix> = onLink
        .filter { if (it.isV6) it.length in 48..128 else it.length in 16..32 }
        .map { IpPrefix.of(it.isV6, it.network, it.length) }
        .filter { p -> (if (p.isV6) LOCAL_V6 else LOCAL_V4).none { it.contains(p) } }
        .distinct().sorted()

    /** Total routes (both families) the planner aims for; IPv4 gets the larger share. */
    const val ROUTE_BUDGET = 3000

    data class Plan(
        /** Routes into the tunnel. */
        val include: List<IpPrefix>,
        /** Routes excluded from the tunnel (Android 13+ `excludeRoute`). Empty on older systems. */
        val exclude: List<IpPrefix>,
        /** How many bypass blocks made it into the plan. */
        val bypassBlocks: Int,
    )

    /**
     * @param bypassV4/bypassV6 ordered bypass blocks, most important first.
     * @param keepInTunnel addresses that must stay in the tunnel even if they fall inside a bypass
     *   block or a LAN range: the tunnel subnet and its DNS servers.
     * @param supportsExcludeRoute true on Android 13+ (API 33).
     * @param lan extra networks of the current local network ([extraLan]); the private ranges,
     *   link-local, multicast and broadcast always go direct, so the router, printer, TV and shared
     *   folders stay reachable with the VPN on.
     */
    fun plan(
        bypassV4: List<IpPrefix>,
        bypassV6: List<IpPrefix>,
        keepInTunnel: List<IpPrefix>,
        supportsExcludeRoute: Boolean,
        lan: List<IpPrefix> = emptyList(),
        budget: Int = ROUTE_BUDGET,
    ): Plan {
        val v4 = family(false, bypassV4, LOCAL_V4 + lan.filter { !it.isV6 }, keepInTunnel, supportsExcludeRoute, budget * 3 / 4)
        val v6 = family(true, bypassV6, LOCAL_V6 + lan.filter { it.isV6 }, keepInTunnel, supportsExcludeRoute,
            budget - v4.include.size - v4.exclude.size)
        return Plan(v4.include + v6.include, v4.exclude + v6.exclude, v4.bypassBlocks + v6.bypassBlocks)
    }

    private fun family(
        isV6: Boolean,
        ordered: List<IpPrefix>,
        local: List<IpPrefix>,
        keep: List<IpPrefix>,
        supportsExclude: Boolean,
        budget: Int,
    ): Plan {
        val keepSet = PrefixSet.of(isV6, keep)
        // Complement routes are ~2.5x the number of blocks they leave out; budget accordingly.
        val take = if (supportsExclude) budget else budget * 2 / 5
        var n = minOf(take, ordered.size)
        while (true) {
            val direct = PrefixSet.of(isV6, ordered.take(n) + local).minus(keepSet)
            val plan = if (supportsExclude) {
                Plan(listOf(if (isV6) IpPrefix.ANY_V6 else IpPrefix.ANY_V4), direct.toPrefixes(), n)
            } else {
                Plan(direct.complement().toPrefixes(), emptyList(), n)
            }
            val routes = plan.include.size + plan.exclude.size
            if (routes <= budget || n == 0) return plan
            n = maxOf(0, n - maxOf(1, (routes - budget) / 2))
        }
    }
}
