package net.paramnezia.core.fleet

import net.paramnezia.core.config.AwgParam
import net.paramnezia.core.config.TunnelConfig
import net.paramnezia.core.crypto.WgKey
import net.paramnezia.core.net.IpPrefix
import kotlinx.serialization.Serializable
import net.paramnezia.core.config.ConfigException

/**
 * The fleet: one owner's servers and trusted devices. The admin device signs every new version;
 * trusted devices accept only newer, correctly signed versions, so a manifest can be stored on any
 * untrusted mirror. The body is encrypted with the fleet key, so mirrors learn nothing either.
 */
@Serializable
data class FleetManifest(
    val fleetId: String,
    /** Strictly increasing; devices ignore anything not newer than what they have. */
    val version: Long,
    val issuedAt: Long,
    /** Servers in order of preference: the first is the master, the rest are standbys. */
    val servers: List<FleetServer>,
    val devices: List<FleetDevice>,
    /** Where devices look for newer versions (HTTPS mirrors, plus each server's in-tunnel URL). */
    val mirrors: List<String>,
    /**
     * Settings the admin shares with every device (bypass rules, tracker blocking). Null: devices
     * keep their own. Older apps ignore the field.
     */
    val policy: FleetPolicy? = null,
    /** Co-administrators the owner appointed (since 2.15). Older apps ignore the field. */
    val admins: List<FleetCoAdmin> = emptyList(),
    /** Their credentials, each sealed to its device's key (see [GrantBox]). */
    val grants: List<SealedGrant> = emptyList(),
    /** The owner's own device (WireGuard public key), so co-admins cannot remove it. */
    val ownerDevice: String? = null,
    /** Unique identifier of the owner's last deliberate switch to a new endpoint. */
    val endpointRotation: String? = null,
) {
    fun deviceFor(publicKey: WgKey): FleetDevice? = devices.firstOrNull { it.publicKey == publicKey.toBase64() }
}

/** Fleet-wide settings; mirrors the app's own settings minus per-device ones (battery). */
@Serializable
data class FleetPolicy(
    val bypassRussia: Boolean = true,
    /** Ignored since 2.11.0 (the local network is always direct); kept for older apps. */
    val bypassLan: Boolean = true,
    val blockTrackers: Boolean = true,
    val forceTunnel: Set<String> = emptySet(),
    val forceDirect: Set<String> = emptySet(),
    val directDns: List<String> = emptyList(),
)

@Serializable
data class FleetServer(
    val id: String,
    val name: String,
    /** Public host or IP clients connect to. */
    val host: String,
    val port: Int,
    val publicKey: String,
    val presharedKey: String? = null,
    /** AmneziaWG parameters by their wg-quick names (`Jc`, `H1`, …). */
    val awg: Map<String, String> = emptyMap(),
    val dns: List<String> = listOf("1.1.1.1", "1.0.0.1"),
    val mtu: Int = 1280,
    val keepalive: String = "25",
    /**
     * More ports the server answers on, besides [port] (since 2.16; older apps ignore them). Devices
     * move to them when [port] is blocked; AmneziaVPN keeps using [port].
     */
    val altPorts: List<Int> = emptyList(),
    /** Other addresses of the same server, typically its IPv6 address (since 2.16). */
    val altHosts: List<String> = emptyList(),
    val tunnelIpv6: Boolean = false,
    /** Addresses assigned on this VPS when a pre-existing peer owns the family's usual address. */
    val peerAddresses: Map<String, String> = emptyMap(),
)

@Serializable
data class FleetDevice(
    val name: String,
    val publicKey: String,
    /** Tunnel address of this device, the same on every server of the fleet. */
    val address: String,
    /**
     * The device has a preshared key of its own on every server ([DevicePsk]), not the server's
     * shared one (since 2.16). Its key came in its invite; administrators derive it.
     */
    val ownPsk: Boolean = false,
)

/**
 * The client config a trusted device should use for [server]. [psk] is the device's own preshared
 * key when it has one ([FleetDevice.ownPsk]); otherwise the server's shared key is used.
 */
fun FleetServer.tunnelFor(device: FleetDevice, privateKey: WgKey, psk: WgKey? = null): TunnelConfig {
    require(privateKey.publicKey().toBase64() == device.publicKey) { "Key does not belong to ${device.name}" }
    val address = peerAddresses[device.publicKey] ?: device.address
    val awgParams = LinkedHashMap<AwgParam, String>()
    for ((k, v) in awg) AwgParam.fromConfKey(k)?.let { awgParams[it] = v }
    return TunnelConfig(
        TunnelConfig.Interface(
            privateKey = privateKey,
            addresses = listOf(IpPrefix.parse(address)) +
                listOfNotNull(if (tunnelIpv6) managedIpv6Address(address) else null),
            dns = dns,
            mtu = mtu,
            awg = awgParams,
        ),
        listOf(
            TunnelConfig.Peer(
                publicKey = WgKey.fromBase64(publicKey),
                presharedKey = (if (device.ownPsk) psk else null) ?: presharedKey?.let(WgKey::fromBase64),
                allowedIps = listOf(IpPrefix.ANY_V4, IpPrefix.ANY_V6),
                endpoint = if (host.contains(':')) "[$host]:$port" else "$host:$port",
                persistentKeepalive = keepalive,
            ),
        ),
    )
}

/** Managed servers derive a unique IPv6 host address from each peer's IPv4 host address. */
fun managedIpv6Address(address: String): IpPrefix? {
    val ipv4 = IpPrefix.parse(address.substringBefore('/'))
    if (ipv4.isV6) return null
    val value = ipv4.network.toLong()
    return IpPrefix.parse("fd38:706e:1::${(value ushr 16).toString(16)}:${(value and 0xffff).toString(16)}/128")
}

/** Turns a server's client config (from a deploy or an imported key) into a fleet server entry. */
fun fleetServerOf(id: String, name: String, tunnel: TunnelConfig): FleetServer {
    val peer = tunnel.peers.first()
    val endpoint = peer.endpoint ?: throw ConfigException("Server config has no endpoint")
    val host = endpoint.substringBeforeLast(':').removePrefix("[").removeSuffix("]")
    val port = endpoint.substringAfterLast(':').toInt()
    return FleetServer(
        id = id, name = name, host = host, port = port,
        publicKey = peer.publicKey.toBase64(),
        presharedKey = peer.presharedKey?.toBase64(),
        awg = tunnel.iface.awg.mapKeys { it.key.conf },
        dns = tunnel.iface.dns.ifEmpty { listOf("1.1.1.1", "1.0.0.1") },
        mtu = tunnel.iface.mtu ?: 1280,
        keepalive = peer.persistentKeepalive ?: "25",
        tunnelIpv6 = tunnel.iface.addresses.any { it.isV6 && IpPrefix.parse("fd38:706e:1::/64").contains(it) },
    )
}
