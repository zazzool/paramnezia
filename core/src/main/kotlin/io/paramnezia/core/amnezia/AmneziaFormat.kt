package io.paramnezia.core.amnezia

import io.paramnezia.core.config.AwgParam
import io.paramnezia.core.config.ConfigException
import io.paramnezia.core.config.TunnelConfig
import io.paramnezia.core.config.WgQuick
import io.paramnezia.core.crypto.WgKey
import io.paramnezia.core.net.IpPrefix
import io.paramnezia.core.profile.Protocol
import io.paramnezia.core.profile.ServerProfile
import io.paramnezia.core.profile.SshCredentials
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * Maps AmneziaVPN server JSON (the payload of `vpn://` keys) to [ServerProfile]s and back.
 * Field names follow amnezia-client `configKeys.h`; format_version 1.
 */
object AmneziaFormat {
    const val FORMAT_VERSION = 1
    const val DEFAULT_DNS1 = "1.1.1.1"
    const val DEFAULT_DNS2 = "1.0.0.1"

    /** One profile per container that carries a client config (or SSH access to create one). */
    fun import(server: JsonObject): List<ServerProfile> {
        val version = server.int("format_version") ?: 0
        if (version > FORMAT_VERSION) throw ConfigException("Config was made by a newer AmneziaVPN (format $version)")

        val host = server.str("hostName").orEmpty()
        val description = server.str("description") ?: server.str("name") ?: host
        val dns = listOfNotNull(server.str("dns1")?.takeIf { it.isNotBlank() }, server.str("dns2")?.takeIf { it.isNotBlank() })
        val ssh = server.str("userName")?.takeIf { it.isNotEmpty() }?.let {
            SshCredentials(
                host = host,
                port = server.int("port")?.takeIf { p -> p > 0 } ?: 22,
                user = it,
                password = server.str("password").orEmpty(),
            )
        }
        val containers = (server["containers"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        val default = server.str("defaultContainer")

        val profiles = containers.mapNotNull { c ->
            val protocol = Protocol.fromContainer(c.str("container") ?: return@mapNotNull null) ?: return@mapNotNull null
            val proto = c[protocol.amneziaKey] as? JsonObject
            val tunnel = if (protocol == Protocol.XRAY) null else proto?.let { tunnelFrom(it, host, dns) }
            if (tunnel == null && ssh == null && protocol != Protocol.XRAY) return@mapNotNull null
            ServerProfile(description, host, protocol, tunnel, ssh, dns) to (c.str("container") == default)
        }
        // Default container first, then the rest in file order.
        val sorted = profiles.sortedByDescending { it.second }.map { it.first }
        if (sorted.isEmpty() && ssh != null) {
            return listOf(ServerProfile(description, host, Protocol.AWG, null, ssh, dns))
        }
        return sorted
    }

    private fun tunnelFrom(proto: JsonObject, host: String, dns: List<String>): TunnelConfig? {
        val last = proto.str("last_config")?.let { s ->
            runCatching { AmneziaKey.json.parseToJsonElement(s).jsonObject }.getOrNull()
        } ?: return null

        val dns1 = dns.getOrNull(0) ?: DEFAULT_DNS1
        val dns2 = dns.getOrNull(1) ?: DEFAULT_DNS2
        val native = last.str("config")
            ?.replace("\$PRIMARY_DNS", dns1)
            ?.replace("\$SECONDARY_DNS", dns2)

        val parsed = native?.let { runCatching { WgQuick.parse(it) }.getOrNull() }
        val base = parsed ?: fromFields(last, host) ?: return null

        // JSON fields win over the stored native text, as in AmneziaVPN itself.
        val awg = LinkedHashMap(base.iface.awg)
        for (p in AwgParam.entries) {
            val v = (last.str(p.conf) ?: proto.str(p.conf))?.trim()
            if (!v.isNullOrEmpty()) awg[p] = v
        }
        val mtu = last.str("mtu")?.toIntOrNull() ?: base.iface.mtu
        val keepalive = last.str("persistent_keep_alive")?.takeIf { it.isNotBlank() }
        val peers = base.peers.mapIndexed { i, p ->
            if (i != 0) p else p.copy(persistentKeepalive = keepalive ?: p.persistentKeepalive)
        }
        return base.copy(iface = base.iface.copy(awg = awg, mtu = mtu), peers = peers)
    }

    /** Builds a tunnel from the discrete JSON fields when the native text is missing or broken. */
    private fun fromFields(last: JsonObject, host: String): TunnelConfig? {
        val priv = last.str("client_priv_key") ?: return null
        val serverPub = last.str("server_pub_key") ?: return null
        val clientIp = last.str("client_ip") ?: return null
        val port = last.str("port")?.toIntOrNull() ?: return null
        val endpointHost = last.str("hostName") ?: host
        val allowed = (last["allowed_ips"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.let(IpPrefix::parseOrNull) }
            ?.takeIf { it.isNotEmpty() } ?: listOf(IpPrefix.ANY_V4, IpPrefix.ANY_V6)
        return runCatching {
            TunnelConfig(
                TunnelConfig.Interface(
                    privateKey = WgKey.fromBase64(priv),
                    addresses = listOf(IpPrefix.parse(clientIp.substringBefore('/') + "/32")),
                ),
                listOf(
                    TunnelConfig.Peer(
                        publicKey = WgKey.fromBase64(serverPub),
                        presharedKey = last.str("psk_key")?.takeIf { it.isNotBlank() }?.let(WgKey::fromBase64),
                        allowedIps = allowed,
                        endpoint = if (endpointHost.contains(':')) "[$endpointHost]:$port" else "$endpointHost:$port",
                    ),
                ),
            )
        }.getOrNull()
    }

    /**
     * Exports a client tunnel as an AmneziaVPN-importable server JSON. Without [ssh] this is a
     * "connection only" key; with it, a full-access key that lets AmneziaVPN manage the server.
     */
    fun export(
        name: String,
        tunnel: TunnelConfig,
        ssh: SshCredentials? = null,
        dns: List<String> = emptyList(),
        serverParams: Map<String, String> = emptyMap(),
    ): JsonObject {
        val protocol = if (tunnel.isAmnezia) Protocol.AWG else Protocol.WIREGUARD
        val peer = tunnel.peers.firstOrNull() ?: throw ConfigException("Tunnel has no peer")
        val (host, port) = io.paramnezia.core.config.Uapi.splitEndpoint(peer.endpoint ?: throw ConfigException("Peer has no endpoint"))
        val dns1 = dns.getOrNull(0) ?: tunnel.iface.dns.getOrNull(0) ?: DEFAULT_DNS1
        val dns2 = dns.getOrNull(1) ?: tunnel.iface.dns.getOrNull(1) ?: DEFAULT_DNS2
        val clientPub = tunnel.iface.privateKey.publicKey().toBase64()

        val last = buildJsonObject {
            put("config", WgQuick.write(tunnel))
            put("hostName", host)
            put("port", port)
            put("client_ip", tunnel.iface.addresses.firstOrNull()?.address?.hostAddress.orEmpty())
            put("client_priv_key", tunnel.iface.privateKey.toBase64())
            put("client_pub_key", clientPub)
            put("server_pub_key", peer.publicKey.toBase64())
            peer.presharedKey?.let { put("psk_key", it.toBase64()) }
            put("clientId", clientPub)
            put("allowed_ips", buildJsonArray { peer.allowedIps.forEach { add(JsonPrimitive(it.toString())) } })
            peer.persistentKeepalive?.let { put("persistent_keep_alive", it) }
            tunnel.iface.mtu?.let { put("mtu", it.toString()) }
            for ((p, v) in tunnel.iface.awg) put(p.conf, v)
        }
        val protoJson = buildJsonObject {
            put("port", port.toString())
            put("transport_proto", "udp")
            for ((k, v) in serverParams) put(k, v)
            for ((p, v) in tunnel.iface.awg) if (p.conf !in serverParams) put(p.conf, v)
            put("last_config", AmneziaKey.json.encodeToString(JsonObject.serializer(), last))
        }
        return buildJsonObject {
            put("format_version", FORMAT_VERSION)
            put("description", name)
            put("hostName", ssh?.host ?: host)
            if (ssh != null) {
                put("userName", ssh.user)
                put("password", ssh.password)
                put("port", ssh.port)
            }
            put("containers", buildJsonArray {
                add(buildJsonObject {
                    put("container", protocol.amneziaContainer)
                    put(protocol.amneziaKey, protoJson)
                })
            })
            put("defaultContainer", protocol.amneziaContainer)
            put("dns1", dns1)
            put("dns2", dns2)
        }
    }

    private fun JsonObject.str(key: String): String? = when (val v: JsonElement? = this[key]) {
        is JsonPrimitive -> v.contentOrNull
        else -> null
    }

    private fun JsonObject.int(key: String): Int? = str(key)?.toIntOrNull()
}
