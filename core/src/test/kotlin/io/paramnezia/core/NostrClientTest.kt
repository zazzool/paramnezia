package io.paramnezia.core

import io.paramnezia.core.nostr.NostrClient
import io.paramnezia.core.nostr.NostrEvent
import io.paramnezia.core.nostr.Schnorr
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataInputStream
import java.net.ServerSocket
import java.security.MessageDigest
import java.util.Base64
import kotlin.concurrent.thread

/** Runs the client against a tiny in-process relay that stores one event and serves it back. */
class NostrClientTest {
    private class MiniRelay : AutoCloseable {
        val server = ServerSocket(0)
        val stored = ArrayList<String>()
        val url = "ws://127.0.0.1:${server.localPort}/"

        init {
            thread(isDaemon = true) {
                while (!server.isClosed) {
                    val s = runCatching { server.accept() }.getOrNull() ?: break
                    thread(isDaemon = true) { runCatching { serve(s) }; s.close() }
                }
            }
        }

        private fun serve(s: java.net.Socket) {
            val input = DataInputStream(s.getInputStream())
            val out = s.getOutputStream()
            val headers = generateSequence { readLine(input).takeIf { it.isNotEmpty() } }.toList()
            val key = headers.first { it.startsWith("Sec-WebSocket-Key:", true) }.substringAfter(':').trim()
            val accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray()))
            out.write("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: $accept\r\n\r\n".toByteArray())
            // A ping first: the client must answer it and carry on.
            out.write(byteArrayOf(0x89.toByte(), 0))
            while (true) {
                val b0 = input.read(); if (b0 < 0) return
                val b1 = input.read()
                var len = b1 and 0x7f
                if (len == 126) len = input.readUnsignedShort()
                val mask = ByteArray(4).also(input::readFully)
                val p = ByteArray(len).also(input::readFully)
                for (i in p.indices) p[i] = (p[i].toInt() xor mask[i % 4].toInt()).toByte()
                when (b0 and 0x0f) {
                    0x8 -> return
                    0xA -> continue
                }
                val msg = Json.parseToJsonElement(p.decodeToString()).jsonArray
                when (msg[0].jsonPrimitive.content) {
                    "EVENT" -> {
                        val ev = msg[1].toString()
                        synchronized(stored) { stored += ev }
                        val id = NostrEvent.fromJson(ev)!!.id
                        send(out, """["OK","$id",true,""]""")
                    }
                    "REQ" -> {
                        val sub = msg[1].jsonPrimitive.content
                        synchronized(stored) { stored.forEach { send(out, """["EVENT","$sub",$it]""") } }
                        send(out, """["EOSE","$sub"]""")
                    }
                }
            }
        }

        private fun send(out: java.io.OutputStream, text: String) {
            val b = text.toByteArray()
            val header = if (b.size < 126) byteArrayOf(0x81.toByte(), b.size.toByte())
            else byteArrayOf(0x81.toByte(), 126, (b.size shr 8).toByte(), b.size.toByte())
            out.write(header + b); out.flush()
        }

        private fun readLine(i: DataInputStream): String {
            val sb = StringBuilder()
            while (true) { val c = i.read(); if (c == '\n'.code || c < 0) return sb.toString().trimEnd('\r'); sb.append(c.toChar()) }
        }

        override fun close() = server.close()
    }

    @Test fun publishThenFetch() = MiniRelay().use { relay ->
        val secret = Schnorr.generateSecret()
        val big = "x".repeat(3000) // forces the 16-bit length form
        val ev = NostrEvent.create(secret, NostrEvent.KIND_APP_DATA, listOf(listOf("d", "tag")), big)
        val client = NostrClient()
        assertTrue(client.publish(relay.url, ev))
        val got = client.fetch(relay.url, listOf(ev.pubkey), "tag")
        assertEquals(listOf(ev), got)
        assertTrue(got.single().isValid())
    }
}
