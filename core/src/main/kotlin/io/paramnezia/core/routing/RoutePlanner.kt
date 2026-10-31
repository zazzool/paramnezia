package io.paramnezia.core.routing

import io.paramnezia.core.net.IpPrefix
import io.paramnezia.core.net.PrefixSet

/**
 * Decides which routes the VPN interface gets.
 *
 * Android caps the size of a VPN configuration (it crosses Binder in one transaction), so a few
 * thousand routes is the practical ceiling. The bypass list is ordered by importance, and we take as
 * many blocks as fit the budget. Blocks that do not fit simply stay in the tunnel: that is the safe
 * failure mode, because they still work, only not directly.
 */
object RoutePlanner {
    /** Networks that never belong in the tunnel: LAN, link-local, multicast, CGNAT. */
    val LOCAL_V4 = listOf("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "169.254.0.0/16", "100.64.0.0/10", "224.0.0.0/4")
        .map(IpPrefix::parse)
    val LOCAL_V6 = listOf("fc00::/7", "fe80::/10", "ff00::/8").map(IpPrefix::parse)

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
     * @param bypassLan send private networks direct, so the LAN printer and router stay reachable.
     */
    fun plan(
        bypassV4: List<IpPrefix>,
        bypassV6: List<IpPrefix>,
        keepInTunnel: List<IpPrefix>,
        supportsExcludeRoute: Boolean,
        bypassLan: Boolean = true,
        budget: Int = ROUTE_BUDGET,
    ): Plan {
        val v4 = family(false, bypassV4, if (bypassLan) LOCAL_V4 else emptyList(), keepInTunnel, supportsExcludeRoute, budget * 3 / 4)
        val v6 = family(true, bypassV6, if (bypassLan) LOCAL_V6 else emptyList(), keepInTunnel, supportsExcludeRoute,
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
