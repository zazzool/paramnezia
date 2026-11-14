/*
 * Behaviour after JmDNS (javax.jmdns.impl: DNSOutgoing name compression, DNSRecord.suppressedBy,
 * tasks.state.Prober / Announcer / Canceler, tasks.Responder, NameRegister.incrementName, the
 * conflict rules of DNSRecord.Service.handleQuery / handleResponse, DNSConstants timings),
 * Copyright 2003-2005 Arthur van Hoff, Rick Blair and the JmDNS authors, licensed under the Apache
 * License, Version 2.0 (http://www.apache.org/licenses/LICENSE-2.0).
 *
 * Changes: one socket and two threads for all interfaces instead of a JmDNS instance with its
 * own timers per interface; IPv4 and one service type only; names in received packets are read
 * by following compression pointers anywhere in the packet (JmDNS resolves only pointers to names
 * it has already read, and loses forward pointers into record data); TTL 120 s instead of an hour,
 * so a phone that left the Wi-Fi without saying goodbye drops out of the list in minutes.
 */
package net.paramnezia.app.lan

import net.paramnezia.app.platform.Log
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Multicast DNS service discovery (RFC 6762 / 6763), just what sharing in the local network needs.
 *
 *  - [advertise]: probes for the instance name first (three probes 250 ms apart) and takes
 *    "Name (2)", "Name (3)" … if another device already uses it; then announces (PTR, SRV, TXT, A)
 *    twice a second apart, answers queries (shared answers after a random 20–115 ms, known
 *    answers suppressed, one-shot resolvers answered directly), defends the name, and says goodbye
 *    (TTL 0) when closed;
 *  - [browse]: asks for the type at growing intervals (1, 2, 4 … 60 s) listing the instances it
 *    already knows (so they do not all answer again), resolves instances from whatever records
 *    arrive, honours cache-flush, prefers the address in one of our own subnets, and reports an
 *    instance lost on goodbye or when its records expire.
 */
class Mdns(private val serviceType: String, private val interfaces: () -> List<LanNet.Lan> = LanNet::interfaces) {
    private val type = "$serviceType.local"
    private val group: InetAddress = InetAddress.getByName(GROUP)

    // --- advertising ------------------------------------------------------------------------------

    fun advertise(instance: String, port: Int, txt: Map<String, String> = emptyMap()): AutoCloseable {
        val lans = interfaces()
        if (lans.isEmpty()) return AutoCloseable {}
        val host = "paramnezia-${lans.first().host.replace('.', '-')}.local"
        val socket = open(lans) ?: return AutoCloseable {}
        val timer = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "mdns-announce").apply { isDaemon = true } }
        val ad = Advertisement(clean(instance), host, port, txt, lans, socket, timer)
        timer.execute { ad.probe() }

        val reader = Thread({
            val buf = ByteArray(9000)
            while (!socket.isClosed) {
                val p = DatagramPacket(buf, buf.size)
                runCatching { socket.receive(p) }.onFailure { if (socket.isClosed) return@Thread }
                val m = Message.parse(buf, p.length) ?: continue
                val from = InetSocketAddress(p.address, p.port)
                timer.execute { ad.received(m, from) }
            }
        }, "mdns-responder").apply { isDaemon = true; start() }

        return AutoCloseable {
            timer.submit { ad.goodbye() }.runCatching { get(2, TimeUnit.SECONDS) }
            timer.shutdownNow()
            socket.close()
            reader.interrupt()
        }
    }

    private enum class State { PROBING, ANNOUNCED, CLOSED }
    private class Entry<T>(val value: T, val until: Long, val ttl: Int, val at: Long)

    /** One advertised instance; everything runs on [timer], so no locking. */
    private inner class Advertisement(
        private var label: String, private val host: String, private val port: Int, private val txt: Map<String, String>,
        private val lans: List<LanNet.Lan>, private val socket: MulticastSocket, private val timer: java.util.concurrent.ScheduledExecutorService,
    ) {
        private var state = State.PROBING
        private var probes = 0
        private var conflicts = 0
        private var pending: ScheduledFuture<*>? = null
        private val name get() = "$label.$type"

        fun records(ttl: Int): List<Out> = listOf(
            Out(type, TYPE_PTR, ttl, target = name),
            Out(name, TYPE_SRV, ttl, cacheFlush = true, target = host, port = port),
            Out(name, TYPE_TXT, ttl, cacheFlush = true, raw = txtData(txt)),
        ) + lans.map { Out(host, TYPE_A, ttl, cacheFlush = true, raw = it.address.address) }

        /** Prober: three probes 250 ms apart (the first after 0–250 ms), asking for our name with our SRV as the proposal. */
        fun probe() {
            if (state == State.CLOSED) return
            state = State.PROBING
            probes = 0
            // After many conflicts in a row, slow down to one probe a second (PROBE_THROTTLE_COUNT).
            val first = if (conflicts >= 10) 1_000L else Random.nextLong(0, PROBE_WAIT_MS)
            pending?.cancel(false)
            pending = timer.schedule({ sendProbe() }, first, TimeUnit.MILLISECONDS)
        }

        private fun sendProbe() {
            if (state != State.PROBING) return
            if (probes == 3) { announce(); return }
            probes++
            val srv = records(TTL).filter { it.name == name }
            send(socket, lans, Message.write(id = 0, response = false, questions = listOf(Question(name, TYPE_ANY, unicast = true)), authority = srv))
            pending = timer.schedule({ sendProbe() }, PROBE_WAIT_MS, TimeUnit.MILLISECONDS)
        }

        /** Announcer: the full record set twice, a second apart. */
        private fun announce() {
            state = State.ANNOUNCED
            conflicts = 0
            send(socket, lans, Message.write(response = true, answers = records(TTL)))
            pending = timer.schedule({ if (state == State.ANNOUNCED) send(socket, lans, Message.write(response = true, answers = records(TTL))) }, 1, TimeUnit.SECONDS)
        }

        /** Canceler: the same records with TTL 0, so others forget the share at once. */
        fun goodbye() {
            if (state == State.ANNOUNCED) runCatching { send(socket, lans, Message.write(response = true, answers = records(0))) }
            state = State.CLOSED
            pending?.cancel(false)
        }

        /** NameRegister.incrementNameWithParentesis: "Name" → "Name (2)" → "Name (3)". */
        private fun rename() {
            conflicts++
            val m = Regex("""^(.*) \((\d+)\)$""").find(label)
            label = if (m != null) "${m.groupValues[1]} (${m.groupValues[2].toInt() + 1})" else "$label (2)"
            Log.i(TAG, "name taken on this network, now \"${unescape(label)}\"")
            probe()
        }

        private fun ourSrv(r: Record) = r.port == port && r.target.equals(host, true)

        fun received(m: Message, from: InetSocketAddress) {
            if (state == State.CLOSED) return
            if (m.isResponse) {
                // DNSRecord.Service.handleResponse: someone else answers for our name with another SRV.
                if (m.records.any { it.type == TYPE_SRV && it.name.equals(name, true) && !ourSrv(it) }) {
                    if (state == State.PROBING) rename() else probe()
                }
                return
            }
            // DNSRecord.Service.handleQuery: a conflicting probe while we probe; the lexicographically
            // later proposal wins (RFC 6762 §8.2), ours is ignored if identical (our own probe echoed).
            if (state == State.PROBING) {
                val theirs = m.authority.filter { it.type == TYPE_SRV && it.name.equals(name, true) }
                if (theirs.isNotEmpty() && theirs.none { ourSrv(it) }) {
                    val mine = srvBytes(port, host)
                    if (theirs.any { compareUnsigned(srvBytes(it.port, it.target), mine) > 0 }) rename()
                }
                return
            }
            answer(m, from)
        }

        /** Responder: answers, minus those the querier already knows (DNSRecord.suppressedBy). */
        private fun answer(q: Message, from: InetSocketAddress) {
            val all = records(TTL)
            val answers = all.filter { r -> q.questions.any { it.matches(r) } }
                .filterNot { r -> q.records.any { k -> r.sameAs(k) && k.ttl > r.ttl / 2 } }
            if (answers.isEmpty()) return
            val extra = if (answers.any { it.type == TYPE_PTR }) all.filterNot { it in answers } else emptyList()
            if (from.port != PORT) {
                // Legacy unicast (RFC 6762 §6.7): the query's id and questions back, short TTLs, no cache-flush bit.
                val legacy = (answers + extra).map { it.copy(ttl = minOf(it.ttl, 10), cacheFlush = false) }
                val reply = Message.write(id = q.id, response = true, questions = q.questions, answers = legacy)
                runCatching { socket.send(DatagramPacket(reply, reply.size, from)) }
                return
            }
            val reply = Message.write(response = true, answers = answers, additional = extra)
            val unicast = q.questions.all { it.unicast }
            fun go() { if (state == State.ANNOUNCED) { if (unicast) runCatching { socket.send(DatagramPacket(reply, reply.size, from)) } else send(socket, lans, reply) } }
            // Answers others may give too (the PTR) wait 20–115 ms, so they don't all collide; unique ones go now.
            if (answers.any { !it.cacheFlush }) timer.schedule({ go() }, Random.nextLong(RESPONSE_MIN_MS, RESPONSE_MAX_MS), TimeUnit.MILLISECONDS)
            else go()
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
            val known = cache.knownPointers(System.currentTimeMillis()).map { (inst, ttl) -> Out(type, TYPE_PTR, ttl, target = inst) }
            // The first query asks for unicast replies (QU), later ones are plain (QM).
            send(socket, lans, Message.write(response = false, questions = listOf(Question(type, TYPE_PTR, unicast = interval == 1_000L)), answers = known))
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
        /** Instance (lowercase, DNS names are case-insensitive) → its name as written. */
        private val ptr = ConcurrentHashMap<String, Entry<String>>()
        private val srv = ConcurrentHashMap<String, Entry<Pair<String, Int>>>()
        private val a = ConcurrentHashMap<String, MutableMap<String, Entry<Unit>>>()

        fun add(records: List<Record>, now: Long) {
            for (r in records) {
                val until = now + r.ttl * 1000L
                when (r.type) {
                    TYPE_PTR -> if (r.name.equals(type, true)) {
                        val inst = r.target
                        if (r.ttl == 0) ptr.remove(inst.lowercase()) else ptr[inst.lowercase()] = Entry(inst, until, r.ttl, now)
                    }
                    TYPE_SRV -> if (r.data.size >= 7) {
                        if (r.ttl == 0) srv.remove(r.name.lowercase()) else srv[r.name.lowercase()] = Entry(r.target.lowercase() to r.port, until, r.ttl, now)
                    }
                    TYPE_A -> if (r.data.size == 4) {
                        val ip = InetAddress.getByAddress(r.data).hostAddress
                        val m = a.getOrPut(r.name.lowercase()) { ConcurrentHashMap() }
                        // Cache-flush (RFC 6762 §10.2): other addresses of this host older than a second are stale.
                        if (r.cacheFlush) m.entries.removeIf { it.key != ip && now - it.value.at > 1_000 }
                        if (r.ttl == 0) m.remove(ip) else m[ip] = Entry(Unit, until, r.ttl, now)
                    }
                }
            }
        }

        /** Live instances with more than half their TTL left, to list as known answers in a query. */
        fun knownPointers(now: Long): List<Pair<String, Int>> = ptr.values.mapNotNull { e ->
            val left = ((e.until - now) / 1000).toInt()
            if (left > e.ttl / 2) e.value to left else null
        }

        /** Instances with a live PTR, SRV and an address, as instance (as written) → (address, port). */
        fun instances(now: Long): Map<String, Pair<String, Int>> {
            val out = HashMap<String, Pair<String, Int>>()
            for ((inst, entry) in ptr) {
                if (entry.until < now) { ptr.remove(inst); continue }
                val s = srv[inst]?.takeIf { it.until >= now } ?: continue
                val (target, port) = s.value
                val addrs = a[target]?.filterValues { it.until >= now }?.keys ?: continue
                // The address in one of our own subnets: the others belong to the sharer's other networks.
                val pick = addrs.firstOrNull { ip -> lans.any { it.contains(InetAddress.getByName(ip)) } } ?: addrs.firstOrNull() ?: continue
                out[entry.value] = pick to port
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

    /** A question; [unicast] is the QU bit (top bit of the class): "answer me directly". */
    internal class Question(val name: String, val type: Int, val unicast: Boolean = false) {
        fun matches(r: Out) = name.equals(r.name, true) && (type == r.type || type == TYPE_ANY)
    }

    /** A record to send; names in its data ([target]) are written compressed like the rest. */
    internal data class Out(
        val name: String, val type: Int, val ttl: Int, val cacheFlush: Boolean = false,
        val target: String? = null, val port: Int = 0, val raw: ByteArray? = null,
    ) {
        /** Same record as a received one (DNSRecord.equals + sameValue). */
        fun sameAs(r: Record): Boolean = r.type == type && r.name.equals(name, true) && when (type) {
            TYPE_PTR -> r.target.equals(target, true)
            TYPE_SRV -> r.port == port && r.target.equals(target, true)
            else -> r.data.contentEquals(raw ?: ByteArray(0))
        }
    }

    /**
     * A received resource record; [data] is the raw RDATA. Names inside it (PTR, SRV) may be
     * compressed, pointing anywhere in the packet, so the record keeps the whole [message] and the
     * RDATA [offset].
     */
    internal class Record(val name: String, val type: Int, val ttl: Int, val data: ByteArray, val cacheFlush: Boolean = false,
                          val message: ByteArray = data, val offset: Int = 0) {
        /** PTR: the name pointed to; SRV: the target host. */
        val target: String by lazy {
            when (type) {
                TYPE_PTR -> readName(message, offset).first
                TYPE_SRV -> if (data.size >= 7) readName(message, offset + 6).first else ""
                else -> ""
            }
        }
        val port: Int get() = if (type == TYPE_SRV && data.size >= 6) ((data[4].toInt() and 0xFF) shl 8) or (data[5].toInt() and 0xFF) else 0
    }

    internal class Message(val id: Int, val isResponse: Boolean, val questions: List<Question>, val records: List<Record>, val authority: List<Record>) {
        /** DNSOutgoing.MessageOutputStream: a name already written becomes a pointer to it. */
        private class Writer {
            val out = ByteArrayOutputStream()
            private val names = HashMap<String, Int>()
            fun u16(v: Int) { out.write(v shr 8); out.write(v and 0xFF) }
            fun u32(v: Int) { u16(v ushr 16); u16(v and 0xFFFF) }
            fun name(name: String) {
                val labels = splitLabels(name)
                for (i in labels.indices) {
                    val suffix = labels.subList(i, labels.size).joinToString(".") { it.lowercase() }
                    val at = names[suffix]
                    if (at != null) { u16(0xC000 or at); return }
                    if (out.size() < 0x3FFF) names[suffix] = out.size()
                    val bytes = labels[i].toByteArray(Charsets.UTF_8).take(63).toByteArray()
                    out.write(bytes.size); out.write(bytes)
                }
                out.write(0)
            }
            fun record(r: Out) {
                name(r.name); u16(r.type); u16(CLASS_IN or if (r.cacheFlush) 0x8000 else 0); u32(r.ttl)
                val lengthAt = out.size()
                u16(0)
                when (r.type) {
                    TYPE_PTR -> name(r.target!!)
                    TYPE_SRV -> { u16(0); u16(0); u16(r.port); name(r.target!!) }
                    else -> out.write(r.raw ?: ByteArray(0))
                }
                val bytes = out.toByteArray()
                val len = bytes.size - lengthAt - 2
                out.reset(); out.write(bytes, 0, lengthAt); u16(len); out.write(bytes, lengthAt + 2, len)
            }
        }

        companion object {
            fun write(id: Int = 0, response: Boolean, questions: List<Question> = emptyList(), answers: List<Out> = emptyList(),
                      authority: List<Out> = emptyList(), additional: List<Out> = emptyList()): ByteArray {
                val w = Writer()
                w.u16(id); w.u16(if (response) 0x8400 else 0) // response, authoritative
                w.u16(questions.size); w.u16(answers.size); w.u16(authority.size); w.u16(additional.size)
                for (q in questions) { w.name(q.name); w.u16(q.type); w.u16(CLASS_IN or if (q.unicast) 0x8000 else 0) }
                for (r in answers + authority + additional) w.record(r)
                return w.out.toByteArray()
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
                    qs += Question(n, u16(b, next), unicast = u16(b, next + 2) and 0x8000 != 0); p = next + 4
                }
                val answers = ArrayList<Record>(); val auth = ArrayList<Record>()
                for (k in 0 until an + ns + ar) {
                    val (n, next) = readName(b, p)
                    val t = u16(b, next)
                    val cls = u16(b, next + 2)
                    val ttl = ((b[next + 4].toInt() and 0xFF) shl 24) or ((b[next + 5].toInt() and 0xFF) shl 16) or ((b[next + 6].toInt() and 0xFF) shl 8) or (b[next + 7].toInt() and 0xFF)
                    val rdLen = u16(b, next + 8)
                    val start = next + 10
                    if (start + rdLen > len) return null
                    val r = Record(n, t, ttl, b.copyOfRange(start, start + rdLen), cacheFlush = cls and 0x8000 != 0, message = b, offset = start)
                    if (k in an until an + ns) auth += r else answers += r
                    p = start + rdLen
                }
                Message(u16(b, 0), flags and 0x8000 != 0, qs, answers, auth)
            }.getOrNull()
        }
    }

    private companion object {
        const val TAG = "LanShare"
        const val GROUP = "224.0.0.251"
        const val PORT = 5353
        const val TTL = 120
        const val PROBE_WAIT_MS = 250L
        const val RESPONSE_MIN_MS = 20L
        const val RESPONSE_MAX_MS = 115L
        const val TYPE_A = 1; const val TYPE_PTR = 12; const val TYPE_TXT = 16; const val TYPE_SRV = 33; const val TYPE_ANY = 255
        const val CLASS_IN = 1

        fun u16(b: ByteArray, p: Int) = ((b[p].toInt() and 0xFF) shl 8) or (b[p + 1].toInt() and 0xFF)

        fun srvBytes(port: Int, target: String): ByteArray = ByteArrayOutputStream().also { o ->
            o.write(0); o.write(0); o.write(0); o.write(0); o.write(port shr 8); o.write(port and 0xFF)
            for (l in splitLabels(target)) { val b = l.lowercase().toByteArray(Charsets.UTF_8); o.write(b.size); o.write(b) }
            o.write(0)
        }.toByteArray()

        fun compareUnsigned(a: ByteArray, b: ByteArray): Int {
            for (i in 0 until minOf(a.size, b.size)) { val d = (a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF); if (d != 0) return d }
            return a.size - b.size
        }

        fun txtData(map: Map<String, String>): ByteArray = ByteArrayOutputStream().also { o ->
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

        fun clean(instance: String) = instance.take(55).replace("\\", "").replace(".", "\\.")
        fun unescape(s: String) = s.replace("\\.", ".")

        /** The name at [start] of [msg] (compression pointers followed); returns it and the offset after it. */
        fun readName(msg: ByteArray, start: Int): Pair<String, Int> {
            val sb = StringBuilder()
            var p = start
            var after = -1
            var jumps = 0
            while (true) {
                val len = msg[p].toInt() and 0xFF
                when {
                    len == 0 -> { if (after < 0) after = p + 1; break }
                    len and 0xC0 == 0xC0 -> {
                        if (++jumps > 20) error("loop")
                        if (after < 0) after = p + 2
                        p = ((len and 0x3F) shl 8) or (msg[p + 1].toInt() and 0xFF)
                    }
                    len and 0xC0 != 0 -> error("unsupported label type") // extended (0x40) and reserved (0x80)
                    else -> {
                        if (sb.isNotEmpty()) sb.append('.')
                        sb.append(String(msg, p + 1, len, Charsets.UTF_8).replace(".", "\\."))
                        p += 1 + len
                    }
                }
            }
            return sb.toString() to after
        }
    }
}
