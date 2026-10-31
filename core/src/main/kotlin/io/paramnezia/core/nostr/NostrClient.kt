package io.paramnezia.core.nostr

import java.io.BufferedInputStream
import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.net.SocketFactory
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Short-lived Nostr relay sessions: connect, ask or publish, close. A sync is a handful of frames
 * once in many hours, so a persistent connection (and its keepalives) would only cost battery.
 *
 * The WebSocket client is a minimal RFC 6455 implementation (text frames, ping/pong, close) so the
 * app needs no HTTP library, and so the socket can come from any [SocketFactory], for example one
 * bound to the physical network while the tunnel is down.
 */
class NostrClient(
    private val socketFactory: SocketFactory = SocketFactory.getDefault(),
    private val timeoutMs: Int = 15_000,
) {
    /** Events matching the filter from [relay], until it signals end of stored events. */
    fun fetch(relay: String, authors: List<String>, dTag: String, limit: Int = 20): List<NostrEvent> =
        WebSocket.connect(relay, socketFactory, timeoutMs).use { ws ->
            val sub = "pn" + System.nanoTime().toString(36)
            ws.sendText(NostrEvent.requestFrame(sub, authors, dTag, limit))
            val out = ArrayList<NostrEvent>()
            while (true) {
                val text = ws.readText() ?: break
                when (val f = NostrEvent.parseRelayFrame(text)) {
                    is RelayFrame.Event -> if (f.subId == sub) out += f.event
                    is RelayFrame.EndOfStored -> if (f.subId == sub) break
                    else -> {}
                }
            }
            runCatching { ws.sendText(NostrEvent.closeFrame(sub)) }
            out
        }

    /** Publishes [event]; true when the relay accepted it. */
    fun publish(relay: String, event: NostrEvent): Boolean =
        WebSocket.connect(relay, socketFactory, timeoutMs).use { ws ->
            ws.sendText(NostrEvent.publishFrame(event))
            var accepted: Boolean? = null
            while (accepted == null) {
                val text = ws.readText() ?: break
                val f = NostrEvent.parseRelayFrame(text)
                if (f is RelayFrame.Ok && f.eventId == event.id) accepted = f.accepted
            }
            accepted == true
        }
}

/** Minimal client-side WebSocket (RFC 6455): enough for Nostr. */
class WebSocket private constructor(private val socket: Socket) : Closeable {
    private val input: InputStream = BufferedInputStream(socket.getInputStream())
    private val output: OutputStream = socket.getOutputStream()

    fun sendText(text: String) = sendFrame(0x1, text.toByteArray())

    /** Next text message, answering pings on the way; null when the server closed. */
    fun readText(): String? {
        val message = java.io.ByteArrayOutputStream()
        while (true) {
            val b0 = input.read().takeIf { it >= 0 } ?: return null
            val b1 = input.readByteOrThrow()
            val fin = b0 and 0x80 != 0
            val opcode = b0 and 0x0f
            var len = (b1 and 0x7f).toLong()
            if (len == 126L) len = ((input.readByteOrThrow() shl 8) or input.readByteOrThrow()).toLong()
            else if (len == 127L) { len = 0; repeat(8) { len = (len shl 8) or input.readByteOrThrow().toLong() } }
            if (len > MAX_MESSAGE) throw IOException("Frame too large")
            val mask = if (b1 and 0x80 != 0) ByteArray(4) { input.readByteOrThrow().toByte() } else null
            val payload = ByteArray(len.toInt())
            var read = 0
            while (read < payload.size) {
                val n = input.read(payload, read, payload.size - read)
                if (n < 0) throw EOFException()
                read += n
            }
            if (mask != null) for (i in payload.indices) payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
            when (opcode) {
                0x8 -> return null
                0x9 -> sendFrame(0xA, payload)
                0xA -> {}
                0x0, 0x1, 0x2 -> {
                    message.write(payload)
                    if (message.size() > MAX_MESSAGE) throw IOException("Message too large")
                    if (fin) return message.toString(Charsets.UTF_8.name())
                }
            }
        }
    }

    private fun sendFrame(opcode: Int, payload: ByteArray) {
        val header = java.io.ByteArrayOutputStream()
        header.write(0x80 or opcode)
        when {
            payload.size < 126 -> header.write(0x80 or payload.size)
            payload.size < 65536 -> { header.write(0x80 or 126); header.write(payload.size shr 8); header.write(payload.size and 0xff) }
            else -> { header.write(0x80 or 127); for (i in 7 downTo 0) header.write(((payload.size.toLong() shr (8 * i)) and 0xff).toInt()) }
        }
        // Clients must mask every frame.
        val mask = ByteArray(4).also(random::nextBytes)
        header.write(mask)
        val masked = ByteArray(payload.size) { (payload[it].toInt() xor mask[it % 4].toInt()).toByte() }
        synchronized(output) { output.write(header.toByteArray() + masked); output.flush() }
    }

    override fun close() {
        runCatching { sendFrame(0x8, byteArrayOf(0x03, 0xE8.toByte())) }
        runCatching { socket.close() }
    }

    companion object {
        private const val MAX_MESSAGE = 4 shl 20
        private val random = SecureRandom()

        fun connect(url: String, socketFactory: SocketFactory, timeoutMs: Int): WebSocket {
            val uri = URI(url)
            val secure = uri.scheme.equals("wss", true)
            val host = uri.host ?: throw IOException("Bad relay URL $url")
            val port = if (uri.port > 0) uri.port else if (secure) 443 else 80
            val raw = socketFactory.createSocket()
            raw.connect(InetSocketAddress(host, port), timeoutMs)
            raw.soTimeout = timeoutMs
            val socket = if (secure) {
                (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(raw, host, port, true).also {
                    it as SSLSocket
                    it.sslParameters = it.sslParameters.apply {
                        serverNames = listOf(SNIHostName(host))
                        endpointIdentificationAlgorithm = "HTTPS"
                    }
                    it.startHandshake()
                }
            } else raw
            try {
                val key = Base64.getEncoder().encodeToString(ByteArray(16).also(random::nextBytes))
                val path = (uri.rawPath?.ifEmpty { "/" } ?: "/") + (uri.rawQuery?.let { "?$it" } ?: "")
                val hostHeader = if (uri.port > 0) "$host:$port" else host
                socket.getOutputStream().apply {
                    write(("GET $path HTTP/1.1\r\nHost: $hostHeader\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                        "Sec-WebSocket-Key: $key\r\nSec-WebSocket-Version: 13\r\nUser-Agent: paramnezia\r\n\r\n").toByteArray())
                    flush()
                }
                val ws = WebSocket(socket)
                val status = ws.readLine()
                if (!status.startsWith("HTTP/1.1 101")) throw IOException("Relay refused WebSocket: $status")
                val expected = Base64.getEncoder().encodeToString(
                    MessageDigest.getInstance("SHA-1").digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray()),
                )
                var accepted = false
                while (true) {
                    val line = ws.readLine()
                    if (line.isEmpty()) break
                    if (line.substringBefore(':').trim().equals("Sec-WebSocket-Accept", true) && line.substringAfter(':').trim() == expected) accepted = true
                }
                if (!accepted) throw IOException("Bad WebSocket handshake")
                return ws
            } catch (e: Exception) {
                socket.close()
                throw e
            }
        }

        private fun InputStream.readByteOrThrow(): Int = read().also { if (it < 0) throw EOFException() }
    }

    private fun readLine(): String {
        val sb = StringBuilder()
        while (true) {
            val c = input.readByteOrThrow()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(c.toChar())
            if (sb.length > 8192) throw IOException("Header too long")
        }
    }
}
