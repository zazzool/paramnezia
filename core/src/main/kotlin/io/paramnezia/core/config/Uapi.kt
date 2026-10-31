package io.paramnezia.core.config

import io.paramnezia.core.crypto.WgKey
import java.net.Inet6Address
import java.net.InetAddress

/** Builds the amneziawg-go IPC ("UAPI") configuration string from a [TunnelConfig]. */
object Uapi {

    /**
     * @param resolve turns an endpoint host into an address; amneziawg-go only accepts IP literals.
     *   Called on the caller's thread, so resolve on a background dispatcher.
     */
    fun write(config: TunnelConfig, resolve: (String) -> InetAddress = InetAddress::getByName): String = buildString {
        val i = config.iface
        line("private_key", i.privateKey.toHex())
        i.listenPort?.let { line("listen_port", it.toString()) }
        for ((p, raw) in i.awg.entries.sortedBy { it.key.ordinal }) {
            val v = raw.trim()
            if (v.isEmpty()) continue
            when (p.kind) {
                AwgParam.Kind.KEY -> line(p.uapi, WgKey.fromBase64(v).toHex())
                AwgParam.Kind.BOOL -> line(p.uapi, (AwgParam.toBool(v) ?: throw ConfigException("Invalid ${p.conf}: $v")).toString())
                else -> line(p.uapi, v)
            }
        }
        line("replace_peers", "true")
        for (peer in config.peers) {
            line("public_key", peer.publicKey.toHex())
            peer.presharedKey?.let { line("preshared_key", it.toHex()) }
            peer.endpoint?.let { line("endpoint", resolveEndpoint(it, resolve)) }
            line("persistent_keepalive_interval", peer.persistentKeepalive ?: "0")
            line("replace_allowed_ips", "true")
            for (ip in peer.allowedIps) line("allowed_ip", ip.toString())
        }
    }

    /** Splits `host:port` / `[v6]:port` into its parts. */
    fun splitEndpoint(endpoint: String): Pair<String, Int> {
        val e = endpoint.trim()
        val (host, port) = if (e.startsWith("[")) {
            val close = e.indexOf(']')
            if (close < 0) throw ConfigException("Invalid endpoint: $endpoint")
            e.substring(1, close) to e.substring(close + 1).removePrefix(":")
        } else {
            val colon = e.lastIndexOf(':')
            if (colon < 0) throw ConfigException("Endpoint has no port: $endpoint")
            e.substring(0, colon) to e.substring(colon + 1)
        }
        val p = port.toIntOrNull()?.takeIf { it in 1..65535 } ?: throw ConfigException("Invalid endpoint port: $endpoint")
        return host to p
    }

    private fun resolveEndpoint(endpoint: String, resolve: (String) -> InetAddress): String {
        val (host, port) = splitEndpoint(endpoint)
        val addr = resolve(host)
        return if (addr is Inet6Address) "[${addr.hostAddress.substringBefore('%')}]:$port" else "${addr.hostAddress}:$port"
    }

    private fun StringBuilder.line(k: String, v: String) {
        append(k).append('=').append(v).append('\n')
    }
}
