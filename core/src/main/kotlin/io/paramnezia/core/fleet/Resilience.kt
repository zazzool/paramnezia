package io.paramnezia.core.fleet

import io.paramnezia.core.amnezia.AmneziaKey
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Messages trusted devices exchange through the tunnel (UDP to each other's tunnel address).
 * The server only forwards packets whose source address belongs to the sender's key, so the source
 * address identifies the device. Manifests carry their own signature and need no further trust.
 */
@Serializable
sealed interface GossipMessage {
    /** "Here is the newest manifest I know." Sent when a device learns a newer version. */
    @Serializable @SerialName("manifest")
    data class Manifest(val signed: String) : GossipMessage

    /** "My newest version is [version]." Peers that know a newer one answer with [Manifest]. */
    @Serializable @SerialName("version")
    data class Version(val fleetId: String, val version: Long) : GossipMessage

    /** Reachability of a server as seen by the sender. Collected by the admin device. */
    @Serializable @SerialName("health")
    data class Health(val serverId: String, val reachable: Boolean, val at: Long) : GossipMessage

    companion object {
        const val PORT = 51999
        private val json = Json { ignoreUnknownKeys = true; classDiscriminator = "t" }

        fun encode(m: GossipMessage): ByteArray = AmneziaKey.qCompress(json.encodeToString(serializer(), m).toByteArray(), 9)

        fun decode(bytes: ByteArray): GossipMessage? = runCatching {
            val raw = AmneziaKey.qUncompressOrNull(bytes) ?: return null
            json.decodeFromString(serializer(), raw.decodeToString())
        }.getOrNull()
    }
}

/**
 * Picks which fleet server a device should use. A server is considered blocked after
 * [deadAfterMs] without a handshake while the device had a working network; the device then moves to
 * the next one and retries the blocked server later.
 */
class FailoverPolicy(
    private val deadAfterMs: Long = 150_000,
    private val retryBlockedAfterMs: Long = 6 * 3_600_000L,
) {
    private val blockedAt = HashMap<String, Long>()

    fun markBlocked(serverId: String, now: Long) { blockedAt[serverId] = now }
    fun markHealthy(serverId: String) { blockedAt.remove(serverId) }
    fun isBlocked(serverId: String, now: Long) = blockedAt[serverId]?.let { now - it < retryBlockedAfterMs } == true

    /** True when the current server should be given up on. */
    fun shouldFailOver(lastHandshakeAgeMs: Long?, connectedForMs: Long, networkValidated: Boolean): Boolean =
        networkValidated && connectedForMs >= deadAfterMs && (lastHandshakeAgeMs == null || lastHandshakeAgeMs >= deadAfterMs)

    /** First server in manifest order that is not currently considered blocked; falls back to the master. */
    fun choose(servers: List<FleetServer>, now: Long): FleetServer? =
        servers.firstOrNull { !isBlocked(it.id, now) } ?: servers.firstOrNull()
}

/**
 * Admin side: decides when the master is gone for everyone (not just one flaky phone) and a standby
 * should be promoted. Reports older than [windowMs] are forgotten.
 */
class HealthBook(private val windowMs: Long = 15 * 60_000L, private val quorum: Int = 2) {
    private val reports = HashMap<String, HashMap<String, GossipMessage.Health>>() // server -> reporter -> report

    fun record(reporter: String, h: GossipMessage.Health) {
        reports.getOrPut(h.serverId) { HashMap() }[reporter] = h
    }

    fun isDown(serverId: String, now: Long, devices: Int): Boolean {
        val fresh = reports[serverId].orEmpty().values.filter { now - it.at < windowMs }
        if (fresh.any { it.reachable }) return false
        val down = fresh.count { !it.reachable }
        // With a single trusted device its own report is enough.
        return down >= minOf(quorum, maxOf(1, devices))
    }

    /** A new server order with the first healthy standby promoted, or null if no change is needed. */
    fun promotion(manifest: FleetManifest, now: Long): List<FleetServer>? {
        val master = manifest.servers.firstOrNull() ?: return null
        if (!isDown(master.id, now, manifest.devices.size)) return null
        val standby = manifest.servers.drop(1).firstOrNull { !isDown(it.id, now, manifest.devices.size) } ?: return null
        return listOf(standby) + manifest.servers.filter { it.id != standby.id && it.id != master.id } + master
    }
}
