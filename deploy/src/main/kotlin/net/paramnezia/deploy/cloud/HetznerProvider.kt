package net.paramnezia.deploy.cloud

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Hetzner Cloud API v1 (`https://api.hetzner.cloud/v1`, Bearer project token with read & write).
 *
 * Verified against Hetzner's own Go client, github.com/hetznercloud/hcloud-go (`hcloud/schema/
 * location.go`: name, city, country, latitude/longitude; `server_type.go`: name, cores, memory GB,
 * disk GB, architecture, deprecated, prices[location, price_monthly.gross]; `server.go`:
 * `POST /servers` {name, server_type, image, location, ssh_keys[], user_data, labels}, reply
 * `server` with id, status and `public_net.ipv4.ip`; `ssh_key.go`).
 */
class HetznerProvider(token: String, transport: CloudHttp.Transport = CloudHttp.UrlTransport, base: String = BASE) : CloudProvider {
    override val info = INFO
    private val http = CloudHttp(base, mapOf("Authorization" to "Bearer $token"), transport)

    override fun verify() { http.get("/servers?per_page=1") }

    override fun regions(): List<CloudRegion> = parseRegions(http.get("/locations").obj())

    override fun plans(region: String): List<CloudPlan> = parsePlans(http.get("/server_types?per_page=50").obj(), region)

    override fun catalog(): CloudCatalog {
        val (l, t) = Parallel.both({ http.get("/locations").obj() }, { http.get("/server_types?per_page=50").obj() })
        val regions = parseRegions(l)
        return CloudCatalog.of(info.id, regions, regions.associate { it.id to parsePlans(t, it.id) })
    }

    private fun parseRegions(o: JsonObject?): List<CloudRegion> = o?.get("locations").arr().mapNotNull { it.obj() }.mapNotNull { r ->
        val id = r.str("name") ?: return@mapNotNull null
        val city = r.str("city") ?: id
        CloudRegion(id, city, Geo.country(r.str("country"), city, id))
    }.sortedWith(compareBy({ it.inRussia }, { it.km ?: Int.MAX_VALUE }))

    private fun parsePlans(o: JsonObject?, region: String): List<CloudPlan> = o?.get("server_types").arr().mapNotNull { it.obj() }
        .filter { it.bool("deprecated") != true && (it.str("architecture") ?: "x86") == "x86" }
        .mapNotNull { t ->
            val name = t.str("name") ?: return@mapNotNull null
            val price = t["prices"].arr().mapNotNull { it.obj() }.firstOrNull { it.str("location") == region } ?: return@mapNotNull null
            CloudPlan(
                id = name, region = region, title = name.uppercase(),
                cpu = t.int("cores") ?: 0,
                ramMb = ((t.num("memory") ?: 0.0) * 1024).toInt(),
                diskGb = t.int("disk") ?: 0,
                monthly = price["price_monthly"].obj()?.num("gross")?.let { Math.round(it * 100) / 100.0 },
                currency = "EUR",
            )
        }.let(CloudProviders::dedupe)

    override fun create(name: String, region: String, plan: CloudPlan, authorizedKey: String, userData: String, idempotencyKey: String): CloudServer {
        val body = buildJsonObject {
            put("name", name)
            put("server_type", plan.id)
            put("image", "ubuntu-24.04")
            put("location", region)
            putJsonArray("ssh_keys") { add(JsonPrimitive(sshKeyId(authorizedKey))) }
            put("user_data", userData)
            putJsonObject("labels") { put("paramnezia", "1") }
        }
        return parse(http.post("/servers", body).obj()?.get("server").obj())
    }

    override fun get(id: String): CloudServer = try {
        parse(http.get("/servers/${id.toLong()}").obj()?.get("server").obj())
    } catch (e: CloudException) {
        if (e.code == "not_found") CloudServer(id, CloudServer.State.GONE, null) else throw e
    }

    override fun find(name: String): CloudServer? =
        http.get("/servers?name=${java.net.URLEncoder.encode(name, "UTF-8")}").obj()?.get("servers").arr().mapNotNull { it.obj() }
            .firstOrNull { it.str("name") == name }?.let(::parse)

    override fun delete(id: String) {
        try { http.delete("/servers/${id.toLong()}") } catch (e: CloudException) { if (e.code != "not_found") throw e }
    }

    private fun sshKeyId(publicKey: String): Long {
        val known = http.get("/ssh_keys?per_page=50").obj()?.get("ssh_keys").arr().mapNotNull { it.obj() }
            .firstOrNull { sameKey(it.str("public_key"), publicKey) }
        known?.str("id")?.toLongOrNull()?.let { return it }
        // Names are unique per project: tie this one to the key itself.
        val tag = Integer.toHexString(publicKey.trim().split(' ').take(2).joinToString(" ").hashCode())
        val made = http.post("/ssh_keys", buildJsonObject { put("name", "paramnezia-$tag"); put("public_key", publicKey.trim()) })
        return made.obj()?.get("ssh_key").obj()?.str("id")?.toLongOrNull() ?: throw CloudException("bad_reply", "Hetzner не вернул id SSH-ключа")
    }

    private fun parse(o: JsonObject?): CloudServer {
        o ?: throw CloudException("bad_reply", "Hetzner не вернул данные сервера")
        val id = o.str("id") ?: throw CloudException("bad_reply", "Hetzner не вернул id сервера")
        val status = o.str("status").orEmpty()
        val ip = o["public_net"].obj()?.get("ipv4").obj()?.str("ip")?.takeIf { it.isNotBlank() }
        val state = when (status) {
            "running" -> if (ip != null) CloudServer.State.READY else CloudServer.State.PENDING
            "deleting" -> CloudServer.State.GONE
            else -> CloudServer.State.PENDING
        }
        return CloudServer(id, state, ip, status)
    }

    companion object {
        const val BASE = "https://api.hetzner.cloud/v1"

        val INFO = ProviderInfo(
            id = "hetzner",
            name = "Hetzner",
            tokenUrl = "https://console.hetzner.cloud/projects",
            tokenHint = "Hetzner Console → проект → Security → API tokens → «Generate», права Read & Write",
            currency = "EUR",
            blurb = "Нужен зарубежный аккаунт · Германия и Финляндия · от 4 €",
            color = 0xFFD50C2D,
        )
    }
}
