package net.paramnezia.core.net

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/** What one geolocation database says about an address. */
data class GeoAnswer(
    val source: String,
    val ip: String?,
    val countryCode: String?,
    val city: String? = null,
    val region: String? = null,
    val asn: String? = null,
    val org: String? = null,
)

/**
 * The agreed view of an address. Geolocation databases disagree surprisingly often (one of them puts
 * Yandex's DNS in Norway), so the country is a vote, and city / region are shown only when at least
 * two databases name the same one.
 */
data class GeoVerdict(
    val ip: String,
    val countryCode: String?,
    val city: String?,
    val region: String?,
    val asn: String?,
    val org: String?,
    /** Databases that named [countryCode], out of [answered]. */
    val agreeing: Int,
    val answered: Int,
    /** Other countries named, with their vote counts (empty when everyone agrees). */
    val others: Map<String, Int>,
) {
    val confident: Boolean get() = countryCode != null && (others.isEmpty() || agreeing > others.values.sum())
}

/**
 * Free HTTPS geolocation services, each run on its own database, plus the parsing of their answers.
 * All of them answer without a key; ipinfo.io and ipwho.is allow tens of thousands of requests per
 * month per address, far beyond a few checks a day.
 */
object GeoIp {
    data class Provider(val name: String, val url: (String) -> String, val parse: (String) -> GeoAnswer?)

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    val lookups: List<Provider> = listOf(
        Provider("ipinfo.io", { "https://ipinfo.io/$it/json" }, ::parseIpinfo),
        Provider("ipwho.is", { "https://ipwho.is/$it" }, ::parseIpwhois),
        Provider("ip.sb", { "https://api.ip.sb/geoip/$it" }, ::parseIpSb),
        Provider("geojs.io", { "https://get.geojs.io/v1/ip/geo/$it.json" }, ::parseGeojs),
    )

    /** "What is my address" services. Cloudflare's trace also tells the country its own sites see. */
    const val CLOUDFLARE_TRACE = "https://www.cloudflare.com/cdn-cgi/trace"
    val SELF_PLAIN = listOf("https://api.ipify.org", "https://ipinfo.io/ip")

    /** Yandex's own address check (the one yandex.ru/internet shows), IPv4 first. */
    val RUSSIAN_SELF = listOf("https://ipv4-internet.yandex.net/api/v0/ip", "https://yandex.ru/internet/api/v0/ip")

    /**
     * Plain "what is my IP" services for asking over the phone's own network, bypassing the VPN. That
     * is the route Russian sites take when split tunneling is on, so the answer is the address they see.
     * Different companies and CDNs, so one of them being down or blocked does not matter.
     */
    val CARRIER_SELF = listOf("https://api.ipify.org", "https://ipv4.icanhazip.com", "https://checkip.amazonaws.com", "https://ipinfo.io/ip")

    private fun obj(body: String): JsonObject? = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
    private fun JsonObject.s(k: String): String? = (this[k] as? JsonPrimitive)?.content?.trim()?.takeIf { it.isNotEmpty() && it != "null" }

    fun parseIpinfo(body: String): GeoAnswer? = obj(body)?.let { o ->
        if (o.s("bogon") == "true") return null
        val org = o.s("org")
        val asn = org?.substringBefore(' ')?.takeIf { it.startsWith("AS") }
        GeoAnswer("ipinfo.io", o.s("ip"), o.s("country"), o.s("city"), o.s("region"), asn, if (asn != null) org.substringAfter(' ') else org)
    }

    fun parseIpwhois(body: String): GeoAnswer? = obj(body)?.let { o ->
        if (o.s("success") == "false") return null
        val c = o["connection"] as? JsonObject
        GeoAnswer("ipwho.is", o.s("ip"), o.s("country_code"), o.s("city"), o.s("region"),
            c?.s("asn")?.let { "AS$it" }, c?.s("isp") ?: c?.s("org"))
    }

    fun parseIpSb(body: String): GeoAnswer? = obj(body)?.let { o ->
        GeoAnswer("ip.sb", o.s("ip"), o.s("country_code"), o.s("city"), o.s("region"), o.s("asn")?.let { "AS$it" }, o.s("asn_organization") ?: o.s("isp"))
    }

    fun parseGeojs(body: String): GeoAnswer? = obj(body)?.let { o ->
        GeoAnswer("geojs.io", o.s("ip"), o.s("country_code"), o.s("city"), o.s("region"), o.s("asn")?.let { "AS$it" }, o.s("organization_name"))
    }

    /** Cloudflare's `key=value` trace: the address and the country ("loc") its edge sees. */
    fun parseCloudflareTrace(body: String): GeoAnswer? {
        val kv = body.lineSequence().mapNotNull { l -> l.indexOf('=').takeIf { it > 0 }?.let { l.substring(0, it) to l.substring(it + 1).trim() } }.toMap()
        val ip = kv["ip"] ?: return null
        return GeoAnswer("cloudflare", ip, kv["loc"]?.takeIf { it.length == 2 && it != "XX" && it != "T1" })
    }

    /** Plain-text or JSON-quoted address from a "what is my IP" endpoint. */
    fun parsePlainIp(body: String?): String? = body?.trim()?.trim('"')?.takeIf { isPublicIp(it) }

    /**
     * The address most services agree on. Ties go to IPv4, which is what most sites log and what
     * the user compares with their server's address.
     */
    fun pickIp(seen: List<String>): String? = seen.filter(::isPublicIp)
        .groupingBy { it.lowercase() }.eachCount()
        .entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { if (':' in it.key) 1 else 0 })
        .firstOrNull()?.key

    fun consensus(ip: String, answers: List<GeoAnswer>): GeoVerdict {
        val valid = answers.filter { it.countryCode?.length == 2 && (it.ip == null || it.ip.equals(ip, ignoreCase = true)) }
        val votes = valid.groupingBy { it.countryCode!!.uppercase() }.eachCount()
        // Ties keep the order of [lookups] (ipinfo.io first): its data is the most carefully kept.
        val country = votes.entries.maxWithOrNull(compareBy<Map.Entry<String, Int>> { it.value }
            .thenByDescending { e -> valid.indexOfFirst { it.countryCode!!.uppercase() == e.key } })?.key
        val same = valid.filter { it.countryCode!!.uppercase() == country }
        fun agreed(pick: (GeoAnswer) -> String?): String? {
            val names = same.mapNotNull(pick)
            val counts = names.groupingBy { it.lowercase() }.eachCount()
            val best = counts.maxByOrNull { it.value } ?: return null
            // A lone database is trusted only when it is the only one that answered at all.
            if (best.value < 2 && valid.size > 1) return null
            return names.first { it.lowercase() == best.key }
        }
        val city = agreed { it.city }
        val withAsn = answers.filter { it.asn != null || it.org != null }
        return GeoVerdict(
            ip = ip,
            countryCode = country,
            city = city,
            region = if (city == null) agreed { it.region } else null,
            asn = withAsn.firstNotNullOfOrNull { it.asn },
            org = withAsn.firstNotNullOfOrNull { it.org },
            agreeing = votes[country] ?: 0,
            answered = valid.size,
            others = votes.filterKeys { it != country },
        )
    }

    /** True for an address a site on the internet could see (not private, loopback, CGNAT, …). */
    fun isPublicIp(s: String): Boolean {
        if (s.length !in 3..45) return false
        if (':' in s) {
            if (!s.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' || it == ':' || it == '.' }) return false
            val l = s.lowercase()
            return !(l == "::1" || l == "::" || l.startsWith("fe8") || l.startsWith("fe9") || l.startsWith("fea") || l.startsWith("feb") ||
                l.startsWith("fc") || l.startsWith("fd"))
        }
        val p = s.split('.').map { it.toIntOrNull() ?: return false }
        if (p.size != 4 || p.any { it !in 0..255 }) return false
        val (a, b) = p[0] to p[1]
        return !(a == 0 || a == 10 || a == 127 || a >= 224 ||
            (a == 100 && b in 64..127) || (a == 169 && b == 254) || (a == 172 && b in 16..31) ||
            (a == 192 && b == 168) || (a == 198 && b in 18..19))
    }
}
