package net.paramnezia.deploy.cloud

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * DigitalOcean API v2 (`https://api.digitalocean.com/v2`, Bearer personal access token).
 *
 * Verified against DigitalOcean's own Go client, github.com/digitalocean/godo (`regions.go`: slug,
 * name, available; `sizes.go`: slug, memory MB, vcpus, disk GB, price_monthly, regions, available;
 * `droplets.go`: `POST /droplets` {name, region, size, image, ssh_keys[], user_data, tags}, reply
 * `droplet` with id, status and `networks.v4[]` {ip_address, type}; `keys.go`: `/account/keys`).
 */
class DigitalOceanProvider(token: String, transport: CloudHttp.Transport = CloudHttp.UrlTransport, base: String = BASE) : CloudProvider {
    override val info = INFO
    private val http = CloudHttp(base, mapOf("Authorization" to "Bearer $token"), transport)

    override fun verify() { http.get("/account") }

    override fun regions(): List<CloudRegion> = parseRegions(http.get("/regions?per_page=200").obj())

    override fun plans(region: String): List<CloudPlan> = parsePlans(http.get("/sizes?per_page=200").obj(), region)

    override fun catalog(): CloudCatalog {
        val (r, s) = Parallel.both({ http.get("/regions?per_page=200").obj() }, { http.get("/sizes?per_page=200").obj() })
        val regions = parseRegions(r)
        return CloudCatalog.of(info.id, regions, regions.associate { it.id to parsePlans(s, it.id) })
    }

    private fun parseRegions(o: JsonObject?): List<CloudRegion> = o?.get("regions").arr().mapNotNull { it.obj() }
        .filter { it.bool("available") != false }
        .mapNotNull { r ->
            val slug = r.str("slug") ?: return@mapNotNull null
            val name = r.str("name") ?: slug
            CloudRegion(slug, name, Geo.country(slug, name))
        }.sortedWith(compareBy({ it.inRussia }, { it.km ?: Int.MAX_VALUE }, { it.id }))

    private fun parsePlans(o: JsonObject?, region: String): List<CloudPlan> = o?.get("sizes").arr().mapNotNull { it.obj() }
        .filter { s -> s.bool("available") != false && s["regions"].arr().any { (it as? JsonPrimitive)?.content == region } }
        .filter { s -> s.str("slug")?.startsWith("s-") == true } // basic droplets: the rest are for databases and GPUs
        .mapNotNull { s ->
            val slug = s.str("slug") ?: return@mapNotNull null
            CloudPlan(
                id = slug, region = region, title = slug,
                cpu = s.int("vcpus") ?: 0,
                ramMb = s.int("memory") ?: 0,
                diskGb = s.int("disk") ?: 0,
                monthly = s.num("price_monthly"),
                currency = "USD",
            )
        }.let(CloudProviders::dedupe)

    override fun create(name: String, region: String, plan: CloudPlan, authorizedKey: String, userData: String, idempotencyKey: String): CloudServer {
        val body = buildJsonObject {
            put("name", name)
            put("region", region)
            put("size", plan.id)
            put("image", "ubuntu-24-04-x64")
            putJsonArray("ssh_keys") { add(JsonPrimitive(sshKeyId(authorizedKey))) }
            put("user_data", userData)
            putJsonArray("tags") { add(JsonPrimitive("paramnezia")) }
        }
        return parse(http.post("/droplets", body).obj()?.get("droplet").obj())
    }

    override fun get(id: String): CloudServer = try {
        parse(http.get("/droplets/${id.toLong()}").obj()?.get("droplet").obj())
    } catch (e: CloudException) {
        if (e.code == "not_found") CloudServer(id, CloudServer.State.GONE, null) else throw e
    }

    override fun list(): List<CloudServer> = http.get("/droplets?per_page=200").obj()?.get("droplets").arr().mapNotNull { it.obj() }.map(::parse)

    override fun find(name: String): CloudServer? =
        http.get("/droplets?tag_name=paramnezia&per_page=200").obj()?.get("droplets").arr().mapNotNull { it.obj() }
            .firstOrNull { it.str("name") == name }?.let(::parse)

    // power_cycle: off and on again, without asking the OS (godo DropletActions.PowerCycle).
    override fun reboot(id: String) { http.post("/droplets/${id.toLong()}/actions", buildJsonObject { put("type", "power_cycle") }) }

    override fun delete(id: String) {
        try { http.delete("/droplets/${id.toLong()}") } catch (e: CloudException) { if (e.code != "not_found") throw e }
    }

    private fun sshKeyId(publicKey: String): Long {
        val known = http.get("/account/keys?per_page=200").obj()?.get("ssh_keys").arr().mapNotNull { it.obj() }
            .firstOrNull { sameKey(it.str("public_key"), publicKey) }
        known?.str("id")?.toLongOrNull()?.let { return it }
        val made = http.post("/account/keys", buildJsonObject { put("name", "Paramnezia"); put("public_key", publicKey.trim()) })
        return made.obj()?.get("ssh_key").obj()?.str("id")?.toLongOrNull() ?: throw CloudException("bad_reply", "DigitalOcean не вернул id SSH-ключа")
    }

    private fun parse(o: JsonObject?): CloudServer {
        o ?: throw CloudException("bad_reply", "DigitalOcean не вернул данные сервера")
        val id = o.str("id") ?: throw CloudException("bad_reply", "DigitalOcean не вернул id сервера")
        val status = o.str("status").orEmpty()
        val ip = o["networks"].obj()?.get("v4").arr().mapNotNull { it.obj() }.firstOrNull { it.str("type") == "public" }?.str("ip_address")
        val state = when (status) {
            "active" -> if (ip != null) CloudServer.State.READY else CloudServer.State.PENDING
            "archive" -> CloudServer.State.GONE
            else -> CloudServer.State.PENDING
        }
        return CloudServer(id, state, ip, status)
    }

    companion object {
        const val BASE = "https://api.digitalocean.com/v2"

        val INFO = ProviderInfo(
            id = "digitalocean",
            name = "DigitalOcean",
            tokenUrl = "https://cloud.digitalocean.com/account/api/tokens",
            tokenHint = "DigitalOcean → API → «Generate New Token», права Full Access (или droplet, ssh_key, regions, sizes)",
            currency = "USD",
            blurb = "Нужна зарубежная карта · сайт открывается только через VPN · от 4 $",
            color = 0xFF0069FF,
        )
    }
}
