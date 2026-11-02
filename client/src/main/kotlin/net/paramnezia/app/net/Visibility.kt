package net.paramnezia.app.net

import net.paramnezia.app.platform.Http
import net.paramnezia.core.net.GeoAnswer
import net.paramnezia.core.net.GeoIp
import net.paramnezia.core.net.GeoVerdict
import net.paramnezia.app.platform.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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

/** Where the address Russian sites see came from. */
enum class RuSource {
    /** Yandex's own address check, routed like any Russian site: exactly what Russian sites see. */
    YANDEX,
    /** Asked over the phone's own network, bypassing the VPN: the route Russian sites take with split tunneling on. */
    CARRIER,
    /** Russian sites go the same way as the rest (VPN off, or "Russian sites direct" off). */
    SAME,
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
    val russianSource: RuSource? = null,
    /** Why the Russian side is unknown, in a few words for the screen (null when it is known). */
    val russianError: String? = null,
    val foreignError: String? = null,
) {
    /** Split tunneling at work: the two kinds of sites see different addresses. */
    val split: Boolean get() = foreign != null && russian != null && foreign.ip != russian.ip
}

/**
 * Asks "what is my address" the way any app would: the requests follow the same routes as all
 * other traffic, so the foreign ones go through the tunnel and Yandex's goes direct. Each address
 * is then looked up in four independent geolocation databases (plus Cloudflare's own view for the
 * foreign side) and the country is decided by a vote. Lookups are cached per address, so a refresh
 * on the same network costs only a few small "what is my IP" requests.
 *
 * The Russian side never depends on one service: Yandex is asked first (two hosts, then once
 * more), and in parallel the phone's own network is asked directly, past the VPN, through several
 * unrelated services; that is the route Russian sites take when split tunneling is on. When
 * Russian sites are not split off at all, they see what foreign sites see.
 */
class VisibilityCheck(
    private val net: Http,
    /** How the physical network is called in error texts ("сеть телефона", "сеть компьютера"). */
    private val directLabel: String = "сеть телефона",
) {
    private val geoCache = ConcurrentHashMap<String, GeoVerdict>()
    /** Probes are plain blocking sockets: losers of a race finish here instead of holding the caller. */
    private val probes = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    suspend fun run(key: String, serverHost: String?, vpnUp: Boolean = true, ruDirect: Boolean = true): Visibility = withContext(Dispatchers.IO) {
        coroutineScope {
            val cf = async { get(GeoIp.CLOUDFLARE_TRACE)?.let(GeoIp::parseCloudflareTrace) }
            val plain = GeoIp.SELF_PLAIN.map { url -> async { GeoIp.parsePlainIp(get(url)) } }
            val ru = async { russian(vpnUp, ruDirect) }
            val serverIps = async { serverHost?.let(::resolve) }

            val cfAnswer = cf.await()
            val foreignIp = GeoIp.pickIp(listOfNotNull(cfAnswer?.ip) + plain.awaitAll().filterNotNull())
            val probe = ru.await()
            var russianIp = probe.ip
            var source = probe.source
            if (russianIp == null && (!vpnUp || !ruDirect) && foreignIp != null) { russianIp = foreignIp; source = RuSource.SAME }
            if (russianIp != null && russianIp == foreignIp && source == RuSource.YANDEX && (!vpnUp || !ruDirect)) source = RuSource.SAME
            Log.i(TAG, "foreign=${foreignIp ?: "-"} russian=${russianIp ?: "-"} via=$source vpn=$vpnUp ruDirect=$ruDirect" +
                (if (russianIp == null) " errors=${probe.errors}" else ""))

            val foreignGeo = async { foreignIp?.let { geo(it, cfAnswer?.takeIf { a -> a.ip.equals(it, true) }) } }
            val russianGeo = async { russianIp?.let { if (it == foreignIp) null else geo(it, null) } }
            val f = foreignIp?.let { SeenAddress(it, foreignGeo.await()) }
            val r = russianIp?.let { ip -> if (ip == foreignIp) f else SeenAddress(ip, russianGeo.await()) }
            val ips = serverIps.await()
            Visibility(
                f, r, System.currentTimeMillis(), key,
                foreignIsServer = if (ips.isNullOrEmpty() || f == null) null else f.ip in ips,
                russianSource = if (r != null) source else null,
                russianError = if (r == null) probe.errors else null,
                foreignError = if (f == null) "нет ответа от сервисов проверки" else null,
            )
        }
    }

    private class RuProbe(val ip: String?, val source: RuSource?, val errors: String?)

    private suspend fun russian(vpnUp: Boolean, ruDirect: Boolean): RuProbe {
        val errors = java.util.concurrent.ConcurrentLinkedQueue<String>()
        fun ask(url: String, direct: Boolean, label: String, timeoutMs: Int): String? {
            val res = net.fetch(url, direct, timeoutMs = timeoutMs)
            val ip = if (url == GeoIp.CLOUDFLARE_TRACE) res.body?.let(GeoIp::parseCloudflareTrace)?.ip?.takeIf(GeoIp::isPublicIp)
                else GeoIp.parsePlainIp(res.body)
            if (ip == null) {
                // Yandex answers "127.0.0.1" to requests it does not want to tell the address to
                // (that is what made the Russian side always fail): a real answer, just not an address.
                val why = res.error ?: if (res.body?.trim()?.trim('"')?.startsWith("127.") == true) "скрыл адрес" else "непонятный ответ"
                errors += "$label: $why"
                Log.i(TAG, "$label $url: $why${res.body?.let { " «" + it.take(80).replace('\n', ' ') + "»" } ?: ""}")
            }
            return ip
        }
        val yandex = GeoIp.RUSSIAN_SELF.map { url -> suspend { ask(url, false, "Яндекс", 8_000) } }
        // Past the VPN: only meaningful while the tunnel is up and Russian sites are split off.
        val carrier: Deferred<String?>? = if (vpnUp && ruDirect) {
            if (!net.hasDirect()) { errors += "$directLabel: не найдена"; null }
            else probes.async {
                firstOf((GeoIp.RUSSIAN_SELF + GeoIp.CARRIER_SELF + GeoIp.CLOUDFLARE_TRACE).map { url ->
                    suspend { ask(url, true, directLabel, 8_000) }
                })
            }
        } else if (!vpnUp) probes.async {
            // No tunnel: every site sees the same address, any service will do.
            firstOf((GeoIp.CARRIER_SELF + GeoIp.CLOUDFLARE_TRACE).map { url -> suspend { ask(url, false, "сервисы проверки", 8_000) } })
        } else null

        firstOf(yandex)?.let { return RuProbe(it, RuSource.YANDEX, null) }
        carrier?.await()?.let { return RuProbe(it, if (vpnUp) RuSource.CARRIER else RuSource.SAME, null) }
        // A second try at Yandex: the first one may have raced the tunnel's own start.
        delay(1_000)
        firstOf(GeoIp.RUSSIAN_SELF.map { url -> suspend { ask(url, false, "Яндекс (повтор)", 12_000) } })?.let { return RuProbe(it, RuSource.YANDEX, null) }
        return RuProbe(null, null, summarize(errors.toList()))
    }

    /** The first non-null answer of [tasks] run in parallel; the others are left to finish on their own. */
    private suspend fun <T : Any> firstOf(tasks: List<suspend () -> T?>): T? {
        if (tasks.isEmpty()) return null
        val results = Channel<T?>(tasks.size)
        tasks.forEach { t -> probes.launch { results.send(runCatching { t() }.getOrNull()) } }
        repeat(tasks.size) { results.receive()?.let { return it } }
        return null
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
        private const val TAG = "ParamneziaSeen"

        /** Distinct reasons, shortest first, e.g. "Яндекс: нет ответа; сеть телефона: HTTP 403". */
        internal fun summarize(errors: List<String>): String {
            if (errors.isEmpty()) return "нет ответа"
            val bySide = errors.groupBy({ it.substringBefore(':') }, { it.substringAfter(": ") })
            return bySide.entries.joinToString("; ") { (side, why) -> "$side: ${why.distinct().take(2).joinToString(", ")}" }
        }

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
