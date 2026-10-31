package io.paramnezia.core

import io.paramnezia.core.amnezia.AmneziaFormat
import io.paramnezia.core.amnezia.AmneziaKey
import io.paramnezia.core.crypto.WgKey
import io.paramnezia.core.fleet.Ed25519
import io.paramnezia.core.fleet.FleetAdmin
import io.paramnezia.core.fleet.FleetCrypto
import io.paramnezia.core.fleet.FleetMember
import io.paramnezia.core.fleet.FleetServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FleetTest {
    private val ed = Ed25519.Jca

    private fun server(id: String, host: String) = FleetServer(
        id, "Server $id", host, 41000, WgKey.generatePrivate().publicKey().toBase64(), WgKey.generatePreshared().toBase64(),
        mapOf("Jc" to "5", "Jmin" to "10", "Jmax" to "50", "H1" to "1"),
    )

    @Test
    fun ed25519SignVerify() {
        val (seed, pub) = ed.generate()
        val sig = ed.sign(seed, "hello".toByteArray())
        assertTrue(ed.verify(pub, "hello".toByteArray(), sig))
        assertFalse(ed.verify(pub, "hellO".toByteArray(), sig))
        // RFC 8032 test 1.
        val seed1 = hex("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60")
        assertEquals("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a", toHex(Ed25519.Jca.publicFromSeed(seed1)))
    }

    @Test
    fun masterServerMoveReachesDevicesWithoutUserAction() {
        val (admin, empty) = FleetAdmin.create("Vladimir", ed)
        val v1 = admin.next(empty) { it.copy(servers = listOf(server("a", "198.51.100.1"))) }
        val (phone, phoneKey) = admin.newDevice(v1, "phone")
        val v2 = admin.next(v1) { it.copy(devices = it.devices + phone) }
        val (tablet, _) = admin.newDevice(v2, "tablet")
        val v3 = admin.next(v2) { it.copy(devices = it.devices + tablet) }
        assertEquals("10.8.1.2/32", phone.address)
        assertEquals("10.8.1.3/32", tablet.address)
        // Addresses the server already gave to others (its own clients, link users) are skipped.
        assertEquals("10.8.1.6/32", admin.newDevice(v3, "tv", reserved = listOf("10.8.1.4", "10.8.1.5/32")).first.address)

        // The invite is a normal AmneziaVPN key that also carries fleet membership.
        val invite = admin.invite(v3, phone, phoneKey)
        assertEquals("198.51.100.1", AmneziaFormat.import(AmneziaKey.decode(invite)).single().tunnel!!.peers.single().endpoint!!.substringBefore(':'))
        val member = assertNotNullT(FleetMember.fromInvite(invite, ed))
        assertEquals("198.51.100.1:41000", member.tunnels().single().second.peers.single().endpoint)

        // Master moves: admin publishes v4 with the new server first.
        val v4 = admin.next(v3) { it.copy(servers = listOf(server("b", "203.0.113.9")) + it.servers) }
        val published = FleetCrypto.encode(admin.seal(v4))
        assertTrue(member.offer(published))
        assertEquals("203.0.113.9:41000", member.tunnels().first().second.peers.single().endpoint)
        assertEquals("10.8.1.2/32", member.tunnels().first().second.iface.addresses.single().toString())

        // Replays of old versions and forgeries are ignored.
        assertFalse(member.offer(FleetCrypto.encode(admin.seal(v3))))
        val (evil, _) = FleetAdmin.create("evil", ed)
        val forged = FleetCrypto.encode(evil.seal(v4.copy(version = v4.version + 10)))
        assertFalse(member.offer(forged))
        val tampered = FleetCrypto.decode(published)!!.let { it.copy(version = it.version + 1) }
        assertFalse(member.offer(FleetCrypto.encode(tampered)))
        assertEquals(v4.version, member.manifest!!.version)

        // Revocation: the device stops getting tunnels.
        val v5 = admin.next(v4) { m -> m.copy(devices = m.devices.filterNot { it.name == "phone" }) }
        assertTrue(member.offer(FleetCrypto.encode(admin.seal(v5))))
        assertTrue(member.tunnels().isEmpty())
    }

    @Test
    fun manifestBodyIsEncrypted() {
        val (admin, empty) = FleetAdmin.create("x", ed)
        val m = admin.next(empty) { it.copy(servers = listOf(server("a", "198.51.100.77"))) }
        val wire = FleetCrypto.encode(admin.seal(m))
        assertFalse("198.51.100.77" in wire)
        assertFalse("Jmin" in wire)
    }

    private fun <T> assertNotNullT(v: T?): T { assertNotNull(v); return v!! }
    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    private fun toHex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
}
