package net.paramnezia.core.net

import java.math.BigInteger
import java.net.InetAddress

/** An IPv4 or IPv6 network in CIDR form. The address is always normalized to the network address. */
class IpPrefix private constructor(
    val isV6: Boolean,
    /** Network address as an unsigned integer. */
    val network: BigInteger,
    val length: Int,
) : Comparable<IpPrefix> {

    val bits: Int get() = if (isV6) 128 else 32

    /** First address of the block. */
    val first: BigInteger get() = network

    /** Last address of the block (inclusive). */
    val last: BigInteger get() = network + BigInteger.ONE.shiftLeft(bits - length) - BigInteger.ONE

    val address: InetAddress get() = InetAddress.getByAddress(toBytes(network, if (isV6) 16 else 4))

    fun contains(other: IpPrefix): Boolean =
        isV6 == other.isV6 && other.length >= length && other.first >= first && other.last <= last

    override fun compareTo(other: IpPrefix): Int {
        if (isV6 != other.isV6) return if (isV6) 1 else -1
        val c = network.compareTo(other.network)
        return if (c != 0) c else length.compareTo(other.length)
    }

    override fun equals(other: Any?): Boolean =
        other is IpPrefix && isV6 == other.isV6 && network == other.network && length == other.length

    override fun hashCode(): Int = (network.hashCode() * 31 + length) * 2 + if (isV6) 1 else 0

    override fun toString(): String = "${address.hostAddress.let(::compactV6)}/$length"

    companion object {
        private val IPV4 = Regex("""\d{1,3}(\.\d{1,3}){3}""")

        val ANY_V4: IpPrefix by lazy { parse("0.0.0.0/0") }
        val ANY_V6: IpPrefix by lazy { parse("::/0") }

        fun of(isV6: Boolean, address: BigInteger, length: Int): IpPrefix {
            val bits = if (isV6) 128 else 32
            require(length in 0..bits) { "Invalid prefix length $length" }
            val hostMask = BigInteger.ONE.shiftLeft(bits - length) - BigInteger.ONE
            return IpPrefix(isV6, address.andNot(hostMask), length)
        }

        /** Parses `10.0.0.0/8`, `2001:db8::/32` or a bare address (treated as a host route). */
        fun parse(text: String): IpPrefix {
            val s = text.trim()
            val slash = s.indexOf('/')
            val addrPart = if (slash >= 0) s.substring(0, slash) else s
            val isV6 = addrPart.contains(':')
            require(isV6 || IPV4.matches(addrPart)) { "Not an IP address: $addrPart" }
            // Literal addresses only: InetAddress.getByName never hits DNS for these.
            val bytes = InetAddress.getByName(addrPart).address
            val v6 = bytes.size == 16
            val len = if (slash >= 0) s.substring(slash + 1).toInt() else if (v6) 128 else 32
            return of(v6, BigInteger(1, bytes), len)
        }

        fun parseOrNull(text: String): IpPrefix? = runCatching { parse(text) }.getOrNull()

        internal fun toBytes(value: BigInteger, size: Int): ByteArray {
            val raw = value.toByteArray()
            val out = ByteArray(size)
            val n = minOf(raw.size, size)
            System.arraycopy(raw, raw.size - n, out, size - n, n)
            return out
        }

        private fun compactV6(s: String): String {
            if (!s.contains(':')) return s
            // Java prints full-width IPv6 (0:0:0:...); collapse the longest zero run like inet_ntop.
            val groups = s.substringBefore('%').split(':').map { it.trimStart('0').ifEmpty { "0" } }
            var bestStart = -1; var bestLen = 0; var i = 0
            while (i < groups.size) {
                if (groups[i] == "0") {
                    var j = i
                    while (j < groups.size && groups[j] == "0") j++
                    if (j - i > bestLen && j - i > 1) { bestStart = i; bestLen = j - i }
                    i = j
                } else i++
            }
            if (bestStart < 0) return groups.joinToString(":")
            val head = groups.subList(0, bestStart).joinToString(":")
            val tail = groups.subList(bestStart + bestLen, groups.size).joinToString(":")
            return "$head::$tail"
        }
    }
}
