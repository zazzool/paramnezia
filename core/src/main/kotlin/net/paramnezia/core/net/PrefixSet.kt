package net.paramnezia.core.net

import java.math.BigInteger

/**
 * An immutable set of IP addresses of one family, stored as sorted, disjoint, non-adjacent ranges.
 * Used to turn "everything except Russian networks" into the shortest possible route list.
 */
class PrefixSet private constructor(
    val isV6: Boolean,
    private val starts: Array<BigInteger>,
    private val ends: Array<BigInteger>,
) {
    private val bits get() = if (isV6) 128 else 32
    private val maxAddr get() = BigInteger.ONE.shiftLeft(bits) - BigInteger.ONE

    val isEmpty: Boolean get() = starts.isEmpty()

    /** Number of addresses covered. */
    val size: BigInteger get() = starts.indices.fold(BigInteger.ZERO) { acc, i -> acc + ends[i] - starts[i] + BigInteger.ONE }

    fun contains(address: IpPrefix): Boolean {
        if (address.isV6 != isV6) return false
        var lo = 0; var hi = starts.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            when {
                address.first < starts[mid] -> hi = mid - 1
                address.first > ends[mid] -> lo = mid + 1
                else -> return address.last <= ends[mid]
            }
        }
        return false
    }

    /** All addresses of this family that are NOT in the set. */
    fun complement(): PrefixSet {
        val s = ArrayList<BigInteger>(); val e = ArrayList<BigInteger>()
        var cursor = BigInteger.ZERO
        for (i in starts.indices) {
            if (starts[i] > cursor) { s += cursor; e += starts[i] - BigInteger.ONE }
            cursor = ends[i] + BigInteger.ONE
        }
        if (cursor <= maxAddr) { s += cursor; e += maxAddr }
        return PrefixSet(isV6, s.toTypedArray(), e.toTypedArray())
    }

    fun union(other: PrefixSet): PrefixSet {
        require(other.isV6 == isV6)
        return build(isV6, (starts.indices.map { starts[it] to ends[it] }) + other.starts.indices.map { other.starts[it] to other.ends[it] })
    }

    fun minus(other: PrefixSet): PrefixSet = complement().union(other).complement()

    /** Minimal list of CIDR blocks that exactly covers the set. */
    fun toPrefixes(): List<IpPrefix> {
        val out = ArrayList<IpPrefix>()
        for (i in starts.indices) {
            var start = starts[i]
            val end = ends[i]
            while (start <= end) {
                // Largest block aligned at `start` that does not run past `end`.
                var len = if (start.signum() == 0) 0 else bits - start.lowestSetBit
                while (len < bits && start + BigInteger.ONE.shiftLeft(bits - len) - BigInteger.ONE > end) len++
                out += IpPrefix.of(isV6, start, len)
                start += BigInteger.ONE.shiftLeft(bits - len)
            }
        }
        return out
    }

    companion object {
        fun empty(isV6: Boolean) = PrefixSet(isV6, emptyArray(), emptyArray())

        fun of(isV6: Boolean, prefixes: Iterable<IpPrefix>): PrefixSet =
            build(isV6, prefixes.filter { it.isV6 == isV6 }.map { it.first to it.last })

        private fun build(isV6: Boolean, ranges: List<Pair<BigInteger, BigInteger>>): PrefixSet {
            val sorted = ranges.sortedBy { it.first }
            val s = ArrayList<BigInteger>(); val e = ArrayList<BigInteger>()
            for ((a, b) in sorted) {
                if (s.isNotEmpty() && a <= e.last() + BigInteger.ONE) {
                    if (b > e.last()) e[e.size - 1] = b
                } else { s += a; e += b }
            }
            return PrefixSet(isV6, s.toTypedArray(), e.toTypedArray())
        }
    }
}
