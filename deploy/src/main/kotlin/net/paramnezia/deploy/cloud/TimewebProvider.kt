package net.paramnezia.deploy.cloud

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Timeweb Cloud public API v1 (`https://api.timeweb.cloud/api/v1`, Bearer token).
 *
 * Verified against Timeweb's own CLI, github.com/timeweb-cloud/twc (`twc/api/client.py`,
 * `twc/commands/server.py`): `GET /presets/servers` (`server_presets`: id, location, price, cpu,
 * ram MB, disk MB, bandwidth), `GET /os/servers` (`servers_os`: id, name, version), `GET|POST /ssh-keys`,
 * `POST /servers` (name, bandwidth, preset_id, os_id, ssh_keys_ids, cloud_init), `GET|DELETE
 * /servers/{id}` (`server.status` is `on` when running, public IPv4 in `networks[].ips[]`).
 */
class TimewebProvider(token: String, transport: CloudHttp.Transport = CloudHttp.UrlTransport, base: String = BASE) : CloudProvider {
    override val info = INFO
    private val http = CloudHttp(base, mapOf("Authorization" to "Bearer $token"), transport)

    override fun verify() { http.get("/account/status") }

    private fun presets(): List<JsonObject> = http.get("/presets/servers").obj()?.get("server_presets").arr().mapNotNull { it.obj() }

    override fun regions(): List<CloudRegion> = regionsOf(presets())

    override fun plans(region: String): List<CloudPlan> = plansOf(presets(), region)

    /** One request: the preset list has every location and price. */
    override fun catalog(): CloudCatalog {
        val all = presets()
        val regions = regionsOf(all)
        return CloudCatalog.of(info.id, regions, regions.associate { it.id to plansOf(all, it.id) })
    }

    private fun regionsOf(presets: List<JsonObject>): List<CloudRegion> = presets.mapNotNull { it.str("location") }.distinct()
        .map { CloudRegion(it, LOCATIONS[it]?.first ?: it, LOCATIONS[it]?.second ?: it.substringBefore('-').uppercase()) }
        .sortedWith(compareBy({ it.inRussia }, { ORDER.indexOf(it.id).let { i -> if (i < 0) 99 else i } }))

    private fun plansOf(presets: List<JsonObject>, region: String): List<CloudPlan> = presets.filter { it.str("location") == region }.mapNotNull { p ->
        val id = p.int("id") ?: return@mapNotNull null
        val ram = p.int("ram") ?: 0
        val cpu = p.int("cpu") ?: 0
        CloudPlan(
            id = id.toString(),
            region = region,
            title = p.str("description_short") ?: "$cpu vCPU / ${ram / 1024} ГБ",
            cpu = cpu,
            ramMb = ram,
            diskGb = (p.int("disk") ?: 0) / 1024,
            monthly = p.num("price"),
            currency = "RUB",
            extra = mapOf("bandwidth" to (p.int("bandwidth") ?: 200).toString()),
        )
    }.let(CloudProviders::dedupe)

    override fun create(name: String, region: String, plan: CloudPlan, authorizedKey: String, userData: String, idempotencyKey: String): CloudServer {
        val body = buildJsonObject {
            put("name", name)
            put("preset_id", plan.id.toInt())
            put("bandwidth", plan.extra["bandwidth"]?.toIntOrNull() ?: 200)
            put("os_id", osId())
            putJsonArray("ssh_keys_ids") { add(JsonPrimitive(sshKeyId(authorizedKey))) }
            put("is_ddos_guard", false)
            put("cloud_init", userData)
        }
        return parse(http.post("/servers", body).obj()?.get("server").obj())
    }

    override fun get(id: String): CloudServer = try {
        parse(http.get("/servers/${id.toLong()}").obj()?.get("server").obj())
    } catch (e: CloudException) {
        if (e.code == "not_found") CloudServer(id, CloudServer.State.GONE, null) else throw e
    }

    override fun find(name: String): CloudServer? =
        http.get("/servers?limit=100").obj()?.get("servers").arr().mapNotNull { it.obj() }.firstOrNull { it.str("name") == name }?.let(::parse)

    override fun delete(id: String) {
        try { http.delete("/servers/${id.toLong()}") } catch (e: CloudException) { if (e.code != "not_found") throw e }
    }

    private fun osId(): Int {
        val all = http.get("/os/servers").obj()?.get("servers_os").arr().mapNotNull { it.obj() }
        for (v in listOf("24.04", "22.04")) all.firstOrNull { it.str("name") == "ubuntu" && it.str("version") == v }?.int("id")?.let { return it }
        throw CloudException("no_image", "У Timeweb нет образа Ubuntu 22.04/24.04")
    }

    /** The account's id for [publicKey], uploading it once (the same key is reused for every server). */
    private fun sshKeyId(publicKey: String): Int {
        val key = publicKey.trim()
        val known = http.get("/ssh-keys").obj()?.get("ssh_keys").arr().mapNotNull { it.obj() }
            .firstOrNull { it.str("body")?.trim()?.split(' ')?.take(2) == key.split(' ').take(2) }
        known?.int("id")?.let { return it }
        val made = http.post("/ssh-keys", buildJsonObject { put("name", "Paramnezia"); put("body", key); put("is_default", false) })
        return made.obj()?.get("ssh_key").obj()?.int("id") ?: throw CloudException("bad_reply", "Timeweb не вернул id SSH-ключа")
    }

    private fun parse(o: JsonObject?): CloudServer {
        o ?: throw CloudException("bad_reply", "Timeweb не вернул данные сервера")
        val id = o.int("id")?.toString() ?: o.str("id") ?: throw CloudException("bad_reply", "Timeweb не вернул id сервера")
        val status = o.str("status").orEmpty()
        var ip: String? = null
        for (n in o["networks"].arr().mapNotNull { it.obj() }) if (n.str("type") == "public") {
            for (a in n["ips"].arr().mapNotNull { it.obj() }) if (a.str("type") == "ipv4") {
                if (ip == null || a.bool("is_main") == true) ip = a.str("ip")
            }
        }
        val state = when (status) {
            "on" -> CloudServer.State.READY
            "removed", "removing" -> CloudServer.State.GONE
            "blocked", "permanent_blocked", "no_paid" -> CloudServer.State.FAILED
            else -> CloudServer.State.PENDING
        }
        return CloudServer(id, state, ip, status)
    }

    companion object {
        const val BASE = "https://api.timeweb.cloud/api/v1"

        val INFO = ProviderInfo(
            id = "timeweb",
            name = "Timeweb Cloud",
            tokenUrl = "https://timeweb.cloud/my/api-keys",
            tokenHint = "Панель Timeweb Cloud → API и Terraform → «Создать токен»",
            currency = "RUB",
            blurb = "Российские карты, ₽ · Европа и Казахстан",
            color = 0xFF4F46E5,
        )

        /** Location → (name, country). From `twc` (ServiceRegion) and timeweb.cloud. */
        val LOCATIONS = mapOf(
            "nl-1" to ("Амстердам" to "NL"),
            "de-1" to ("Франкфурт" to "DE"),
            "pl-1" to ("Гданьск" to "PL"),
            "kz-1" to ("Алматы" to "KZ"),
            "ru-1" to ("Санкт-Петербург" to "RU"),
            "ru-2" to ("Новосибирск" to "RU"),
            "ru-3" to ("Москва" to "RU"),
        )
        private val ORDER = LOCATIONS.keys.toList()
    }
}
