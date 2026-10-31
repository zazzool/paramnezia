package io.paramnezia.core

import io.paramnezia.core.net.IpPrefix
import io.paramnezia.core.net.PrefixSet
import io.paramnezia.core.routing.AppRules
import io.paramnezia.core.routing.BypassLists
import io.paramnezia.core.routing.DomainMatcher
import io.paramnezia.core.routing.RoutePlanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.math.BigInteger

class RoutingTest {
    private fun p(s: String) = IpPrefix.parse(s)

    @Test
    fun prefixParsingNormalizes() {
        assertEquals("10.0.0.0/8", p("10.1.2.3/8").toString())
        assertEquals("2001:db8::/32", p("2001:0db8:0:0::1/32").toString())
        assertEquals("::/0", IpPrefix.ANY_V6.toString())
        assertEquals("1.2.3.4/32", p("1.2.3.4").toString())
    }

    @Test
    fun complementOfSingleBlock() {
        val c = PrefixSet.of(false, listOf(p("10.0.0.0/8"))).complement().toPrefixes().map { it.toString() }
        assertEquals(listOf("0.0.0.0/5", "8.0.0.0/7", "11.0.0.0/8", "12.0.0.0/6", "16.0.0.0/4", "32.0.0.0/3", "64.0.0.0/2", "128.0.0.0/1"), c)
    }

    @Test
    fun setAlgebraIsExact() {
        val a = PrefixSet.of(false, listOf(p("10.0.0.0/24"), p("10.0.1.0/24"), p("10.0.3.0/24")))
        assertEquals(listOf("10.0.0.0/23", "10.0.3.0/24"), a.toPrefixes().map { it.toString() })
        val b = a.minus(PrefixSet.of(false, listOf(p("10.0.0.128/25"))))
        assertEquals(listOf("10.0.0.0/25", "10.0.1.0/24", "10.0.3.0/24"), b.toPrefixes().map { it.toString() })
        assertEquals(BigInteger.valueOf(128 + 256 + 256), b.size)
        assertTrue(b.contains(p("10.0.1.7/32")))
        assertFalse(b.contains(p("10.0.0.200/32")))
        assertEquals(BigInteger.ONE.shiftLeft(32), b.union(b.complement()).size)
    }

    @Test
    fun plannerKeepsTunnelSubnetAndDnsInside() {
        val keep = listOf(p("10.8.1.0/24"), p("172.29.172.254/32"))
        for (exclude in listOf(true, false)) {
            val plan = RoutePlanner.plan(listOf(p("5.136.0.0/13")), emptyList(), keep, supportsExcludeRoute = exclude)
            val tunnel = if (exclude) {
                PrefixSet.of(false, plan.include).minus(PrefixSet.of(false, plan.exclude))
            } else PrefixSet.of(false, plan.include)
            assertTrue(tunnel.contains(p("10.8.1.1/32")))
            assertTrue(tunnel.contains(p("172.29.172.254/32")))
            assertTrue(tunnel.contains(p("8.8.8.8/32")))
            assertFalse(tunnel.contains(p("5.140.1.1/32")))
            assertFalse(tunnel.contains(p("192.168.1.1/32")))
            assertFalse(tunnel.contains(p("10.0.0.1/32")))
        }
    }

    @Test
    fun bundledListFitsRouteBudget() {
        val dir = File("../app/src/main/assets/bypass")
        val v4 = BypassLists.parsePrefixes(File(dir, "ipv4.txt").readText())
        val v6 = BypassLists.parsePrefixes(File(dir, "ipv6.txt").readText())
        assertTrue(v4.size > 5000)
        val keep = listOf(p("10.8.1.0/24"), p("1.1.1.1/32"))
        for (exclude in listOf(true, false)) {
            val t0 = System.nanoTime()
            val plan = RoutePlanner.plan(v4, v6, keep, exclude)
            val ms = (System.nanoTime() - t0) / 1_000_000
            val v4Routes = plan.include.count { !it.isV6 } + plan.exclude.count { !it.isV6 }
            val v6Routes = plan.include.count { it.isV6 } + plan.exclude.count { it.isV6 }
            println("excludeRoute=$exclude: v4=$v4Routes v6=$v6Routes blocks=${plan.bypassBlocks} in ${ms}ms")
            assertTrue(v4Routes + v6Routes <= RoutePlanner.ROUTE_BUDGET)
            assertTrue(plan.bypassBlocks > 500)
        }
    }

    @Test
    fun domainsAndApps() {
        val d = DomainMatcher.parse("ru\nxn--p1ai\nyandex.net\nfull:exact.example.com\n")
        assertTrue(d.matches("gosuslugi.ru"))
        assertTrue(d.matches("a.b.yandex.net."))
        assertTrue(d.matches("xn--80aswg.xn--p1ai"))
        assertTrue(d.matches("exact.example.com"))
        assertFalse(d.matches("sub.exact.example.com"))
        assertFalse(d.matches("youtube.com"))

        val apps = AppRules.parse(File("../app/src/main/assets/bypass/apps.txt").readText())
        val installed = listOf("ru.sberbankmobile", "com.yandex.browser", "org.telegram.messenger", "com.google.android.youtube", "ru.mail.mailapp")
        assertEquals(
            listOf("com.yandex.browser", "org.telegram.messenger", "ru.sberbankmobile"),
            apps.select(installed, forceTunnel = setOf("ru.mail.mailapp"), forceDirect = setOf("org.telegram.messenger"), self = "io.paramnezia"),
        )
    }
}
