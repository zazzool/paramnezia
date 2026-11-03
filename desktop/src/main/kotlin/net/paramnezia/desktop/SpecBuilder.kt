package net.paramnezia.desktop

import net.paramnezia.app.data.Settings
import net.paramnezia.core.config.TunnelConfig
import net.paramnezia.core.config.Uapi
import net.paramnezia.core.net.IpPrefix
import net.paramnezia.core.net.PrefixSet
import net.paramnezia.core.routing.BypassLists
import net.paramnezia.core.routing.RoutePlanner
import net.paramnezia.tunnel.DnsPolicy
import net.paramnezia.tunnel.TunnelSpec
import java.net.InetAddress

/**
 * Turns a config plus the user's settings into what the service runs: the same lists and rules
 * as on Android (shared files from app/src/main/assets/bypass), with routes planned the way
 * Android before 13 does it, as the complement of everything that goes direct. Windows takes far
 * more routes than an Android VPN, so more of the Russian list fits.
 *
 * Two Windows-specific additions: the server's own address and the Russian resolvers always go
 * direct (the service's sockets must never loop into the tunnel), and without IPv6 in the config
 * the adapter still gets a private IPv6 address and IPv6 routes, so foreign IPv6 traffic cannot
 * leave past the VPN; it simply fails over to IPv4.
 */
class SpecBuilder(private val asset: (String) -> String) {
    private val v4 by lazy { BypassLists.parsePrefixes(asset("ipv4.txt")) }
    private val v6 by lazy { BypassLists.parsePrefixes(asset("ipv6.txt")) }

    private var cachedKey: Any? = null
    private var cachedPlan: RoutePlanner.Plan? = null

    /** DNS filter lists for the service, by the current settings. */
    fun dnsRules(settings: Settings): Triple<String, String, String> = Triple(
        if (settings.blockTrackers) asset("blocklist.txt") else "",
        if (settings.bypassRussia) asset("domains.txt") else "",
        asset("allow.txt"),
    )

    fun build(serverId: String, serverName: String, config: TunnelConfig, settings: Settings): TunnelSpec {
        val dnsServers = config.iface.dns.filter { it.first().isDigit() || ':' in it }.ifEmpty { listOf("1.1.1.1", "1.0.0.1") }
        // Always on: besides blocking and Russian names, the filter sends local names (nas.lan,
        // printer.local) to the router of the physical network (the service finds it).
        val useDns = true
        val directDns = settings.directDns.ifEmpty { DnsPolicy(emptyList()).direct }
        val keep = config.iface.addresses.map { a -> if (!a.isV6 && a.length > 24) IpPrefix.parse("${a.address.hostAddress}/24") else a } +
            dnsServers.mapNotNull { host(it) }
        // The local network is always direct, whatever the settings or the family policy say.
        // Windows needs no extra networks of the current Wi-Fi: its own on-link routes are more
        // specific than ours, so a LAN on public addresses still wins over the tunnel.
        val key = listOf(keep, settings.bypassRussia)
        val plan = cachedPlan.takeIf { cachedKey == key } ?: RoutePlanner.plan(
            if (settings.bypassRussia) v4 else emptyList(),
            if (settings.bypassRussia) v6 else emptyList(),
            keep, supportsExcludeRoute = false, budget = ROUTE_BUDGET,
        ).also { cachedKey = key; cachedPlan = it }
        return TunnelSpec(
            serverId, serverName, config, plan.include,
            keepaliveActive = settings.battery.screenOn, keepaliveIdle = settings.battery.screenOff,
            dns = if (useDns) DnsPolicy(tunnel = dnsServers, direct = directDns) else null,
            excludedApps = Programs.excluded(settings.forceDirect),
        )
    }

    /**
     * The service request for [spec]: resolves the endpoint (amneziawg-go takes addresses only) and
     * keeps it and the direct resolvers out of the tunnel. Blocking (DNS): call off the UI thread.
     */
    fun request(spec: TunnelSpec, keepalive: String, resolve: (String) -> InetAddress = InetAddress::getByName): ServiceClient.UpSpec {
        val config = spec.config.copy(peers = spec.config.peers.map { it.copy(persistentKeepalive = keepalive) })
        val endpoints = ArrayList<IpPrefix>()
        val uapi = Uapi.write(config) { h -> resolve(h).also { a -> host(a.hostAddress.substringBefore('%'))?.let(endpoints::add) } }
        val outside = endpoints + (spec.dns?.direct.orEmpty()).mapNotNull { host(it) }
        val routes = minus(spec.routes, outside).toMutableList()
        val addresses = spec.config.iface.addresses.map { it.toString() }.toMutableList()
        if (spec.config.iface.addresses.none { it.isV6 }) {
            addresses += FALLBACK_V6
            // Everything IPv6 but the local network (ULA, link-local, multicast stay on the LAN).
            if (routes.none { it.isV6 }) routes += minus(listOf(IpPrefix.ANY_V6), RoutePlanner.LOCAL_V6)
        }
        val dns = spec.dns
        return ServiceClient.UpSpec(
            name = spec.serverName,
            uapi = uapi,
            addresses = addresses,
            routes = routes.map { it.toString() } + listOfNotNull(dns?.let { "${it.virtual}/32" }),
            dnsConfig = dns?.encode().orEmpty(),
            dnsServers = dns?.let { listOf(it.virtual) } ?: spec.config.iface.dns,
            dnsSearch = spec.config.iface.dnsSearch,
            mtu = spec.config.iface.mtu ?: 1280,
            excludedApps = spec.excludedApps,
        )
    }

    companion object {
        /** Routes the planner aims for; Windows handles this many without noticeable cost. */
        const val ROUTE_BUDGET = 8000
        /** Private IPv6 address for configs without IPv6 (never routed anywhere). */
        const val FALLBACK_V6 = "fd70:6e7a:6177:6700::2/128"

        fun host(ip: String): IpPrefix? = IpPrefix.parseOrNull(if (':' in ip) "$ip/128" else "$ip/32")

        fun minus(routes: List<IpPrefix>, outside: List<IpPrefix>): List<IpPrefix> {
            if (outside.isEmpty()) return routes
            return listOf(false, true).flatMap { v6 ->
                val r = routes.filter { it.isV6 == v6 }
                val o = outside.filter { it.isV6 == v6 }
                if (o.isEmpty() || r.isEmpty()) r else PrefixSet.of(v6, r).minus(PrefixSet.of(v6, o)).toPrefixes()
            }
        }
    }
}
