package net.paramnezia.deploy.cloud

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Cloudzy public API v1 (`https://dash.cloudzy.com/api/v1`, Bearer developer token).
 *
 * Verified against Cloudzy's own CLI, github.com/CloudzyVPS/cli (`src/api/ops.rs`,
 * `src/commands/catalog.rs`, `src/commands/servers.rs`): `GET /regions`, `GET /pricing/catalog`
 * (`plans`, `prices` with `planId`/`regionId`/`billingCycle`/`monthlyEquivCents`), `GET /os-templates`,
 * `POST /services` with an `Idempotency-Key` header, `GET|DELETE /services/{id}` (`state`, `ipAddress`).
 */
class CloudzyProvider(token: String, transport: CloudHttp.Transport = CloudHttp.UrlTransport, base: String = BASE) : CloudProvider {
    override val info = INFO
    private val http = CloudHttp(base, mapOf("Authorization" to "Bearer $token"), transport)

    override fun verify() { http.get("/auth/me") }

    override fun regions(): List<CloudRegion> = parseRegions(http.get("/regions"))

    override fun plans(region: String): List<CloudPlan> = parsePlans(http.get("/pricing/catalog").obj(), region)

    /** Two requests at once (regions and the price list), instead of one price list per region. */
    override fun catalog(): CloudCatalog {
        val (regionsJson, priceJson) = Parallel.both({ http.get("/regions") }, { http.get("/pricing/catalog").obj() })
        val regions = parseRegions(regionsJson)
        return CloudCatalog.of(info.id, regions, regions.associate { it.id to parsePlans(priceJson, it.id) })
    }

    /** The catalog leaves out the IPv4 address; the quote has the real monthly sum. */
    override fun exactPrice(plan: CloudPlan): Double? = runCatching {
        val q = http.post("/pricing/quote", buildJsonObject {
            put("planId", plan.id); put("region", plan.region); put("billingCycle", CYCLE)
            put("quantity", 1); put("includeIpv4", true); putJsonObject("extras") {}
        }).obj()
        q?.num("subtotalMonthlyCents")?.let { it / 100.0 }
    }.getOrNull()

    private fun parseRegions(json: kotlinx.serialization.json.JsonElement): List<CloudRegion> = json.arr().mapNotNull { it.obj() }
        .filter { it.bool("isHidden") != true && it.bool("isActive") != false && it.bool("isOutOfStock") != true }
        .mapNotNull { r ->
            val id = r.str("id") ?: return@mapNotNull null
            val name = r.str("name") ?: id
            CloudRegion(id, name, countryOf(r.str("countryCode") ?: r.str("country") ?: name, r.str("abbr"), id))
        }
        .sortedWith(compareBy({ it.inRussia }, { it.name }))

    private fun parsePlans(catalog: JsonObject?, region: String): List<CloudPlan> {
        catalog ?: return emptyList()
        val prices = catalog["prices"].arr().mapNotNull { it.obj() }
        return catalog["plans"].arr().mapNotNull { it.obj() }.mapNotNull { p ->
            val id = p.str("id") ?: return@mapNotNull null
            fun pick(rid: String) = prices.firstOrNull { it.str("planId") == id && it.str("billingCycle") == CYCLE && (it.str("regionId") ?: "") == rid }
            val price = pick(region) ?: pick("") ?: return@mapNotNull null
            if (price.bool("inStock") == false) return@mapNotNull null
            CloudPlan(
                id = id,
                region = region,
                title = p.str("name") ?: p.str("slug") ?: id,
                cpu = p.int("cpuCores") ?: p.int("cpu") ?: 0,
                ramMb = p.int("memoryMb") ?: p.int("ramMb") ?: 0,
                diskGb = p.int("diskGb") ?: 0,
                monthly = price.num("monthlyEquivCents")?.let { it / 100.0 },
                currency = price.str("currency") ?: "USD",
            )
        }.let(CloudProviders::dedupe)
    }

    override fun create(name: String, region: String, plan: CloudPlan, authorizedKey: String, userData: String, idempotencyKey: String): CloudServer {
        val body = buildJsonObject {
            put("planId", plan.id)
            put("region", region)
            put("hostname", name)
            put("billingCycle", CYCLE)
            put("osTemplateId", osTemplate())
            putJsonArray("sshAuthorizedKeys") { add(JsonPrimitive(authorizedKey)) }
            putJsonObject("config") { put("userData", userData) }
        }
        return parse(http.post("/services", body, mapOf("Idempotency-Key" to idempotencyKey)).obj())
    }

    override fun get(id: String): CloudServer = try {
        parse(http.get("/services/${seg(id)}").obj())
    } catch (e: CloudException) {
        if (e.code == "not_found") CloudServer(id, CloudServer.State.GONE, null) else throw e
    }

    override fun find(name: String): CloudServer? =
        http.get("/services").arr().mapNotNull { it.obj() }.firstOrNull { it.str("hostname") == name }?.let(::parse)

    override fun delete(id: String) {
        try { http.delete("/services/${seg(id)}") } catch (e: CloudException) { if (e.code != "not_found") throw e }
    }

    /** Newest Ubuntu LTS the account can install (ids look like `ubuntu-24.04`). */
    private fun osTemplate(): String {
        val all = http.get("/os-templates").arr().mapNotNull { it.obj() }.filter { it.bool("available") != false }
        for (v in listOf("24.04", "22.04")) {
            all.firstOrNull { o ->
                val text = listOf(o.str("id"), o.str("name"), o.str("family")).joinToString(" ").lowercase()
                "ubuntu" in text && (o.str("version") == v || v in text)
            }?.str("id")?.let { return it }
        }
        return "ubuntu-24.04"
    }

    private fun parse(o: JsonObject?): CloudServer {
        o ?: throw CloudException("bad_reply", "Cloudzy не вернул данные сервера")
        val id = o.str("id") ?: throw CloudException("bad_reply", "Cloudzy не вернул id сервера")
        val state = when (o.str("state")) {
            "active" -> CloudServer.State.READY
            "terminated" -> CloudServer.State.GONE
            in FAILED -> CloudServer.State.FAILED
            else -> CloudServer.State.PENDING
        }
        val ip = o.str("ipAddress")?.takeIf { it.isNotBlank() && it.count { c -> c == '.' } == 3 }
        return CloudServer(id, state, ip, o.str("failureReason") ?: o.str("state").orEmpty())
    }

    companion object {
        const val BASE = "https://dash.cloudzy.com/api/v1"
        const val CYCLE = "monthly"
        private val FAILED = setOf("failed_provisioning", "suspended_nonpayment", "suspended_abuse", "suspended_fraud_hold", "suspended_admin")

        val INFO = ProviderInfo(
            id = "cloudzy",
            name = "Cloudzy",
            tokenUrl = "https://dash.cloudzy.com/",
            tokenHint = "Панель Cloudzy → Developer API → новый токен (права: серверы, SSH-ключи, просмотр цен)",
            currency = "USD",
            blurb = "Криптовалюта и карты · 15+ стран · от 5 $",
            color = 0xFF2F6BFF,
        )

        private fun seg(id: String) = java.net.URLEncoder.encode(id, "UTF-8")

        /** Cloudzy names regions by city ("New York City") and gives a short code (`abbr`). */
        internal fun countryOf(vararg hints: String?): String = Geo.country(*hints)
    }
}
