package net.paramnezia.deploy

import java.time.Instant

/**
 * A server as an administrator sees it (from info.sh): the AmneziaWG engine, the interface and its
 * peers, the ports clients can use, the throughput tuning and the host. Every field is optional:
 * an AmneziaVPN-made container or an older host may not tell everything.
 */
data class ServerStatus(
    val os: String? = null,
    val kernel: String? = null,
    val uptimeSec: Long? = null,
    val load: String? = null,
    val cpus: Int? = null,
    /** Total and available memory, MiB. */
    val memTotalMb: Long? = null,
    val memFreeMb: Long? = null,
    /** The host's TCP congestion control (bbr when tuned). */
    val congestion: String? = null,
    val dockerVersion: String? = null,
    /** /etc/sysctl.d/90-paramnezia.conf is in place (tune.sh). */
    val hostTuned: Boolean = false,
    /** running, exited, … or "missing". */
    val container: String? = null,
    val startedAt: Instant? = null,
    /** When the image the container runs was built (the age of the engine). */
    val imageBuilt: Instant? = null,
    val engine: String? = null,
    val engineSum: String? = null,
    val tools: String? = null,
    val iface: String? = null,
    val mtu: Int? = null,
    val listenPort: Int? = null,
    val peers: Int? = null,
    /** Peers with a handshake in the last 3 minutes: connected right now. */
    val activePeers: Int? = null,
    val rxBytes: Long? = null,
    val txBytes: Long? = null,
    /** TCP MSS clamping rules in the container (tune.sh puts two). */
    val mssRules: Int? = null,
    /** Every host port leading to the listener (main and spare). */
    val ports: List<Int> = emptyList(),
    val ipv6: String? = null,
    /** The invite page container's state, if there is one. */
    val invitePage: String? = null,
) {
    val tuned: Boolean get() = hostTuned && (mssRules ?: 0) >= 2

    companion object {
        /** From info.sh's `::result::` lines. */
        fun parse(r: Map<String, String>): ServerStatus {
            fun s(k: String) = r[k]?.trim()?.takeIf { it.isNotEmpty() }
            fun l(k: String) = s(k)?.toLongOrNull()
            fun time(k: String) = s(k)?.let { runCatching { Instant.parse(it) }.getOrNull() }?.takeIf { it.epochSecond > 0 }
            val mem = s("mem")?.split(' ')?.mapNotNull { it.toLongOrNull() }
            val transfer = s("transfer")?.split(' ')?.mapNotNull { it.toLongOrNull() }
            return ServerStatus(
                os = s("os"), kernel = s("kernel"), uptimeSec = l("uptime"), load = s("load"), cpus = s("cpus")?.toIntOrNull(),
                memTotalMb = mem?.getOrNull(0), memFreeMb = mem?.getOrNull(1),
                congestion = s("cc"), dockerVersion = s("docker"), hostTuned = s("tuned") == "1",
                container = s("container"), startedAt = time("started"), imageBuilt = time("image_created"),
                engine = s("engine"), engineSum = s("engine_sum"), tools = s("tools"),
                iface = s("iface"), mtu = s("mtu")?.toIntOrNull(), listenPort = s("listen")?.toIntOrNull(),
                peers = s("peers")?.toIntOrNull(), activePeers = s("active")?.toIntOrNull(),
                rxBytes = transfer?.getOrNull(0), txBytes = transfer?.getOrNull(1),
                mssRules = s("mss")?.toIntOrNull(),
                ports = s("ports")?.split(',')?.mapNotNull { it.trim().toIntOrNull() }.orEmpty(),
                ipv6 = s("ipv6")?.takeIf { ':' in it }, invitePage = s("invite"),
            )
        }
    }
}
