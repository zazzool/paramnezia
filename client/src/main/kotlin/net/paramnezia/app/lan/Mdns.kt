package net.paramnezia.app.lan

import net.paramnezia.app.platform.Log
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Multicast DNS service discovery (RFC 6762 / 6763), just what sharing in the local network needs:
 * one service type, IPv4, one socket for all interfaces. Replaces JmDNS, which ran one instance
 * with its own threads and timers per network interface for the same job.
 *
 *  - [advertise]: announces the instance (PTR, SRV, TXT, A), twice a second apart as the RFC asks,
 *    answers queries for the type and its names, and says goodbye (TTL 0) when closed;
 *  - [browse]: asks for the type at growing intervals (1, 2, 4 … 60 s), resolves instances from
 *    whatever records arrive (answers or additionals, any order, compressed names), prefers the
 *    address in one of our own subnets, and reports an instance lost on goodbye or when its records
 *    expire.
 */
class Mdns(private val serviceType: String, private val interfaces: () -> List<LanNet.Lan> = LanNet::interfaces) {
    private val type = "$serviceType.local"
    private val group: InetAddress = InetAddress.getByName(GROUP)

    // --- advertising ------------------------------------------------------------------------------

    fun advertise(instance: String, port: Int, txt: Map<String, String> = emptyMap()): AutoCloseable {
        val lans = interfaces()
        if (lans.isEmpty()) return AutoCloseable {}
        val name = "${clean(instance)}.$type"
        val host = "paramnezia-${lans.first().host.replace('.', '-')}.local"
        val socket = open(lans) ?: return AutoCloseable {}
        val timer = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "mdns-announce").apply { isDaemon = true } }

        fun records(ttl: Int) = listOf(
            Record(type, TYPE_PTR, ttl, name(name)),
            Record(name, TYPE_SRV, ttl, srv(port, host), cacheFlush = true),
            Record(name, TYPE_TXT, ttl, txt(txt), cacheFlush = true),
        ) + lans.map { Record(host, TYPE_A, ttl, it.address.address, cacheFlush = true) }

        fun announce(ttl: Int) = send(socket, lans, Message.response(records(ttl)))
        timer.execute { announce(TTL) }
        timer.schedule({ announce(TTL) }, 1, TimeUnit.SECONDS)

        val reader = Thread({
            val buf = ByteArray(9000)
            while (!socket.isClosed) {
                val p = DatagramPacket(buf, buf.size)
                runCatching { socket.receive(p) }.onFailure { if (socket.isClosed) return@Thread }
                val q = Message.parse(buf, p.length) ?: continue
                if (q.isResponse) continue
                val all = records(TTL)
                val answers = all.filter { r -> q.questions.any { it.matches(r) } }
                if (answers.isEmpty()) continue
                val extra = if (answers.any { it.type == TYPE_PTR }) all - answers.toSet() else emptyList()
                val reply = Message.response(answers, extra)
                // A query from a port other than 5353 is a one-shot resolver: answer it directly.
                if (p.port != PORT) runCatching { socket.send(DatagramPacket(reply, reply.size, p.address, p.port)) }
                else send(socket, lans, reply)
            }
        }, "mdns-responder").apply { isDaemon = true; start() }

        return AutoCloseable {
            timer.shutdownNow()
            // Goodbye: the same records with TTL 0, so others forget the share at once.
            runCatching { announce(0) }
            socket.close()
            reader.interrupt()
        }
    }

    // --- browsing ---------------------------------------------------------------------------------

    fun browse(onFound: (String, String, Int) -> Unit, onLost: (String) -> Unit): AutoCloseable {
        val lans = interfaces()
        val socket = open(lans) ?: return AutoCloseable {}
        val cache = Cache(lans)
        val reported = ConcurrentHashMap<String, Pair<String, Int>>()
        val timer = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "mdns-browse").apply { isDaemon = true } }
        var next: ScheduledFuture<*>? = null

        fun update() {
            val now = System.currentTimeMillis()
            val live = cache.instances(now)
            for ((inst, at) in live) if (reported[inst] != at) { reported[inst] = at; onFound(instanceLabel(inst), at.first, at.second) }
            for (inst in reported.keys - live.keys) { reported.remove(inst); onLost(instanceLabel(inst)) }
        }

        fun query(interval: Long) {
            send(socket, lans, Message.query(type, TYPE_PTR))
            update()
            next = timer.schedule({ query(minOf(interval * 2, 60_000)) }, interval, TimeUnit.MILLISECONDS)
        }
        timer.execute { query(1_000) }

        val reader = Thread({
            val buf = ByteArray(9000)
            while (!socket.isClosed) {
                val p = DatagramPacket(buf, buf.size)
                runCatching { socket.receive(p) }.onFailure { if (socket.isClosed) return@Thread }
                val m = Message.parse(buf, p.length) ?: continue
                if (!m.isResponse) continue
                cache.add(m.records, System.currentTimeMillis())
                timer.execute { update() }
            }
        }, "mdns-browser").apply { isDaemon = true; start() }

        return AutoCloseable {
            next?.cancel(false)
            timer.shutdownNow()
            socket.close()
            reader.interrupt()
        }
    }

    private fun instanceLabel(fqdn: String): String =
        unescape(if (fqdn.endsWith(".$type", ignoreCase = true)) fqdn.dropLast(type.length + 1) else fqdn)

    /** What the browser knows: PTR (type → instance), SRV (instance → host, port), A (host → address). */
    internal inner class Cache(private val lans: List<LanNet.Lan>) {
        /** Instance (lowercase, DNS names are case-insensitive) → its name as written, and until when it lives. */
        private val ptr = ConcurrentHashMap<String, Pair<String, Long>>()
        private val srv = ConcurrentHashMap<String, Triple<String, Int, Long>>()
        private val a = ConcurrentHashMap<String, MutableMap<String, Long>>()

        fun add(records: List<Record>, now: Long) {
            for (r in records) {
                val until = now + r.ttl * 1000L
                when (r.type) {
                    TYPE_PTR -> if (r.name.equals(type, true)) {
                        val inst = readName(r.message, r.offset).first
                        if (r.ttl == 0) ptr.remove(inst.lowercase()) else ptr[inst.lowercase()] = inst to until
                    }
                    TYPE_SRV -> if (r.data.size >= 7) {
                        val port = ((r.data[4].toInt() and 0xFF) shl 8) or (r.data[5].toInt() and 0xFF)
                        val target = readName(r.message, r.offset + 6).first.lowercase()
                        if (r.ttl == 0) srv.remove(r.name.lowercase()) else srv[r.name.lowercase()] = Triple(target, port, until)
                    }
                    TYPE_A -> if (r.data.size == 4) {
                        val ip = InetAddress.getByAddress(r.data).hostAddress
                        val m = a.getOrPut(r.name.lowercase()) { ConcurrentHashMap() }
                        if (r.ttl == 0) m.remove(ip) else m[ip] = until
                    }
                }
            }
        }

        /** Instances with a live PTR, SRV and an address, as instance (as written) → (address, port). */
        fun instances(now: Long): Map<String, Pair<String, Int>> {
            val out = HashMap<String, Pair<String, Int>>()
            for ((inst, entry) in ptr) {
                val (written, until) = entry
                if (until < now) { ptr.remove(inst); continue }
                val (target, port, sUntil) = srv[inst] ?: continue
                if (sUntil < now) continue
                val addrs = a[target]?.filterValues { it >= now }?.keys ?: continue
                // The address in one of our own subnets: the others belong to the sharer's other networks.
                val pick = addrs.firstOrNull { ip -> lans.any { it.contains(InetAddress.getByName(ip)) } } ?: addrs.firstOrNull() ?: continue
                out[written] = pick to port
            }
            return out
        }
    }

    // --- socket -----------------------------------------------------------------------------------

    private fun open(lans: List<LanNet.Lan>): MulticastSocket? = runCatching {
        val s = MulticastSocket(null)
        s.reuseAddress = true // Windows' own mDNS responder holds 5353 too
        s.bind(InetSocketAddress(PORT))
        s.timeToLive = 255
        var joined = 0
        for (lan in lans) {
            val nif = NetworkInterface.getByInetAddress(lan.address) ?: continue
            runCatching { s.joinGroup(InetSocketAddress(group, PORT), nif); joined++ }.onFailure { Log.w(TAG, "join on ${lan.host}: ${it.message}") }
        }
        if (joined == 0) { s.close(); null } else s
    }.onFailure { Log.w(TAG, "mDNS socket: ${it.message}") }.getOrNull()

    private fun send(socket: MulticastSocket, lans: List<LanNet.Lan>, msg: ByteArray) {
        for (lan in lans) runCatching {
            NetworkInterface.getByInetAddress(lan.address)?.let { socket.networkInterface = it }
            socket.send(DatagramPacket(msg, msg.size, group, PORT))
        }
    }

    // --- wire format ------------------------------------------------------------------------------

    internal class Question(val name: String, val type: Int) {
        fun matches(r: Record) = name.equals(r.name, true) && (type == r.type || type == TYPE_ANY)
    }

    /**
     * A resource record; [data] is the raw RDATA. Names inside it (PTR, SRV) may be compressed, pointing
     * anywhere in the packet, so a received record keeps the whole [message] and the RDATA [offset].
     */
    internal class Record(val name: String, val type: Int, val ttl: Int, val data: ByteArray, val cacheFlush: Boolean = false,
                          val message: ByteArray = data, val offset: Int = 0)

    internal class Message(val isResponse: Boolean, val questions: List<Question>, val records: List<Record>) {
        companion object {
            fun query(name: String, type: Int): ByteArray {
                val o = ByteArrayOutputStream()
                o.write(ByteArray(4)); u16(o, 1); u16(o, 0); u16(o, 0); u16(o, 0)
                writeName(o, name); u16(o, type); u16(o, CLASS_IN)
                return o.toByteArray()
            }

            fun response(answers: List<Record>, additional: List<Record> = emptyList()): ByteArray {
                val o = ByteArrayOutputStream()
                u16(o, 0); u16(o, 0x8400) // response, authoritative
                u16(o, 0); u16(o, answers.size); u16(o, 0); u16(o, additional.size)
                for (r in answers + additional) {
                    writeName(o, r.name); u16(o, r.type)
                    u16(o, CLASS_IN or if (r.cacheFlush) 0x8000 else 0)
                    o.write(byteArrayOf((r.ttl shr 24).toByte(), (r.ttl shr 16).toByte(), (r.ttl shr 8).toByte(), r.ttl.toByte()))
                    u16(o, r.data.size); o.write(r.data)
                }
                return o.toByteArray()
            }

            fun parse(buf: ByteArray, len: Int): Message? = runCatching {
                val b = buf.copyOf(len)
                if (len < 12) return null
                val flags = u16(b, 2)
                val qd = u16(b, 4); val an = u16(b, 6); val ns = u16(b, 8); val ar = u16(b, 10)
                var p = 12
                val qs = ArrayList<Question>()
                repeat(qd) {
                    val (n, next) = readName(b, p)
                    qs += Question(n, u16(b, next)); p = next + 4
                }
                val rs = ArrayList<Record>()
                repeat(an + ns + ar) {
                    val (n, next) = readName(b, p)
                    val t = u16(b, next)
                    val ttl = ((b[next + 4].toInt() and 0xFF) shl 24) or ((b[next + 5].toInt() and 0xFF) shl 16) or ((b[next + 6].toInt() and 0xFF) shl 8) or (b[next + 7].toInt() and 0xFF)
                    val rdLen = u16(b, next + 8)
                    val start = next + 10
                    if (start + rdLen > len) return null
                    rs += Record(n, t, ttl, b.copyOfRange(start, start + rdLen), message = b, offset = start)
                    p = start + rdLen
                }
                Message(flags and 0x8000 != 0, qs, rs)
            }.getOrNull()

        }
    }

    private companion object {
        const val TAG = "LanShare"
        const val GROUP = "224.0.0.251"
        const val PORT = 5353
        const val TTL = 120
        const val TYPE_A = 1; const val TYPE_PTR = 12; const val TYPE_TXT = 16; const val TYPE_SRV = 33; const val TYPE_ANY = 255
        const val CLASS_IN = 1

        fun u16(o: ByteArrayOutputStream, v: Int) { o.write(v shr 8); o.write(v and 0xFF) }
        fun u16(b: ByteArray, p: Int) = ((b[p].toInt() and 0xFF) shl 8) or (b[p + 1].toInt() and 0xFF)

        fun writeName(o: ByteArrayOutputStream, name: String) {
            for (label in splitLabels(name)) {
                val bytes = label.toByteArray(Charsets.UTF_8).take(63).toByteArray()
                o.write(bytes.size); o.write(bytes)
            }
            o.write(0)
        }

        fun name(n: String): ByteArray = ByteArrayOutputStream().also { writeName(it, n) }.toByteArray()
        fun srv(port: Int, target: String): ByteArray = ByteArrayOutputStream().also { u16(it, 0); u16(it, 0); u16(it, port); writeName(it, target) }.toByteArray()
        fun txt(map: Map<String, String>): ByteArray = ByteArrayOutputStream().also { o ->
            if (map.isEmpty()) o.write(0)
            for ((k, v) in map) { val e = "$k=$v".toByteArray(Charsets.UTF_8).take(255).toByteArray(); o.write(e.size); o.write(e) }
        }.toByteArray()

        /** Labels of a name; a dot inside the instance label is escaped as "\.". */
        fun splitLabels(name: String): List<String> {
            val out = ArrayList<String>(); val cur = StringBuilder(); var i = 0
            while (i < name.length) {
                val c = name[i]
                when {
                    c == '\\' && i + 1 < name.length -> { cur.append(name[i + 1]); i++ }
                    c == '.' -> { if (cur.isNotEmpty()) out += cur.toString(); cur.clear() }
                    else -> cur.append(c)
                }
                i++
            }
            if (cur.isNotEmpty()) out += cur.toString()
            return out
        }

        fun clean(instance: String) = instance.take(60).replace("\\", "").replace(".", "\\.")
        fun unescape(s: String) = s.replace("\\.", ".")

        /** The name at [start] of [msg] (compression pointers followed); returns it and the offset after it. */
        fun readName(msg: ByteArray, start: Int): Pair<String, Int> {
            val sb = StringBuilder()
            var p = start
            val buf = msg
            var after = -1
            var jumps = 0
            while (true) {
                val len = buf[p].toInt() and 0xFF
                when {
                    len == 0 -> { if (after < 0) after = p + 1; break }
                    len and 0xC0 == 0xC0 -> {
                        if (++jumps > 20) error("loop")
                        if (after < 0) after = p + 2
                        p = ((len and 0x3F) shl 8) or (buf[p + 1].toInt() and 0xFF)
                    }
                    else -> {
                        if (sb.isNotEmpty()) sb.append('.')
                        sb.append(String(buf, p + 1, len, Charsets.UTF_8).replace(".", "\\."))
                        p += 1 + len
                    }
                }
            }
            return sb.toString() to after
        }
    }
}
