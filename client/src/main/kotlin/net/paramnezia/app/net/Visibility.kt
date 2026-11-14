package net.paramnezia.app.net

import net.paramnezia.app.data.StoredGeo
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
import kotlinx.coroutines.withTimeoutOrNull
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
    /**
     * The VPN is on, yet foreign sites saw exactly the address the physical network has past the
     * VPN: foreign traffic went around the tunnel. Shown as a warning, never as "foreign sites see
     * Russia", and the tunnel is asked to check itself.
     */
    val foreignLeak: Boolean = false,
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
    /** Persisted geolocation verdicts (AppState.geo), so a restart does not ask the databases again. */
    private val loadGeo: () -> Map<String, StoredGeo> = { emptyMap() },
    private val saveGeo: (Map<String, StoredGeo>) -> Unit = {},
) {
    private val geoCache: ConcurrentHashMap<String, GeoVerdict> by lazy {
        val now = System.currentTimeMillis()
        ConcurrentHashMap(runCatching { loadGeo() }.getOrDefault(emptyMap())
            .filterValues { now - it.at < GEO_KEEP_MS }.mapValues { it.value.verdict })
    }
    /** Probes are plain blocking sockets: losers of a race finish here instead of holding the caller. */
    private val probes = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    suspend fun run(key: String, serverHost: String?, vpnUp: Boolean = true, ruDirect: Boolean = true): Visibility = withContext(Dispatchers.IO) {
        coroutineScope {
            val errors = java.util.concurrent.ConcurrentLinkedQueue<String>()
            // The address the physical network has, asked past the VPN: what Russian sites see with
            // split tunneling on, and the proof of a leak if foreign sites see the same one.
            val direct: Deferred<String?>? = if (vpnUp) {
                if (!net.hasDirect()) { errors += "$directLabel: не найдена"; null }
                else probes.async {
                    // Right after the tunnel comes up Android needs a moment before names resolve on the
                    // physical network for us ("имя не найдено" from every service at once): try again.
                    var ip: String? = null
                    for (pause in DIRECT_RETRY_MS) {
                        if (pause > 0) delay(pause)
                        // Past the VPN every service sees the same address; Yandex often hides it, so last.
                        ip = firstOf((GeoIp.CARRIER_SELF + GeoIp.CLOUDFLARE_TRACE + GeoIp.RUSSIAN_SELF).map { url ->
                            suspend { ask(url, true, directLabel, 8_000, errors) }
                        })
                        if (ip != null) break
                    }
                    ip
                }
            } else null
            val foreign = async { foreign(vpnUp) }
            val ru = async { russian(vpnUp, ruDirect, direct, errors) }
            val serverIps = async { serverHost?.let(::resolve) }

            var (cfAnswer, foreignIp) = foreign.await()
            val ips = serverIps.await()
            val directIp = if (vpnUp) withTimeoutOrNull(12_000) { direct?.await() } else null
            // Did foreign traffic go around the tunnel? The physical network's own address proves it,
            // and so does the physical network's provider (ASN): mobile carriers give every connection
            // another address from their pool, so equal addresses alone miss most leaks.
            suspend fun leaky(ip: String?, cf: GeoAnswer?): Boolean {
                if (!vpnUp || ip == null || directIp == null) return false
                if (!ips.isNullOrEmpty() && ip in ips) return false
                if (directIp.equals(ip, ignoreCase = true)) return true
                val a = geo(ip, cf?.takeIf { it.ip.equals(ip, true) })?.asn ?: return false
                return a == geo(directIp, null)?.asn
            }
            var leak = leaky(foreignIp, cfAnswer)
            // Possibly a request that raced the tunnel's start: look again (twice) before saying so.
            for (pause in LEAK_RETRY_MS) {
                if (!leak) break
                Log.w(TAG, "foreign sites saw the physical network ($foreignIp, direct $directIp); checking again")
                delay(pause)
                val again = foreign(vpnUp)
                if (again.second != null) { cfAnswer = again.first; foreignIp = again.second }
                leak = leaky(foreignIp, cfAnswer)
            }
            val probe = ru.await()
            var russianIp = probe.ip
            var source = probe.source
            if (russianIp == null && (!vpnUp || !ruDirect) && foreignIp != null && !leak) { russianIp = foreignIp; source = RuSource.SAME }
            if (russianIp != null && russianIp == foreignIp && source == RuSource.YANDEX && (!vpnUp || !ruDirect)) source = RuSource.SAME
            Log.i(TAG, "foreign=${foreignIp ?: "-"} russian=${russianIp ?: "-"} via=$source vpn=$vpnUp ruDirect=$ruDirect leak=$leak" +
                (if (russianIp == null) " errors=${probe.errors}" else ""))

            val fIp = foreignIp
            val foreignGeo = async { fIp?.let { geo(it, cfAnswer?.takeIf { a -> a.ip.equals(it, true) }) } }
            val russianGeo = async { russianIp?.let { if (it == fIp) null else geo(it, null) } }
            val f = fIp?.let { SeenAddress(it, foreignGeo.await()) }
            val r = russianIp?.let { ip -> if (ip == fIp) f else SeenAddress(ip, russianGeo.await()) }
            Visibility(
                f, r, System.currentTimeMillis(), key,
                foreignIsServer = if (ips.isNullOrEmpty() || f == null) null else f.ip in ips,
                russianSource = if (r != null) source else null,
                russianError = if (r == null) probe.errors else null,
                foreignError = if (f == null) "нет ответа от сервисов проверки" else null,
                foreignLeak = leak,
            )
        }
    }

    /**
     * Cloudflare's view plus plain "what is my IP" services. With the VPN on they are asked through
     * the tunnel only ([Http.getThroughTunnel]): a request that cannot get through the tunnel gives
     * no answer rather than the physical network's address under "foreign sites".
     */
    private suspend fun foreign(vpnUp: Boolean): Pair<GeoAnswer?, String?> = coroutineScope {
        fun fetch(url: String) = if (vpnUp) net.getThroughTunnel(url, 1024) else get(url)
        val cf = async { fetch(GeoIp.CLOUDFLARE_TRACE)?.let(GeoIp::parseCloudflareTrace) }
        val plain = GeoIp.SELF_PLAIN.map { url -> async { GeoIp.parsePlainIp(fetch(url)) } }
        val cfAnswer = cf.await()
        cfAnswer to GeoIp.pickIp(listOfNotNull(cfAnswer?.ip) + plain.awaitAll().filterNotNull())
    }

    private class RuProbe(val ip: String?, val source: RuSource?, val errors: String?)

    private fun ask(url: String, direct: Boolean, label: String, timeoutMs: Int, errors: MutableCollection<String>): String? {
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

    private suspend fun russian(vpnUp: Boolean, ruDirect: Boolean, direct: Deferred<String?>?, errors: MutableCollection<String>): RuProbe {
        val yandex = GeoIp.RUSSIAN_SELF.map { url -> suspend { ask(url, false, "Яндекс", 8_000, errors) } }
        // Past the VPN: what Russian sites see only while the tunnel is up and they are split off.
        val carrier: Deferred<String?>? = if (vpnUp && ruDirect) direct
        else if (!vpnUp) probes.async {
            // No tunnel: every site sees the same address, any service will do.
            firstOf((GeoIp.CARRIER_SELF + GeoIp.CLOUDFLARE_TRACE).map { url -> suspend { ask(url, false, "сервисы проверки", 8_000, errors) } })
        } else null

        firstOf(yandex)?.let { return RuProbe(it, RuSource.YANDEX, null) }
        carrier?.await()?.let { return RuProbe(it, if (vpnUp) RuSource.CARRIER else RuSource.SAME, null) }
        // A second try at Yandex: the first one may have raced the tunnel's own start.
        delay(1_000)
        firstOf(GeoIp.RUSSIAN_SELF.map { url -> suspend { ask(url, false, "Яндекс (повтор)", 12_000, errors) } })?.let { return RuProbe(it, RuSource.YANDEX, null) }
        return RuProbe(null, null, summarize(errors.toList()))
    }

    /**
     * The first non-null answer of [tasks], started one after another ("happy eyeballs"): the next one
     * goes when the previous has failed or stayed silent for [STAGGER_MS]. A healthy network answers the
     * first request, so a check costs one or two requests per side instead of all of them at once; a
     * broken one still tries everything within a second or two. Losers finish on their own.
     */
    private suspend fun <T : Any> firstOf(tasks: List<suspend () -> T?>): T? {
        if (tasks.isEmpty()) return null
        class Got(val value: T?)
        val results = Channel<Got>(tasks.size)
        var started = 0
        var finished = 0
        fun startNext() { val t = tasks[started++]; probes.launch { results.send(Got(runCatching { t() }.getOrNull())) } }
        startNext()
        while (true) {
            val got = if (started < tasks.size) withTimeoutOrNull(STAGGER_MS) { results.receive() } else results.receive()
            if (got == null) { startNext(); continue }
            finished++
            got.value?.let { return it }
            if (started < tasks.size) startNext() else if (finished == started) return null
        }
    }

    /**
     * Country of [ip] by vote. Two databases are asked first; when they (and Cloudflare's own view,
     * [extra]) agree, that settles it, and the other two are asked only on disagreement or silence.
     * The answer is the same as asking all four whenever the first two agree, at half the requests.
     */
    private suspend fun geo(ip: String, extra: GeoAnswer?): GeoVerdict? {
        geoCache[ip]?.let { return it }
        suspend fun ask(ps: List<GeoIp.Provider>) = coroutineScope {
            ps.map { p -> async { get(p.url(ip), 16_384)?.let { runCatching { p.parse(it) }.getOrNull() } } }.awaitAll()
        }.filterNotNull()
        var answers = ask(GeoIp.lookups.take(2)) + listOfNotNull(extra)
        val first = GeoIp.consensus(ip, answers)
        if (first.answered < 2 || first.others.isNotEmpty() || first.city == null && first.region == null) {
            answers = answers + ask(GeoIp.lookups.drop(2))
        }
        if (answers.isEmpty()) return null
        return GeoIp.consensus(ip, answers).also { if (it.answered >= 2) remember(ip, it) }
    }

    private fun remember(ip: String, v: GeoVerdict) {
        geoCache[ip] = v
        val now = System.currentTimeMillis()
        // Both sides are looked up in parallel: read-modify-write of the store, one at a time.
        synchronized(saveLock) {
            runCatching {
                val kept = loadGeo().filter { (k, e) -> k != ip && now - e.at < GEO_KEEP_MS }.entries
                .sortedByDescending { it.value.at }.take(GEO_KEEP - 1).associate { it.key to it.value }
                saveGeo(kept + (ip to StoredGeo(v, now)))
            }
        }
    }

    private val saveLock = Any()

    private fun get(url: String, max: Int = 1024): String? =
        net.get(url, max, fallbackDirect = false, fresh = true, timeoutMs = 8_000)

    private fun resolve(host: String): Set<String> =
        runCatching { java.net.InetAddress.getAllByName(host).mapNotNull { it.hostAddress?.substringBefore('%') }.toSet() }.getOrDefault(emptySet())

    companion object {
        private const val TAG = "ParamneziaSeen"
        private const val STAGGER_MS = 400L
        /** Second and third looks when foreign sites seem to see the physical network. */
        private val LEAK_RETRY_MS = listOf(2_500L, 5_000L)
        /** Addresses kept across restarts, and for how long (hosters rarely move an address). */
        private const val GEO_KEEP = 16
        private const val GEO_KEEP_MS = 7 * 24 * 3600_000L
        /** Pauses before each try of the physical network (the first goes at once). */
        private val DIRECT_RETRY_MS = listOf(0L, 1_500L, 3_000L)

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

fun countryName(code: String?): String? = code?.takeIf { it.length == 2 }?.let(CountryNames::ru)
