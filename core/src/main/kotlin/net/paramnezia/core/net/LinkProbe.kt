package net.paramnezia.core.net

import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.SocketChannel

/**
 * One look at whether traffic gets through the tunnel and back: a tiny DNS query to each resolver
 * and a TCP handshake with a few well-known hosts on port 443, all at once, on one thread. The first
 * answer of any kind wins, so one resolver being slow or one port being filtered does not fail the
 * tunnel, and a healthy tunnel answers in one round trip. Sent the normal way, so they follow the
 * tunnel's routes: an answer proves the whole path (device → server → internet → back).
 *
 * Addresses must be literal IPs (no name lookups here: DNS may be what is broken).
 */
object LinkProbe {
    /** Hosts that answer on 443 everywhere and are never in the "Russian direct" lists. */
    val TCP_TARGETS = listOf("1.1.1.1", "8.8.8.8", "9.9.9.9")
    val DNS_TARGETS = listOf("1.1.1.1", "8.8.8.8", "9.9.9.9")

    /** DNS query: "." IN NS, recursion desired. Tiny, cacheable, answered by every resolver. */
    private val QUERY = byteArrayOf(0, 0, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 2, 0, 1)

    /** Round trip of the first answer in ms, or null when nothing came back within [timeoutMs]. */
    fun run(dns: List<String>, tcp: List<String> = TCP_TARGETS, timeoutMs: Long = 2_500): Long? = runAt(
        dns.mapNotNull(::literal).filter { it.address.size == 4 }.distinct().take(4).map { InetSocketAddress(it, 53) },
        tcp.mapNotNull(::literal).distinct().take(3).map { InetSocketAddress(it, 443) },
        timeoutMs,
    )

    /** [run] with explicit ports (tests use local stand-ins). */
    fun runAt(dns: List<InetSocketAddress>, tcp: List<InetSocketAddress>, timeoutMs: Long): Long? {
        if (dns.isEmpty() && tcp.isEmpty()) return null
        val selector = runCatching { Selector.open() }.getOrNull() ?: return null
        val channels = ArrayList<java.nio.channels.Channel>()
        try {
            val started = System.nanoTime()
            val id = (started and 0xffff).toInt()
            val query = QUERY.copyOf().also { it[0] = (id shr 8).toByte(); it[1] = id.toByte() }
            if (dns.isNotEmpty()) runCatching {
                val udp = DatagramChannel.open().also(channels::add)
                udp.configureBlocking(false)
                for (a in dns) runCatching { udp.send(ByteBuffer.wrap(query), a) }
                udp.register(selector, SelectionKey.OP_READ)
            }
            for (a in tcp) runCatching {
                val ch = SocketChannel.open().also(channels::add)
                ch.configureBlocking(false)
                if (ch.connect(a)) return elapsed(started)
                ch.register(selector, SelectionKey.OP_CONNECT)
            }
            val deadline = started + timeoutMs * 1_000_000
            val buf = ByteBuffer.allocate(512)
            while (selector.keys().isNotEmpty()) {
                val left = (deadline - System.nanoTime()) / 1_000_000
                if (left <= 0) return null
                if (selector.select(left) == 0) continue
                val it = selector.selectedKeys().iterator()
                while (it.hasNext()) {
                    val key = it.next(); it.remove()
                    when (val ch = key.channel()) {
                        is DatagramChannel -> {
                            buf.clear()
                            val from = runCatching { ch.receive(buf) }.getOrNull()
                            // A reply to our id with the "response" bit set.
                            if (from != null && buf.position() >= 12 && buf.get(0) == query[0] && buf.get(1) == query[1] &&
                                buf.get(2).toInt() and 0x80 != 0) return elapsed(started)
                        }
                        is SocketChannel -> {
                            if (runCatching { ch.finishConnect() }.getOrDefault(false)) return elapsed(started)
                            // Refused or unreachable: that target is out, the others still count.
                            if (!ch.isOpen || !ch.isConnectionPending) key.cancel()
                        }
                    }
                }
            }
            return null
        } catch (_: Exception) {
            return null
        } finally {
            channels.forEach { runCatching { it.close() } }
            runCatching { selector.close() }
        }
    }

    private fun elapsed(started: Long) = ((System.nanoTime() - started) / 1_000_000).coerceAtLeast(1)

    private fun literal(s: String): InetAddress? {
        val t = s.trim()
        if (t.isEmpty() || !(t[0].isDigit() || ':' in t)) return null
        return runCatching { InetAddress.getByName(t) }.getOrNull()
    }
}
