package net.paramnezia.app.fleet

import kotlinx.coroutines.runBlocking
import net.paramnezia.app.data.AppState
import net.paramnezia.app.data.Repo
import net.paramnezia.app.data.StoredFleet
import net.paramnezia.app.data.StoredProfile
import net.paramnezia.app.data.Servers
import net.paramnezia.core.config.WgQuick
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
        val endpointChanges = mutableListOf<String>()
        val memberManager = FleetManager(memberRepo, network, ed, this) { updates++ }
        memberManager.onEndpointChanged = { endpointChanges += it }
        assertTrue(memberManager.join(admin.invite(original, member, memberKey), member.name))
        assertEquals(listOf("first"), memberManager.member()!!.tunnels().map { it.first.id })

        network.mirror = ownerManager.stored!!.manifest
        assertTrue(memberManager.sync())
        assertEquals(listOf("first", "second"), memberManager.member()!!.tunnels().map { it.first.id })
        ownerRepo.update { it.copy(activeProfile = first.id) }
        ownerManager.promote(second.id, useAutomaticSelection = true)
        assertNull(ownerRepo.current.activeProfile)
        val promotedManifest = ownerManager.stored!!.manifest
        ownerRepo.update { it.copy(activeProfile = first.id) }
        ownerManager.promote(second.id, useAutomaticSelection = true)
        assertNull(ownerRepo.current.activeProfile)
        assertEquals(promotedManifest, ownerManager.stored!!.manifest)
        network.mirror = ownerManager.stored!!.manifest
        assertTrue(memberManager.sync())
        assertEquals(listOf("second", "first"), memberManager.member()!!.tunnels().map { it.first.id })
        memberRepo.update { it.copy(activeProfile = second.id) }

        ownerManager.editServer(second.id, "New backup", "backup.example:8443", confirmRecovery = true)
        network.mirror = ownerManager.stored!!.manifest
        assertTrue(memberManager.sync())
        val (server, tunnel) = memberManager.member()!!.tunnels().first()
        assertNull(memberRepo.current.activeProfile)
        assertEquals("New backup", server.name)
        assertTrue(server.altPorts.isEmpty())
        assertEquals("backup.example:8443", tunnel.peers.single().endpoint)

        ownerManager.editServer(second.id, "New backup", "[2001:db8::42]:8443")
        val manual = ownerManager.addressUpdate()!!
        assertTrue(memberManager.offer(manual))
        assertEquals("[2001:db8::42]:8443", memberManager.member()!!.tunnels().first().second.peers.single().endpoint)
        assertFalse(memberManager.offer(manual))
        assertFalse(memberManager.offer(network.mirror!!))

        ownerManager.removeServer(second.id)
        network.mirror = ownerManager.stored!!.manifest
        assertTrue(memberManager.sync())
        assertEquals(listOf("first"), memberManager.member()!!.tunnels().map { it.first.id })
        assertNull(memberRepo.current.activeProfile)
        assertFalse(memberManager.sync())
        assertEquals(6, updates)
        assertEquals(listOf("second", "second"), endpointChanges)
    }

    @Test
    fun changedEndpointIsAcceptedOverPhysicalNetworkWithoutAnyTunnelServer() = runBlocking {
        val ed = Ed25519.Jca
        val (admin, empty) = FleetAdmin.create("Family", ed)
        val (device, key) = admin.newDevice(empty, "Phone")
        val initial = admin.next(empty) {
            it.copy(
                servers = listOf(FleetServer("primary", "Primary", "192.0.2.1", 51820,
                    WgKey.generatePrivate().publicKey().toBase64())),
                devices = listOf(device),
                mirrors = listOf("https://api.github.com/gists/example", "https://independent.example/fleet.json"),
            )
        }
        val rotated = admin.next(initial) {
            it.copy(servers = listOf(it.servers.single().copy(host = "192.0.2.2")), endpointRotation = "rotation-1")
        }
        val network = ManifestNetwork()
        val endpoints = mutableListOf<String>()
        val repo = memoryRepo(AppState())
        val memberManager = FleetManager(repo, network, ed, this) {}
        memberManager.onEndpointChanged = { endpoints += it }
        assertTrue(memberManager.join(admin.invite(initial, device, key), device.name))
        repo.update { it.copy(activeProfile = "primary") }
        network.directMirror = FleetCrypto.encode(admin.seal(rotated))
        assertTrue(memberManager.syncRecoveryChannel())
        assertEquals("192.0.2.2:51820", memberManager.member()!!.tunnels().single().second.peers.single().endpoint)
        assertFalse(memberManager.syncRecoveryChannel())
        assertEquals(listOf("primary"), endpoints)
        assertNull(repo.current.activeProfile)
    }

    @Test
    fun localEndpointCanChangeWithoutChangingKeysOrServerProtocol() = runBlocking {
        val key = WgKey.generatePrivate()
        val peer = WgKey.generatePrivate().publicKey()
        val conf = """
            [Interface]
            PrivateKey = ${key.toBase64()}
            Address = 10.8.1.2/32
            [Peer]
            PublicKey = ${peer.toBase64()}
            AllowedIPs = 0.0.0.0/0
            Endpoint = 192.0.2.1:51820
        """.trimIndent()
        val first = StoredProfile("one", "One", conf, "WIREGUARD")
        val second = StoredProfile("two", "Two", conf, "AWG_LEGACY", altHosts = listOf("2001:db8::1"))
        val repo = memoryRepo(AppState(profiles = listOf(first, second)))
        val fleet = FleetManager(repo, ManifestNetwork(), Ed25519.Jca, this) {}
        val servers = Servers(repo, fleet, "Test")
        servers.editEndpoint(second.id, "New name", "[2001:db8::42]:8443")
        val edited = repo.current.profiles.last()
        val parsed = WgQuick.parse(edited.conf)
        assertEquals("New name", edited.name)
        assertEquals("[2001:db8::42]:8443", parsed.peers.single().endpoint)
        assertEquals(key, parsed.iface.privateKey)
        assertEquals(peer, parsed.peers.single().publicKey)
        assertEquals("AWG_LEGACY", edited.protocol)
        assertTrue(edited.altHosts.isEmpty())
        servers.makePrimary(second.id)
        assertEquals(second.id, repo.current.activeProfile)
        servers.remove(second.id)
        assertEquals(listOf(first), repo.current.profiles)
        assertNull(repo.current.activeProfile)
    }

    private fun memoryRepo(state: AppState): Repo = Repo(object : StateStore {
        override val lastProblem: String? = null
        override fun read() = Json.encodeToString(AppState.serializer(), state)
        override fun write(text: String) = Unit
        override fun quarantine() = Unit
    })

    private class ManifestNetwork : Http {
        var mirror: String? = null
        var directMirror: String? = null
        override fun get(url: String, maxBytes: Int, fallbackDirect: Boolean, fresh: Boolean, timeoutMs: Int) =
            mirror.takeIf { url.startsWith("https://") }

        override fun fetch(url: String, direct: Boolean, maxBytes: Int, timeoutMs: Int, userAgent: String) = Http.Fetched(directMirror.takeIf { direct && url == "https://independent.example/fleet.json" }, null)
        override fun getViaTor(url: String, maxBytes: Int): String? = null
        override fun hasDirect() = true
    }
}
