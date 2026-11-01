package net.paramnezia.core

import net.paramnezia.core.net.GeoAnswer
import net.paramnezia.core.net.GeoIp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Real answers of the geolocation services (saved in test resources), and the vote over them. */
class GeoIpTest {
    private fun res(name: String) = javaClass.getResource("/geo/$name")!!.readText()

    private fun answers(ip: String) = listOfNotNull(
        GeoIp.parseIpinfo(res("ipinfo-$ip.json")),
        GeoIp.parseIpwhois(res("ipwho-$ip.json")),
        GeoIp.parseIpSb(res("ipsb-$ip.json")),
        GeoIp.parseGeojs(res("geojs-$ip.json")),
    )

    @Test fun parsesEveryProvider() {
        val a = answers("213.87.141.36")
        assertEquals(4, a.size)
        a.forEach { assertEquals(it.source, "213.87.141.36", it.ip); assertEquals(it.source, "RU", it.countryCode); assertEquals("AS8359", it.asn) }
        assertEquals("MTS PJSC", a[0].org)
    }

    @Test fun oneWrongDatabaseIsOutvoted() {
        // ip.sb places Yandex's resolver in Norway; the other three say Russia.
        val a = answers("77.88.8.8")
        assertEquals("NO", a.first { it.source == "ip.sb" }.countryCode)
        val v = GeoIp.consensus("77.88.8.8", a)
        assertEquals("RU", v.countryCode)
        assertEquals(3, v.agreeing)
        assertEquals(4, v.answered)
        assertEquals(mapOf("NO" to 1), v.others)
        assertTrue(v.confident)
        assertEquals("Moscow", v.city)
    }

    @Test fun cityOnlyWhenTwoDatabasesAgree() {
        val v = GeoIp.consensus("8.8.8.8", answers("8.8.8.8"))
        assertEquals("US", v.countryCode)
        assertEquals("Mountain View", v.city) // ipinfo + ip.sb; ipwho.is says San Jose
        val split = GeoIp.consensus("1.2.3.4", listOf(
            GeoAnswer("a", "1.2.3.4", "NL", "Amsterdam", "North Holland"),
            GeoAnswer("b", "1.2.3.4", "NL", "Haarlem", "North Holland"),
        ))
        assertNull(split.city)
        assertEquals("North Holland", split.region)
    }

    @Test fun tieGoesToTheFirstProvider() {
        val v = GeoIp.consensus("1.2.3.4", listOf(GeoAnswer("ipinfo.io", "1.2.3.4", "DE"), GeoAnswer("x", "1.2.3.4", "NL")))
        assertEquals("DE", v.countryCode)
        assertFalse(v.confident)
    }

    @Test fun answersForAnotherAddressAreIgnored() {
        val v = GeoIp.consensus("1.2.3.4", listOf(GeoAnswer("a", "5.6.7.8", "US"), GeoAnswer("b", "1.2.3.4", "FI")))
        assertEquals("FI", v.countryCode)
        assertEquals(1, v.answered)
    }

    @Test fun cloudflareTrace() {
        val a = GeoIp.parseCloudflareTrace("fl=1\nh=www.cloudflare.com\nip=203.0.113.9\nts=1\nloc=NL\nwarp=off\n")!!
        assertEquals("203.0.113.9", a.ip)
        assertEquals("NL", a.countryCode)
        assertNull(GeoIp.parseCloudflareTrace("ip=203.0.113.9\nloc=XX\n")!!.countryCode)
    }

    @Test fun publicAddressesOnly() {
        for (ip in listOf("127.0.0.1", "10.1.2.3", "192.168.0.1", "172.20.0.1", "100.64.1.1", "198.18.0.53", "169.254.1.1", "::1", "fd00::1", "fe80::1", "garbage"))
            assertFalse(ip, GeoIp.isPublicIp(ip))
        for (ip in listOf("77.88.8.8", "8.8.8.8", "2a02:6b8::feed:0ff"))
            assertTrue(ip, GeoIp.isPublicIp(ip))
        assertNull(GeoIp.parsePlainIp("\"127.0.0.1\"")) // what a proxy in front of Yandex may answer
        assertEquals("5.6.7.8", GeoIp.parsePlainIp("\"5.6.7.8\"\n"))
    }

    @Test fun pickIpPrefersTheMajorityThenIpv4() {
        assertEquals("5.6.7.8", GeoIp.pickIp(listOf("5.6.7.8", "2001:db8::1", "5.6.7.8")))
        assertEquals("5.6.7.8", GeoIp.pickIp(listOf("2a00::1", "5.6.7.8")))
        assertNull(GeoIp.pickIp(listOf("127.0.0.1")))
    }
}
