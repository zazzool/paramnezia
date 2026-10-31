package io.paramnezia.core.config

import io.paramnezia.core.crypto.WgKey
import io.paramnezia.core.net.IpPrefix

/** Reads and writes the wg-quick / awg-quick INI format used by every WireGuard and AmneziaWG client. */
object WgQuick {

    fun parse(text: String): TunnelConfig {
        var iface: Section? = null
        val peers = ArrayList<Section>()
        var current: Section? = null

        for ((lineNo, rawLine) in text.lineSequence().withIndex()) {
            // wg-quick drops everything after '#'; AmneziaWG I-packet tags never contain it.
            val line = rawLine.substringBefore('#').trim()
            if (line.isEmpty()) continue
            if (line.startsWith("[") && line.endsWith("]")) {
                current = when (line.substring(1, line.length - 1).trim().lowercase()) {
                    "interface" -> iface ?: Section().also { iface = it }
                    "peer" -> Section().also { peers += it }
                    else -> throw ConfigException("Unknown section $line on line ${lineNo + 1}")
                }
                continue
            }
            val eq = line.indexOf('=')
            if (eq <= 0 || current == null) throw ConfigException("Unexpected line ${lineNo + 1}: $rawLine")
            current.entries += line.substring(0, eq).trim() to line.substring(eq + 1).trim()
        }
        val i = iface ?: throw ConfigException("Missing [Interface] section")
        return TunnelConfig(parseInterface(i), peers.map(::parsePeer))
    }

    /** One INI section, in file order, keys with their original casing. */
    private class Section {
        val entries = ArrayList<Pair<String, String>>()

        fun all(key: String) = entries.filter { it.first.equals(key, ignoreCase = true) }.map { it.second }
        fun single(key: String): String? = all(key).lastOrNull()
        fun list(key: String): List<String> =
            all(key).flatMap { it.split(',') }.map { it.trim() }.filter { it.isNotEmpty() }
        fun extras(known: Set<String>) = entries.filter { it.first.lowercase() !in known }
    }

    private val interfaceKeys = setOf(
        "privatekey", "address", "dns", "mtu", "listenport", "excludedapplications", "includedapplications",
    ) + AwgParam.entries.map { it.conf.lowercase() }
    private val peerKeys = setOf("publickey", "presharedkey", "allowedips", "endpoint", "persistentkeepalive")

    private fun parseInterface(s: Section): TunnelConfig.Interface {
        val priv = s.single("PrivateKey") ?: throw ConfigException("Interface has no PrivateKey")
        val dnsAll = s.list("DNS")
        val awg = LinkedHashMap<AwgParam, String>()
        for (p in AwgParam.entries) {
            val v = s.single(p.conf) ?: continue
            if (v.isNotEmpty()) awg[p] = v
        }
        return TunnelConfig.Interface(
            privateKey = key(priv, "PrivateKey"),
            addresses = s.list("Address").map { prefix(it, "Address") },
            dns = dnsAll.filter { IpPrefix.parseOrNull(it) != null },
            dnsSearch = dnsAll.filter { IpPrefix.parseOrNull(it) == null },
            mtu = s.single("MTU")?.let { int(it, "MTU") },
            listenPort = s.single("ListenPort")?.let { int(it, "ListenPort") },
            excludedApplications = s.list("ExcludedApplications"),
            includedApplications = s.list("IncludedApplications"),
            awg = awg,
            extra = s.extras(interfaceKeys),
        )
    }

    private fun parsePeer(s: Section): TunnelConfig.Peer {
        val pub = s.single("PublicKey") ?: throw ConfigException("Peer has no PublicKey")
        return TunnelConfig.Peer(
            publicKey = key(pub, "PublicKey"),
            presharedKey = s.single("PresharedKey")?.takeIf { it.isNotEmpty() }?.let { key(it, "PresharedKey") },
            allowedIps = s.list("AllowedIPs").map { prefix(it, "AllowedIPs") },
            endpoint = s.single("Endpoint")?.takeIf { it.isNotEmpty() },
            persistentKeepalive = s.single("PersistentKeepalive")?.takeIf { it.isNotEmpty() && it != "off" && it != "0" },
            extra = s.extras(peerKeys),
        )
    }

    private fun key(v: String, name: String) =
        runCatching { WgKey.fromBase64(v) }.getOrElse { throw ConfigException("Invalid $name", it) }

    private fun prefix(v: String, name: String) =
        IpPrefix.parseOrNull(v) ?: throw ConfigException("Invalid $name: $v")

    private fun int(v: String, name: String) = v.toIntOrNull() ?: throw ConfigException("Invalid $name: $v")

    fun write(config: TunnelConfig): String = buildString {
        val i = config.iface
        appendLine("[Interface]")
        if (i.addresses.isNotEmpty()) appendLine("Address = ${i.addresses.joinToString(", ")}")
        if (i.dns.isNotEmpty() || i.dnsSearch.isNotEmpty()) appendLine("DNS = ${(i.dns + i.dnsSearch).joinToString(", ")}")
        appendLine("PrivateKey = ${i.privateKey.toBase64()}")
        i.listenPort?.let { appendLine("ListenPort = $it") }
        i.mtu?.let { appendLine("MTU = $it") }
        if (i.excludedApplications.isNotEmpty()) appendLine("ExcludedApplications = ${i.excludedApplications.joinToString(", ")}")
        if (i.includedApplications.isNotEmpty()) appendLine("IncludedApplications = ${i.includedApplications.joinToString(", ")}")
        for ((p, v) in i.awg.entries.sortedBy { it.key.ordinal }) appendLine("${p.conf} = $v")
        for ((k, v) in i.extra) appendLine("$k = $v")
        for (p in config.peers) {
            appendLine()
            appendLine("[Peer]")
            appendLine("PublicKey = ${p.publicKey.toBase64()}")
            p.presharedKey?.let { appendLine("PresharedKey = ${it.toBase64()}") }
            if (p.allowedIps.isNotEmpty()) appendLine("AllowedIPs = ${p.allowedIps.joinToString(", ")}")
            p.endpoint?.let { appendLine("Endpoint = $it") }
            p.persistentKeepalive?.let { appendLine("PersistentKeepalive = $it") }
            for ((k, v) in p.extra) appendLine("$k = $v")
        }
    }
}
