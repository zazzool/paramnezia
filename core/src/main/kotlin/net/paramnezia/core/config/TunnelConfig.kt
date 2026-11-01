package net.paramnezia.core.config

import net.paramnezia.core.crypto.WgKey
import net.paramnezia.core.net.IpPrefix

/**
 * A WireGuard / AmneziaWG client configuration. A config with no [Interface.awg] parameters is plain
 * WireGuard and works with any WireGuard server; with them it needs an AmneziaWG server.
 */
data class TunnelConfig(
    val iface: Interface,
    val peers: List<Peer>,
) {
    val isAmnezia: Boolean get() = iface.awg.isNotEmpty()

    data class Interface(
        val privateKey: WgKey,
        val addresses: List<IpPrefix> = emptyList(),
        /** DNS servers (IP literals). */
        val dns: List<String> = emptyList(),
        val dnsSearch: List<String> = emptyList(),
        val mtu: Int? = null,
        val listenPort: Int? = null,
        val excludedApplications: List<String> = emptyList(),
        val includedApplications: List<String> = emptyList(),
        /** AmneziaWG parameters, values exactly as written in the config (e.g. `"100-200"`, `"on"`). */
        val awg: Map<AwgParam, String> = emptyMap(),
        /** Keys we do not interpret (PostUp, Table, …), kept so a re-export does not lose them. */
        val extra: List<Pair<String, String>> = emptyList(),
    )

    data class Peer(
        val publicKey: WgKey,
        val presharedKey: WgKey? = null,
        val allowedIps: List<IpPrefix> = emptyList(),
        /** `host:port`, `[v6]:port`. Host may be a DNS name. */
        val endpoint: String? = null,
        /** Seconds, or an AmneziaWG 3 range such as `"25-35"`. */
        val persistentKeepalive: String? = null,
        val extra: List<Pair<String, String>> = emptyList(),
    )
}

class ConfigException(message: String, cause: Throwable? = null) : Exception(message, cause)
