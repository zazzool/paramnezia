package net.paramnezia.core

import net.paramnezia.core.net.LinkProbe
import net.paramnezia.core.net.LinkWatch
import net.paramnezia.core.net.LinkWatch.Action
import net.paramnezia.core.net.LinkWatch.Counters
import net.paramnezia.core.net.LinkWatch.Phase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel

class LinkWatchTest {
    private val t0 = 1_700_000_000_000L
    private fun c(rx: Long, tx: Long, hsAgoSec: Long, now: Long) = Counters(rx, tx, now / 1000 - hsAgoSec)

    @Test
    fun freshTunnelIsNotTrustedUntilAProbeComesBack() {
        val w = LinkWatch()
        w.started(t0)
        assertEquals(Phase.VERIFYING, w.phase)
        // Counters alone never verify: a handshake does not prove the internet behind the server.
        assertEquals(Action.Probe, w.onCounters(t0, c(5000, 5000, 1, t0), screenOn = true))
        assertTrue(w.onProbe(t0 + 100, null, true) is Action.Wait)
        assertEquals(Phase.VERIFYING, w.phase)
        val v = w.onProbe(t0 + 900, 42, true)
        assertTrue(v is Action.Verified)
        assertEquals(42L, (v as Action.Verified).rttMs)
        assertEquals(Phase.GOOD, w.phase)
    }

    @Test
    fun deadServerIsRebuiltThenFailedOver() {
        val w = LinkWatch()
        w.started(t0)
        var now = t0
        var a: Action = Action.Probe
        while (a !is Action.Rebuild && a !is Action.Failover) { now += 1000; a = w.onProbe(now, null, true) }
        assertEquals(Action.Rebuild(0), a)
        w.started(now)
        a = Action.Probe
        while (a !is Action.Rebuild && a !is Action.Failover) { now += 1000; a = w.onProbe(now, null, true) }
        assertTrue("second failed build asks for another server", a is Action.Failover)
        assertTrue((a as Action.Failover).delayMs > 0)
        // A switch to another server starts its own count.
        w.started(now, sameServer = false)
        assertEquals(0, w.failedBuilds)
    }

    @Test
    fun failuresClimbTheLadderAndAnAnswerResetsIt() {
        val w = LinkWatch()
        w.started(t0)
        w.onProbe(t0, 30, true)
        assertTrue(w.onProbe(t0 + 60_000, null, true) is Action.Wait)
        assertEquals(Phase.UNSTABLE, w.phase)
        assertTrue(w.onProbe(t0 + 64_000, null, true) is Action.Rebind)
        assertEquals(Action.Rebuild(0), w.onProbe(t0 + 68_000, null, true))
        // ...but one answer in between puts everything back.
        val w2 = LinkWatch()
        w2.started(t0); w2.onProbe(t0, 30, true)
        w2.onProbe(t0 + 60_000, null, true)
        assertTrue(w2.onProbe(t0 + 64_000, 35, true) is Action.Wait)
        assertEquals(Phase.GOOD, w2.phase)
        assertTrue(w2.onProbe(t0 + 120_000, null, true) is Action.Wait)
    }

    @Test
    fun busyTunnelCostsNoProbesAndIdleScreenOffCostsNone() {
        val w = LinkWatch()
        w.started(t0)
        w.onCounters(t0, c(0, 0, 1, t0), true)
        w.onProbe(t0, 30, true)
        var now = t0 + 60_000
        w.onCounters(now, c(1_000, 1_000, 10, now), true)
        now += 60_000
        // 50 KB came back since the last look: the path is proven.
        assertTrue(w.onCounters(now, c(51_000, 20_000, 30, now), true) is Action.Wait)
        // Screen off, idle, fresh session: no traffic from us at all.
        now += 60_000
        assertTrue(w.onCounters(now, c(51_000, 20_000, 60, now), false) is Action.Wait)
        // Screen off but sent without reply: look.
        now += 60_000
        assertEquals(Action.Probe, w.onCounters(now, c(51_000, 25_000, 90, now), false))
        // A hint from outside (network changed) makes even an idle screen-off look probe once.
        w.onProbe(now, 30, false)
        now += 60_000
        w.suspect()
        assertEquals(Action.Probe, w.onCounters(now, c(51_000, 25_000, 30, now), false))
        w.onProbe(now, 30, false)
        // Stale session with the screen off: look.
        now += 60_000
        assertEquals(Action.Probe, w.onCounters(now, c(51_000, 25_000, 400, now), false))
    }

    @Test
    fun wedgedTunnelCountsAsFailure() {
        val w = LinkWatch()
        w.started(t0); w.onProbe(t0, 30, true)
        w.onCounters(t0 + 60_000, null, true)
        assertEquals(1, w.failures)
        assertEquals(Phase.UNSTABLE, w.phase)
    }

    @Test
    fun probeAnswersOnTcpOrDnsAndGivesUpQuietly() {
        // A local fake resolver stands in for the internet.
        DatagramChannel.open().use { resolver ->
            resolver.bind(InetSocketAddress("127.0.0.1", 0))
            val thread = Thread {
                val buf = ByteBuffer.allocate(512)
                val from = resolver.receive(buf)
                buf.flip()
                val reply = ByteArray(buf.remaining()).also { buf.get(it) }
                reply[2] = (reply[2].toInt() or 0x80).toByte()
                resolver.send(ByteBuffer.wrap(reply), from)
            }.apply { isDaemon = true; start() }
            val port = resolver.socket().localPort
            assertTrue(LinkProbe.runAt(listOf(InetSocketAddress("127.0.0.1", port)), emptyList(), 2_000) != null)
            thread.join(1_000)
        }
        // Nothing listens: no answer, no exception.
        assertNull(LinkProbe.runAt(emptyList(), listOf(InetSocketAddress("127.0.0.1", 1)), 500))
    }
}
