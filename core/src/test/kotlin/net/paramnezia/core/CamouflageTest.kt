package net.paramnezia.core

import net.paramnezia.core.config.AwgParam
import net.paramnezia.core.config.Camouflage
import net.paramnezia.core.config.Camouflage.Mimic
import net.paramnezia.core.config.CamouflageLadder
import net.paramnezia.core.config.TunnelConfig
import net.paramnezia.core.config.WgQuick
import net.paramnezia.core.crypto.WgKey
import net.paramnezia.core.fleet.DevicePsk
import net.paramnezia.core.fleet.Ed25519
import net.paramnezia.core.fleet.FleetAdmin
import net.paramnezia.core.fleet.FleetMember
import net.paramnezia.core.fleet.FleetServer
import net.paramnezia.core.fleet.tunnelFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CamouflageTest {
    private val serverKey = WgKey.generatePrivate().publicKey()

    private fun awg3(endpoint: String = "198.51.100.7:41000") = WgQuick.parse(
        """
        [Interface]
        PrivateKey = ${WgKey.generatePrivate().toBase64()}
        Address = 10.8.1.2/32
        Jc = 5
        Jmin = 10
        Jmax = 50
        S1 = 12
        S2 = 12
        S3 = 12
        S4 = 12
        H1 = 1
        H2 = 2
        H3 = 3
        H4 = 4
        I1 = <r 2><b 0x858000010001>
        HeaderProtectionKey = ${WgKey.generatePrivate().toBase64()}
        RandomTrailers = on
        [Peer]
        PublicKey = ${serverKey.toBase64()}
        AllowedIPs = 0.0.0.0/0
        Endpoint = $endpoint
        """.trimIndent(),
    )

    private fun plainWg() = WgQuick.parse(
        """
        [Interface]
        PrivateKey = ${WgKey.generatePrivate().toBase64()}
        Address = 10.8.1.2/32
        [Peer]
        PublicKey = ${serverKey.toBase64()}
        AllowedIPs = 0.0.0.0/0
        Endpoint = 198.51.100.7:51820
        """.trimIndent(),
    )

    /** The bytes a signature spec produces, with random parts as zeros. */
    private fun bytes(spec: String): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        for (m in Regex("<(\\w+)\\s*([^>]*)>").findAll(spec)) {
            val (tag, arg) = m.destructured
            when (tag) {
                "b" -> arg.removePrefix("0x").chunked(2).forEach { out.write(it.toInt(16)) }
                "r" -> repeat(arg.toInt()) { out.write(0) }
                else -> error("unexpected tag $tag")
            }
        }
        return out.toByteArray()
    }

    private fun u16(b: ByteArray, at: Int) = ((b[at].toInt() and 0xFF) shl 8) or (b[at + 1].toInt() and 0xFF)
    private fun u24(b: ByteArray, at: Int) = ((b[at].toInt() and 0xFF) shl 16) or u16(b, at + 1)

    @Test
    fun stunBindingRequestIsWellFormed() {
        val b = bytes(Camouflage.STUN_BINDING)
        assertEquals(20, b.size)
        assertEquals(0x0001, u16(b, 0)) // Binding Request
        assertEquals(0, u16(b, 2)) // no attributes
        assertEquals("2112a442", b.copyOfRange(4, 8).joinToString("") { "%02x".format(it) })
    }

    @Test
    fun dtlsClientHelloParses() {
        val b = bytes(Camouflage.dtlsClientHello())
        assertEquals(0x16, b[0].toInt()) // handshake record
        assertEquals(0xfeff, u16(b, 1))
        assertEquals(b.size - 13, u16(b, 11)) // record length
        assertEquals(0x01, b[13].toInt()) // ClientHello
        val body = u24(b, 14)
        assertEquals(b.size - 25, body)
        assertEquals(body, u24(b, 22)) // one fragment
        assertEquals(0xfefd, u16(b, 25)) // DTLS 1.2
        var at = 25 + 2 + 32
        assertEquals(0, b[at++].toInt()) // session id
        assertEquals(0, b[at++].toInt()) // cookie
        val suites = u16(b, at); at += 2 + suites
        assertEquals(1, b[at].toInt()); at += 2 // compression: null
        val extLen = u16(b, at); at += 2
        assertEquals(b.size, at + extLen)
        val types = ArrayList<Int>()
        while (at < b.size) { types += u16(b, at); at += 4 + u16(b, at + 2) }
        assertEquals(b.size, at)
        assertTrue("use_srtp", 0x000e in types)
        assertTrue("extended master secret", 0x0017 in types)
    }

    @Test
    fun mimicIsStablePerServerAndDiffersAcrossServers() {
        assertEquals(Camouflage.mimicFor(serverKey.toBase64()), Camouflage.mimicFor(serverKey.toBase64()))
        val seen = (1..64).map { Camouflage.mimicFor(WgKey.generatePrivate().publicKey().toBase64()) }.toSet()
        assertEquals(setOf(Mimic.STUN, Mimic.WEBRTC), seen)
    }

    @Test
    fun ladderTriesTheLikelyFixesFirst() {
        val own = Camouflage.mimicFor(serverKey.toBase64())
        val other = if (own == Mimic.STUN) Mimic.WEBRTC else Mimic.STUN
        val l = Camouflage.ladder("198.51.100.7", 41000, serverKey.toBase64(), listOf(3478, 23456, 443), listOf("2001:db8::7"))
        assertEquals(Camouflage.Variant("198.51.100.7", 41000, own), l[0])
        assertEquals(Camouflage.Variant("198.51.100.7", 41000, other), l[1])
        assertEquals(Camouflage.Variant("198.51.100.7", 3478, own), l[2])
        // Within the first round: the IPv6 address.
        assertEquals(Camouflage.Variant("2001:db8::7", 41000, own), l[3])
        assertEquals("[2001:db8::7]:41000", l[3].endpoint())
        assertTrue(Camouflage.Variant("198.51.100.7", 41000, Mimic.PLAIN) in l)
        assertEquals(l.size, l.toSet().size)
        // No IPv6 on the device: no IPv6 variants.
        assertTrue(Camouflage.ladder("198.51.100.7", 41000, serverKey.toBase64(), listOf(3478), listOf("2001:db8::7"), ipv6 = false).none { ':' in it.host })
        // Plain WireGuard: only places change.
        val wg = Camouflage.ladderOf(plainWg(), listOf(3478))
        assertEquals(listOf(51820, 3478), wg.map { it.port })
        assertTrue(wg.all { it.mimic == Mimic.PLAIN })
    }

    @Test
    fun applyDressesOnlyWhatTheClientOwns() {
        val c = awg3()
        val v = Camouflage.Variant("198.51.100.7", 3478, Mimic.WEBRTC)
        val d = Camouflage.apply(c, v)
        assertEquals("198.51.100.7:3478", d.peers.single().endpoint)
        assertEquals(Camouflage.STUN_BINDING, d.iface.awg[AwgParam.I1])
        assertTrue(d.iface.awg[AwgParam.I2]!!.startsWith("<b 0x16feff"))
        // What the server checks stays exactly as it was.
        for (p in listOf(AwgParam.S1, AwgParam.S2, AwgParam.S3, AwgParam.S4, AwgParam.H1, AwgParam.H2, AwgParam.H3, AwgParam.H4, AwgParam.HEADER_PROTECTION_KEY, AwgParam.RANDOM_TRAILERS))
            assertEquals(p.name, c.iface.awg[p], d.iface.awg[p])
        assertEquals(c.peers.single().publicKey, d.peers.single().publicKey)
        // Heavy padding at the start of the flow, a little afterwards.
        assertEquals("24-255", d.iface.awg[AwgParam.CONTENT_PADDING_ADDITION])
        assertEquals("0-31", Camouflage.padding(c)!!.steady)
        // No signature at all.
        val plain = Camouflage.apply(c, Camouflage.Variant("198.51.100.7", 41000, Mimic.PLAIN))
        assertNull(plain.iface.awg[AwgParam.I1])
        assertEquals("5", plain.iface.awg[AwgParam.JC])
        // A plain WireGuard server gets no signatures, and its packets go back to WireGuard's own rounding.
        val wg = Camouflage.apply(plainWg(), Camouflage.Variant("198.51.100.7", 51820, Mimic.STUN))
        assertNull(wg.iface.awg[AwgParam.I1])
        assertFalse(Camouflage.obfuscated(plainWg()))
        assertEquals("0", Camouflage.padding(plainWg())!!.steady)
        // The dressed config still writes and parses (it goes to the tunnel as UAPI).
        assertEquals(d.iface.awg, WgQuick.parse(WgQuick.write(d)).iface.awg)
        net.paramnezia.core.config.Uapi.write(d) { java.net.InetAddress.getByName(it) }
    }

    @Test
    fun ladderRemembersPerNetworkAndHandsOverAfterARound() {
        val steps = Camouflage.ladder("198.51.100.7", 41000, serverKey.toBase64(), listOf(3478, 23456, 443))
        var saved: Map<String, String> = emptyMap()
        val ladder = CamouflageLadder { saved = it }
        val first = ladder.pick("home", "s1", steps)!!
        assertEquals(steps[0], first)
        // Failures walk the ladder; with other servers around, a round ends with null.
        var v = first
        repeat(Camouflage.ROUND - 1) { v = ladder.next("s1", v.id, steps, mayStay = false)!! }
        assertEquals(steps[Camouflage.ROUND - 1], v)
        assertNull(ladder.next("s1", v.id, steps, mayStay = false))
        // Coming back continues where the ladder stopped, not from the top.
        assertEquals(steps[Camouflage.ROUND], ladder.pick("home", "s1", steps))
        // A lone server keeps trying.
        assertEquals(steps[Camouflage.ROUND + 1], ladder.next("s1", steps[Camouflage.ROUND].id, steps, mayStay = true))
        // What worked is remembered for this network only.
        ladder.worked("home", "s1", steps[2].id)
        assertEquals(mapOf("home|s1" to steps[2].id), saved)
        ladder.networkChanged()
        assertEquals(steps[0], ladder.pick("cafe", "s1", steps))
        ladder.networkChanged()
        assertEquals(steps[2], CamouflageLadder(saved).pick("home", "s1", steps))
        // Bounded memory.
        repeat(CamouflageLadder.MAX_ENTRIES + 10) { ladder.worked("net$it", "s1", steps[1].id) }
        assertEquals(CamouflageLadder.MAX_ENTRIES, saved.size)
    }

    @Test
    fun devicesHaveTheirOwnPresharedKeys() {
        val ed = Ed25519.Jca
        val (admin, empty) = FleetAdmin.create("Vladimir", ed)
        val server = FleetServer("a", "A", "198.51.100.1", 41000, serverKey.toBase64(), WgKey.generatePreshared().toBase64(),
            mapOf("Jc" to "5", "H1" to "1"), altPorts = listOf(3478))
        val v1 = admin.next(empty) { it.copy(servers = listOf(server)) }
        val (phone, phoneKey) = admin.newDevice(v1, "phone")
        val (tablet, _) = admin.newDevice(v1, "tablet")
        assertTrue(phone.ownPsk)
        val v2 = admin.next(v1) { it.copy(devices = listOf(phone, tablet)) }
        val mine = admin.pskFor(phone)!!
        assertNotEquals(mine, admin.pskFor(tablet))
        assertNotEquals(server.presharedKey, mine.toBase64())
        // The invite carries the key; the joined device uses it on every server.
        val member = FleetMember.fromInvite(admin.invite(v2, phone, phoneKey), ed)!!
        assertEquals(mine, member.devicePsk)
        assertEquals(mine, member.tunnels().single().second.peers.single().presharedKey)
        // A co-admin derives the same keys from the seed in its grant.
        val coSeed = DevicePsk.seed(admin.signingSeed)
        assertEquals(mine, FleetAdmin(admin.trust, ByteArray(32), ed, signer = "x", pskSeed = coSeed).pskFor(phone))
        // A co-admin without it (a grant from before 2.16) makes devices with the shared key.
        val old = FleetAdmin(admin.trust, ByteArray(32), ed, signer = "x")
        val (legacy, legacyKey) = old.newDevice(v2, "legacy")
        assertFalse(legacy.ownPsk)
        assertEquals(server.presharedKey, server.tunnelFor(legacy, legacyKey).peers.single().presharedKey!!.toBase64())
        // A device from before 2.16 keeps the server's shared key whatever it holds.
        val older = phone.copy(ownPsk = false)
        assertEquals(server.presharedKey, server.tunnelFor(older, phoneKey, mine).peers.single().presharedKey!!.toBase64())
    }
}
