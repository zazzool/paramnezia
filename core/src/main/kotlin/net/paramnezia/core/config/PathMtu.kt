package net.paramnezia.core.config

/**
 * The tunnel's MTU, found per network instead of fixed. Every config used to get a safe 1280: it works
 * everywhere, but each full packet then carries 1240 bytes of TCP payload instead of 1380 at the
 * WireGuard default of 1420. Video moves almost only full packets, so that is about 11 % more
 * packets (and encryption, and battery) for the same picture, and slower downloads where the phone's
 * CPU is the limit.
 *
 * After a tunnel is verified the client sends probes of the [candidates] sizes through it (RFC 8899
 * style, package `pmtu` in the native code) and keeps the largest that came back. Android has to
 * rebuild the interface to change its MTU, so the result is remembered per network and server and
 * used from the next connect on; Windows changes it in place.
 */
object PathMtu {
    /** What every IPv6 link must carry, and what a tunnel starts with when nothing is known. */
    const val SAFE = 1280

    /** WireGuard's default, and what the server's own interface usually has (awg-quick: 1500 - 80). */
    const val MAX = 1420

    /**
     * Sizes worth probing for [config], largest first: the most a 1500-byte path carries after
     * WireGuard's 32 bytes, the transport padding S4, UDP and the outer IP header ([ipv6]
     * endpoint: 40 bytes), capped at [MAX]; then a few steps down for narrower paths (PPPoE, mobile
     * networks with tunnels of their own). Only sizes above [SAFE].
     */
    fun candidates(config: TunnelConfig, ipv6: Boolean = endpointIsV6(config)): List<Int> {
        val s4 = config.iface.awg[AwgParam.S4]?.trim()?.toIntOrNull()?.coerceIn(0, 1000) ?: 0
        val top = minOf(MAX, 1500 - (if (ipv6) 40 else 20) - 8 - 32 - s4)
        return (listOf(top) + listOf(1400, 1360, 1320)).filter { it in (SAFE + 1)..top }.distinct().sortedDescending()
    }

    /**
     * Own tunnel address and the server's (the first host of the device's /24, where AmneziaVPN's
     * layout puts it), or null for a config without an IPv4 tunnel address.
     */
    fun addresses(config: TunnelConfig): Pair<String, String>? {
        val own = config.iface.addresses.firstOrNull { !it.isV6 }?.address?.hostAddress ?: return null
        val parts = own.split('.')
        if (parts.size != 4 || parts[3] == "1") return null
        return own to "${parts[0]}.${parts[1]}.${parts[2]}.1"
    }

    /** The probe order for the native side: `"<own> <server> <sizes>"`, or null when nothing to probe. */
    fun probeSpec(config: TunnelConfig): String? {
        val (own, server) = addresses(config) ?: return null
        val sizes = candidates(config).ifEmpty { return null }
        return "$own $server ${sizes.joinToString(",")}"
    }

    /**
     * The MTU to build with: what was measured on this network if anything, else the config's own
     * (or [SAFE]). Never below [SAFE].
     */
    fun choose(config: TunnelConfig, measured: Int?): Int = maxOf(SAFE, measured ?: config.iface.mtu ?: SAFE)

    /**
     * What a probe result means for the next build: the largest size that came back, or, when none
     * did, the config's own MTU (an imported config whose server does not answer pings keeps what it
     * came with; ours come with 1280).
     */
    fun fromProbe(config: TunnelConfig, best: Int): Int = if (best > SAFE) best else maxOf(SAFE, config.iface.mtu ?: SAFE)

    private fun endpointIsV6(config: TunnelConfig): Boolean =
        config.peers.firstOrNull()?.endpoint?.let { runCatching { ':' in Uapi.splitEndpoint(it).first }.getOrNull() } == true
}
