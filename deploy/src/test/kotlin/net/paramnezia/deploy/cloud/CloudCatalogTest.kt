package net.paramnezia.deploy.cloud

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/** Scripted API: (method path?query) → (code, body); path is what follows `/api/v1`. */
private class Script(private val routes: Map<String, Pair<Int, String>>) : CloudHttp.Transport {
    val calls = java.util.Collections.synchronizedList(ArrayList<Triple<String, String, String?>>())
    override fun send(method: String, url: String, headers: Map<String, String>, body: String?): Pair<Int, String> {
        val path = url.substringAfter("/api/v1")
        calls += Triple(method, path, body)
        return routes["$method $path"] ?: (404 to """{"message":"no route $method $path"}""")
    }
    fun body(method: String, path: String) = Json.parseToJsonElement(calls.last { it.first == method && it.second == path }.third!!).jsonObject
}

private const val BASE = "https://api.test/api/v1"

class CloudCatalogTest {

    @Test fun geoKnowsProviderNames() {
        assertEquals("US", Geo.country("New York City"))
        assertEquals("US", Geo.country("nyc3", "New York 3"))
        assertEquals("NL", Geo.country("ams3", "Amsterdam 3"))
        assertEquals("DE", Geo.country("DE", "Falkenstein", "fsn1"))
        assertEquals("FI", Geo.country("hel1"))
        assertEquals("CH", Geo.country("Bern"))
        assertEquals("RU", Geo.country("Moscow"))
        assertEquals("AU", Geo.country("Sydney"))
        assertEquals("??", Geo.country("Atlantis"))
        assertEquals("🇳🇱", Geo.flag("NL"))
        assertEquals("🌐", Geo.flag("??"))
        assertEquals(Geo.Area.EUROPE, Geo.area("NL"))
        assertEquals(Geo.Area.AMERICA, Geo.area("US"))
        val ams = CloudRegion("ams", "Amsterdam", "NL")
        assertEquals("Амстердам", ams.city)
        assertEquals(Geo.Reach.NEAR, ams.reach)
        assertEquals(Geo.Reach.FAR, CloudRegion("syd", "Sydney", "AU").reach)
        // A city name in Russian that is not in the table stays as given.
        assertEquals("Атлантида", CloudRegion("x", "Атлантида", "??").city)
    }

    @Test fun samePlansShownOnce() {
        val a = CloudPlan("std-1", "ams", "Standard 1GB", 1, 1024, 25, 6.95, "USD")
        val b = CloudPlan("hf-1", "ams", "Other line 1GB", 1, 1024, 25, 6.95, "USD")
        val c = CloudPlan("std-2", "ams", "2GB", 1, 2048, 60, 14.95, "USD")
        val d = CloudPlan("std-05", "ams", "512", 1, 512, 20, 4.95, "USD")
        assertEquals(listOf("std-05", "std-1", "std-2"), CloudProviders.dedupe(listOf(c, a, b, d)).map { it.id })
    }

    private fun plan(id: String, region: String, ram: Int, price: Double) = CloudPlan(id, region, id, 1, ram, 25, price, "USD")

    @Test fun recommendsLastPlaceElseClosestForeignWithAFittingPlan() {
        val regions = listOf(
            CloudRegion("syd", "Sydney", "AU"), CloudRegion("nyc", "New York City", "US"),
            CloudRegion("ams", "Amsterdam", "NL"), CloudRegion("hel", "Helsinki", "FI"), CloudRegion("msk", "Moscow", "RU"),
        )
        val plans = mapOf(
            "syd" to listOf(plan("s1", "syd", 1024, 3.0)),
            "nyc" to listOf(plan("n1", "nyc", 1024, 5.0)),
            "ams" to listOf(plan("a0", "ams", 512, 2.0), plan("a1", "ams", 1024, 6.0), plan("a2", "ams", 2048, 9.0)),
            "hel" to listOf(plan("h0", "hel", 512, 2.0)), // only too small: skipped
            "msk" to listOf(plan("m1", "msk", 1024, 1.0)),
        )
        val cat = CloudCatalog.of("cloudzy", regions, plans)
        val pick = CloudProviders.recommend(cat)!!
        assertEquals("ams", pick.region.id)
        assertEquals("a1", pick.plan.id)
        assertEquals("ближе всего к России", pick.reason)
        assertEquals("nyc", CloudProviders.recommend(cat, lastRegion = "nyc")!!.region.id)
        assertFalse(CloudProviders.recommend(cat)!!.region.inRussia)
    }

    @Test fun catalogSurvivesTheCache() {
        val cat = CloudCatalog.of("timeweb", listOf(CloudRegion("nl-1", "Амстердам", "NL")),
            mapOf("nl-1" to listOf(CloudPlan("21", "nl-1", "x", 1, 1024, 15, 477.0, "RUB", mapOf("bandwidth" to "200")))))
        val back = CloudCatalog.decode(CloudCatalog.encode(cat))
        assertEquals(cat, back)
        assertEquals(null, CloudCatalog.decode("{broken"))
    }

    @Test fun cloudzyCatalogIsTwoRequestsAndDeduplicated() {
        val api = Script(mapOf(
            "GET /regions" to (200 to """[{"id":"r1","name":"New York City","abbr":"NYC"},{"id":"r2","name":"Amsterdam","abbr":"AMS"}]"""),
            "GET /pricing/catalog" to (200 to """{"plans":[
                {"id":"a","name":"Standard","instanceType":"standard","cpuCores":1,"memoryMb":1024,"diskGb":25},
                {"id":"b","name":"Standard","instanceType":"other","cpuCores":1,"memoryMb":1024,"diskGb":25}],
              "prices":[
                {"planId":"a","regionId":"","billingCycle":"monthly","monthlyEquivCents":695,"currency":"USD"},
                {"planId":"b","regionId":"","billingCycle":"monthly","monthlyEquivCents":695,"currency":"USD"}]}"""),
            "POST /pricing/quote" to (200 to """{"subtotalMonthlyCents":795,"currency":"USD"}"""),
        ))
        val p = CloudzyProvider("t", api, BASE)
        val cat = p.catalog()
        assertEquals(2, api.calls.size)
        assertEquals(setOf("US", "NL"), cat.regions.map { it.countryCode }.toSet())
        assertEquals(1, cat.plansIn("r2").size)
        assertEquals("Нью-Йорк", cat.regions.first { it.id == "r1" }.city)
        assertEquals(7.95, p.exactPrice(cat.plansIn("r2")[0])!!, 0.001)
        val q = api.body("POST", "/pricing/quote")
        assertEquals("true", q["includeIpv4"]!!.jsonPrimitive.content)
        assertEquals("r2", q["region"]!!.jsonPrimitive.content)
    }

    @Test fun vultrCatalogAndCreate() {
        val api = Script(mapOf(
            "GET /regions?per_page=500" to (200 to """{"regions":[{"id":"ams","city":"Amsterdam","country":"NL","continent":"Europe"},
                {"id":"ewr","city":"New Jersey","country":"US","continent":"North America"}]}"""),
            "GET /plans?per_page=500" to (200 to """{"plans":[
                {"id":"vc2-1c-1gb","vcpu_count":1,"ram":1024,"disk":25,"monthly_cost":5,"type":"vc2","locations":["ams","ewr"]},
                {"id":"vc2-1c-0.5gb","vcpu_count":1,"ram":512,"disk":10,"monthly_cost":2.5,"type":"vc2","locations":["ewr"]},
                {"id":"vcg-a100","vcpu_count":12,"ram":120000,"disk":1400,"monthly_cost":1750,"type":"vcg","locations":["ams"]}]}"""),
            "GET /os?per_page=500" to (200 to """{"os":[{"id":1743,"name":"Ubuntu 22.04 LTS x64","arch":"x64","family":"ubuntu"},
                {"id":2284,"name":"Ubuntu 24.04 LTS x64","arch":"x64","family":"ubuntu"}]}"""),
            "GET /ssh-keys?per_page=500" to (200 to """{"ssh_keys":[]}"""),
            "POST /ssh-keys" to (201 to """{"ssh_key":{"id":"key-1"}}"""),
            "POST /instances" to (202 to """{"instance":{"id":"i-1","main_ip":"0.0.0.0","status":"pending","power_status":"stopped"}}"""),
            "GET /instances/i-1" to (200 to """{"instance":{"id":"i-1","main_ip":"192.0.2.5","status":"active","power_status":"running"}}"""),
        ))
        val p = VultrProvider("v", api, BASE)
        val cat = p.catalog()
        assertEquals(listOf("ams", "ewr"), cat.regions.map { it.id }) // Amsterdam is closer to Moscow
        assertEquals(listOf("vc2-1c-1gb"), cat.plansIn("ams").map { it.id }) // no GPU plans
        assertEquals(2, cat.plansIn("ewr").size)
        val made = p.create("paramnezia-x", "ams", cat.plansIn("ams")[0], "ssh-rsa AAAA admin", "#cloud-config\n", "k")
        assertEquals(CloudServer.State.PENDING, made.state)
        val body = api.body("POST", "/instances")
        assertEquals(2284, body["os_id"]!!.jsonPrimitive.content.toInt())
        assertEquals("key-1", body["sshkey_id"]!!.jsonArray[0].jsonPrimitive.content)
        assertEquals("#cloud-config\n", String(Base64.getDecoder().decode(body["user_data"]!!.jsonPrimitive.content)))
        val ready = p.get("i-1")
        assertEquals(CloudServer.State.READY, ready.state)
        assertEquals("192.0.2.5", ready.ipv4)
    }

    @Test fun hetznerCatalogAndCreate() {
        val api = Script(mapOf(
            "GET /locations" to (200 to """{"locations":[{"name":"ash","city":"Ashburn, VA","country":"US"},{"name":"hel1","city":"Helsinki","country":"FI"}]}"""),
            "GET /server_types?per_page=50" to (200 to """{"server_types":[
                {"name":"cx22","cores":2,"memory":4.0,"disk":40,"architecture":"x86","deprecated":false,
                 "prices":[{"location":"hel1","price_monthly":{"net":"3.79","gross":"4.5101"}}]},
                {"name":"cax11","cores":2,"memory":4.0,"disk":40,"architecture":"arm","prices":[{"location":"hel1","price_monthly":{"gross":"4.0"}}]},
                {"name":"cpx11","cores":2,"memory":2.0,"disk":40,"architecture":"x86",
                 "prices":[{"location":"ash","price_monthly":{"gross":"5.39"}},{"location":"hel1","price_monthly":{"gross":"5.39"}}]}]}"""),
            "GET /ssh_keys?per_page=50" to (200 to """{"ssh_keys":[{"id":77,"public_key":"ssh-rsa AAAA laptop"}]}"""),
            "POST /servers" to (201 to """{"server":{"id":42,"status":"initializing","public_net":{"ipv4":{"ip":"198.51.100.9"}}}}"""),
            "GET /servers/42" to (200 to """{"server":{"id":42,"status":"running","public_net":{"ipv4":{"ip":"198.51.100.9"}}}}"""),
        ))
        val p = HetznerProvider("h", api, BASE)
        val cat = p.catalog()
        assertEquals("hel1", cat.regions.first().id)
        assertEquals("Хельсинки", cat.regions.first().city)
        assertEquals(listOf("cx22", "cpx11"), cat.plansIn("hel1").map { it.id }) // no ARM
        assertEquals(4.51, cat.plansIn("hel1")[0].monthly!!, 0.001)
        assertEquals(4096, cat.plansIn("hel1")[0].ramMb)
        p.create("paramnezia-x", "hel1", cat.plansIn("hel1")[0], "ssh-rsa AAAA admin", "#cloud-config\n", "k")
        val body = api.body("POST", "/servers")
        assertEquals("cx22", body["server_type"]!!.jsonPrimitive.content)
        assertEquals("ubuntu-24.04", body["image"]!!.jsonPrimitive.content)
        assertEquals("77", body["ssh_keys"]!!.jsonArray[0].jsonPrimitive.content) // same key, other comment: reused
        assertEquals("#cloud-config\n", body["user_data"]!!.jsonPrimitive.content)
        assertEquals("198.51.100.9", p.get("42").ipv4)
    }

    @Test fun digitalOceanCatalogAndCreate() {
        val api = Script(mapOf(
            "GET /regions?per_page=200" to (200 to """{"regions":[{"slug":"nyc3","name":"New York 3","available":true},
                {"slug":"ams3","name":"Amsterdam 3","available":true},{"slug":"old1","name":"Old","available":false}]}"""),
            "GET /sizes?per_page=200" to (200 to """{"sizes":[
                {"slug":"s-1vcpu-1gb","memory":1024,"vcpus":1,"disk":25,"price_monthly":6,"regions":["ams3","nyc3"],"available":true},
                {"slug":"db-s-1vcpu-1gb","memory":1024,"vcpus":1,"disk":10,"price_monthly":15,"regions":["ams3"],"available":true}]}"""),
            "GET /account/keys?per_page=200" to (200 to """{"ssh_keys":[]}"""),
            "POST /account/keys" to (201 to """{"ssh_key":{"id":512,"fingerprint":"aa"}}"""),
            "POST /droplets" to (202 to """{"droplet":{"id":3164494,"status":"new","networks":{"v4":[]}}}"""),
            "GET /droplets/3164494" to (200 to """{"droplet":{"id":3164494,"status":"active","networks":{"v4":[
                {"ip_address":"10.0.0.2","type":"private"},{"ip_address":"203.0.113.44","type":"public"}]}}}"""),
        ))
        val p = DigitalOceanProvider("d", api, BASE)
        val cat = p.catalog()
        assertEquals(listOf("ams3", "nyc3"), cat.regions.map { it.id })
        assertEquals(listOf("s-1vcpu-1gb"), cat.plansIn("ams3").map { it.id })
        p.create("paramnezia-x", "ams3", cat.plansIn("ams3")[0], "ssh-rsa AAAA admin", "#cloud-config\n", "k")
        val body = api.body("POST", "/droplets")
        assertEquals("ubuntu-24-04-x64", body["image"]!!.jsonPrimitive.content)
        assertEquals("512", body["ssh_keys"]!!.jsonArray[0].jsonPrimitive.content)
        val s = p.get("3164494")
        assertEquals(CloudServer.State.READY, s.state)
        assertEquals("203.0.113.44", s.ipv4)
    }

    @Test fun everyProviderOpens() {
        for (i in CloudProviders.all) {
            assertNotNull(CloudProviders.open(i.id, "t"))
            assertTrue(i.blurb.isNotEmpty())
        }
    }
}
