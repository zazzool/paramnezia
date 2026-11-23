package net.paramnezia.core.config

import java.security.MessageDigest

/**
 * How a tunnel presents itself on the wire, on top of what the server fixes (S1–S4, H1–H4, the
 * header protection key). Everything here is decided by the client alone, so it can change per
 * network and per attempt without touching the server, and AmneziaVPN keeps working with the same
 * server:
 *
 *  - **Signature packets** (I1–I5) go out before every handshake and are dropped by the server.
 *    Instead of one static packet shared by every server in the world (the DNS answer for icloud.com
 *    AmneziaVPN ships, which a censor can match byte for byte), each server gets a coherent
 *    [Mimic] chosen from its own key: a STUN Binding Request, as WebRTC sends to a random port before
 *    a call, or that request followed by a DTLS 1.2 ClientHello with WebRTC's `use_srtp` extension.
 *    The first packet of a flow then parses as a known protocol, which is what the "fully encrypted
 *    traffic" detectors (Wu et al., USENIX Security 2023) exempt, and different servers no longer
 *    share one fingerprint.
 *  - **Endpoint**: the server can listen on several ports (and an IPv6 address); a blocked or
 *    throttled port is left for the next one.
 *  - **Content padding**: zeros inside the encrypted payload (`content_padding_addition`), which any
 *    WireGuard receiver strips. It replaces AmneziaWG 3's random trailers on data packets, which pad
 *    every small packet (each TCP ACK of a download) by half the free room on average, ~650 bytes:
 *    tens of percent of hidden upload. Padding is heavy only at the start of a flow, where
 *    classifiers look (the FRONT idea, Gong and Wang, USENIX Security 2020), and small afterwards.
 *
 * The client walks a [ladder] of [Variant]s when handshakes fail and remembers per network what
 * worked ([CamouflageLadder]).
 */
object Camouflage {

    enum class Mimic(val id: String) {
        /** A STUN Binding Request (RFC 8489), what a WebRTC client sends first. */
        STUN("stun"),
        /** STUN, then a DTLS 1.2 ClientHello offering SRTP, as a WebRTC call starts. */
        WEBRTC("webrtc"),
        /** No signature packets: only the server's junk packets. The last resort. */
        PLAIN("plain");

        companion object {
            fun of(id: String): Mimic? = entries.firstOrNull { it.id == id }
        }
    }

    /** One way to reach a server: address, port, first packets. */
    data class Variant(val host: String, val port: Int, val mimic: Mimic) {
        val id: String get() = "${mimic.id}@${endpoint()}"
        fun endpoint(): String = Uapi.formatEndpoint(host, port)
    }

    /** What a tunnel pads: [start] for the first [afterMs] of a flow, then [steady]. UAPI ranges. */
    data class Padding(val start: String, val steady: String, val afterMs: Long)

    /** 20 bytes: type 0x0001, length 0, the magic cookie and a fresh 96-bit transaction id. */
    const val STUN_BINDING = "<b 0x000100002112a442><r 12>"

    /** The STUN request, then the DTLS ClientHello ([dtlsClientHello]). */
    val WEBRTC_PACKETS: List<String> by lazy { listOf(STUN_BINDING, dtlsClientHello()) }

    /** The mimic a server presents by default, from its public key: the same on every device. */
    fun mimicFor(serverKey: String): Mimic = if (digest(serverKey)[0].toInt() and 1 == 0) Mimic.STUN else Mimic.WEBRTC

    /** The signature packets of [m], as I1, I2, … */
    fun signatures(m: Mimic): Map<AwgParam, String> = when (m) {
        Mimic.STUN -> mapOf(AwgParam.I1 to STUN_BINDING)
        Mimic.WEBRTC -> mapOf(AwgParam.I1 to WEBRTC_PACKETS[0], AwgParam.I2 to WEBRTC_PACKETS[1])
        Mimic.PLAIN -> emptyMap()
    }

    /** Variants per server tried before another server gets a turn (see [CamouflageLadder]). */
    const val ROUND = 4

    /**
     * The order to try a server in. The first [ROUND] steps are the ones most likely to help, so a
     * device with other servers moves on soon when an address is banned outright:
     *  1. the main port with the server's own signature;
     *  2. the main port with the other signature (a fingerprint blocked, not the address);
     *  3. the other ports (a port blocked or throttled);
     *  4. the IPv6 address, when the server has one and so does the device (bans are mostly IPv4);
     *  5. no signature at all.
     */
    fun ladder(
        host: String,
        port: Int,
        serverKey: String,
        altPorts: List<Int> = emptyList(),
        altHosts: List<String> = emptyList(),
        ipv6: Boolean = true,
        obfuscated: Boolean = true,
    ): List<Variant> {
        val own = mimicFor(serverKey)
        val other = if (own == Mimic.STUN) Mimic.WEBRTC else Mimic.STUN
        val ports = (listOf(port) + altPorts).filter { it in 1..65535 }.distinct()
        val hosts6 = if (ipv6) altHosts.filter { ':' in it && it != host } else emptyList()
        val hosts4 = altHosts.filter { ':' !in it && it != host }
        if (!ipv6 && ':' in host && hosts4.isNotEmpty()) {
            return ladder(hosts4.first(), port, serverKey, altPorts, hosts4.drop(1), ipv6, obfuscated)
        }
        val out = LinkedHashSet<Variant>()
        if (!obfuscated) {
            // Plain WireGuard: signatures mean nothing to it; only addresses and ports change.
            for (p in ports) out += Variant(host, p, Mimic.PLAIN)
            for (h in hosts6 + hosts4) for (p in ports) out += Variant(h, p, Mimic.PLAIN)
            return out.toList()
        }
        out += Variant(host, port, own)
        out += Variant(host, port, other)
        for (p in ports.drop(1)) out += Variant(host, p, own)
        // Try the other address family before spending the whole round on spare ports.
        val crossFamily = (if (':' in host) hosts4 else hosts6).firstOrNull()?.let { Variant(it, port, own) }
        if (crossFamily != null) {
            val list = out.toMutableList()
            list.add(minOf(ROUND - 1, list.size), crossFamily)
            out.clear(); out += list
        }
        for (h in hosts6 + hosts4) for (p in ports) out += Variant(h, p, own)
        out += Variant(host, port, Mimic.PLAIN)
        for (p in ports.drop(1)) out += Variant(host, p, other)
        return out.toList()
    }

    /** Whether [config] talks to an AmneziaWG server (any obfuscation parameter set). */
    fun obfuscated(config: TunnelConfig): Boolean {
        val a = config.iface.awg
        return listOf(AwgParam.JC, AwgParam.S1, AwgParam.H1, AwgParam.HEADER_PROTECTION_KEY).any { p ->
            a[p]?.let { v -> v.isNotBlank() && v != "0" && !(p == AwgParam.H1 && v == "1" && a[AwgParam.H2] == null) } == true
        }
    }

    /** The ladder for a config on its own (a standalone server: only its signatures change). */
    fun ladderOf(config: TunnelConfig, altPorts: List<Int> = emptyList(), altHosts: List<String> = emptyList(), ipv6: Boolean = true): List<Variant> {
        val peer = config.peers.firstOrNull() ?: return emptyList()
        val (host, port) = Uapi.splitEndpoint(peer.endpoint ?: return emptyList())
        return ladder(host, port, peer.publicKey.toBase64(), altPorts, altHosts, ipv6, obfuscated(config))
    }

    /**
     * [config] dressed as [v]: its endpoint, its signature packets and junk to match, and content
     * padding for the start of the flow (what follows is in [padding]).
     */
    fun apply(config: TunnelConfig, v: Variant): TunnelConfig {
        val awg = LinkedHashMap(config.iface.awg)
        if (obfuscated(config)) {
            for (i in I_PACKETS) awg.remove(i)
            awg.putAll(signatures(v.mimic))
            if (v.mimic != Mimic.PLAIN) {
                // A WebRTC client sends a handful of small packets before media, not a burst of
                // random ones: fewer, longer junk packets after the signature. Junk is the client's
                // own business; the server neither checks nor needs these values.
                awg[AwgParam.JC] = "2"
                awg[AwgParam.JMIN] = "40"
                awg[AwgParam.JMAX] = "120"
            }
        }
        padding(config)?.let { awg[AwgParam.CONTENT_PADDING_ADDITION] = it.start }
        val peers = config.peers.mapIndexed { i, p -> if (i == 0) p.copy(endpoint = v.endpoint()) else p }
        return config.copy(iface = config.iface.copy(awg = awg), peers = peers)
    }

    /**
     * Padding for [config]: with random trailers on (AmneziaWG 3) the steady value replaces them on
     * data packets; without, data packets go back to WireGuard's own 16-byte rounding ("0").
     */
    fun padding(config: TunnelConfig): Padding? {
        val trailers = config.iface.awg[AwgParam.RANDOM_TRAILERS]?.let(AwgParam::toBool) == true
        return if (trailers) Padding(start = "24-255", steady = "0-31", afterMs = FRONT_MS)
        else Padding(start = "16-160", steady = "0", afterMs = FRONT_MS)
    }

    /** How long the start of a flow is padded heavily. */
    const val FRONT_MS = 20_000L

    private val I_PACKETS = listOf(AwgParam.I1, AwgParam.I2, AwgParam.I3, AwgParam.I4, AwgParam.I5)

    private fun digest(s: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(("paramnezia-camouflage|" + s).toByteArray())

    /**
     * A DTLS 1.2 ClientHello as a WebRTC stack sends it (BoringSSL's offer: ECDHE with AES-GCM and
     * ChaCha20, `use_srtp`, extended master secret), its 32 random bytes fresh on every handshake.
     * Lengths are computed, so the record parses cleanly.
     */
    fun dtlsClientHello(): String {
        fun hex(vararg b: Int) = b.joinToString("") { "%02x".format(it and 0xFF) }
        fun u16(n: Int) = hex(n shr 8, n)
        fun u24(n: Int) = hex(n shr 16, n shr 8, n)
        val suites = listOf(0xc02b, 0xc02f, 0xcca9, 0xcca8, 0xc009, 0xc013, 0xc00a, 0xc014, 0x009c, 0x009d, 0x002f, 0x0035)
        val suitesHex = suites.joinToString("") { u16(it) }
        val ext = StringBuilder()
        fun extension(type: Int, body: String) { ext.append(u16(type)).append(u16(body.length / 2)).append(body) }
        extension(0x0017, "") // extended_master_secret
        extension(0xff01, "00") // renegotiation_info
        extension(0x000a, u16(6) + u16(0x001d) + u16(0x0017) + u16(0x0018)) // supported_groups
        extension(0x000b, "0100") // ec_point_formats: uncompressed
        extension(0x0023, "") // session_ticket
        val sigs = listOf(0x0403, 0x0804, 0x0401, 0x0503, 0x0805, 0x0501, 0x0806, 0x0601, 0x0201)
        extension(0x000d, u16(sigs.size * 2) + sigs.joinToString("") { u16(it) }) // signature_algorithms
        extension(0x000e, u16(4) + u16(0x0007) + u16(0x0001) + "00") // use_srtp: AEAD_AES_128_GCM, AES128_CM_SHA1_80
        // Body after the random: session id, cookie, suites, compression, extensions.
        val tail = "00" + "00" + u16(suites.size * 2) + suitesHex + "0100" + u16(ext.length / 2) + ext
        val bodyLen = 2 + 32 + tail.length / 2
        val handshakeHead = "01" + u24(bodyLen) + u16(0) + u24(0) + u24(bodyLen)
        val recordLen = handshakeHead.length / 2 + bodyLen
        val record = "16" + "feff" + u16(0) + "000000000000" + u16(recordLen)
        return "<b 0x$record${handshakeHead}fefd><r 32><b 0x$tail>"
    }
}

/**
 * Walks [Camouflage.ladder] for each server and remembers, per network, the variant that last
 * worked. Shared by the Android service host and the Windows app; [memory] is persisted by them.
 *
 * Keys are `<network>|<server id>`; at most [MAX_ENTRIES] networks × servers are kept, the oldest
 * forgotten first.
 */
class CamouflageLadder(memory: Map<String, String> = emptyMap(), private val onChange: (Map<String, String>) -> Unit = {}) {
    private val remembered = LinkedHashMap(memory)
    /** The variant in use per server during this run (id), and steps since it was last reset. */
    private val current = HashMap<String, String>()
    private val steps = HashMap<String, Int>()

    @Synchronized fun memory(): Map<String, String> = LinkedHashMap(remembered)

    /** The variant to connect [serverId] with on [network]: the one in use, else the one that last worked here, else the first. */
    @Synchronized
    fun pick(network: String, serverId: String, ladder: List<Camouflage.Variant>): Camouflage.Variant? {
        if (ladder.isEmpty()) return null
        current[serverId]?.let { id -> ladder.firstOrNull { it.id == id }?.let { return it } }
        val v = remembered["$network|$serverId"]?.let { id -> ladder.firstOrNull { it.id == id } } ?: ladder.first()
        current[serverId] = v.id
        return v
    }

    /**
     * [failed] did not answer. Returns the next variant of the same server, or null when this round
     * is over ([Camouflage.ROUND] tries) and [mayStay] is false: then the caller tries another server,
     * and coming back here continues where the ladder stopped.
     */
    @Synchronized
    fun next(serverId: String, failed: String, ladder: List<Camouflage.Variant>, mayStay: Boolean): Camouflage.Variant? {
        if (ladder.size <= 1) return null
        val at = ladder.indexOfFirst { it.id == failed }.coerceAtLeast(0)
        val n = (steps[serverId] ?: 0) + 1
        val v = ladder[(at + 1) % ladder.size]
        current[serverId] = v.id
        if (n >= Camouflage.ROUND && !mayStay) { steps[serverId] = 0; return null }
        steps[serverId] = n
        return v
    }

    /** [variant] carried traffic on [network]: use it first there from now on. */
    @Synchronized
    fun worked(network: String, serverId: String, variant: String) {
        steps.remove(serverId)
        current[serverId] = variant
        val key = "$network|$serverId"
        if (remembered[key] == variant) return
        remembered.remove(key)
        remembered[key] = variant
        while (remembered.size > MAX_ENTRIES) remembered.remove(remembered.keys.first())
        onChange(LinkedHashMap(remembered))
    }

    /** A new network: start each server from what worked there (or the top), not from the last network's variant. */
    @Synchronized
    fun networkChanged() { current.clear(); steps.clear() }

    companion object {
        const val MAX_ENTRIES = 64
    }
}
