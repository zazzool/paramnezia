package net.paramnezia.core.net

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * One look at whether traffic gets through the tunnel and back: a tiny DNS query to each resolver
 * and a TCP handshake with a few well-known hosts on port 443, all at once. The first answer of any
 * kind wins, so one resolver being slow or one port being filtered does not fail the tunnel, and a
 * healthy tunnel answers in one round trip.
 *
 * The sockets come from [Sockets]: on Android they are bound to the VPN network itself. Right after
 * a tunnel is built, Android needs up to a second or two before it routes the app's traffic into it
 * by default; an unbound probe in that moment would go over the physical network and "prove" the
 * wrong path.
 *
 * Addresses must be literal IPs (no name lookups here: DNS may be what is broken).
 */
object LinkProbe {
    /** Hosts that answer on 53 and 443 everywhere and are never in the "Russian direct" lists. */
    val TCP_TARGETS = listOf("1.1.1.1", "8.8.8.8", "9.9.9.9")
    val DNS_TARGETS = listOf("1.1.1.1", "8.8.8.8", "9.9.9.9")

    /** Makes the probe's sockets; override to bind them to a network. */
    open class Sockets {
        open fun udp(): DatagramSocket = DatagramSocket()
        open fun tcp(): Socket = Socket()
    }

    /** DNS query: "." IN NS, recursion desired. Tiny, cacheable, answered by every resolver. */
    private val QUERY = byteArrayOf(0, 0, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 2, 0, 1)

    /** Round trip of the first answer in ms, or null when nothing came back within [timeoutMs]. */
    fun run(dns: List<String>, tcp: List<String> = TCP_TARGETS, timeoutMs: Long = 2_500, sockets: Sockets = Sockets()): Long? = runAt(
        dns.mapNotNull(::literal).filter { it.address.size == 4 }.distinct().take(4).map { InetSocketAddress(it, 53) },
        tcp.mapNotNull(::literal).distinct().take(3).map { InetSocketAddress(it, 443) },
        timeoutMs, sockets,
    )

    /** [run] with explicit ports (tests use local stand-ins). */
    fun runAt(dns: List<InetSocketAddress>, tcp: List<InetSocketAddress>, timeoutMs: Long, sockets: Sockets = Sockets()): Long? {
        if (dns.isEmpty() && tcp.isEmpty()) return null
        val started = System.nanoTime()
        val won = AtomicLong(-1)
        val done = CountDownLatch(1)
        val open = java.util.concurrent.ConcurrentLinkedQueue<java.io.Closeable>()
        fun win() { if (won.compareAndSet(-1, ((System.nanoTime() - started) / 1_000_000).coerceAtLeast(1))) done.countDown() }
        val threads = ArrayList<Thread>()
        if (dns.isNotEmpty()) threads += Thread {
            runCatching {
                val s = sockets.udp().also(open::add)
                s.soTimeout = timeoutMs.toInt().coerceAtLeast(1)
                val id = (started and 0xffff).toInt()
                val q = QUERY.copyOf().also { it[0] = (id shr 8).toByte(); it[1] = id.toByte() }
                for (a in dns) runCatching { s.send(DatagramPacket(q, q.size, a)) }
                val buf = ByteArray(512)
                while (won.get() < 0) {
                    val p = DatagramPacket(buf, buf.size)
                    s.receive(p)
                    // A reply to our id with the "response" bit set.
                    if (p.length >= 12 && buf[0] == q[0] && buf[1] == q[1] && buf[2].toInt() and 0x80 != 0) { win(); break }
                }
            }
        }
        for (a in tcp) threads += Thread {
            runCatching {
                val s = sockets.tcp().also(open::add)
                s.connect(a, timeoutMs.toInt().coerceAtLeast(1))
                win()
            }
        }
        threads.forEach { it.isDaemon = true; it.name = "pn-probe"; it.start() }
        done.await(timeoutMs, TimeUnit.MILLISECONDS)
        // Losers are cut short: closing their sockets ends their blocking calls at once.
        open.forEach { runCatching { it.close() } }
        return won.get().takeIf { it > 0 }
    }

    private fun literal(s: String): InetAddress? {
        val t = s.trim()
        if (t.isEmpty() || !(t[0].isDigit() || ':' in t)) return null
        return runCatching { InetAddress.getByName(t) }.getOrNull()
    }
}
