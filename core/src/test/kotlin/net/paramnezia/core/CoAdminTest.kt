package net.paramnezia.core

import net.paramnezia.core.crypto.WgKey
import net.paramnezia.core.fleet.AdminGrant
import net.paramnezia.core.fleet.Ed25519
import net.paramnezia.core.fleet.FleetAdmin
import net.paramnezia.core.fleet.FleetCoAdmin
import net.paramnezia.core.fleet.FleetCrypto
import net.paramnezia.core.fleet.FleetMember
import net.paramnezia.core.fleet.FleetServer
import net.paramnezia.core.fleet.GrantBox
import net.paramnezia.core.fleet.SignedManifest
import net.paramnezia.core.profile.SshCredentials
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class CoAdminTest {
    private val ed = Ed25519.Jca
    private fun b64(b: ByteArray) = Base64.getEncoder().encodeToString(b)
    private val server = FleetServer("a", "A", "198.51.100.1", 41000, WgKey.generatePrivate().publicKey().toBase64())

    private class Family(val owner: FleetAdmin, val v: net.paramnezia.core.fleet.FleetManifest, val momKey: WgKey, val kidKey: WgKey)

    private fun family(): Family {
        val (owner, empty) = FleetAdmin.create("Owner", ed)
        val v1 = owner.next(empty) { it.copy(servers = listOf(server)) }
        val (mom, momKey) = owner.newDevice(v1, "Мама")
        val (kid, kidKey) = owner.newDevice(v1, "Сын", reserved = listOf(mom.address))
        return Family(owner, owner.next(v1) { it.copy(devices = listOf(mom, kid)) }, momKey, kidKey)
    }

    private fun appoint(f: Family): Triple<net.paramnezia.core.fleet.FleetManifest, FleetAdmin, AdminGrant> {
        val (seed, pub) = ed.generate()
        val grant = AdminGrant(b64(seed), b64(pub), mapOf("a" to SshCredentials("198.51.100.1", 22, "root", privateKey = "KEY")),
            hostKeys = mapOf("198.51.100.1:22" to "SHA256:x"))
        val momPub = f.momKey.publicKey()
        val v = f.owner.next(f.v) {
            it.copy(ownerDevice = f.kidKey.publicKey().toBase64(), admins = listOf(FleetCoAdmin("Мама", momPub.toBase64(), b64(pub), "ssh-ed25519 AAAA mom", 1)),
                grants = listOf(GrantBox.seal(grant, momPub)))
        }
        return Triple(v, FleetAdmin(f.owner.trust, seed, ed, signer = b64(pub)), grant)
    }

    private fun member(f: Family, key: WgKey) = FleetMember(f.owner.trust, key, ed).also { assertTrue(it.offer(FleetCrypto.encode(f.owner.seal(f.v)))) }

    @Test fun grantOpensOnlyOnItsDevice() {
        val f = family()
        val (v, _, grant) = appoint(f)
        val mom = member(f, f.momKey); val kid = member(f, f.kidKey)
        val wire = FleetCrypto.encode(f.owner.seal(v))
        assertTrue(mom.offer(wire)); assertTrue(kid.offer(wire))
        assertEquals(grant, mom.grant())
        assertNull(kid.grant())
        // The sealed box itself, tampered with, does not open.
        val sealed = v.grants.single()
        val raw = Base64.getDecoder().decode(sealed.box).also { it[20] = (it[20] + 1).toByte() }
        assertNull(GrantBox.open(sealed.copy(box = b64(raw)), f.momKey))
        assertNull(GrantBox.open(sealed, f.kidKey))
    }

    @Test fun coAdminManagesDevicesButNotAdmins() {
        val f = family()
        val (v, momAdmin, _) = appoint(f)
        val kid = member(f, f.kidKey)
        assertTrue(kid.offer(FleetCrypto.encode(f.owner.seal(v))))
        // Mom (co-admin) adds a person: accepted.
        val (grandpa, _) = momAdmin.newDevice(v, "Дедушка")
        val v2 = momAdmin.next(v) { it.copy(devices = it.devices + grandpa) }
        assertTrue(kid.offer(FleetCrypto.encode(momAdmin.seal(v2))))
        assertEquals(3, kid.manifest!!.devices.size)
        // She cannot appoint someone else, drop the grants, or remove herself from the list.
        val (otherSeed, otherPub) = ed.generate()
        val sneaky = momAdmin.next(v2) { it.copy(admins = it.admins + FleetCoAdmin("Сын", f.kidKey.publicKey().toBase64(), b64(otherPub), "x", 2)) }
        assertFalse(kid.offer(FleetCrypto.encode(momAdmin.seal(sneaky))))
        assertFalse(kid.offer(FleetCrypto.encode(momAdmin.seal(momAdmin.next(v2) { it.copy(grants = emptyList()) }))))
        // A key nobody appointed is refused, and a co-admin signature never passes as the owner's.
        val stranger = FleetAdmin(f.owner.trust, otherSeed, ed, signer = b64(otherPub))
        assertFalse(kid.offer(FleetCrypto.encode(stranger.seal(stranger.next(v2) { it }))))
        val forged = momAdmin.seal(momAdmin.next(v2) { it }).copy(signer = null)
        assertFalse(kid.offer(FleetCrypto.encode(forged)))
        // Nor remove the owner's device (here: the son's) or her own.
        assertFalse(kid.offer(FleetCrypto.encode(momAdmin.seal(momAdmin.next(v2) { m -> m.copy(devices = m.devices.filter { it.publicKey != f.kidKey.publicKey().toBase64() }) }))))
        assertFalse(kid.offer(FleetCrypto.encode(momAdmin.seal(momAdmin.next(v2) { m -> m.copy(devices = m.devices.filter { it.publicKey != f.momKey.publicKey().toBase64() }) }))))
        assertFalse(kid.offer(FleetCrypto.encode(momAdmin.seal(momAdmin.next(v2) { m -> m.copy(ownerDevice = null) }))))
        // But she can remove someone she added.
        assertTrue(kid.offer(FleetCrypto.encode(momAdmin.seal(momAdmin.next(v2) { m -> m.copy(devices = m.devices.filter { it.publicKey != grandpa.publicKey }) }))))
        // Replays of older versions go nowhere.
        assertFalse(kid.offer(FleetCrypto.encode(momAdmin.seal(v2))))
    }

    @Test fun ownerTakesRightsBackAndAlwaysWins() {
        val f = family()
        val (v, momAdmin, _) = appoint(f)
        val kid = member(f, f.kidKey)
        assertTrue(kid.offer(FleetCrypto.encode(f.owner.seal(v))))
        // A co-admin version numbered far ahead (as if she tried to lock the family out)…
        val ahead = momAdmin.next(v) { it }.let { it.copy(version = it.version + 10_000_000) }
        assertTrue(kid.offer(FleetCrypto.encode(momAdmin.seal(ahead))))
        // …does not stop the owner's revocation, although its number is lower.
        val revoked = f.owner.next(v) { it.copy(admins = emptyList(), grants = emptyList()) }
        assertTrue(revoked.version < ahead.version)
        assertTrue(kid.offer(FleetCrypto.encode(f.owner.seal(revoked))))
        assertTrue(kid.manifest!!.admins.isEmpty())
        // After that her signature counts for nothing, and the owner's old versions cannot be replayed.
        assertFalse(kid.offer(FleetCrypto.encode(momAdmin.seal(ahead.copy(version = ahead.version + 1)))))
        assertFalse(kid.offer(FleetCrypto.encode(f.owner.seal(v))))
        assertEquals(revoked.version, kid.ownerVersion)
    }

    @Test fun joiningFromACoAdminInvite() {
        val f = family()
        val (v, momAdmin, _) = appoint(f)
        val (aunt, auntKey) = momAdmin.newDevice(v, "Тётя")
        val v2 = momAdmin.next(v) { it.copy(devices = it.devices + aunt) }
        val member = assertNotNullT(FleetMember.fromInvite(momAdmin.invite(v2, aunt, auntKey), ed))
        assertEquals("Тётя", member.manifest!!.deviceFor(auntKey.publicKey())!!.name)
        assertEquals(1, member.tunnels().size)
        // The owner's next version still reaches her.
        assertTrue(member.offer(FleetCrypto.encode(f.owner.seal(f.owner.next(v2) { it }))))
    }

    @Test fun oldOwnerManifestsStillVerify() {
        val f = family()
        val wire = FleetCrypto.encode(f.owner.seal(f.v))
        assertFalse("signer" in wire && "\"signer\":\"" in wire)
        val decoded = FleetCrypto.decode(wire)!!
        assertEquals(null, decoded.signer)
        assertNotNull(FleetCrypto.open(decoded, f.owner.trust, ed))
        // A manifest from before 2.16 (no signer field at all) opens.
        val legacy = """{"fleetId":"${decoded.fleetId}","version":${decoded.version},"body":"${decoded.body}","sig":"${decoded.sig}"}"""
        assertNotNull(FleetCrypto.open(FleetCrypto.decode(legacy)!!, f.owner.trust, ed))
        assertEquals(SignedManifest(decoded.fleetId, decoded.version, decoded.body, decoded.sig).signedBytes().toList(), decoded.signedBytes().toList())
    }

    private fun <T> assertNotNullT(v: T?): T { assertNotNull(v); return v!! }
}
