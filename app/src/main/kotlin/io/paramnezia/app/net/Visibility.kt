package io.paramnezia.app.net

import io.paramnezia.app.tunnel.Net
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The address a site sees, and what is known about it. */
data class SeenAddress(val ip: String, val country: String?, val countryCode: String?, val city: String?, val org: String?)

/** What foreign and Russian sites see right now. Either side is null when its check failed. */
data class Visibility(val foreign: SeenAddress?, val russian: SeenAddress?, val checkedAt: Long) {
    /** Split tunneling at work: the two kinds of sites see different addresses. */
    val split: Boolean get() = foreign != null && russian != null && foreign.ip != russian.ip
}

/**
 * Asks one foreign and one Russian "what is my IP" service, the way any app would: the request
 * follows the same routes as everything else, so the foreign one goes through the tunnel and the
 * Russian one (a Yandex host) goes direct. Three small HTTPS requests, only when the user looks.
 */
class VisibilityCheck(private val net: Net) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun run(): Visibility = withContext(Dispatchers.IO) {
        coroutineScope {
            val foreign = async { foreign() }
            val russianIp = async { russianIp() }
            val ru = russianIp.await()
            val f = foreign.await()
            // Details for the Russian-side address come from the same foreign service (by IP).
            val ruDetails = ru?.let { ip -> if (ip == f?.ip) f else lookup(ip) ?: SeenAddress(ip, null, null, null, null) }
            Visibility(f, ruDetails, System.currentTimeMillis())
        }
    }

    private fun foreign(): SeenAddress? =
        parse(net.get(FOREIGN, 16_384, fallbackDirect = false))
            ?: net.get(FOREIGN_PLAIN, 256, fallbackDirect = false)?.trim()?.takeIf(::looksLikeIp)?.let { SeenAddress(it, null, null, null, null) }

    private fun russianIp(): String? = RUSSIAN.firstNotNullOfOrNull { url ->
        net.get(url, 256, fallbackDirect = false)?.trim()?.trim('"')?.takeIf(::looksLikeIp)
    }

    private fun lookup(ip: String): SeenAddress? = parse(net.get("$FOREIGN?ip=$ip", 16_384, fallbackDirect = false))

    private fun parse(body: String?): SeenAddress? = runCatching {
        val o: JsonObject = json.parseToJsonElement(body ?: return null).jsonObject
        fun s(k: String) = o[k]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
        SeenAddress(s("ip") ?: return null, s("country"), s("country_iso"), s("city"), s("asn_org"))
    }.getOrNull()

    private fun looksLikeIp(s: String) = s.length in 3..45 && s.all { it.isDigit() || it == '.' || it == ':' || it in 'a'..'f' || it in 'A'..'F' }

    companion object {
        const val FOREIGN = "https://ifconfig.co/json"
        const val FOREIGN_PLAIN = "https://api.ipify.org"
        /** Yandex's own address check (yandex.ru/internet). */
        val RUSSIAN = listOf("https://ipv4-internet.yandex.net/api/v0/ip", "https://yandex.ru/internet/api/v0/ip")

        /** 🇷🇺 from "RU". */
        fun flag(code: String?): String = code?.takeIf { it.length == 2 }?.uppercase()?.let { c ->
            String(Character.toChars(0x1F1E6 + (c[0] - 'A'))) + String(Character.toChars(0x1F1E6 + (c[1] - 'A')))
        } ?: "🌐"
    }
}
