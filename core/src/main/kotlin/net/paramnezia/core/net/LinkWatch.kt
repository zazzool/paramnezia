package net.paramnezia.core.net

/**
 * Decides, from cheap observations, whether a tunnel really carries traffic and what to do when it
 * does not. Shared by the Android VPN service and the Windows engine, which only measure (read the
 * tunnel's counters, send a probe) and carry out the [Action]s; the decisions live here, in one
 * tested place.
 *
 * The life of a connection:
 *  - **Verifying.** Right after the tunnel is built nothing is known: a WireGuard device "up" says
 *    nothing about the server answering. Probes go out quickly one after another until one comes back
 *    through the tunnel ([Action.Verified]); only then is the connection shown as protected. No
 *    answer within [Policy.verifyMs]: the tunnel is rebuilt, and after [Policy.failoverAfter] failed
 *    builds in a row the host is asked for another server.
 *  - **Established.** Traffic that came back since the last look proves the path by itself, so a
 *    busy tunnel costs no probes at all. An idle one is probed (with the screen on), or only when
 *    the counters look wrong (screen off: stale session, sent without reply).
 *  - **Failures in a row climb a ladder:** first only a quick second look (one lost packet is not
 *    a broken tunnel), then fresh sockets in the same tunnel (cures stuck NAT bindings and sockets
 *    left on a network that is gone, without touching routes: nothing leaks past the tunnel), then a
 *    full rebuild, which starts verifying again. Any answer resets the ladder.
 */
class LinkWatch(private val policy: Policy = Policy()) {
    data class Policy(
        /** How long a fresh tunnel has to answer before it is rebuilt. */
        val verifyMs: Long = 20_000,
        /** Pauses between verification probes; the last one repeats. */
        val verifySteps: List<Long> = listOf(250, 750, 1_000, 1_500, 2_000, 3_000),
        /** Bytes received since the last look that prove the path without a probe. */
        val provenRxBytes: Long = 2_048,
        /** WireGuard renews a session every 2 minutes while traffic flows; older than this means stale. */
        val staleHandshakeSec: Long = 180,
        /** Probe even a busy tunnel this often with the screen on, so the ping shown stays true. */
        val rttMaxAgeMs: Long = 5 * 60_000,
        val watchScreenOnMs: Long = 60_000,
        val watchScreenOffMs: Long = 15 * 60_000,
        /** Second look after a first failure. */
        val confirmMs: Long = 4_000,
        /** Failures in a row (established) before fresh sockets, and before a rebuild. */
        val rebindAfter: Int = 2,
        val rebuildAfter: Int = 3,
        /** Failed builds of the same server in a row before asking for another one. */
        val failoverAfter: Int = 2,
        val backoffBaseMs: Long = 5_000,
        val backoffMaxMs: Long = 5 * 60_000,
        /** A dead server with the screen off is retried less often: the user is not waiting for it. */
        val backoffMaxScreenOffMs: Long = 15 * 60_000,
    )

    data class Counters(val rxBytes: Long, val txBytes: Long, val lastHandshakeEpochSec: Long)

    enum class Phase { VERIFYING, GOOD, UNSTABLE }

    /** What to do after a look. */
    sealed interface Action {
        /** Look again in [ms]. */
        data class Wait(val ms: Long) : Action
        /** Send a probe now and report it with [onProbe]. */
        data object Probe : Action
        /** First answer of a fresh tunnel: show it as protected; look again in [ms]. */
        data class Verified(val rttMs: Long, val ms: Long) : Action
        /** Fresh sockets in the same tunnel, then look again in [ms]. */
        data class Rebind(val ms: Long) : Action
        /** Build the tunnel again after [delayMs] (0 = now). */
        data class Rebuild(val delayMs: Long) : Action
        /** The server looks dead: switch to another one if there is one, else rebuild after [delayMs]. */
        data class Failover(val delayMs: Long) : Action
    }

    var phase: Phase = Phase.VERIFYING; private set
    /** Smoothed probe round trip, ms; null until a probe came back. */
    var rttMs: Long? = null; private set
    var rttAt: Long = 0; private set
    /** Failures in a row since the last answer. */
    var failures: Int = 0; private set
    /** Builds in a row that never answered. */
    var failedBuilds: Int = 0; private set

    private var verifyStart = 0L
    private var verifyStep = 0
    private var last: Counters? = null
    private var suspected = false

    /** A (re)built tunnel: verify it before trusting it. Pass [sameServer] = false after a switch. */
    fun started(now: Long, sameServer: Boolean = true) {
        phase = Phase.VERIFYING
        verifyStart = now
        verifyStep = 0
        failures = 0
        last = null
        if (!sameServer) failedBuilds = 0
    }

    /** The sockets were replaced: the counters keep counting, the next look starts fresh. */
    fun rebound() { last = null }

    /** Something hints at trouble (the network changed, a site saw the real address): probe at the next look. */
    fun suspect() { suspected = true; last = null }

    /**
     * A look at the counters (no network traffic). Null [c] means the tunnel did not answer at all
     * (wedged, or gone from under us), which counts as a failed probe.
     */
    fun onCounters(now: Long, c: Counters?, screenOn: Boolean): Action {
        if (c == null) return onProbe(now, null, screenOn)
        val prev = last
        last = c
        if (phase == Phase.VERIFYING) return Action.Probe
        val handshakeAge = if (c.lastHandshakeEpochSec == 0L) Long.MAX_VALUE else now / 1000 - c.lastHandshakeEpochSec
        val stale = handshakeAge > policy.staleHandshakeSec
        val rx = if (prev != null) c.rxBytes - prev.rxBytes else 0
        val sentWithoutReply = prev != null && c.txBytes > prev.txBytes && rx <= 0
        if (phase == Phase.UNSTABLE || suspected) { suspected = false; return Action.Probe }
        val proven = rx >= policy.provenRxBytes && !stale
        val rttOld = now - rttAt > policy.rttMaxAgeMs
        val look = when {
            stale || sentWithoutReply -> true
            proven -> screenOn && rttOld
            else -> screenOn
        }
        return if (look) Action.Probe else Action.Wait(interval(screenOn))
    }

    /** The result of a probe: the round trip in ms, or null for no answer. */
    fun onProbe(now: Long, rtt: Long?, screenOn: Boolean): Action {
        if (rtt != null) {
            rttMs = rttMs?.let { (it * 3 + rtt) / 4 } ?: rtt
            rttAt = now
            failures = 0
            suspected = false
            val wasVerifying = phase == Phase.VERIFYING
            phase = Phase.GOOD
            if (wasVerifying) { failedBuilds = 0; return Action.Verified(rtt, interval(screenOn)) }
            return Action.Wait(interval(screenOn))
        }
        if (phase == Phase.VERIFYING) {
            if (now - verifyStart < policy.verifyMs) {
                val pause = policy.verifySteps[minOf(verifyStep, policy.verifySteps.lastIndex)]
                verifyStep++
                return Action.Wait(pause)
            }
            failedBuilds++
            val delay = backoff(failedBuilds - 1, screenOn)
            return if (failedBuilds >= policy.failoverAfter) Action.Failover(delay) else Action.Rebuild(delay)
        }
        failures++
        phase = Phase.UNSTABLE
        return when {
            failures >= policy.rebuildAfter -> Action.Rebuild(0)
            failures >= policy.rebindAfter -> Action.Rebind(policy.confirmMs)
            else -> Action.Wait(policy.confirmMs)
        }
    }

    /** The device itself is offline: nothing to fix, nothing counts; look again later. */
    fun offline(screenOn: Boolean): Action.Wait {
        last = null
        return Action.Wait(if (phase == Phase.VERIFYING) policy.confirmMs else minOf(interval(screenOn), policy.watchScreenOnMs))
    }

    /** Pause before the next look while all is well. */
    fun interval(screenOn: Boolean): Long = if (screenOn) policy.watchScreenOnMs else policy.watchScreenOffMs

    private fun backoff(n: Int, screenOn: Boolean): Long =
        if (n <= 0) 0 else minOf(policy.backoffBaseMs shl minOf(n - 1, 10), if (screenOn) policy.backoffMaxMs else policy.backoffMaxScreenOffMs)
}
