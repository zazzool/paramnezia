package net.paramnezia.core.qr

/** A QR symbol: [size] × [size] modules, true = dark. No quiet zone. */
class QrMatrix(val size: Int) {
    private val bits = BooleanArray(size * size)
    operator fun get(x: Int, y: Int): Boolean = bits[y * size + x]
    operator fun set(x: Int, y: Int, v: Boolean) { bits[y * size + x] = v }
}

/**
 * QR Code encoder (ISO/IEC 18004, model 2): byte mode, UTF-8 with an ECI header when the text is not
 * plain ASCII, the smallest version that fits at the requested error correction level, and the mask
 * chosen by the standard's penalty rules. Replaces zxing's writer: the app only ever encodes keys and
 * links, so byte mode alone covers it, and every reader decodes it.
 */
object QrEncoder {
    fun encode(text: String, level: EcLevel = EcLevel.M): QrMatrix = encode(text, level, null)

    internal fun encode(text: String, level: EcLevel, forceMask: Int?): QrMatrix {
        val data = text.toByteArray(Charsets.UTF_8)
        val utf8 = data.any { it < 0 }
        val version = QrVersion.ALL.firstOrNull { v -> bitLength(data.size, utf8, v.number) <= v.blocks(level).dataCodewords * 8 }
            ?: throw IllegalArgumentException("Too long for a QR code: ${data.size} bytes")
        val codewords = interleave(dataCodewords(data, utf8, version, level), version.blocks(level))
        return build(version, level, codewords, forceMask)
    }

    private fun countBits(version: Int) = if (version < 10) 8 else 16

    private fun bitLength(bytes: Int, eci: Boolean, version: Int) = (if (eci) 12 else 0) + 4 + countBits(version) + 8 * bytes

    private fun dataCodewords(data: ByteArray, eci: Boolean, version: QrVersion, level: EcLevel): IntArray {
        val capacity = version.blocks(level).dataCodewords
        val bits = BitWriter()
        if (eci) { bits.put(0b0111, 4); bits.put(26, 8) } // ECI 26 = UTF-8
        bits.put(0b0100, 4)
        bits.put(data.size, countBits(version.number))
        for (b in data) bits.put(b.toInt() and 0xFF, 8)
        bits.put(0, minOf(4, capacity * 8 - bits.length)) // terminator
        while (bits.length % 8 != 0) bits.put(0, 1)
        val out = bits.bytes().toMutableList()
        var pad = 0
        while (out.size < capacity) out += if (pad++ % 2 == 0) 0xEC else 0x11
        return out.toIntArray()
    }

    /** Splits into blocks, adds error correction to each, interleaves (data first, then EC). */
    private fun interleave(data: IntArray, ec: EcBlocks): IntArray {
        val blocks = ArrayList<IntArray>()
        var offset = 0
        for ((count, len) in ec.groups) repeat(count) { blocks += data.copyOfRange(offset, offset + len); offset += len }
        val ecs = blocks.map { ReedSolomon.encode(it, ec.ecPerBlock) }
        val out = ArrayList<Int>(ec.totalCodewords)
        for (i in 0 until blocks.maxOf { it.size }) for (b in blocks) if (i < b.size) out += b[i]
        for (i in 0 until ec.ecPerBlock) for (e in ecs) out += e[i]
        return out.toIntArray()
    }

    private fun build(version: QrVersion, level: EcLevel, codewords: IntArray, forceMask: Int? = null): QrMatrix {
        val function = QrLayout.functionMask(version)
        var best: QrMatrix? = null
        var bestPenalty = Int.MAX_VALUE
        for (mask in 0 until 8) {
            if (forceMask != null && mask != forceMask) continue
            val m = QrMatrix(version.size)
            QrLayout.drawFunctionPatterns(m, version)
            QrLayout.placeData(m, function, codewords, mask)
            QrLayout.drawFormat(m, level, mask)
            QrLayout.drawVersion(m, version)
            val p = penalty(m)
            if (p < bestPenalty) { bestPenalty = p; best = m }
        }
        return best!!
    }

    /** ISO/IEC 18004 §7.8.3 penalty: runs, 2×2 blocks, finder-like patterns, dark/light balance. */
    internal fun penalty(m: QrMatrix): Int {
        val n = m.size
        var p = 0
        for (horizontal in listOf(true, false)) for (a in 0 until n) {
            var run = 1
            var last = if (horizontal) m[0, a] else m[a, 0]
            for (b in 1 until n) {
                val v = if (horizontal) m[b, a] else m[a, b]
                if (v == last) run++ else { if (run >= 5) p += run - 2; run = 1; last = v }
            }
            if (run >= 5) p += run - 2
        }
        for (y in 0 until n - 1) for (x in 0 until n - 1) {
            val v = m[x, y]
            if (m[x + 1, y] == v && m[x, y + 1] == v && m[x + 1, y + 1] == v) p += 3
        }
        val pattern = booleanArrayOf(true, false, true, true, true, false, true)
        for (horizontal in listOf(true, false)) for (a in 0 until n) for (b in 0..n - 7) {
            fun at(i: Int) = if (horizontal) m[i, a] else m[a, i]
            if ((0 until 7).all { at(b + it) == pattern[it] }) {
                val before = (b - 4 until b).all { it < 0 || !at(it) }
                val after = (b + 7 until b + 11).all { it >= n || !at(it) }
                if (before || after) p += 40
            }
        }
        var dark = 0
        for (y in 0 until n) for (x in 0 until n) if (m[x, y]) dark++
        p += (Math.abs(dark * 20 - n * n * 10) / (n * n)) * 10
        return p
    }

    private class BitWriter {
        private val bits = ArrayList<Boolean>()
        val length: Int get() = bits.size
        fun put(value: Int, count: Int) { for (i in count - 1 downTo 0) bits += (value shr i) and 1 == 1 }
        fun bytes(): List<Int> = (0 until bits.size / 8).map { i -> (0 until 8).fold(0) { acc, j -> (acc shl 1) or if (bits[i * 8 + j]) 1 else 0 } }
    }
}

/** Where everything sits in a symbol: shared by the encoder and the decoder. */
internal object QrLayout {
    /** True for modules that belong to function patterns (finders, timing, alignment, format, version). */
    fun functionMask(v: QrVersion): QrMatrix {
        val n = v.size
        val f = QrMatrix(n)
        fun rect(x0: Int, y0: Int, w: Int, h: Int) { for (y in y0 until y0 + h) for (x in x0 until x0 + w) if (x in 0 until n && y in 0 until n) f[x, y] = true }
        rect(0, 0, 9, 9); rect(n - 8, 0, 8, 9); rect(0, n - 8, 9, 8)
        rect(6, 0, 1, n); rect(0, 6, n, 1)
        for (c in alignmentCentres(v)) rect(c.first - 2, c.second - 2, 5, 5)
        if (v.number >= 7) { rect(n - 11, 0, 3, 6); rect(0, n - 11, 6, 3) }
        return f
    }

    fun alignmentCentres(v: QrVersion): List<Pair<Int, Int>> {
        val a = v.alignment
        val out = ArrayList<Pair<Int, Int>>()
        for (x in a) for (y in a) {
            val last = a.last()
            if ((x == 6 && y == 6) || (x == 6 && y == last) || (x == last && y == 6)) continue
            out += x to y
        }
        return out
    }

    fun drawFunctionPatterns(m: QrMatrix, v: QrVersion) {
        val n = v.size
        fun finder(cx: Int, cy: Int) {
            for (dy in -4..4) for (dx in -4..4) {
                val x = cx + dx; val y = cy + dy
                if (x !in 0 until n || y !in 0 until n) continue
                val d = maxOf(Math.abs(dx), Math.abs(dy))
                m[x, y] = d != 2 && d != 4
            }
        }
        finder(3, 3); finder(n - 4, 3); finder(3, n - 4)
        for (i in 8 until n - 8) { m[i, 6] = i % 2 == 0; m[6, i] = i % 2 == 0 }
        for ((cx, cy) in alignmentCentres(v)) for (dy in -2..2) for (dx in -2..2) m[cx + dx, cy + dy] = maxOf(Math.abs(dx), Math.abs(dy)) != 1
        m[8, n - 8] = true // the dark module
    }

    fun maskBit(mask: Int, x: Int, y: Int): Boolean = when (mask) {
        0 -> (x + y) % 2 == 0
        1 -> y % 2 == 0
        2 -> x % 3 == 0
        3 -> (x + y) % 3 == 0
        4 -> (y / 2 + x / 3) % 2 == 0
        5 -> (x * y) % 2 + (x * y) % 3 == 0
        6 -> ((x * y) % 2 + (x * y) % 3) % 2 == 0
        else -> ((x + y) % 2 + (x * y) % 3) % 2 == 0
    }

    /** The data modules in reading order: two-column zigzag from the bottom right, skipping column 6. */
    fun dataOrder(function: QrMatrix): List<Int> {
        val n = function.size
        val out = ArrayList<Int>()
        var x = n - 1
        var up = true
        while (x > 0) {
            if (x == 6) x--
            for (i in 0 until n) {
                val y = if (up) n - 1 - i else i
                for (dx in 0..1) { val xx = x - dx; if (!function[xx, y]) out += y * n + xx }
            }
            up = !up
            x -= 2
        }
        return out
    }

    fun placeData(m: QrMatrix, function: QrMatrix, codewords: IntArray, mask: Int) {
        val n = m.size
        for ((i, pos) in dataOrder(function).withIndex()) {
            val x = pos % n; val y = pos / n
            val bit = i / 8 < codewords.size && (codewords[i / 8] shr (7 - i % 8)) and 1 == 1
            m[x, y] = bit xor maskBit(mask, x, y)
        }
    }

    /** BCH(15,5) format word: level and mask, masked with 0x5412. */
    fun formatWord(level: EcLevel, mask: Int): Int {
        val data = (level.bits shl 3) or mask
        var r = data shl 10
        for (i in 14 downTo 10) if ((r shr i) and 1 == 1) r = r xor (0x537 shl (i - 10))
        return ((data shl 10) or r) xor 0x5412
    }

    /** BCH(18,6) version word (versions 7 and up). */
    fun versionWord(version: Int): Int {
        var r = version shl 12
        for (i in 17 downTo 12) if ((r shr i) and 1 == 1) r = r xor (0x1F25 shl (i - 12))
        return (version shl 12) or r
    }

    /** Positions of format bit i (0 = least significant) in the two copies, as (x, y). */
    fun formatPositions(n: Int, i: Int): Pair<Pair<Int, Int>, Pair<Int, Int>> {
        val a = when {
            i < 6 -> 8 to i
            i < 8 -> 8 to i + 1
            i == 8 -> 7 to 8
            else -> 14 - i to 8
        }
        val b = if (i < 8) n - 1 - i to 8 else 8 to n - 15 + i
        return a to b
    }

    fun drawFormat(m: QrMatrix, level: EcLevel, mask: Int) {
        val w = formatWord(level, mask)
        for (i in 0 until 15) {
            val bit = (w shr i) and 1 == 1
            val (a, b) = formatPositions(m.size, i)
            m[a.first, a.second] = bit
            m[b.first, b.second] = bit
        }
    }

    fun drawVersion(m: QrMatrix, v: QrVersion) {
        if (v.number < 7) return
        val n = m.size
        val w = versionWord(v.number)
        for (i in 0 until 18) {
            val bit = (w shr i) and 1 == 1
            val a = i / 3; val b = n - 11 + i % 3
            m[a, b] = bit
            m[b, a] = bit
        }
    }
}
