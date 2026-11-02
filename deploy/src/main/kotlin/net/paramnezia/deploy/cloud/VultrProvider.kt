package net.paramnezia.deploy.cloud

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.util.Base64

/**
 * Vultr API v2 (`https://api.vultr.com/v2`, Bearer API key).
 *
 * Verified against Vultr's own Go client and CLI, github.com/vultr/govultr (`region.go`: id, city,
 * country, continent; `plans.go`: id, vcpu_count, ram MB, disk GB, monthly_cost, type, locations;
 * `os.go`; `ssh_key.go`; `instance.go`: `POST /instances` {region, plan, os_id, label, hostname,
 * sshkey_id[], user_data}, reply `instance` with id, main_ip, status, power_status) and
 * github.com/vultr/vultr-cli (`cmd/instance/instance.go`: user_data is sent base64-encoded).
 */
class VultrProvider(token: String, transport: CloudHttp.Transport = CloudHttp.UrlTransport, base: String = BASE) : CloudProvider {
    override val info = INFO
    private val http = CloudHttp(base, mapOf("Authorization" to "Bearer $token"), transport)

    override fun verify() { http.get("/account") }

    override fun regions(): List<CloudRegion> = parseRegions(http.get("/regions?per_page=500").obj())

    override fun plans(region: String): List<CloudPlan> = parsePlans(http.get("/plans?per_page=500").obj(), region)

    override fun catalog(): CloudCatalog {
        val (r, p) = Parallel.both({ http.get("/regions?per_page=500").obj() }, { http.get("/plans?per_page=500").obj() })
        val regions = parseRegions(r)
        return CloudCatalog.of(info.id, regions, regions.associate { it.id to parsePlans(p, it.id) })
    }

    private fun parseRegions(o: JsonObject?): List<CloudRegion> = o?.get("regions").arr().mapNotNull { it.obj() }.mapNotNull { r ->
        val id = r.str("id") ?: return@mapNotNull null
        val city = r.str("city") ?: id
        CloudRegion(id, city, Geo.country(r.str("country"), city, id))
    }.sortedWith(compareBy({ it.inRussia }, { it.km ?: Int.MAX_VALUE }))

    private fun parsePlans(o: JsonObject?, region: String): List<CloudPlan> = o?.get("plans").arr().mapNotNull { it.obj() }
        .filter { it.str("type") in TYPES && it["locations"].arr().any { l -> (l as? JsonPrimitive)?.content == region } }
        .mapNotNull { p ->
            val id = p.str("id") ?: return@mapNotNull null
            CloudPlan(
                id = id, region = region, title = id,
                cpu = p.int("vcpu_count") ?: p.int("cpu_count") ?: 0,
                ramMb = p.int("ram") ?: 0,
                diskGb = p.int("disk") ?: 0,
                monthly = p.num("monthly_cost"),
                currency = "USD",
            )
        }.let(CloudProviders::dedupe)

    override fun create(name: String, region: String, plan: CloudPlan, authorizedKey: String, userData: String, idempotencyKey: String): CloudServer {
        val body = buildJsonObject {
            put("region", region)
            put("plan", plan.id)
            put("os_id", osId())
            put("label", name)
            put("hostname", name)
            putJsonArray("sshkey_id") { add(JsonPrimitive(sshKeyId(authorizedKey))) }
            put("user_data", Base64.getEncoder().encodeToString(userData.toByteArray()))
            put("backups", "disabled")
            put("activation_email", false)
        }
        return parse(http.post("/instances", body).obj()?.get("instance").obj())
    }

    override fun get(id: String): CloudServer = try {
        parse(http.get("/instances/${seg(id)}").obj()?.get("instance").obj())
    } catch (e: CloudException) {
        if (e.code == "not_found") CloudServer(id, CloudServer.State.GONE, null) else throw e
    }

    override fun find(name: String): CloudServer? =
        http.get("/instances?per_page=500&label=${seg(name)}").obj()?.get("instances").arr().mapNotNull { it.obj() }
            .firstOrNull { it.str("label") == name }?.let(::parse)

    override fun delete(id: String) {
        try { http.delete("/instances/${seg(id)}") } catch (e: CloudException) { if (e.code != "not_found") throw e }
    }

    private fun osId(): Int {
        val all = http.get("/os?per_page=500").obj()?.get("os").arr().mapNotNull { it.obj() }
        for (v in listOf("24.04", "22.04")) {
            all.firstOrNull { o -> o.str("family") == "ubuntu" && o.str("arch") == "x64" && v in o.str("name").orEmpty() }?.int("id")?.let { return it }
        }
        throw CloudException("no_image", "У Vultr нет образа Ubuntu 22.04/24.04")
    }

    private fun sshKeyId(publicKey: String): String {
        val known = http.get("/ssh-keys?per_page=500").obj()?.get("ssh_keys").arr().mapNotNull { it.obj() }
            .firstOrNull { sameKey(it.str("ssh_key"), publicKey) }
        known?.str("id")?.let { return it }
        val made = http.post("/ssh-keys", buildJsonObject { put("name", "Paramnezia"); put("ssh_key", publicKey.trim()) })
        return made.obj()?.get("ssh_key").obj()?.str("id") ?: throw CloudException("bad_reply", "Vultr не вернул id SSH-ключа")
    }

    private fun parse(o: JsonObject?): CloudServer {
        o ?: throw CloudException("bad_reply", "Vultr не вернул данные сервера")
        val id = o.str("id") ?: throw CloudException("bad_reply", "Vultr не вернул id сервера")
        val status = o.str("status").orEmpty()
        val ip = o.str("main_ip")?.takeIf { it.isNotBlank() && it != "0.0.0.0" }
        val state = when {
            status == "active" && o.str("power_status") == "running" && ip != null -> CloudServer.State.READY
            status in setOf("suspended", "closed") -> CloudServer.State.FAILED
            else -> CloudServer.State.PENDING
        }
        return CloudServer(id, state, ip, status)
    }

    companion object {
        const val BASE = "https://api.vultr.com/v2"
        /** Regular, high-frequency and high-performance cloud compute (not GPU or dedicated). */
        private val TYPES = setOf("vc2", "vhf", "vhp")

        val INFO = ProviderInfo(
            id = "vultr",
            name = "Vultr",
            tokenUrl = "https://my.vultr.com/settings/#settingsapi",
            tokenHint = "Vultr → Account → API → Enable API. В «Access Control» разрешите все IPv4 (0.0.0.0/0), иначе телефон не пустят",
            currency = "USD",
            blurb = "Нужна зарубежная карта или PayPal · 30+ городов · от 5 $",
            color = 0xFF007BFC,
        )

        private fun seg(id: String) = java.net.URLEncoder.encode(id, "UTF-8")
    }
}
