package net.paramnezia.core

import net.paramnezia.core.fleet.FailoverPolicy
import net.paramnezia.core.fleet.FleetDevice
import net.paramnezia.core.fleet.FleetManifest
import net.paramnezia.core.fleet.FleetServer
import net.paramnezia.core.fleet.GossipMessage
import net.paramnezia.core.fleet.HealthBook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResilienceTest {
    private fun s(id: String) = FleetServer(id, id, "$id.example", 1, "k")
    private val manifest = FleetManifest("f", 1, 0, listOf(s("a"), s("b"), s("c")),
        listOf(FleetDevice("p", "x", "10.8.1.2/32"), FleetDevice("t", "y", "10.8.1.3/32")), emptyList())

    @Test
    fun gossipRoundTrip() {
        for (m in listOf(GossipMessage.Manifest("{}"), GossipMessage.Version("f", 7), GossipMessage.Health("a", false, 5))) {
            assertEquals(m, GossipMessage.decode(GossipMessage.encode(m)))
        }
        assertNull(GossipMessage.decode(byteArrayOf(1, 2, 3)))
    }

    @Test
    fun failoverSkipsBlockedServers() {
        val p = FailoverPolicy(deadAfterMs = 100, retryBlockedAfterMs = 1000)
        assertFalse(p.shouldFailOver(null, connectedForMs = 50, networkValidated = true))
        assertFalse(p.shouldFailOver(null, connectedForMs = 500, networkValidated = false))
        assertTrue(p.shouldFailOver(null, connectedForMs = 500, networkValidated = true))
        assertFalse(p.shouldFailOver(10, connectedForMs = 500, networkValidated = true))
        p.markBlocked("a", now = 0)
        assertEquals("b", p.choose(manifest.servers, now = 10)!!.id)
        assertEquals("a", p.choose(manifest.servers, now = 2000)!!.id)
        p.markBlocked("b", 0); p.markBlocked("c", 0)
        assertEquals("a", p.choose(manifest.servers, now = 10)!!.id)
    }

    @Test
    fun adminPromotesStandbyOnlyWithQuorum() {
        val book = HealthBook(windowMs = 1000, quorum = 2)
        book.record("p", GossipMessage.Health("a", false, 100))
        assertNull(book.promotion(manifest, now = 200))
        book.record("t", GossipMessage.Health("a", false, 150))
        assertEquals(listOf("b", "c", "a"), book.promotion(manifest, now = 200)!!.map { it.id })
        book.record("t", GossipMessage.Health("a", true, 180))
        assertNull(book.promotion(manifest, now = 200))
        assertNull(HealthBook(1000).apply { record("p", GossipMessage.Health("a", false, 0)) }.promotion(manifest, now = 5000))
    }
}
