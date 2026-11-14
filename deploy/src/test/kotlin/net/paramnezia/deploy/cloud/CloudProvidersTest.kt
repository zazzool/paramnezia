package net.paramnezia.deploy.cloud

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int
import net.paramnezia.deploy.MemoryHostKeyStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.security.MessageDigest
import java.util.Base64

/** Answers from a script of (method path) → (code, body) and records every request. */
private class FakeApi(private val routes: Map<String, Pair<Int, String>>) : CloudHttp.Transport {
    val calls = ArrayList<Triple<String, String, String?>>()
    val headers = ArrayList<Map<String, String>>()
    override fun send(method: String, url: String, headers: Map<String, String>, body: String?): Pair<Int, String> {
        val path = url.substringAfter("/api/v1")
        calls += Triple(method, path, body); this.headers += headers
        return routes["$method $path"] ?: (404 to """{"message":"no route $method $path"}""")
    }
    fun body(method: String, path: String) = Json.parseToJsonElement(calls.last { it.first == method && it.second == path }.third!!).jsonObject
}

class CloudProvidersTest {

    @Test fun cloudzyCatalogRegionsAndCreate() {
        val api = FakeApi(mapOf(
            "GET /regions" to (200 to """[{"id":"ams","name":"Amsterdam","abbr":"NL","isActive":true},
                {"id":"msk","name":"Moscow","isActive":true},{"id":"hid","name":"Hidden","isHidden":true},
                {"id":"full","name":"Full","isOutOfStock":true}]"""),
            "GET /pricing/catalog" to (200 to """{"plans":[
                {"id":"p512","name":"512MB","cpuCores":1,"memoryMb":512,"diskGb":10},
                {"id":"p1","name":"1GB","cpuCores":1,"memoryMb":1024,"diskGb":25},
                {"id":"p2","name":"2GB","cpuCores":1,"memoryMb":2048,"diskGb":40}],
              "prices":[
                {"planId":"p512","regionId":"","billingCycle":"monthly","monthlyEquivCents":295,"currency":"USD"},
                {"planId":"p1","regionId":"","billingCycle":"monthly","monthlyEquivCents":495,"currency":"USD"},
                {"planId":"p1","regionId":"ams","billingCycle":"monthly","monthlyEquivCents":595,"currency":"USD"},
                {"planId":"p2","regionId":"","billingCycle":"hourly","monthlyEquivCents":900,"currency":"USD"}]}"""),
            "GET /os-templates" to (200 to """[{"id":"debian-12","name":"Debian","version":"12"},{"id":"ubuntu-24.04","name":"Ubuntu","family":"ubuntu","version":"24.04"}]"""),
            "POST /services" to (201 to """{"id":"s-1","hostname":"paramnezia-abc","state":"provisioning"}"""),
            "GET /services/s-1" to (200 to """{"id":"s-1","state":"active","ipAddress":"203.0.113.7"}"""),
        ))
        val p = CloudzyProvider("hpt_x", api)
        val regions = p.regions()
        assertEquals(listOf("ams", "msk"), regions.map { it.id })
        assertTrue(regions.last().inRussia)

        val plans = p.plans("ams")
        assertEquals(listOf("p512", "p1"), plans.map { it.id }) // p2 has no monthly price
        assertEquals(5.95, plans[1].monthly!!, 0.001)        // regional price wins over the base one
        assertEquals("p1", CloudProviders.suggest(plans)!!.id) // 512 MB is too small for the build
        assertEquals("5.95 $/мес", plans[1].priceText())

        val s = p.create("paramnezia-abc", "ams", plans[1], "ssh-rsa AAAA admin", "#cloud-config\n", "idem-1")
        assertEquals(CloudServer.State.PENDING, s.state)
        val body = api.body("POST", "/services")
        assertEquals("p1", body["planId"]!!.jsonPrimitive.content)
        assertEquals("ubuntu-24.04", body["osTemplateId"]!!.jsonPrimitive.content)
        assertEquals("monthly", body["billingCycle"]!!.jsonPrimitive.content)
        assertEquals("ssh-rsa AAAA admin", body["sshAuthorizedKeys"]!!.jsonArray[0].jsonPrimitive.content)
        assertEquals("#cloud-config\n", body["config"]!!.jsonObject["userData"]!!.jsonPrimitive.content)
        val h = api.headers[api.calls.indexOfLast { it.first == "POST" }]
        assertEquals("idem-1", h["Idempotency-Key"])
        assertEquals("Bearer hpt_x", h["Authorization"])

        val ready = p.get("s-1")
        assertEquals(CloudServer.State.READY, ready.state)
        assertEquals("203.0.113.7", ready.ipv4)
    }

    @Test fun timewebPresetsSshKeyAndCreate() {
        val api = FakeApi(mapOf(
            "GET /presets/servers" to (200 to """{"server_presets":[
                {"id":11,"location":"ru-1","price":200,"cpu":1,"ram":1024,"disk":15360,"bandwidth":200},
                {"id":21,"location":"nl-1","price":477,"cpu":1,"ram":1024,"disk":15360,"bandwidth":200,"description_short":"1 x 3.3 ГГц CPU"},
                {"id":22,"location":"nl-1","price":300,"cpu":1,"ram":512,"disk":5120,"bandwidth":100}]}"""),
            "GET /os/servers" to (200 to """{"servers_os":[{"id":79,"name":"ubuntu","version":"22.04"},{"id":99,"name":"ubuntu","version":"24.04"}]}"""),
            "GET /ssh-keys" to (200 to """{"ssh_keys":[{"id":5,"name":"other","body":"ssh-rsa BBBB x"}]}"""),
            "POST /ssh-keys" to (201 to """{"ssh_key":{"id":6}}"""),
            "POST /servers" to (201 to """{"server":{"id":4242,"status":"installing","networks":[]}}"""),
            "GET /servers/4242" to (200 to """{"server":{"id":4242,"status":"on","networks":[{"type":"public","ips":[
                {"type":"ipv6","ip":"2a03::1","is_main":true},{"type":"ipv4","ip":"198.51.100.4","is_main":true}]}]}}"""),
        ))
        val p = TimewebProvider("tw", api)
        val regions = p.regions()
        assertEquals(listOf("nl-1", "ru-1"), regions.map { it.id })
        assertEquals("Амстердам", regions[0].name)

        val plans = p.plans("nl-1")
        assertEquals(listOf("22", "21"), plans.map { it.id })
        assertEquals("21", CloudProviders.suggest(plans)!!.id)
        assertEquals("477 ₽/мес", plans[1].priceText())

        p.create("paramnezia-abc", "nl-1", plans[1], "ssh-rsa AAAA admin", "#cloud-config\n", "unused")
        val body = api.body("POST", "/servers")
        assertEquals(21, body["preset_id"]!!.jsonPrimitive.int)
        assertEquals(99, body["os_id"]!!.jsonPrimitive.int)
        assertEquals(200, body["bandwidth"]!!.jsonPrimitive.int)
        assertEquals(6, body["ssh_keys_ids"]!!.jsonArray[0].jsonPrimitive.int)
        assertEquals("ssh-rsa AAAA admin", api.body("POST", "/ssh-keys")["body"]!!.jsonPrimitive.content)

        val s = p.get("4242")
        assertEquals(CloudServer.State.READY, s.state)
        assertEquals("198.51.100.4", s.ipv4)
    }

    @Test fun timewebReusesUploadedKey() {
        val api = FakeApi(mapOf(
            "GET /os/servers" to (200 to """{"servers_os":[{"id":99,"name":"ubuntu","version":"24.04"}]}"""),
            "GET /ssh-keys" to (200 to """{"ssh_keys":[{"id":7,"name":"Paramnezia","body":"ssh-rsa AAAA another-comment\n"}]}"""),
            "POST /servers" to (201 to """{"server":{"id":1,"status":"installing"}}"""),
        ))
        TimewebProvider("tw", api).create("n", "nl-1", CloudPlan("21", "nl-1", "", 1, 1024, 15, 1.0, "RUB"), "ssh-rsa AAAA admin", "", "")
        assertEquals(7, api.body("POST", "/servers")["ssh_keys_ids"]!!.jsonArray[0].jsonPrimitive.int)
        assertTrue(api.calls.none { it.first == "POST" && it.second == "/ssh-keys" })
    }

    @Test fun errorsAreTyped() {
        val api = FakeApi(mapOf("GET /auth/me" to (401 to """{"message":"Invalid token"}""")))
        try { CloudzyProvider("bad", api).verify(); fail() } catch (e: CloudException) {
            assertEquals("auth_failed", e.code); assertEquals("Invalid token", e.message)
        }
        assertEquals(CloudServer.State.GONE, CloudzyProvider("t", api).get("nope").state)
    }

    @Test fun hostKeyFingerprintMatchesWhatSshSees() {
        val k = SshKeyPair.generate("test")
        assertTrue(k.privatePem.startsWith("-----BEGIN OPENSSH PRIVATE KEY-----"))
        assertTrue(k.publicLine.startsWith("ssh-ed25519 "))
        // The client fingerprints the host key blob the server sends: the same bytes as in the public line.
        val blob = net.paramnezia.deploy.ssh.SshKeys.parse(k.privatePem, "").publicBlob
        val expected = "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(blob))
        assertEquals(expected, k.fingerprint)
    }

    @Test fun cloudInitCarriesHostKeyAndAdminKey() {
        val host = SshKeyPair.generate("h")
        val text = CloudProvisioner.cloudInit(host, "ssh-rsa AAAA admin")
        assertTrue(text.startsWith("#cloud-config\n"))
        assertTrue("ssh_genkeytypes: [ed25519]" in text)
        assertTrue("    -----BEGIN OPENSSH PRIVATE KEY-----" in text)
        assertTrue("  ed25519_public: ${host.publicLine}" in text)
        assertTrue(text.lines().count { it.trim() == "ssh-rsa AAAA admin" || it.trim() == "- ssh-rsa AAAA admin" } == 2)
        assertTrue(text.lines().none { it.contains('\t') })
    }

    private class FakeProvider(var states: MutableList<CloudServer>, val lostAnswer: Boolean = false) : CloudProvider {
        override val info = CloudzyProvider.INFO
        var creates = 0; var finds = 0
        override fun verify() {}
        override fun regions() = emptyList<CloudRegion>()
        override fun plans(region: String) = emptyList<CloudPlan>()
        override fun create(name: String, region: String, plan: CloudPlan, authorizedKey: String, userData: String, idempotencyKey: String): CloudServer {
            creates++
            if (lostAnswer && creates == 1) throw CloudException("network", "reset")
            return states.removeAt(0)
        }
        override fun get(id: String) = states.removeAt(0)
        override fun find(name: String): CloudServer? { finds++; return if (lostAnswer) CloudServer("s9", CloudServer.State.PENDING, null) else null }
        override fun delete(id: String) {}
    }

    private val plan = CloudPlan("p1", "ams", "1GB", 1, 1024, 25, 4.95, "USD")

    @Test fun provisionWaitsForIpPinsHostKeyAndReturnsKeyAccess() {
        val store = MemoryHostKeyStore()
        val admin = SshKeyPair.generate("admin")
        val p = FakeProvider(mutableListOf(
            CloudServer("s1", CloudServer.State.PENDING, null),
            CloudServer("s1", CloudServer.State.PENDING, null),
            CloudServer("s1", CloudServer.State.READY, "203.0.113.9"),
        ))
        var created: String? = null
        var sshChecked = false
        val stages = ArrayList<CloudProvisioner.Stage>()
        val creds = CloudProvisioner(store, sleep = {}, sshReady = { sshChecked = true })
            .provision(p, "ams", plan, admin, "paramnezia-t", { stages += it }) { created = it }
        assertEquals("s1", created)
        assertEquals("203.0.113.9", creds.host)
        assertEquals("root", creds.user)
        assertEquals(admin.privatePem, creds.privateKey)
        assertEquals("", creds.password)
        assertTrue(sshChecked)
        assertTrue(store.get("203.0.113.9", 22)!!.startsWith("SHA256:"))
        assertEquals(CloudProvisioner.Stage.entries.toList(), stages)
    }

    @Test fun lostCreateAnswerDoesNotBuyTwice() {
        val p = FakeProvider(mutableListOf(CloudServer("s9", CloudServer.State.READY, "203.0.113.10")), lostAnswer = true)
        var created: String? = null
        CloudProvisioner(MemoryHostKeyStore(), sleep = {}, sshReady = {}).provision(p, "ams", plan, SshKeyPair.generate("a"), "n") { created = it }
        assertEquals(1, p.creates)
        assertEquals(1, p.finds)
        assertEquals("s9", created)
    }

    @Test fun failedServerIsReportedWithItsId() {
        val p = FakeProvider(mutableListOf(CloudServer("s1", CloudServer.State.PENDING, null), CloudServer("s1", CloudServer.State.FAILED, null, "no_stock")))
        var created: String? = null
        try {
            CloudProvisioner(MemoryHostKeyStore(), sleep = {}, sshReady = {}).provision(p, "ams", plan, SshKeyPair.generate("a"), "n") { created = it }
            fail()
        } catch (e: CloudException) { assertEquals("create_failed", e.code) }
        assertEquals("s1", created) // the caller can still delete it
        assertNull(null)
    }
}
