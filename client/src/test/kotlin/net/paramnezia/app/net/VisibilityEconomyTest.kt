package net.paramnezia.app.net

import kotlinx.coroutines.runBlocking
import net.paramnezia.app.data.StoredGeo
import net.paramnezia.app.platform.Http
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue

class VisibilityEconomyTest {
    private val server = "203.0.113.7"
    private val home = "198.51.100.20"

    /** A healthy network: every service answers at once; Yandex hides the address (as it often does). */
    private inner class FakeNet : Http {
        val asked = ConcurrentLinkedQueue<String>()
        override fun hasDirect() = true
        override fun getViaTor(url: String, maxBytes: Int): String? = null
        override fun get(url: String, maxBytes: Int, fallbackDirect: Boolean, fresh: Boolean, timeoutMs: Int): String? {
            asked += "via-tunnel $url"
            val ip = Regex("""\d+\.\d+\.\d+\.\d+""").find(url.substringAfter("://").substringAfter('/'))?.value
            val country = if (ip == home) "RU" else "NL"
            return when {
                url.contains("cdn-cgi/trace") -> "ip=$server\nloc=NL\n"
                url.contains("ipinfo.io/") && ip != null -> """{"ip":"$ip","country":"$country","city":"Amsterdam","org":"AS1 Host"}"""
                url.contains("ipwho.is/") -> """{"success":true,"ip":"$ip","country_code":"$country","city":"Amsterdam"}"""
                url.contains("ip.sb") -> """{"ip":"$ip","country_code":"$country","city":"Amsterdam"}"""
                url.contains("geojs") -> """{"ip":"$ip","country_code":"$country","city":"Amsterdam"}"""
                else -> server
            }
        }
        override fun fetch(url: String, direct: Boolean, maxBytes: Int, timeoutMs: Int, userAgent: String): Http.Fetched {
            asked += (if (direct) "direct " else "routed ") + url
            return if (url.contains("yandex")) Http.Fetched("\"127.0.0.1\"", null) else Http.Fetched(if (direct) home else server, null)
        }
    }

    @Test
    fun healthyNetworkCostsFewRequestsAndARestartCostsNoGeoLookups() = runBlocking {
        val store = HashMap<String, StoredGeo>()
        val net = FakeNet()
        val check = VisibilityCheck(net, loadGeo = { store }, saveGeo = { store.clear(); store.putAll(it) })
        val v = check.run("k", serverHost = null, vpnUp = true, ruDirect = true)
        assertEquals(server, v.foreign?.ip)
        assertEquals(home, v.russian?.ip)
        assertEquals("NL", v.foreign?.countryCode)
        assertFalse(v.foreignLeak)
        val direct = net.asked.count { it.startsWith("direct ") }
        // Staggered: the first direct service answers, the other six are never asked.
        assertTrue("direct requests: ${net.asked}", direct <= 2)
        // Two geolocation databases agree for each address: the other two are not asked.
        val geo = net.asked.count { it.contains("ipwho.is/") || it.contains("ipinfo.io/2") || it.contains("ipinfo.io/1") || it.contains("ip.sb") || it.contains("geojs") }
        assertEquals("geo requests: ${net.asked}", 4, geo)
        assertEquals(setOf(server, home), store.keys)

        // A restart: the verdicts come from the store, no database is asked.
        val net2 = FakeNet()
        VisibilityCheck(net2, loadGeo = { store }, saveGeo = {}).run("k", serverHost = null, vpnUp = true, ruDirect = true)
        assertEquals(0, net2.asked.count { it.contains("ipwho.is/") || it.contains("ip.sb") || it.contains("geojs") })
    }
}
