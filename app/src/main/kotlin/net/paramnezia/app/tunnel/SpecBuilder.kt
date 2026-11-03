package net.paramnezia.app.tunnel

import android.content.Context
import android.os.Build
import net.paramnezia.app.data.Settings
import net.paramnezia.core.config.TunnelConfig
import net.paramnezia.core.net.IpPrefix
import net.paramnezia.core.routing.AppRules
import net.paramnezia.core.routing.BypassLists
import net.paramnezia.core.routing.RoutePlanner
import net.paramnezia.tunnel.DnsPolicy
import net.paramnezia.tunnel.TunnelRuntime
import net.paramnezia.tunnel.TunnelSpec

/** Turns a config plus the user's settings into what the VPN service runs. */
class SpecBuilder(private val context: Context) {
    private fun asset(name: String) = context.assets.open("bypass/$name").bufferedReader().use { it.readText() }

    private val v4 by lazy { BypassLists.parsePrefixes(asset("ipv4.txt")) }
    private val v6 by lazy { BypassLists.parsePrefixes(asset("ipv6.txt")) }
    private val apps by lazy { AppRules.parse(asset("apps.txt")) }

    /** Route plans are the expensive part; they only change with the config's addresses and settings. */
    private var cachedKey: Any? = null
    private var cachedPlan: RoutePlanner.Plan? = null

    /** Loads the DNS filter rules into the native library for the current settings. */
    fun applyDnsRules(settings: Settings) {
        TunnelRuntime.setDnsRules(
            block = if (settings.blockTrackers) asset("blocklist.txt") else "",
            direct = if (settings.bypassRussia) asset("domains.txt") else "",
            allow = asset("allow.txt"),
        )
    }

    fun build(
        serverId: String,
        serverName: String,
        config: TunnelConfig,
        settings: Settings,
    ): TunnelSpec {
        val dnsServers = config.iface.dns.filter { it.first().isDigit() || ':' in it }.ifEmpty { listOf("1.1.1.1", "1.0.0.1") }
        // The DNS filter is always on: besides blocking and Russian names it sends local names
        // (nas.lan, printer.local) to the router, which no resolver behind the tunnel knows.
        val useDns = true
        // The tunnel subnet (the in-tunnel manifest URL) and its DNS always stay inside.
        val keep = config.iface.addresses.map { a -> if (!a.isV6 && a.length > 24) IpPrefix.parse("${a.address.hostAddress}/24") else a } +
            dnsServers.mapNotNull { IpPrefix.parseOrNull(if (':' in it) "$it/128" else "$it/32") }

        val excludeSupported = Build.VERSION.SDK_INT >= 33
        // The local network is always direct (Vladimir: «локальная сеть должна быть доступна вне
        // тоннеля всегда»), whatever the settings or the family policy say; lan adds the current
        // Wi-Fi's own networks when they are not private.
        val lan = TunnelRuntime.localNetwork(context).prefixes
        val key = listOf(keep, settings.bypassRussia, lan, excludeSupported)
        val plan = cachedPlan.takeIf { cachedKey == key } ?: RoutePlanner.plan(
            if (settings.bypassRussia) v4 else emptyList(),
            if (settings.bypassRussia) v6 else emptyList(),
            keep, excludeSupported, lan,
        ).also { cachedKey = key; cachedPlan = it }

        val installed = installedPackages()
        val excludedApps = if (settings.bypassRussia) apps.select(installed, settings.forceTunnel, settings.forceDirect, context.packageName)
        else installed.filter { it in settings.forceDirect && it != context.packageName }
        // Orbot is our last-resort way to reach the fleet's mirrors while our own server is blocked,
        // so it must not depend on our tunnel (Tor through a VPN is pointless anyway).
        val withOrbot = if (ORBOT in installed && ORBOT !in settings.forceTunnel && ORBOT !in excludedApps) excludedApps + ORBOT else excludedApps

        return TunnelSpec(
            serverId = serverId,
            serverName = serverName,
            config = config,
            routes = plan.include,
            excludedRoutes = plan.exclude,
            excludedApps = withOrbot,
            keepaliveScreenOn = settings.battery.screenOn,
            keepaliveScreenOff = settings.battery.screenOff,
            dns = if (useDns) DnsPolicy(tunnel = dnsServers, direct = settings.directDns.ifEmpty { DnsPolicy(emptyList()).direct }) else null,
            lan = lan,
        )
    }

    companion object {
        const val ORBOT = "org.torproject.android"
    }

    fun installedPackages(): List<String> =
        context.packageManager.getInstalledApplications(0).map { it.packageName }
}
