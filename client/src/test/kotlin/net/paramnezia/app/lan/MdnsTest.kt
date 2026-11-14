package net.paramnezia.app.lan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class MdnsTest {
    /** A response the way Android's and Apple's responders write it: every name after the first compressed. */
    private fun compressedResponse(): ByteArray {
        val o = ByteArrayOutputStream()
        fun u16(v: Int) { o.write(v shr 8); o.write(v and 0xFF) }
        fun label(s: String) { val b = s.toByteArray(); o.write(b.size); o.write(b) }
        fun ptr(at: Int) { o.write(0xC0 or (at shr 8)); o.write(at and 0xFF) }
        u16(0); u16(0x8400); u16(0); u16(3); u16(0); u16(0)
        // PTR: _paramnezia._tcp.local → "Мама телефон" + pointer to the type.
        val typeAt = o.size()
        label("_paramnezia"); label("_tcp")
        val localAt = o.size()
        label("local"); o.write(0)
        u16(12); u16(1); o.write(byteArrayOf(0, 0, 0, 120))
        val inst = "Мама телефон".toByteArray()
        u16(1 + inst.size + 2)
        val instAt = o.size()
        o.write(inst.size); o.write(inst); ptr(typeAt)
        // SRV: name = pointer to the instance inside the PTR data; target = "phone" + pointer to "local".
        ptr(instAt)
        u16(33); u16(0x8001); o.write(byteArrayOf(0, 0, 0, 120))
        u16(6 + 1 + 5 + 2); u16(0); u16(0); u16(8080)
        val targetAt = o.size()
        label("phone"); ptr(localAt)
        // A: phone.local → 192.168.1.20 (name: pointer to the SRV target).
        ptr(targetAt)
        u16(1); u16(0x8001); o.write(byteArrayOf(0, 0, 0, 120)); u16(4); o.write(byteArrayOf(192.toByte(), 168.toByte(), 1, 20))
        return o.toByteArray()
    }

    @Test
    fun readsCompressedNames() {
        val b = compressedResponse()
        val m = Mdns.Message.parse(b, b.size)!!
        assertTrue(m.isResponse)
        assertEquals(3, m.records.size)
        assertEquals("_paramnezia._tcp.local", m.records[0].name)
        assertEquals("Мама телефон._paramnezia._tcp.local", m.records[1].name)
        assertEquals("phone.local", m.records[2].name)
    }

    @Test
    fun resolvesInstancesAndForgetsThemOnGoodbye() {
        val b = compressedResponse()
        val lan = LanNet.Lan("wlan0", InetAddress.getByName("192.168.1.5") as Inet4Address, 24)
        val cache = Mdns("_paramnezia._tcp") { listOf(lan) }.Cache(listOf(lan))
        val now = 1_000_000L
        cache.add(Mdns.Message.parse(b, b.size)!!.records, now)
        assertEquals(mapOf("Мама телефон._paramnezia._tcp.local" to ("192.168.1.20" to 8080)), cache.instances(now))
        // Expired after the TTL (120 s) without a refresh.
        assertTrue(cache.instances(now + 121_000).isEmpty())
        // Goodbye (TTL 0) removes it at once.
        cache.add(Mdns.Message.parse(b, b.size)!!.records, now)
        val goodbye = b.copyOf().also { g -> for (i in g.indices) if (i + 3 < g.size && g[i] == 0.toByte() && g[i + 1] == 0.toByte() && g[i + 2] == 0.toByte() && g[i + 3] == 120.toByte()) g[i + 3] = 0 }
        cache.add(Mdns.Message.parse(goodbye, goodbye.size)!!.records, now)
        assertTrue(cache.instances(now).isEmpty())
    }

    @Test
    fun garbageIsIgnored() {
        assertEquals(null, Mdns.Message.parse(ByteArray(5), 5))
        val loop = byteArrayOf(0, 0, 0x84.toByte(), 0, 0, 0, 0, 1, 0, 0, 0, 0, 0xC0.toByte(), 12)
        assertEquals(null, Mdns.Message.parse(loop, loop.size))
    }

    @Test
    fun advertiseBrowseAndGoodbyeOverMulticast() {
        // Loopback multicast: works on most machines; skipped where the sandbox forbids it.
        val lo = NetworkInterface.networkInterfaces().toList().firstOrNull { it.isLoopback && it.isUp && it.supportsMulticast() }
        assumeTrue("no multicast on loopback here", lo != null)
        val addr = lo!!.inetAddresses.toList().filterIsInstance<Inet4Address>().firstOrNull()
        assumeTrue(addr != null)
        val lans = { listOf(LanNet.Lan(lo.name, addr!!, 8)) }
        val mdns = Mdns("_pntest._tcp", lans)
        val found = CountDownLatch(1); val lost = CountDownLatch(1)
        var seen: Triple<String, String, Int>? = null
        val browser = mdns.browse({ n, h, p -> seen = Triple(n, h, p); found.countDown() }, { lost.countDown() })
        val ad = mdns.advertise("Папин ноутбук. Kitchen", 18080, mapOf("v" to "1"))
        val ok = found.await(8, TimeUnit.SECONDS)
        assumeTrue("multicast did not loop back here", ok)
        assertEquals(Triple("Папин ноутбук. Kitchen", addr!!.hostAddress, 18080), seen)
        ad.close()
        assertTrue("goodbye not seen", lost.await(5, TimeUnit.SECONDS))
        browser.close()
    }

    /** Names are compressed on the way out, and read back the same. */
    @Test
    fun writesCompressedNamesAndReadsThemBack() {
        val inst = "Мама телефон._paramnezia._tcp.local"
        val out = listOf(
            Mdns.Out("_paramnezia._tcp.local", 12, 120, target = inst),
            Mdns.Out(inst, 33, 120, cacheFlush = true, target = "phone.local", port = 8080),
            Mdns.Out("phone.local", 1, 120, cacheFlush = true, raw = byteArrayOf(192.toByte(), 168.toByte(), 1, 20)),
        )
        val b = Mdns.Message.write(response = true, answers = out)
        val plainSize = 12 + out.sumOf { 10 + it.name.length + 2 + (it.target?.length?.plus(2) ?: 4) + 20 }
        assertTrue("compressed: ${b.size} bytes", b.size < plainSize - 40)
        val m = Mdns.Message.parse(b, b.size)!!
        assertEquals(inst, m.records[0].target)
        assertEquals("phone.local", m.records[1].target)
        assertEquals(8080, m.records[1].port)
        assertTrue(m.records[1].cacheFlush)
        assertTrue(out.indices.all { out[it].sameAs(m.records[it]) })
        val lan = LanNet.Lan("wlan0", InetAddress.getByName("192.168.1.5") as Inet4Address, 24)
        val cache = Mdns("_paramnezia._tcp") { listOf(lan) }.Cache(listOf(lan))
        cache.add(m.records, 0)
        assertEquals(mapOf(inst to ("192.168.1.20" to 8080)), cache.instances(0))
        // Known answers: listed while more than half the TTL is left.
        assertEquals(listOf(inst to 120), cache.knownPointers(0))
        assertTrue(cache.knownPointers(61_000).isEmpty())
    }

    /** Two devices with the same name: the second becomes "Name (2)" (probing, RFC 6762 §8). */
    @Test
    fun aTakenNameIsRenamed() {
        val lo = NetworkInterface.networkInterfaces().toList().firstOrNull { it.isLoopback && it.isUp && it.supportsMulticast() }
        assumeTrue("no multicast on loopback here", lo != null)
        val addr = lo!!.inetAddresses.toList().filterIsInstance<Inet4Address>().firstOrNull()
        assumeTrue(addr != null)
        val lans = { listOf(LanNet.Lan(lo.name, addr!!, 8)) }
        val a = Mdns("_pntest2._tcp", lans).advertise("Телефон", 18081)
        Thread.sleep(1_500)
        // Another device (here: another port) wants the same name.
        val b = Mdns("_pntest2._tcp", lans).advertise("Телефон", 18082)
        val names = java.util.Collections.synchronizedSet(HashSet<String>())
        val browser = Mdns("_pntest2._tcp", lans).browse({ n, _, _ -> names += n }, {})
        val deadline = System.currentTimeMillis() + 8_000
        while (names.size < 2 && System.currentTimeMillis() < deadline) Thread.sleep(100)
        assumeTrue("multicast did not loop back here: $names", names.isNotEmpty())
        assertEquals(setOf("Телефон", "Телефон (2)"), names.toSet())
        browser.close(); a.close(); b.close()
    }
}
