package net.paramnezia.deploy.ssh

import java.io.ByteArrayOutputStream
import java.math.BigInteger

/** RFC 4251 §5 data types, written. */
internal class SshWriter(capacity: Int = 256) {
    private val out = ByteArrayOutputStream(capacity)

    fun byte(v: Int) = apply { out.write(v) }
    fun bool(v: Boolean) = byte(if (v) 1 else 0)
    fun uint32(v: Int) = apply { out.write(v ushr 24); out.write(v ushr 16); out.write(v ushr 8); out.write(v) }
    fun uint32(v: Long) = uint32(v.toInt())
    fun raw(b: ByteArray, off: Int = 0, len: Int = b.size - off) = apply { out.write(b, off, len) }
    fun string(b: ByteArray) = uint32(b.size).raw(b)
    fun string(s: String) = string(s.toByteArray(Charsets.UTF_8))
    fun mpint(v: BigInteger) = string(if (v.signum() == 0) ByteArray(0) else v.toByteArray())
    /** An unsigned big-endian number as an mpint (a leading zero byte added if the top bit is set). */
    fun mpintUnsigned(b: ByteArray) = mpint(BigInteger(1, b))
    fun nameList(names: List<String>) = string(names.joinToString(","))

    val size: Int get() = out.size()
    fun bytes(): ByteArray = out.toByteArray()
}

/** RFC 4251 §5 data types, read; malformed input is an [SshProtocolException], never an index error. */
internal class SshReader(private val b: ByteArray, private var pos: Int = 0, private val end: Int = b.size) {
    private fun need(n: Int) { if (n < 0 || n > end - pos) throw SshProtocolException("truncated message") }

    val remaining: Int get() = end - pos
    fun byte(): Int { need(1); return b[pos++].toInt() and 0xFF }
    fun bool(): Boolean = byte() != 0
    fun uint32(): Int {
        need(4)
        val v = ((b[pos].toInt() and 0xFF) shl 24) or ((b[pos + 1].toInt() and 0xFF) shl 16) or ((b[pos + 2].toInt() and 0xFF) shl 8) or (b[pos + 3].toInt() and 0xFF)
        pos += 4
        return v
    }
    fun uint32Long(): Long = uint32().toLong() and 0xFFFFFFFFL
    fun raw(n: Int): ByteArray { need(n); return b.copyOfRange(pos, pos + n).also { pos += n } }
    fun string(): ByteArray { val n = uint32(); if (n < 0) throw SshProtocolException("bad length"); return raw(n) }
    fun text(): String = String(string(), Charsets.UTF_8)
    fun mpint(): BigInteger { val s = string(); return if (s.isEmpty()) BigInteger.ZERO else BigInteger(s) }
    fun nameList(): List<String> = text().split(',').filter { it.isNotEmpty() }
    fun rest(): ByteArray = raw(remaining)
}

internal open class SshProtocolException(message: String, cause: Throwable? = null) : Exception(message, cause)
