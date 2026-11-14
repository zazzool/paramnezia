package net.paramnezia.core.qr

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** A grayscale picture: [luma] holds width × height bytes (0 black … 255 white), row by row. */
class LumaImage(val width: Int, val height: Int, val luma: ByteArray) {
    init { require(luma.size >= width * height) }
    fun at(x: Int, y: Int): Int = luma[y * width + x].toInt() and 0xFF

    companion object {
        /** From ARGB pixels (desktop images, Android bitmaps). */
        fun fromArgb(width: Int, height: Int, argb: IntArray): LumaImage {
            val l = ByteArray(width * height)
            for (i in 0 until width * height) {
                val p = argb[i]
                val a = p ushr 24
                val r = (p shr 16) and 0xFF; val g = (p shr 8) and 0xFF; val b = p and 0xFF
                // Transparent pixels count as white (QR codes on transparent PNGs).
                val y = (r * 306 + g * 601 + b * 117) shr 10
                l[i] = (if (a < 128) 255 else y).toByte()
            }
            return LumaImage(width, height, l)
        }
    }
}

/**
 * QR Code reader (ISO/IEC 18004, model 2) for camera frames, screenshots and pictures. Replaces
 * zxing's reader with what the app needs:
 *  - adaptive binarization (local thresholds per 8×8 block, smoothed over a 5×5 neighbourhood), and
 *    the inverted image too (light code on a dark background);
 *  - finder patterns found by their 1:1:3:1:1 ratio and cross-checked vertically and horizontally;
 *  - every plausible triple of finders tried, so several codes in one picture are all read
 *    (AmneziaVPN shows long keys as a series of codes);
 *  - perspective correction from the three finders and the bottom-right alignment pattern;
 *  - format and version information from both copies, nearest valid word;
 *  - Reed–Solomon correction per block, then numeric, alphanumeric, byte (UTF-8 / ECI) and kanji segments;
 *  - mirrored codes.
 */
object QrDecoder {
    /** Every distinct text found in [img], in no particular order. */
    fun decodeAll(img: LumaImage): List<String> {
        val found = LinkedHashSet<String>()
        val bin = Binarized.of(img)
        for (inverted in listOf(false, true)) {
            val finders = FinderFinder(bin, inverted).find()
            for (t in triples(finders)) {
                decodeAt(bin, inverted, t)?.let { found += it }
            }
            if (found.isNotEmpty()) break
        }
        return found.toList()
    }

    /** The first text found in [img], or null. */
    fun decode(img: LumaImage): String? = decodeAll(img).firstOrNull()

    // --- geometry ------------------------------------------------------------------------------

    internal class Finder(val x: Double, val y: Double, val module: Double, var count: Int = 1)

    private class Triple(val tl: Finder, val tr: Finder, val bl: Finder)

    private fun dist(a: Finder, b: Finder) = hypot(a.x - b.x, a.y - b.y)

    /** Triples that look like the three corners of one code, best (most confirmed) first. */
    private fun triples(fs: List<Finder>): List<Triple> {
        val list = fs.sortedByDescending { it.count }.take(MAX_FINDERS)
        val out = ArrayList<Pair<Double, Triple>>()
        for (i in list.indices) for (j in i + 1 until list.size) for (k in j + 1 until list.size) {
            val a = list[i]; val b = list[j]; val c = list[k]
            val ms = listOf(a.module, b.module, c.module)
            if (ms.max() > ms.min() * 1.6) continue
            // The right angle is at the corner opposite the longest side.
            val ab = dist(a, b); val bc = dist(b, c); val ac = dist(a, c)
            val (tl, p, q) = when {
                bc >= ab && bc >= ac -> Triple3(a, b, c)
                ac >= ab && ac >= bc -> Triple3(b, a, c)
                else -> Triple3(c, a, b)
            }
            val d1 = dist(tl, p); val d2 = dist(tl, q); val hyp = dist(p, q)
            if (d1 < 7 * tl.module || d2 < 7 * tl.module) continue
            if (abs(d1 - d2) > 0.25 * maxOf(d1, d2)) continue
            if (abs(hyp - sqrt(d1 * d1 + d2 * d2)) > 0.2 * hyp) continue
            // Clockwise in image coordinates: top-right then bottom-left.
            val cross = (p.x - tl.x) * (q.y - tl.y) - (p.y - tl.y) * (q.x - tl.x)
            val t = if (cross > 0) Triple(tl, p, q) else Triple(tl, q, p)
            out += (abs(d1 - d2) / maxOf(d1, d2) - (a.count + b.count + c.count) * 0.01) to t
        }
        return out.sortedBy { it.first }.map { it.second }
    }

    private data class Triple3(val a: Finder, val b: Finder, val c: Finder)

    /**
     * Module size measured from finder [a]'s centre along the line to [b] (the code's own axis), both
     * ways out to the finder's outer edge: 7 modules. A row through a code turned 45° is √2 times
     * longer, so the size from the finder scan alone would misjudge the code's dimension.
     */
    private fun moduleAlong(bin: Binarized, inverted: Boolean, a: Finder, b: Finder): Double? {
        val len = dist(a, b)
        if (len == 0.0) return null
        val ux = (b.x - a.x) / len; val uy = (b.y - a.y) / len
        fun half(sign: Int): Double? {
            // Dark centre, light ring, dark ring: stop at the light beyond (3.5 modules from the centre).
            var phase = 0
            var d = 0.0
            val limit = a.module * 8
            while (d < limit) {
                val x = (a.x + sign * ux * d).toInt(); val y = (a.y + sign * uy * d).toInt()
                if (x !in 0 until bin.width || y !in 0 until bin.height) return null
                val dark = bin.dark(x, y) != inverted
                when (phase) {
                    0 -> if (!dark) phase = 1
                    1 -> if (dark) phase = 2
                    2 -> if (!dark) return d
                }
                d += 0.5
            }
            return null
        }
        val f = half(1) ?: return null
        val r = half(-1) ?: return null
        return (f + r) / 7.0
    }

    private fun decodeAt(bin: Binarized, inverted: Boolean, t: Triple): String? {
        val along = listOfNotNull(
            moduleAlong(bin, inverted, t.tl, t.tr), moduleAlong(bin, inverted, t.tl, t.bl),
            moduleAlong(bin, inverted, t.tr, t.tl), moduleAlong(bin, inverted, t.bl, t.tl),
        )
        val module = if (along.isNotEmpty()) along.average() else (t.tl.module + t.tr.module + t.bl.module) / 3
        val est = ((dist(t.tl, t.tr) + dist(t.tl, t.bl)) / 2 / module).roundToInt() + 7
        val base = when (est and 3) { 0 -> est + 1; 1 -> est; 2 -> est - 1; else -> est - 2 }
        for (size in listOf(base, base + 4, base - 4)) {
            if (size !in 21..177) continue
            val m = sample(bin, inverted, t, size, module) ?: continue
            readMatrix(m)?.let { return it }
            readMatrix(transpose(m))?.let { return it }
        }
        return null
    }

    private fun sample(bin: Binarized, inverted: Boolean, t: Triple, size: Int, module: Double): QrMatrix? {
        val brX = t.tr.x + t.bl.x - t.tl.x
        val brY = t.tr.y + t.bl.y - t.tl.y
        val version = QrVersion.forSize(size) ?: return null
        var dstX = size - 3.5; var dstY = size - 3.5
        var srcX = brX; var srcY = brY
        if (version.number >= 2) {
            // The bottom-right alignment pattern, 3 modules in from the corner finder centres' grid.
            val k = 1.0 - 3.0 / (size - 7)
            val ex = t.tl.x + k * (brX - t.tl.x)
            val ey = t.tl.y + k * (brY - t.tl.y)
            val found = AlignmentFinder(bin, inverted, module).find(ex, ey)
            dstX = size - 6.5; dstY = size - 6.5
            srcX = found?.first ?: ex; srcY = found?.second ?: ey
        }
        val tf = Perspective.quadToQuad(
            3.5, 3.5, size - 3.5, 3.5, dstX, dstY, 3.5, size - 3.5,
            t.tl.x, t.tl.y, t.tr.x, t.tr.y, srcX, srcY, t.bl.x, t.bl.y,
        )
        val m = QrMatrix(size)
        for (y in 0 until size) for (x in 0 until size) {
            val (px, py) = tf.map(x + 0.5, y + 0.5)
            val ix = px.toInt(); val iy = py.toInt()
            if (ix !in 0 until bin.width || iy !in 0 until bin.height) return null
            m[x, y] = bin.dark(ix, iy) != inverted
        }
        return m
    }

    private fun transpose(m: QrMatrix): QrMatrix {
        val t = QrMatrix(m.size)
        for (y in 0 until m.size) for (x in 0 until m.size) t[y, x] = m[x, y]
        return t
    }

    // --- symbol ----------------------------------------------------------------------------------

    /** Decodes a sampled symbol, or null. */
    internal fun readMatrix(m: QrMatrix): String? {
        val n = m.size
        val (level, mask) = readFormat(m) ?: return null
        val version = if (n >= 45) readVersion(m) ?: return null else QrVersion.forSize(n) ?: return null
        if (version.size != n) return null
        val function = QrLayout.functionMask(version)
        val ec = version.blocks(level)
        val raw = IntArray(ec.totalCodewords)
        for ((i, pos) in QrLayout.dataOrder(function).withIndex()) {
            if (i / 8 >= raw.size) break
            val x = pos % n; val y = pos / n
            if (m[x, y] xor QrLayout.maskBit(mask, x, y)) raw[i / 8] = raw[i / 8] or (1 shl (7 - i % 8))
        }
        val data = deinterleave(raw, ec) ?: return null
        return Segments.parse(data, version.number)
    }

    private fun readFormat(m: QrMatrix): Pair<EcLevel, Int>? {
        val n = m.size
        var a = 0; var b = 0
        for (i in 0 until 15) {
            val (p, q) = QrLayout.formatPositions(n, i)
            if (m[p.first, p.second]) a = a or (1 shl i)
            if (m[q.first, q.second]) b = b or (1 shl i)
        }
        var best: Pair<EcLevel, Int>? = null
        var bestD = 4
        for (level in EcLevel.entries) for (mask in 0 until 8) {
            val w = QrLayout.formatWord(level, mask)
            val d = minOf(Integer.bitCount(w xor a), Integer.bitCount(w xor b))
            if (d < bestD) { bestD = d; best = level to mask }
        }
        return best
    }

    private fun readVersion(m: QrMatrix): QrVersion? {
        val n = m.size
        var a = 0; var b = 0
        for (i in 0 until 18) {
            val p = i / 3; val q = n - 11 + i % 3
            if (m[p, q]) a = a or (1 shl i)
            if (m[q, p]) b = b or (1 shl i)
        }
        var best: QrVersion? = null
        var bestD = 4
        for (v in 7..40) {
            val w = QrLayout.versionWord(v)
            val d = minOf(Integer.bitCount(w xor a), Integer.bitCount(w xor b))
            if (d < bestD) { bestD = d; best = QrVersion.of(v) }
        }
        return best
    }

    private fun deinterleave(raw: IntArray, ec: EcBlocks): IntArray? {
        val sizes = ec.groups.flatMap { (count, len) -> List(count) { len } }
        val blocks = sizes.map { IntArray(it + ec.ecPerBlock) }
        var i = 0
        for (k in 0 until sizes.max()) for ((b, len) in sizes.withIndex()) if (k < len) blocks[b][k] = raw[i++]
        for (k in 0 until ec.ecPerBlock) for ((b, len) in sizes.withIndex()) blocks[b][len + k] = raw[i++]
        val out = ArrayList<Int>()
        for ((b, len) in sizes.withIndex()) {
            if (ReedSolomon.decode(blocks[b], ec.ecPerBlock) < 0) return null
            for (k in 0 until len) out += blocks[b][k]
        }
        return out.toIntArray()
    }

    private const val MAX_FINDERS = 12
}

/** Black and white per pixel, from local thresholds. */
internal class Binarized(val width: Int, val height: Int, private val bits: BooleanArray) {
    fun dark(x: Int, y: Int) = bits[y * width + x]

    companion object {
        private const val BLOCK = 8

        fun of(img: LumaImage): Binarized {
            val w = img.width; val h = img.height
            val bits = BooleanArray(w * h)
            val bw = (w + BLOCK - 1) / BLOCK; val bh = (h + BLOCK - 1) / BLOCK
            if (bw < 5 || bh < 5) {
                // Tiny picture: one threshold for all (the mean between the darkest and lightest).
                var lo = 255; var hi = 0
                for (y in 0 until h) for (x in 0 until w) { val v = img.at(x, y); lo = minOf(lo, v); hi = maxOf(hi, v) }
                val t = (lo + hi) / 2
                for (y in 0 until h) for (x in 0 until w) bits[y * w + x] = img.at(x, y) < t
                return Binarized(w, h, bits)
            }
            // Per block: the mean, or (for flat blocks) a value that keeps them on the side of their neighbours.
            val thr = IntArray(bw * bh)
            for (by in 0 until bh) for (bx in 0 until bw) {
                var sum = 0; var lo = 255; var hi = 0; var cnt = 0
                for (y in by * BLOCK until minOf(h, by * BLOCK + BLOCK)) for (x in bx * BLOCK until minOf(w, bx * BLOCK + BLOCK)) {
                    val v = img.at(x, y); sum += v; lo = minOf(lo, v); hi = maxOf(hi, v); cnt++
                }
                var t = sum / cnt
                if (hi - lo <= 24) {
                    t = lo / 2
                    if (by > 0 && bx > 0) {
                        val n = (thr[(by - 1) * bw + bx] + 2 * thr[by * bw + bx - 1] + thr[(by - 1) * bw + bx - 1]) / 4
                        if (lo < n) t = n
                    }
                }
                thr[by * bw + bx] = t
            }
            for (by in 0 until bh) for (bx in 0 until bw) {
                var sum = 0; var cnt = 0
                for (dy in -2..2) for (dx in -2..2) {
                    val yy = (by + dy).coerceIn(0, bh - 1); val xx = (bx + dx).coerceIn(0, bw - 1)
                    sum += thr[yy * bw + xx]; cnt++
                }
                val t = sum / cnt
                for (y in by * BLOCK until minOf(h, by * BLOCK + BLOCK)) for (x in bx * BLOCK until minOf(w, bx * BLOCK + BLOCK)) {
                    bits[y * w + x] = img.at(x, y) <= t
                }
            }
            return Binarized(w, h, bits)
        }
    }
}

/** Finds finder patterns: runs of dark, light, dark (3×), light, dark in the ratio 1:1:3:1:1. */
private class FinderFinder(private val bin: Binarized, private val inverted: Boolean) {
    private fun dark(x: Int, y: Int) = bin.dark(x, y) != inverted
    private val found = ArrayList<QrDecoder.Finder>()

    fun find(): List<QrDecoder.Finder> {
        // Every row for small pictures; big ones are sampled (a finder is at least 7 modules tall).
        val step = maxOf(1, bin.height / 360)
        var y = 0
        while (y < bin.height) {
            val c = IntArray(5)
            var state = 0
            for (x in 0 until bin.width) {
                if (dark(x, y)) {
                    if (state == 1 || state == 3) state++
                    c[state]++
                } else when (state) {
                    0 -> if (c[0] > 0) { state = 1; c[1]++ }
                    2 -> { state = 3; c[3]++ }
                    4 -> {
                        if (ratioOk(c)) check(c, x, y)
                        c[0] = c[2]; c[1] = c[3]; c[2] = c[4]; c[3] = 1; c[4] = 0; state = 3
                    }
                    else -> c[state]++
                }
            }
            if (state == 4 && ratioOk(c)) check(c, bin.width, y)
            y += step
        }
        return found.filter { it.count >= 2 || found.size <= 3 }
    }

    private fun ratioOk(c: IntArray): Boolean {
        val total = c.sum()
        if (total < 7 || c.any { it == 0 }) return false
        val m = total / 7.0
        val v = m / 1.8
        return abs(m - c[0]) < v && abs(m - c[1]) < v && abs(3 * m - c[2]) < 3 * v && abs(m - c[3]) < v && abs(m - c[4]) < v
    }

    private fun check(c: IntArray, endX: Int, y: Int) {
        val total = c.sum()
        val cx = endX - c[4] - c[3] - c[2] / 2.0
        val cy = crossVertical(cx.toInt(), y, c[2], total) ?: return
        val cx2 = crossHorizontal(cx.toInt(), cy.toInt(), c[2], total) ?: return
        val module = total / 7.0
        for (f in found) {
            if (abs(f.x - cx2) <= module && abs(f.y - cy) <= module && abs(f.module - module) <= maxOf(1.0, f.module * 0.5)) {
                val n = f.count
                found[found.indexOf(f)] = QrDecoder.Finder((f.x * n + cx2) / (n + 1), (f.y * n + cy) / (n + 1), (f.module * n + module) / (n + 1), n + 1)
                return
            }
        }
        found += QrDecoder.Finder(cx2, cy, module)
    }

    /** Runs through (x, y) along one axis; returns the refined centre if the ratio holds there too. */
    private fun cross(along: (Int) -> Boolean?, start: Int, centreRun: Int, total: Int): Double? {
        val c = IntArray(5)
        var i = start
        // Centre run (dark), then light, then dark, going backwards.
        while (along(i) == true) { c[2]++; i-- }
        if (along(i) == null) return null
        while (along(i) == false && c[1] <= centreRun) { c[1]++; i-- }
        if (along(i) == null || c[1] > centreRun) return null
        while (along(i) == true && c[0] <= centreRun) { c[0]++; i-- }
        if (c[0] > centreRun) return null
        i = start + 1
        while (along(i) == true) { c[2]++; i++ }
        if (along(i) == null) return null
        while (along(i) == false && c[3] < centreRun) { c[3]++; i++ }
        if (along(i) == null || c[3] >= centreRun) return null
        while (along(i) == true && c[4] < centreRun) { c[4]++; i++ }
        if (c[4] >= centreRun) return null
        if (5 * abs(c.sum() - total) >= 2 * total) return null
        if (!ratioOk(c)) return null
        return i - c[4] - c[3] - c[2] / 2.0
    }

    private fun crossVertical(x: Int, y: Int, centreRun: Int, total: Int): Double? =
        cross({ yy -> if (yy in 0 until bin.height) dark(x, yy) else null }, y, centreRun * 2, total)

    private fun crossHorizontal(x: Int, y: Int, centreRun: Int, total: Int): Double? =
        cross({ xx -> if (xx in 0 until bin.width) dark(xx, y) else null }, x, centreRun * 2, total)
}

/** Finds the alignment pattern near an estimate: a dark module in a light ring (1:1:1 across). */
private class AlignmentFinder(private val bin: Binarized, private val inverted: Boolean, private val module: Double) {
    private fun dark(x: Int, y: Int) = bin.dark(x, y) != inverted

    fun find(ex: Double, ey: Double): Pair<Double, Double>? {
        for (allowance in intArrayOf(4, 8, 16)) {
            val r = (allowance * module).toInt()
            val x0 = maxOf(0, (ex - r).toInt()); val x1 = minOf(bin.width - 1, (ex + r).toInt())
            val y0 = maxOf(0, (ey - r).toInt()); val y1 = minOf(bin.height - 1, (ey + r).toInt())
            if (x1 - x0 < 3 * module || y1 - y0 < 3 * module) continue
            var best: Pair<Double, Double>? = null
            var bestD = Double.MAX_VALUE
            // From the middle row outwards: the nearest match wins.
            val mid = (y0 + y1) / 2
            for (k in 0..(y1 - y0)) {
                val y = mid + if (k % 2 == 0) k / 2 else -(k + 1) / 2
                if (y !in y0..y1) continue
                var x = x0
                while (x <= x1) {
                    // Light, dark, light runs of about one module each.
                    if (dark(x, y)) { x++; continue }
                    var a = 0; while (x + a <= x1 && !dark(x + a, y)) a++
                    var b = 0; while (x + a + b <= x1 && dark(x + a + b, y)) b++
                    var c = 0; while (x + a + b + c <= x1 && !dark(x + a + b + c, y)) c++
                    if (b > 0 && close(a) && close(b) && close(c)) {
                        val cx = x + a + b / 2.0
                        val cy = vertical(cx.toInt(), y)
                        if (cy != null) {
                            val d = hypot(cx - ex, cy - ey)
                            if (d < bestD) { bestD = d; best = cx to cy }
                        }
                    }
                    x += maxOf(1, a)
                }
                if (best != null && k > module * 2) return best
            }
            if (best != null) return best
        }
        return null
    }

    private fun close(run: Int) = abs(run - module) < module * 0.6

    private fun vertical(x: Int, y: Int): Double? {
        var up = 0; while (y - up - 1 >= 0 && dark(x, y - up - 1)) up++
        var down = 0; while (y + down + 1 < bin.height && dark(x, y + down + 1)) down++
        val run = up + down + 1
        if (!close(run)) return null
        val top = y - up; val bottom = y + down
        var lu = 0; while (top - lu - 1 >= 0 && !dark(x, top - lu - 1)) lu++
        var ld = 0; while (bottom + ld + 1 < bin.height && !dark(x, bottom + ld + 1)) ld++
        if (!close(lu) || !close(ld)) return null
        return (top + bottom) / 2.0 + 0.5
    }
}

/** A projective transform between two quadrilaterals (Heckbert's square-to-quad method). */
internal class Perspective(
    private val a11: Double, private val a21: Double, private val a31: Double,
    private val a12: Double, private val a22: Double, private val a32: Double,
    private val a13: Double, private val a23: Double, private val a33: Double,
) {
    fun map(x: Double, y: Double): Pair<Double, Double> {
        val d = a13 * x + a23 * y + a33
        return (a11 * x + a21 * y + a31) / d to (a12 * x + a22 * y + a32) / d
    }

    private fun times(o: Perspective) = Perspective(
        a11 * o.a11 + a21 * o.a12 + a31 * o.a13, a11 * o.a21 + a21 * o.a22 + a31 * o.a23, a11 * o.a31 + a21 * o.a32 + a31 * o.a33,
        a12 * o.a11 + a22 * o.a12 + a32 * o.a13, a12 * o.a21 + a22 * o.a22 + a32 * o.a23, a12 * o.a31 + a22 * o.a32 + a32 * o.a33,
        a13 * o.a11 + a23 * o.a12 + a33 * o.a13, a13 * o.a21 + a23 * o.a22 + a33 * o.a23, a13 * o.a31 + a23 * o.a32 + a33 * o.a33,
    )

    private fun adjoint() = Perspective(
        a22 * a33 - a23 * a32, a23 * a31 - a21 * a33, a21 * a32 - a22 * a31,
        a13 * a32 - a12 * a33, a11 * a33 - a13 * a31, a12 * a31 - a11 * a32,
        a12 * a23 - a13 * a22, a13 * a21 - a11 * a23, a11 * a22 - a12 * a21,
    )

    companion object {
        /** Maps (x0,y0)…(x3,y3) to (u0,v0)…(u3,v3); both in order top-left, top-right, bottom-right, bottom-left. */
        fun quadToQuad(
            x0: Double, y0: Double, x1: Double, y1: Double, x2: Double, y2: Double, x3: Double, y3: Double,
            u0: Double, v0: Double, u1: Double, v1: Double, u2: Double, v2: Double, u3: Double, v3: Double,
        ): Perspective = squareToQuad(u0, v0, u1, v1, u2, v2, u3, v3).times(squareToQuad(x0, y0, x1, y1, x2, y2, x3, y3).adjoint())

        private fun squareToQuad(x0: Double, y0: Double, x1: Double, y1: Double, x2: Double, y2: Double, x3: Double, y3: Double): Perspective {
            val dx3 = x0 - x1 + x2 - x3
            val dy3 = y0 - y1 + y2 - y3
            if (dx3 == 0.0 && dy3 == 0.0) return Perspective(x1 - x0, x2 - x1, x0, y1 - y0, y2 - y1, y0, 0.0, 0.0, 1.0)
            val dx1 = x1 - x2; val dx2 = x3 - x2; val dy1 = y1 - y2; val dy2 = y3 - y2
            val den = dx1 * dy2 - dx2 * dy1
            val a13 = (dx3 * dy2 - dx2 * dy3) / den
            val a23 = (dx1 * dy3 - dx3 * dy1) / den
            return Perspective(
                x1 - x0 + a13 * x1, x3 - x0 + a23 * x3, x0,
                y1 - y0 + a13 * y1, y3 - y0 + a23 * y3, y0,
                a13, a23, 1.0,
            )
        }
    }
}

/** The data segments of a decoded symbol. */
internal object Segments {
    private const val ALNUM = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ $%*+-./:"

    fun parse(data: IntArray, version: Int): String? {
        val r = BitReader(data)
        val out = StringBuilder()
        var charset: java.nio.charset.Charset? = null
        val group = when { version <= 9 -> 0; version <= 26 -> 1; else -> 2 }
        while (r.left >= 4) {
            when (r.read(4)) {
                0 -> break
                1 -> { // numeric
                    var n = r.read(intArrayOf(10, 12, 14)[group])
                    while (n >= 3) { out.append("%03d".format(r.read(10).also { if (it > 999) return null })); n -= 3 }
                    if (n == 2) out.append("%02d".format(r.read(7).also { if (it > 99) return null }))
                    if (n == 1) out.append(r.read(4).also { if (it > 9) return null })
                }
                2 -> { // alphanumeric
                    var n = r.read(intArrayOf(9, 11, 13)[group])
                    while (n >= 2) { val v = r.read(11); if (v >= 45 * 45) return null; out.append(ALNUM[v / 45]).append(ALNUM[v % 45]); n -= 2 }
                    if (n == 1) out.append(ALNUM.getOrNull(r.read(6)) ?: return null)
                }
                4 -> { // bytes
                    val n = r.read(intArrayOf(8, 16, 16)[group])
                    if (r.left < n * 8) return null
                    val bytes = ByteArray(n) { r.read(8).toByte() }
                    out.append(String(bytes, charset ?: guess(bytes)))
                }
                7 -> { // ECI
                    val first = r.read(8)
                    val value = when {
                        first and 0x80 == 0 -> first
                        first and 0xC0 == 0x80 -> ((first and 0x3F) shl 8) or r.read(8)
                        else -> ((first and 0x1F) shl 16) or r.read(16)
                    }
                    charset = eci(value)
                }
                8 -> { // kanji (Shift JIS, 13 bits each)
                    val n = r.read(intArrayOf(8, 10, 12)[group])
                    val bytes = ByteArray(n * 2)
                    for (i in 0 until n) {
                        val v = r.read(13)
                        var a = (v / 0xC0 shl 8) or (v % 0xC0)
                        a += if (a < 0x1F00) 0x8140 else 0xC140
                        bytes[2 * i] = (a shr 8).toByte(); bytes[2 * i + 1] = a.toByte()
                    }
                    out.append(String(bytes, runCatching { charset("Shift_JIS") }.getOrDefault(Charsets.ISO_8859_1)))
                }
                3 -> r.read(16) // structured append: position and parity, the text is per symbol
                5 -> Unit // FNC1 first position
                9 -> r.read(8) // FNC1 second position
                else -> return null
            }
        }
        return out.toString()
    }

    private fun eci(v: Int): java.nio.charset.Charset? = when (v) {
        26 -> Charsets.UTF_8
        1, 3 -> Charsets.ISO_8859_1
        20 -> runCatching { charset("Shift_JIS") }.getOrNull()
        22 -> runCatching { charset("windows-1251") }.getOrNull()
        27, 170 -> Charsets.US_ASCII
        else -> null
    }

    /** No ECI: UTF-8 if the bytes are valid UTF-8 (what nearly every encoder writes), else ISO-8859-1. */
    private fun guess(b: ByteArray): java.nio.charset.Charset {
        val dec = Charsets.UTF_8.newDecoder()
        return if (runCatching { dec.decode(java.nio.ByteBuffer.wrap(b)) }.isSuccess) Charsets.UTF_8 else Charsets.ISO_8859_1
    }

    private class BitReader(private val data: IntArray) {
        private var pos = 0
        val left: Int get() = data.size * 8 - pos
        fun read(n: Int): Int {
            if (n > left) { pos = data.size * 8; return 0 }
            var v = 0
            repeat(n) { v = (v shl 1) or ((data[pos / 8] shr (7 - pos % 8)) and 1); pos++ }
            return v
        }
    }
}
