package net.paramnezia.app.fleet

import kotlinx.coroutines.runBlocking
import net.paramnezia.app.data.AppState
import net.paramnezia.app.data.Repo
import net.paramnezia.app.data.StoredFleet
import net.paramnezia.app.platform.Http
import net.paramnezia.app.platform.StateStore
import net.paramnezia.core.crypto.WgKey
import net.paramnezia.core.fleet.Ed25519
import net.paramnezia.core.fleet.FleetAdmin
import net.paramnezia.core.fleet.FleetCrypto
import net.paramnezia.core.fleet.FleetServer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class FleetServersTest {
    @Test
    fun memberSyncsAddedEditedAndRemovedServers() = runBlocking {
        val ed = Ed25519.Jca
        val (admin, empty) = FleetAdmin.create("Family", ed)
        val (owner, ownerKey) = admin.newDevice(empty, "Owner")
        val first = FleetServer("first", "Primary", "first.example", 51820, WgKey.generatePrivate().publicKey().toBase64())
        val firstVersion = admin.next(empty) { it.copy(servers = listOf(first), devices = listOf(owner)) }
        val (member, memberKey) = admin.newDevice(firstVersion, "Member")
        val mirror = "https://example.com/fleet"
        val original = admin.next(firstVersion) { it.copy(devices = it.devices + member, mirrors = listOf(mirror)) }
        val second = first.copy(id = "second", name = "Backup", host = "second.example", altPorts = listOf(443))
        val added = admin.next(original) { it.copy(servers = it.servers + second) }
        val ownerRepo = memoryRepo(AppState(fleet = StoredFleet(
            admin.trust, ownerKey.toBase64(), owner.name, FleetCrypto.encode(admin.seal(added)),
            adminSeed = Base64.getEncoder().encodeToString(admin.signingSeed),
        )))
        val memberRepo = memoryRepo(AppState())
        val network = ManifestNetwork()
        val ownerManager = FleetManager(ownerRepo, network, ed, this) {}
        var updates = 0
        val memberManager = FleetManager(memberRepo, network, ed, this) { updates++ }
        assertTrue(memberManager.join(admin.invite(original, member, memberKey), member.name))
        assertEquals(listOf("first"), memberManager.member()!!.tunnels().map { it.first.id })

        network.mirror = ownerManager.stored!!.manifest
        assertTrue(memberManager.sync())
        assertEquals(listOf("first", "second"), memberManager.member()!!.tunnels().map { it.first.id })
        memberRepo.update { it.copy(activeProfile = second.id) }

        ownerManager.editServer(second.id, "New backup", "backup.example:8443")
        network.mirror = ownerManager.stored!!.manifest
        assertTrue(memberManager.sync())
        val (server, tunnel) = memberManager.member()!!.tunnels().last()
        assertEquals("New backup", server.name)
        assertTrue(server.altPorts.isEmpty())
        assertEquals("backup.example:8443", tunnel.peers.single().endpoint)

        ownerManager.removeServer(second.id)
        network.mirror = ownerManager.stored!!.manifest
        assertTrue(memberManager.sync())
        assertEquals(listOf("first"), memberManager.member()!!.tunnels().map { it.first.id })
        assertNull(memberRepo.current.activeProfile)
        assertFalse(memberManager.sync())
        assertEquals(4, updates)
    }

    private fun memoryRepo(state: AppState): Repo = Repo(object : StateStore {
        override val lastProblem: String? = null
        override fun read() = Json.encodeToString(AppState.serializer(), state)
        override fun write(text: String) = Unit
        override fun quarantine() = Unit
    })

    private class ManifestNetwork : Http {
        var mirror: String? = null
        override fun get(url: String, maxBytes: Int, fallbackDirect: Boolean, fresh: Boolean, timeoutMs: Int) =
            mirror.takeIf { url.startsWith("https://") }

        override fun fetch(url: String, direct: Boolean, maxBytes: Int, timeoutMs: Int, userAgent: String) = Http.Fetched(null, null)
        override fun getViaTor(url: String, maxBytes: Int): String? = null
        override fun hasDirect() = true
    }
}
