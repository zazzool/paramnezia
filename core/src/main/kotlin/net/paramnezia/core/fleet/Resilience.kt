package net.paramnezia.core.fleet

/** Reachability of a server as one device saw it. Collected by the admin device. */
data class HealthReport(val serverId: String, val reachable: Boolean, val at: Long)

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
    private val reports = HashMap<String, HashMap<String, HealthReport>>() // server -> reporter -> report

    fun record(reporter: String, h: HealthReport) {
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
