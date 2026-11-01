package net.paramnezia.app.net

import net.paramnezia.app.tunnel.Net
import net.paramnezia.core.net.GeoAnswer
import net.paramnezia.core.net.GeoIp
import net.paramnezia.core.net.GeoVerdict
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** The address a site sees, and what the geolocation databases agree on about it. */
data class SeenAddress(val ip: String, val geo: GeoVerdict?) {
    val countryCode: String? get() = geo?.countryCode
    /** Country name in Russian, from the ISO code (the services spell names their own way). */
    val country: String? get() = countryName(countryCode)
    val place: String? get() = geo?.city ?: geo?.region
    val org: String? get() = geo?.org
}

/**
 * What foreign and Russian sites see right now. Either side is null when its check failed.
 * [key] says which tunnel state the check was made in, so the screen never shows the result of
 * an old state as current.
 */
data class Visibility(
    val foreign: SeenAddress?,
    val russian: SeenAddress?,
    val checkedAt: Long,
    val key: String,
    /** The foreign address is the active server's own address (null: unknown, e.g. VPN off). */
    val foreignIsServer: Boolean? = null,
) {
    /** Split tunneling at work: the two kinds of sites see different addresses. */
    val split: Boolean get() = foreign != null && russian != null && foreign.ip != russian.ip
}

/**
 * Asks "what is my address" the way any app would: the requests follow the same routes as all
 * other traffic, so the foreign ones go through the tunnel and Yandex's goes direct. Each address
 * is then looked up in four independent geolocation databases (plus Cloudflare's own view for the
 * foreign side) and the country is decided by a vote. Lookups are cached per address, so a refresh
 * on the same network costs only the three small "what is my IP" requests.
 */
class VisibilityCheck(private val net: Net) {
    private val geoCache = ConcurrentHashMap<String, GeoVerdict>()

    suspend fun run(key: String, serverHost: String?): Visibility = withContext(Dispatchers.IO) {
        coroutineScope {
            val cf = async { get(GeoIp.CLOUDFLARE_TRACE)?.let(GeoIp::parseCloudflareTrace) }
            val plain = GeoIp.SELF_PLAIN.map { url -> async { GeoIp.parsePlainIp(get(url)) } }
            val ruIp = async { GeoIp.RUSSIAN_SELF.firstNotNullOfOrNull { GeoIp.parsePlainIp(get(it)) } }
            val serverIps = async { serverHost?.let(::resolve) }

            val cfAnswer = cf.await()
            val foreignIp = GeoIp.pickIp(listOfNotNull(cfAnswer?.ip) + plain.awaitAll().filterNotNull())
            val russianIp = ruIp.await()

            val foreignGeo = async { foreignIp?.let { geo(it, cfAnswer?.takeIf { a -> a.ip.equals(it, true) }) } }
            val russianGeo = async { russianIp?.let { if (it == foreignIp) null else geo(it, null) } }
            val f = foreignIp?.let { SeenAddress(it, foreignGeo.await()) }
            val r = russianIp?.let { ip -> if (ip == foreignIp) f else SeenAddress(ip, russianGeo.await()) }
            val ips = serverIps.await()
            Visibility(f, r, System.currentTimeMillis(), key, foreignIsServer = if (ips.isNullOrEmpty() || f == null) null else f.ip in ips)
        }
    }

    private suspend fun geo(ip: String, extra: GeoAnswer?): GeoVerdict? {
        geoCache[ip]?.let { return it }
        val answers = coroutineScope {
            GeoIp.lookups.map { p -> async { get(p.url(ip), 16_384)?.let { runCatching { p.parse(it) }.getOrNull() } } }.awaitAll()
        }.filterNotNull() + listOfNotNull(extra)
        if (answers.isEmpty()) return null
        return GeoIp.consensus(ip, answers).also { if (it.answered >= 2) geoCache[ip] = it }
    }

    private fun get(url: String, max: Int = 1024): String? =
        net.get(url, max, fallbackDirect = false, fresh = true, timeoutMs = 8_000)

    private fun resolve(host: String): Set<String> =
        runCatching { java.net.InetAddress.getAllByName(host).mapNotNull { it.hostAddress?.substringBefore('%') }.toSet() }.getOrDefault(emptySet())

    companion object {
        /** 🇷🇺 from "RU". */
        fun flag(code: String?): String = code?.takeIf { it.length == 2 && it.all { c -> c in 'A'..'Z' || c in 'a'..'z' } }?.uppercase()?.let { c ->
            String(Character.toChars(0x1F1E6 + (c[0] - 'A'))) + String(Character.toChars(0x1F1E6 + (c[1] - 'A')))
        } ?: "🌐"
    }
}

private val ru = Locale("ru")

fun countryName(code: String?): String? = code?.takeIf { it.length == 2 }?.let {
    Locale("", it.uppercase()).getDisplayCountry(ru).takeIf { n -> n.isNotBlank() && !n.equals(it, true) }
}
