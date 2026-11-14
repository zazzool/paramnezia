/*
 * Ported from ZXing (com.google.zxing.qrcode.decoder: Decoder, BitMatrixParser, DataBlock,
 * DataMask, FormatInformation, Version.decodeVersionInformation, DecodedBitStreamParser, Mode;
 * com.google.zxing.common: BitSource, CharacterSetECI, StringUtils.guessCharset;
 * com.google.zxing.multi.qrcode.QRCodeMultiReader), Copyright 2007-2008 ZXing authors, licensed
 * under the Apache License, Version 2.0 (http://www.apache.org/licenses/LICENSE-2.0).
 *
 * Changes: Kotlin; failures are null, not exceptions; the matrix is read without being unmasked
 * in place, so the mirrored retry needs no re-masking; consecutive byte segments in the same
 * character set are joined before decoding (a UTF-8 letter split between two segments stays one
 * letter; ZXing decodes each segment on its own); several symbols per picture, the inverted
 * picture too, and a neighbouring dimension when the estimated one fails (see QrDetector.kt).
 */
package net.paramnezia.core.qr

import java.nio.charset.Charset

/** A grayscale picture: [luma] holds width × height bytes (0 black … 255 white), row by row. */
class LumaImage(val width: Int, val height: Int, val luma: ByteArray) {
    init { require(luma.size >= width * height) }
    fun at(x: Int, y: Int): Int = luma[y * width + x].toInt() and 0xFF

    companion object {
        /** From ARGB pixels (desktop images, Android bitmaps). Transparent pixels count as white. */
        fun fromArgb(width: Int, height: Int, argb: IntArray): LumaImage {
            val l = ByteArray(width * height)
            for (i in 0 until width * height) {
                val p = argb[i]
                val r = (p shr 16) and 0xFF; val g = (p shr 8) and 0xFF; val b = p and 0xFF
                l[i] = (if (p ushr 24 < 128) 255 else (r * 306 + g * 601 + b * 117) shr 10).toByte()
            }
            return LumaImage(width, height, l)
        }
    }
}

/**
 * QR Code reader for camera frames, screenshots and pictures (ZXing's QR reader, ported): hybrid
 * binarization, finder and alignment patterns, perspective sampling, format and version
 * information by nearest valid word, Reed–Solomon per block, then numeric, alphanumeric, byte
 * (ECI or guessed character set), Kanji and Hanzi segments.
 */
object QrDecoder {
    /** Every distinct text found in [img] (AmneziaVPN shows long keys as several codes at once). */
    fun decodeAll(img: LumaImage): List<String> {
        val found = LinkedHashSet<String>()
        val bin = Binarizer.hybrid(img) ?: return emptyList()
        for (inverted in listOf(false, true)) {
            val matrix = if (inverted) bin.inverted() else bin
            for (info in FinderPatternFinder(matrix).findMulti()) decodeSymbol(matrix, info)?.let { found += it }
            if (found.isEmpty()) FinderPatternFinder(matrix).find()?.let { info -> decodeSymbol(matrix, info)?.let { found += it } }
            if (found.isNotEmpty()) break
        }
        return found.toList()
    }

    /** The first text found in [img], or null. */
    fun decode(img: LumaImage): String? = decodeAll(img).firstOrNull()

    private fun decodeSymbol(image: BitMatrix, info: FinderInfo): String? {
        val detector = Detector(image)
        for (delta in intArrayOf(0, 4, -4)) {
            val bits = detector.process(info, delta) ?: continue
            readMatrix(bits)?.let { return it }
        }
        return null
    }

    /** Decodes a sampled symbol, or its mirror image (Decoder.decode). */
    internal fun readMatrix(bits: QrMatrix): String? = read(bits, mirrored = false) ?: read(bits, mirrored = true)

    private fun read(bits: QrMatrix, mirrored: Boolean): String? {
        val parser = MatrixParser(bits, mirrored)
        val version = parser.readVersion() ?: return null
        val (level, mask) = parser.readFormat() ?: return null
        val codewords = parser.readCodewords(version, mask)
        val ec = version.blocks(level)
        val data = IntArray(ec.dataCodewords)
        var offset = 0
        for (block in DataBlocks.split(codewords, ec)) {
            if (ReedSolomon.decode(block.codewords, ec.ecPerBlock) < 0) return null
            System.arraycopy(block.codewords, 0, data, offset, block.dataCount)
            offset += block.dataCount
        }
        return BitStreamParser.decode(data, version.number)
    }
}

/** BitMatrixParser: format, version and codewords out of the sampled modules. */
private class MatrixParser(private val bits: QrMatrix, private val mirror: Boolean) {
    private val dimension = bits.size

    private fun copyBit(i: Int, j: Int, acc: Int): Int {
        val bit = if (mirror) bits[j, i] else bits[i, j]
        return if (bit) (acc shl 1) or 1 else acc shl 1
    }

    fun readFormat(): Pair<EcLevel, Int>? {
        // Read top-left format info bits.
        var info1 = 0
        for (i in 0 until 6) info1 = copyBit(i, 8, info1)
        // .. and skip a bit in the timing pattern ...
        info1 = copyBit(7, 8, info1); info1 = copyBit(8, 8, info1); info1 = copyBit(8, 7, info1)
        // .. and skip a bit in the timing pattern ...
        for (j in 5 downTo 0) info1 = copyBit(8, j, info1)
        // Read the top-right/bottom-left pattern too.
        var info2 = 0
        for (j in dimension - 1 downTo dimension - 7) info2 = copyBit(8, j, info2)
        for (i in dimension - 8 until dimension) info2 = copyBit(i, 8, info2)
        return FormatInfo.decode(info1, info2)
    }

    fun readVersion(): QrVersion? {
        val provisional = (dimension - 17) / 4
        if (provisional <= 6) return QrVersion.forSize(dimension)
        // Read top-right version info: 3 wide by 6 tall.
        var v = 0
        val ijMin = dimension - 11
        for (j in 5 downTo 0) for (i in dimension - 9 downTo ijMin) v = copyBit(i, j, v)
        FormatInfo.decodeVersion(v)?.takeIf { it.size == dimension }?.let { return it }
        // Hmm, failed. Try bottom left: 6 wide by 3 tall.
        v = 0
        for (i in 5 downTo 0) for (j in dimension - 9 downTo ijMin) v = copyBit(i, j, v)
        return FormatInfo.decodeVersion(v)?.takeIf { it.size == dimension }
    }

    /** The codewords in placement order, unmasked as they are read (two-column zigzag from bottom right). */
    fun readCodewords(version: QrVersion, mask: Int): IntArray {
        val function = QrLayout.functionMask(version)
        val result = IntArray(version.blocks(EcLevel.L).totalCodewords)
        var resultOffset = 0
        var currentByte = 0
        var bitsRead = 0
        var readingUp = true
        var j = dimension - 1
        while (j > 0) {
            // Skip whole column with vertical alignment pattern; saves time and makes the other code proceed more cleanly.
            if (j == 6) j--
            for (count in 0 until dimension) {
                val i = if (readingUp) dimension - 1 - count else count
                for (col in 0 until 2) {
                    val x = j - col
                    if (!function[x, i]) {
                        bitsRead++
                        currentByte = currentByte shl 1
                        val module = if (mirror) bits[i, x] else bits[x, i]
                        if (module != QrLayout.maskBit(mask, x, i)) currentByte = currentByte or 1
                        if (bitsRead == 8) {
                            if (resultOffset < result.size) result[resultOffset++] = currentByte
                            bitsRead = 0
                            currentByte = 0
                        }
                    }
                }
            }
            readingUp = !readingUp
            j -= 2
        }
        return result
    }
}

/** FormatInformation and Version.decodeVersionInformation: nearest valid word within 3 bits. */
internal object FormatInfo {
    private const val FORMAT_INFO_MASK_QR = 0x5412

    /** Masked format word → (level bits << 3 | mask), generated from the BCH code itself. */
    private val FORMAT_LOOKUP: List<Pair<Int, Int>> = (0 until 32).map { v ->
        val level = EcLevel.entries.first { it.bits == v shr 3 }
        QrLayout.formatWord(level, v and 7) to v
    }

    private val VERSION_LOOKUP: List<Int> = (7..40).map { QrLayout.versionWord(it) }

    fun decode(masked1: Int, masked2: Int): Pair<EcLevel, Int>? {
        val v = doDecode(masked1, masked2)
            // Should return null, but, some QR codes apparently do not mask this info.
            ?: doDecode(masked1 xor FORMAT_INFO_MASK_QR, masked2 xor FORMAT_INFO_MASK_QR) ?: return null
        return EcLevel.entries.first { it.bits == (v shr 3) and 3 } to (v and 7)
    }

    private fun doDecode(masked1: Int, masked2: Int): Int? {
        var bestDifference = Int.MAX_VALUE
        var best = 0
        for ((target, value) in FORMAT_LOOKUP) {
            if (target == masked1 || target == masked2) return value
            var diff = Integer.bitCount(masked1 xor target)
            if (diff < bestDifference) { best = value; bestDifference = diff }
            if (masked1 != masked2) {
                diff = Integer.bitCount(masked2 xor target)
                if (diff < bestDifference) { best = value; bestDifference = diff }
            }
        }
        // Hamming distance of the 32 masked codes is 7, by construction, so <= 3 bits differing means we found a match.
        return if (bestDifference <= 3) best else null
    }

    fun decodeVersion(versionBits: Int): QrVersion? {
        var bestDifference = Int.MAX_VALUE
        var best = 0
        for ((i, target) in VERSION_LOOKUP.withIndex()) {
            if (target == versionBits) return QrVersion.of(i + 7)
            val diff = Integer.bitCount(versionBits xor target)
            if (diff < bestDifference) { best = i + 7; bestDifference = diff }
        }
        return if (bestDifference <= 3) QrVersion.of(best) else null
    }
}

/** DataBlock: de-interleaves the codewords into the Reed–Solomon blocks. */
private object DataBlocks {
    class Block(val dataCount: Int, val codewords: IntArray)

    fun split(raw: IntArray, ec: EcBlocks): List<Block> {
        val result = ArrayList<Block>()
        for ((count, dataCodewords) in ec.groups) repeat(count) { result += Block(dataCodewords, IntArray(ec.ecPerBlock + dataCodewords)) }
        val numBlocks = result.size
        // All blocks have the same amount of data, except that the last n (where n may be 0) have 1 more byte.
        val shorterTotal = result[0].codewords.size
        var longerStartAt = result.size - 1
        while (longerStartAt >= 0 && result[longerStartAt].codewords.size != shorterTotal) longerStartAt--
        longerStartAt++
        val shorterData = shorterTotal - ec.ecPerBlock
        var offset = 0
        for (i in 0 until shorterData) for (j in 0 until numBlocks) result[j].codewords[i] = raw[offset++]
        // Fill out the last data block in the longer ones.
        for (j in longerStartAt until numBlocks) result[j].codewords[shorterData] = raw[offset++]
        // Now add in error correction blocks.
        val max = result[0].codewords.size
        for (i in shorterData until max) for (j in 0 until numBlocks) {
            val iOffset = if (j < longerStartAt) i else i + 1
            result[j].codewords[iOffset] = raw[offset++]
        }
        return result
    }
}

/** DecodedBitStreamParser: the segments of the data codewords. */
internal object BitStreamParser {
    private const val ALPHANUMERIC_CHARS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ $%*+-./:"
    private const val GB2312_SUBSET = 1

    private class BitSource(private val bytes: IntArray) {
        private var byteOffset = 0
        private var bitOffset = 0

        fun available(): Int = 8 * (bytes.size - byteOffset) - bitOffset

        fun readBits(numBits: Int): Int {
            require(numBits in 1..32 && numBits <= available())
            var n = numBits
            var result = 0
            // First, read remainder from current byte.
            if (bitOffset > 0) {
                val bitsLeft = 8 - bitOffset
                val toRead = minOf(n, bitsLeft)
                val bitsToNotRead = bitsLeft - toRead
                val mask = (0xFF shr (8 - toRead)) shl bitsToNotRead
                result = (bytes[byteOffset] and mask) shr bitsToNotRead
                n -= toRead
                bitOffset += toRead
                if (bitOffset == 8) { bitOffset = 0; byteOffset++ }
            }
            // Next read whole bytes.
            if (n > 0) {
                while (n >= 8) { result = (result shl 8) or (bytes[byteOffset] and 0xFF); byteOffset++; n -= 8 }
                // Finally read a partial byte.
                if (n > 0) {
                    val bitsToNotRead = 8 - n
                    val mask = (0xFF shr bitsToNotRead) shl bitsToNotRead
                    result = (result shl n) or ((bytes[byteOffset] and mask) shr bitsToNotRead)
                    bitOffset += n
                }
            }
            return result
        }
    }

    /** Character count bits per mode for versions 1–9, 10–26, 27–40 (Mode.getCharacterCountBits). */
    private fun countBits(mode: Int, version: Int): Int {
        val idx = if (version <= 9) 0 else if (version <= 26) 1 else 2
        return when (mode) {
            1 -> intArrayOf(10, 12, 14)[idx] // numeric
            2 -> intArrayOf(9, 11, 13)[idx]  // alphanumeric
            4 -> intArrayOf(8, 16, 16)[idx]  // byte
            8, 0xD -> intArrayOf(8, 10, 12)[idx] // kanji, hanzi
            else -> throw IllegalArgumentException()
        }
    }

    fun decode(data: IntArray, version: Int): String? = try {
        val bits = BitSource(data)
        val result = StringBuilder(50)
        // Byte segments wait here until something else comes, then are decoded together.
        val pendingBytes = java.io.ByteArrayOutputStream()
        var currentEci: Charset? = null
        var fc1InEffect = false
        fun flushBytes() {
            if (pendingBytes.size() == 0) return
            val b = pendingBytes.toByteArray()
            result.append(String(b, currentEci ?: guessCharset(b)))
            pendingBytes.reset()
        }
        while (true) {
            val mode = if (bits.available() < 4) 0 else bits.readBits(4)
            if (mode != 4) flushBytes()
            when (mode) {
                0 -> break // terminator
                5, 9 -> fc1InEffect = true // FNC1 in first or second position
                3 -> { // structured append: sequence and parity, the texts are joined by the caller
                    if (bits.available() < 16) return null
                    bits.readBits(16)
                }
                7 -> currentEci = eciCharset(parseEciValue(bits)) ?: return null
                0xD -> {
                    val subset = bits.readBits(4)
                    val count = bits.readBits(countBits(mode, version))
                    if (subset == GB2312_SUBSET) decodeHanzi(bits, result, count) ?: return null
                }
                1 -> decodeNumeric(bits, result, bits.readBits(countBits(mode, version))) ?: return null
                2 -> decodeAlphanumeric(bits, result, bits.readBits(countBits(mode, version)), fc1InEffect) ?: return null
                4 -> {
                    val count = bits.readBits(countBits(mode, version))
                    if (8 * count > bits.available()) return null
                    repeat(count) { pendingBytes.write(bits.readBits(8)) }
                }
                8 -> decodeKanji(bits, result, bits.readBits(countBits(mode, version))) ?: return null
                else -> return null
            }
        }
        flushBytes()
        result.toString()
    } catch (e: IllegalArgumentException) { null }

    private fun charset(name: String): Charset? = runCatching { Charset.forName(name) }.getOrNull()

    private fun decodeHanzi(bits: BitSource, result: StringBuilder, count: Int): Unit? {
        val gb2312 = charset("GB2312") ?: return null
        if (count * 13 > bits.available()) return null
        val buffer = ByteArray(2 * count)
        for (k in 0 until count) {
            val twoBytes = bits.readBits(13)
            var assembled = ((twoBytes / 0x060) shl 8) or (twoBytes % 0x060)
            assembled += if (assembled < 0x00A00) 0x0A1A1 else 0x0A6A1
            buffer[2 * k] = (assembled shr 8).toByte(); buffer[2 * k + 1] = assembled.toByte()
        }
        result.append(String(buffer, gb2312))
        return Unit
    }

    private fun decodeKanji(bits: BitSource, result: StringBuilder, count: Int): Unit? {
        val sjis = charset("Shift_JIS") ?: return null
        if (count * 13 > bits.available()) return null
        val buffer = ByteArray(2 * count)
        for (k in 0 until count) {
            val twoBytes = bits.readBits(13)
            var assembled = ((twoBytes / 0x0C0) shl 8) or (twoBytes % 0x0C0)
            assembled += if (assembled < 0x01F00) 0x08140 else 0x0C140
            buffer[2 * k] = (assembled shr 8).toByte(); buffer[2 * k + 1] = assembled.toByte()
        }
        result.append(String(buffer, sjis))
        return Unit
    }

    private fun alnum(value: Int): Char = if (value < ALPHANUMERIC_CHARS.length) ALPHANUMERIC_CHARS[value] else throw IllegalArgumentException()

    private fun decodeAlphanumeric(bits: BitSource, result: StringBuilder, count0: Int, fc1InEffect: Boolean): Unit? {
        val start = result.length
        var count = count0
        while (count > 1) {
            if (bits.available() < 11) return null
            val two = bits.readBits(11)
            result.append(alnum(two / 45)).append(alnum(two % 45))
            count -= 2
        }
        if (count == 1) {
            if (bits.available() < 6) return null
            result.append(alnum(bits.readBits(6)))
        }
        // See section 6.4.8.1, 6.4.8.2: %% is %, a lone % is the GS1 separator.
        if (fc1InEffect) {
            var i = start
            while (i < result.length) {
                if (result[i] == '%') {
                    if (i < result.length - 1 && result[i + 1] == '%') result.deleteCharAt(i + 1) else result.setCharAt(i, 0x1D.toChar())
                }
                i++
            }
        }
        return Unit
    }

    private fun decodeNumeric(bits: BitSource, result: StringBuilder, count0: Int): Unit? {
        var count = count0
        while (count >= 3) {
            if (bits.available() < 10) return null
            val three = bits.readBits(10)
            if (three >= 1000) return null
            result.append(alnum(three / 100)).append(alnum((three / 10) % 10)).append(alnum(three % 10))
            count -= 3
        }
        if (count == 2) {
            if (bits.available() < 7) return null
            val two = bits.readBits(7)
            if (two >= 100) return null
            result.append(alnum(two / 10)).append(alnum(two % 10))
        } else if (count == 1) {
            if (bits.available() < 4) return null
            val one = bits.readBits(4)
            if (one >= 10) return null
            result.append(alnum(one))
        }
        return Unit
    }

    private fun parseEciValue(bits: BitSource): Int {
        val first = bits.readBits(8)
        if (first and 0x80 == 0) return first and 0x7F
        if (first and 0xC0 == 0x80) return ((first and 0x3F) shl 8) or bits.readBits(8)
        if (first and 0xE0 == 0xC0) return ((first and 0x1F) shl 16) or bits.readBits(16)
        throw IllegalArgumentException()
    }

    /** CharacterSetECI: assignment values to character sets (those this JVM has). */
    private fun eciCharset(value: Int): Charset? = when (value) {
        0, 2 -> charset("IBM437")
        1, 3 -> Charsets.ISO_8859_1
        in 4..13 -> charset("ISO-8859-${value - 2}")
        in 15..18 -> charset("ISO-8859-${value - 2}")
        20 -> charset("Shift_JIS")
        21 -> charset("windows-1250"); 22 -> charset("windows-1251"); 23 -> charset("windows-1252"); 24 -> charset("windows-1256")
        25 -> Charsets.UTF_16BE
        26 -> Charsets.UTF_8
        27, 170 -> Charsets.US_ASCII
        28 -> charset("Big5")
        29 -> charset("GB18030")
        30 -> charset("EUC-KR")
        else -> null
    }

    /** StringUtils.guessCharset: UTF-8 if the bytes are valid multi-byte UTF-8, else Shift_JIS or ISO-8859-1. */
    internal fun guessCharset(bytes: ByteArray): Charset {
        if (bytes.size > 2 && ((bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) || (bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()))) return Charsets.UTF_16
        val sjisCharset = charset("Shift_JIS")
        var canBeISO88591 = true
        var canBeShiftJIS = sjisCharset != null
        var canBeUTF8 = true
        var utf8BytesLeft = 0
        var utf8MultiByteChars = 0
        var sjisBytesLeft = 0
        var sjisKatakanaChars = 0
        var sjisCurKatakanaWordLength = 0
        var sjisCurDoubleBytesWordLength = 0
        var sjisMaxKatakanaWordLength = 0
        var sjisMaxDoubleBytesWordLength = 0
        var isoHighOther = 0
        val utf8bom = bytes.size > 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()
        var i = 0
        while (i < bytes.size && (canBeISO88591 || canBeShiftJIS || canBeUTF8)) {
            val value = bytes[i].toInt() and 0xFF
            // UTF-8 stuff
            if (canBeUTF8) {
                if (utf8BytesLeft > 0) {
                    if (value and 0x80 == 0) canBeUTF8 = false else utf8BytesLeft--
                } else if (value and 0x80 != 0) {
                    if (value and 0x40 == 0) canBeUTF8 = false
                    else {
                        utf8BytesLeft++
                        if (value and 0x20 == 0) utf8MultiByteChars++
                        else {
                            utf8BytesLeft++
                            if (value and 0x10 == 0) utf8MultiByteChars++
                            else {
                                utf8BytesLeft++
                                if (value and 0x08 == 0) utf8MultiByteChars++ else canBeUTF8 = false
                            }
                        }
                    }
                }
            }
            // ISO-8859-1 stuff
            if (canBeISO88591) {
                if (value in 0x80..0x9F) canBeISO88591 = false
                else if (value > 0x9F && (value < 0xC0 || value == 0xD7 || value == 0xF7)) isoHighOther++
            }
            // Shift_JIS stuff
            if (canBeShiftJIS) {
                if (sjisBytesLeft > 0) {
                    if (value < 0x40 || value == 0x7F || value > 0xFC) canBeShiftJIS = false else sjisBytesLeft--
                } else if (value == 0x80 || value == 0xA0 || value > 0xEF) canBeShiftJIS = false
                else if (value in 0xA1..0xDF) {
                    sjisKatakanaChars++
                    sjisCurDoubleBytesWordLength = 0
                    sjisCurKatakanaWordLength++
                    if (sjisCurKatakanaWordLength > sjisMaxKatakanaWordLength) sjisMaxKatakanaWordLength = sjisCurKatakanaWordLength
                } else if (value > 0x7F) {
                    sjisBytesLeft++
                    sjisCurKatakanaWordLength = 0
                    sjisCurDoubleBytesWordLength++
                    if (sjisCurDoubleBytesWordLength > sjisMaxDoubleBytesWordLength) sjisMaxDoubleBytesWordLength = sjisCurDoubleBytesWordLength
                } else {
                    sjisCurKatakanaWordLength = 0
                    sjisCurDoubleBytesWordLength = 0
                }
            }
            i++
        }
        if (canBeUTF8 && utf8BytesLeft > 0) canBeUTF8 = false
        if (canBeShiftJIS && sjisBytesLeft > 0) canBeShiftJIS = false
        // Easy -- if there is BOM or at least 1 valid not-single byte character (and no evidence it can't be UTF-8), done.
        if (canBeUTF8 && (utf8bom || utf8MultiByteChars > 0)) return Charsets.UTF_8
        // Easy -- if assuming Shift_JIS or >= 3 valid consecutive not-ascii characters (and no evidence it can't be), done.
        if (canBeShiftJIS && (sjisMaxKatakanaWordLength >= 3 || sjisMaxDoubleBytesWordLength >= 3)) return sjisCharset!!
        // Distinguishing Shift_JIS and ISO-8859-1 can be a little tough for short words.
        if (canBeISO88591 && canBeShiftJIS) {
            return if ((sjisMaxKatakanaWordLength == 2 && sjisKatakanaChars == 2) || isoHighOther * 10 >= bytes.size) sjisCharset!! else Charsets.ISO_8859_1
        }
        if (canBeISO88591) return Charsets.ISO_8859_1
        if (canBeShiftJIS) return sjisCharset!!
        if (canBeUTF8) return Charsets.UTF_8
        // Otherwise, we take a wild guess with UTF-8 (ZXing uses the platform default, which varies).
        return Charsets.UTF_8
    }
}
