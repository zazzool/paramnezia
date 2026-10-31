package io.paramnezia.core

import io.paramnezia.core.fleet.Ed25519
import io.paramnezia.core.fleet.FleetAdmin
import io.paramnezia.core.fleet.FleetCrypto
import io.paramnezia.core.fleet.FleetServer
import io.paramnezia.core.nostr.NostrEvent
import io.paramnezia.core.nostr.NostrFleet
import io.paramnezia.core.nostr.RelayFrame
import io.paramnezia.core.nostr.Schnorr
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NostrTest {
    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    @Test
    fun bip340OfficialVectors() {
        val lines = javaClass.getResource("/bip340-test-vectors.csv")!!.readText().lines().drop(1).filter { it.isNotBlank() }
        assertEquals(19, lines.size)
        for (line in lines) {
            val c = line.split(',')
            val (idx, sk, pk, aux, msg, sig, ok) = Septuple(c[0], c[1], c[2], c[3], c[4], c[5], c[6])
            if (sk.isNotEmpty()) {
                assertArrayEquals("pubkey #$idx", hex(pk), Schnorr.publicKey(hex(sk)))
                assertArrayEquals("sign #$idx", hex(sig), Schnorr.sign(hex(sk), hex(msg), hex(aux)))
            }
            assertEquals("verify #$idx", ok == "TRUE", Schnorr.verify(hex(pk), hex(msg), hex(sig)))
        }
    }

    @Test
    fun eventsSignAndRejectTampering() {
        val sk = Schnorr.generateSecret()
        val e = NostrEvent.create(sk, 30078, listOf(listOf("d", "x")), "hello \"world\"\n")
        assertTrue(e.isValid())
        assertFalse(e.copy(content = "hellO").isValid())
        val back = NostrEvent.parseRelayFrame("""["EVENT","s1",${e.toJson()}]""")
        assertEquals(e, (back as RelayFrame.Event).event)
        assertTrue(NostrEvent.publishFrame(e).startsWith("[\"EVENT\",{"))
    }

    @Test
    fun fleetSlotsPickNewestFromAnyDevice() {
        val (admin, empty) = FleetAdmin.create("v", Ed25519.Jca)
        val m1 = admin.next(empty) { it.copy(servers = listOf(FleetServer("a", "a", "198.51.100.1", 1, "k"))) }
        val (phone, _) = admin.newDevice(m1, "phone")
        val m2 = admin.next(m1) { it.copy(devices = it.devices + phone) }
        val m3 = admin.next(m2) { it.copy(servers = listOf(FleetServer("b", "b", "203.0.113.2", 1, "k")) + it.servers) }

        val nf = NostrFleet(admin.trust)
        val fromAdmin = nf.manifestEvent(FleetCrypto.encode(admin.seal(m2)), version = m2.version)
        val fromPhone = nf.manifestEvent(FleetCrypto.encode(admin.seal(m3)), phone.publicKey, m3.version)
        val stranger = NostrEvent.create(Schnorr.generateSecret(), 30078, listOf(listOf("d", nf.tag), listOf("v", "999999999999999")), "junk")

        val picked = nf.candidates(listOf(fromAdmin, stranger, fromPhone), m2)
        assertEquals(2, picked.size)
        assertEquals(m3.version, FleetCrypto.decode(picked.first())!!.version)
        assertFalse(nf.tag.contains(admin.trust.fleetId))
    }

    private data class Septuple(val a: String, val b: String, val c: String, val d: String, val e: String, val f: String, val g: String)
}
