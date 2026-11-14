package net.paramnezia.core.qr

/** A QR symbol: [size] × [size] modules, true = dark. No quiet zone. */
class QrMatrix(val size: Int) {
    private val bits = BooleanArray(size * size)
    operator fun get(x: Int, y: Int): Boolean = bits[y * size + x]
    operator fun set(x: Int, y: Int, v: Boolean) { bits[y * size + x] = v }
}

/**
 * QR Code encoder, after ZXing's Encoder and MaskUtil (com.google.zxing.qrcode.encoder, Copyright
 * 2008 ZXing authors, Apache License 2.0): the most compact single mode for the text (numeric,
 * alphanumeric or byte), the smallest version that fits, Reed–Solomon per block, interleaving,
 * and the mask with the lowest penalty (rules N1–N4 of ISO/IEC 18004 §7.8.3).
 *
 * Changes: Kotlin; text that is not plain ASCII is encoded as UTF-8 with an ECI header (ZXing
 * defaults to ISO-8859-1 and turns everything else into "?"); the error correction level is
 * raised as far as the chosen version still holds the data — the same size of code, more of it
 * may be damaged or out of focus and still read.
 */
object QrEncoder {
    fun encode(text: String, level: EcLevel = EcLevel.M): QrMatrix = encode(text, level, null)

    private enum class Mode(val bits: Int, val countBits: IntArray) {
        NUMERIC(0b0001, intArrayOf(10, 12, 14)), ALPHANUMERIC(0b0010, intArrayOf(9, 11, 13)), BYTE(0b0100, intArrayOf(8, 16, 16));
        fun countBits(version: Int) = countBits[if (version <= 9) 0 else if (version <= 26) 1 else 2]
    }

    private const val ALPHANUMERIC = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ $%*+-./:"

    /** Encoder.chooseMode: numeric if only digits, alphanumeric if only its 45 characters, else byte. */
    private fun chooseMode(text: String): Mode {
        var hasNumeric = false
        var hasAlphanumeric = false
        for (c in text) {
            if (c in '0'..'9') hasNumeric = true
            else if (ALPHANUMERIC.indexOf(c) >= 0) hasAlphanumeric = true
            else return Mode.BYTE
        }
        return if (hasAlphanumeric) Mode.ALPHANUMERIC else if (hasNumeric) Mode.NUMERIC else Mode.BYTE
    }

    internal fun encode(text: String, level: EcLevel, forceMask: Int?): QrMatrix {
        val mode = chooseMode(text)
        val bytes = text.toByteArray(Charsets.UTF_8)
        val utf8 = mode == Mode.BYTE && bytes.any { it < 0 }
        val data = BitWriter()
        val letters = when (mode) {
            Mode.NUMERIC -> { appendNumeric(text, data); text.length }
            Mode.ALPHANUMERIC -> { appendAlphanumeric(text, data); text.length }
            Mode.BYTE -> { for (b in bytes) data.put(b.toInt() and 0xFF, 8); bytes.size }
        }
        val header = if (utf8) 12 else 0
        fun bitsNeeded(version: Int) = header + 4 + mode.countBits(version) + data.length
        val version = QrVersion.ALL.firstOrNull { v -> bitsNeeded(v.number) <= v.blocks(level).dataCodewords * 8 }
            ?: throw IllegalArgumentException("Too long for a QR code: ${text.length} characters")
        // The strongest level that still fits this version.
        val boosted = listOf(EcLevel.H, EcLevel.Q, EcLevel.M)
            .firstOrNull { rank(it) > rank(level) && bitsNeeded(version.number) <= version.blocks(it).dataCodewords * 8 } ?: level
        val ec = version.blocks(boosted)
        val bits = BitWriter()
        if (utf8) { bits.put(0b0111, 4); bits.put(26, 8) } // ECI 26 = UTF-8
        bits.put(mode.bits, 4)
        bits.put(letters, mode.countBits(version.number))
        bits.append(data)
        val codewords = interleave(terminate(bits, ec.dataCodewords), ec)
        return build(version, boosted, codewords, forceMask)
    }

    private fun rank(l: EcLevel) = when (l) { EcLevel.L -> 0; EcLevel.M -> 1; EcLevel.Q -> 2; EcLevel.H -> 3 }

    /** Encoder.appendNumericBytes: three digits in 10 bits, two in 7, one in 4. */
    private fun appendNumeric(text: String, bits: BitWriter) {
        var i = 0
        while (i < text.length) {
            val n1 = text[i] - '0'
            if (i + 2 < text.length) { bits.put(n1 * 100 + (text[i + 1] - '0') * 10 + (text[i + 2] - '0'), 10); i += 3 }
            else if (i + 1 < text.length) { bits.put(n1 * 10 + (text[i + 1] - '0'), 7); i += 2 }
            else { bits.put(n1, 4); i++ }
        }
    }

    /** Encoder.appendAlphanumericBytes: two characters in 11 bits, a last one in 6. */
    private fun appendAlphanumeric(text: String, bits: BitWriter) {
        var i = 0
        while (i < text.length) {
            val c1 = ALPHANUMERIC.indexOf(text[i])
            if (i + 1 < text.length) { bits.put(c1 * 45 + ALPHANUMERIC.indexOf(text[i + 1]), 11); i += 2 }
            else { bits.put(c1, 6); i++ }
        }
    }

    /** Encoder.terminateBits: terminator, byte alignment, then 0xEC 0x11 … up to [capacity] bytes. */
    private fun terminate(bits: BitWriter, capacity: Int): IntArray {
        bits.put(0, minOf(4, capacity * 8 - bits.length))
        if (bits.length % 8 != 0) bits.put(0, 8 - bits.length % 8)
        val out = bits.bytes().toMutableList()
        var pad = 0
        while (out.size < capacity) out += if (pad++ % 2 == 0) 0xEC else 0x11
        return out.toIntArray()
    }

    /** Encoder.interleaveWithECBytes: blocks with their error correction, interleaved (data first, then EC). */
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

    /** MaskUtil: the four penalty rules. */
    internal fun penalty(m: QrMatrix): Int = rule1(m, true) + rule1(m, false) + rule2(m) + rule3(m) + rule4(m)

    /** Runs of five or more same-coloured modules in a row or column: N1 (3) + run − 5. */
    private fun rule1(m: QrMatrix, horizontal: Boolean): Int {
        var penalty = 0
        for (i in 0 until m.size) {
            var same = 0
            var prev: Boolean? = null
            for (j in 0 until m.size) {
                val bit = if (horizontal) m[j, i] else m[i, j]
                if (bit == prev) same++ else { if (same >= 5) penalty += 3 + (same - 5); same = 1; prev = bit }
            }
            if (same >= 5) penalty += 3 + (same - 5)
        }
        return penalty
    }

    /** 2×2 blocks of one colour: N2 (3) each. */
    private fun rule2(m: QrMatrix): Int {
        var penalty = 0
        for (y in 0 until m.size - 1) for (x in 0 until m.size - 1) {
            val v = m[x, y]
            if (v == m[x + 1, y] && v == m[x, y + 1] && v == m[x + 1, y + 1]) penalty++
        }
        return 3 * penalty
    }

    /** 1:1:3:1:1 finder-like patterns with four light modules on one side: N3 (40) each. */
    private fun rule3(m: QrMatrix): Int {
        val n = m.size
        var count = 0
        fun whiteH(y: Int, from: Int, to: Int) = from >= 0 && to <= n && (from until to).none { m[it, y] }
        fun whiteV(x: Int, from: Int, to: Int) = from >= 0 && to <= n && (from until to).none { m[x, it] }
        for (y in 0 until n) for (x in 0 until n) {
            if (x + 6 < n && m[x, y] && !m[x + 1, y] && m[x + 2, y] && m[x + 3, y] && m[x + 4, y] && !m[x + 5, y] && m[x + 6, y] &&
                (whiteH(y, x - 4, x) || whiteH(y, x + 7, x + 11))) count++
            if (y + 6 < n && m[x, y] && !m[x, y + 1] && m[x, y + 2] && m[x, y + 3] && m[x, y + 4] && !m[x, y + 5] && m[x, y + 6] &&
                (whiteV(x, y - 4, y) || whiteV(x, y + 7, y + 11))) count++
        }
        return count * 40
    }

    /** Dark modules away from half: N4 (10) per 5 % step. */
    private fun rule4(m: QrMatrix): Int {
        var dark = 0
        for (y in 0 until m.size) for (x in 0 until m.size) if (m[x, y]) dark++
        val total = m.size * m.size
        return Math.abs(dark * 2 - total) * 10 / total * 10
    }

    private class BitWriter {
        private val bits = ArrayList<Boolean>()
        val length: Int get() = bits.size
        fun put(value: Int, count: Int) { for (i in count - 1 downTo 0) bits += (value shr i) and 1 == 1 }
        fun append(other: BitWriter) { bits += other.bits }
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
