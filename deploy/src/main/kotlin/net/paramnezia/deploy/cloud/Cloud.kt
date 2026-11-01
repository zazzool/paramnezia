package net.paramnezia.deploy.cloud

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import java.net.HttpURLConnection
import java.net.URL

/**
 * A hosting provider that creates VPS through its HTTP API. Every call blocks and spends nothing
 * except [create], which starts billing; run them off the main thread and only on the admin's tap.
 */
interface CloudProvider {
    val info: ProviderInfo

    /** Checks the token; throws [CloudException] `auth_failed` when it is wrong or revoked. */
    fun verify()

    /** Locations that sell servers, foreign ones first (a VPN server inside Russia does not help). */
    fun regions(): List<CloudRegion>

    /** Plans in [region], cheapest first, each with its price. */
    fun plans(region: String): List<CloudPlan>

    /**
     * Creates a server with Ubuntu, [authorizedKey] for root and [userData] (cloud-init). Starts
     * billing. [idempotencyKey] makes a retried call (after a dropped connection) not buy twice.
     */
    fun create(name: String, region: String, plan: CloudPlan, authorizedKey: String, userData: String, idempotencyKey: String): CloudServer

    fun get(id: String): CloudServer

    /**
     * The server called [name], if it exists: after a create call whose answer was lost, this tells
     * whether it went through, so a retry does not buy a second server.
     */
    fun find(name: String): CloudServer?

    /** Destroys the server and stops billing. */
    fun delete(id: String)
}

/** What the catalog says about a provider (see `providers/README.md`). */
data class ProviderInfo(
    val id: String,
    val name: String,
    /** Where the admin creates an API token. */
    val tokenUrl: String,
    /** Short hint shown next to the token field. */
    val tokenHint: String,
    val currency: String,
)

data class CloudRegion(val id: String, val name: String, val countryCode: String) {
    val inRussia: Boolean get() = countryCode.equals("RU", ignoreCase = true)
}

data class CloudPlan(
    val id: String,
    val region: String,
    val title: String,
    val cpu: Int,
    val ramMb: Int,
    val diskGb: Int,
    /** Price per month in [currency]; null when the provider did not say. */
    val monthly: Double?,
    val currency: String,
    /** Provider-specific extras needed to create (Timeweb: bandwidth). */
    val extra: Map<String, String> = emptyMap(),
) {
    fun priceText(): String = monthly?.let { "${formatMoney(it)} ${symbol(currency)}/мес" } ?: "цена у хостера"

    companion object {
        fun formatMoney(v: Double) = if (v == Math.floor(v)) v.toLong().toString() else String.format(java.util.Locale.ROOT, "%.2f", v)
        fun symbol(c: String) = when (c.uppercase()) { "RUB" -> "₽"; "USD" -> "$"; "EUR" -> "€"; else -> c }
    }
}

data class CloudServer(val id: String, val state: State, val ipv4: String?, val detail: String = "") {
    enum class State { PENDING, READY, FAILED, GONE }
}

class CloudException(val code: String, message: String, cause: Throwable? = null) : Exception(message, cause)

/** Minimal JSON-over-HTTPS client. [transport] is replaced in tests. */
class CloudHttp(
    private val base: String,
    private val headers: Map<String, String>,
    private val transport: Transport = UrlTransport,
) {
    fun interface Transport {
        /** Returns status code and body. */
        fun send(method: String, url: String, headers: Map<String, String>, body: String?): Pair<Int, String>
    }

    fun get(path: String): JsonElement = call("GET", path, null)
    fun post(path: String, body: JsonElement, extraHeaders: Map<String, String> = emptyMap()): JsonElement = call("POST", path, body, extraHeaders)
    fun delete(path: String): JsonElement = call("DELETE", path, null)

    private fun call(method: String, path: String, body: JsonElement?, extraHeaders: Map<String, String> = emptyMap()): JsonElement {
        val h = LinkedHashMap(headers)
        h["Accept"] = "application/json"
        if (body != null) h["Content-Type"] = "application/json"
        h.putAll(extraHeaders)
        var attempt = 0
        while (true) {
            val (code, text) = try {
                transport.send(method, base + path, h, body?.toString())
            } catch (e: java.io.IOException) {
                throw CloudException("network", "Хостер не отвечает: ${e.message}", e)
            }
            // Rate limits: wait a little and try again (the create call is idempotent by key).
            if (code == 429 && attempt < 3) { attempt++; Thread.sleep(2_000L * attempt); continue }
            if (code in 200..299) return if (text.isBlank()) JsonNull else runCatching { Json.parseToJsonElement(text) }.getOrElse { JsonNull }
            throw CloudException(
                when (code) { 401, 403 -> "auth_failed"; 402 -> "no_money"; 404 -> "not_found"; else -> "http_$code" },
                "${errorMessage(text) ?: "HTTP $code"}",
            )
        }
    }

    object UrlTransport : Transport {
        override fun send(method: String, url: String, headers: Map<String, String>, body: String?): Pair<Int, String> {
            val c = URL(url).openConnection() as HttpURLConnection
            c.requestMethod = method
            c.connectTimeout = 20_000
            c.readTimeout = 60_000
            c.instanceFollowRedirects = false
            for ((k, v) in headers) c.setRequestProperty(k, v)
            if (body != null) { c.doOutput = true; c.outputStream.use { it.write(body.toByteArray()) } }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            c.disconnect()
            return code to text
        }
    }

    companion object {
        /** Pulls a human message out of the usual error shapes ({message}, {detail}, {error:{message}}). */
        fun errorMessage(text: String): String? = runCatching {
            val o = Json.parseToJsonElement(text) as? JsonObject ?: return null
            o.str("message") ?: o.str("detail") ?: o.str("title") ?: (o["error"] as? JsonObject)?.str("message") ?: o.str("error")
        }.getOrNull()?.take(300)
    }
}

internal fun JsonElement?.obj(): JsonObject? = this as? JsonObject
internal fun JsonElement?.arr(): List<JsonElement> = when (this) {
    is JsonArray -> this
    is JsonObject -> (this["data"] as? JsonArray) ?: (this["items"] as? JsonArray) ?: emptyList()
    else -> emptyList()
}
internal fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
internal fun JsonObject.num(key: String): Double? = (this[key] as? JsonPrimitive)?.let { it.doubleOrNull ?: it.contentOrNull?.toDoubleOrNull() }
internal fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.let { it.intOrNull ?: it.doubleOrNull?.toInt() }
internal fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull()
