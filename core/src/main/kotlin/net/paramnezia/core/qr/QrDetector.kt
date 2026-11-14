/*
 * Ported from ZXing (com.google.zxing.common: BitMatrix, HybridBinarizer, GlobalHistogramBinarizer,
 * PerspectiveTransform, DefaultGridSampler; com.google.zxing.qrcode.detector: FinderPatternFinder,
 * FinderPattern, AlignmentPatternFinder, AlignmentPattern, Detector; com.google.zxing.multi.qrcode
 * .detector.MultiFinderPatternFinder; ResultPoint.orderBestPatterns), Copyright 2007-2009 ZXing
 * authors, licensed under the Apache License, Version 2.0 (http://www.apache.org/licenses/LICENSE-2.0).
 *
 * Changes: Kotlin, no hints or callbacks, "not found" is null instead of an exception (camera frames
 * are mostly codeless, and exceptions cost); double precision in the perspective transform;
 * the binarized image can be inverted (light codes on dark backgrounds); the detector can sample
 * a neighbouring dimension when the estimated one does not decode (a tilted code whose module size
 * was misjudged by one step); the finder scan always uses the fine row step of TRY_HARDER, and
 * the multi-code finder keeps unconfirmed candidates when fewer than three were confirmed (small
 * codes in a series seen from afar).
 */
package net.paramnezia.core.qr

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** A bit image, one bit per pixel, packed in 32-bit words per row (true = dark). */
internal class BitMatrix(val width: Int, val height: Int) {
    private val rowSize = (width + 31) / 32
    private val bits = IntArray(rowSize * height)

    operator fun get(x: Int, y: Int): Boolean = (bits[y * rowSize + (x shr 5)] ushr (x and 31)) and 1 != 0
    fun set(x: Int, y: Int) { val i = y * rowSize + (x shr 5); bits[i] = bits[i] or (1 shl (x and 31)) }
    fun flip(x: Int, y: Int) { val i = y * rowSize + (x shr 5); bits[i] = bits[i] xor (1 shl (x and 31)) }

    /** Every pixel flipped (padding bits too, harmlessly: they are never read). */
    fun inverted(): BitMatrix = BitMatrix(width, height).also { m -> for (i in bits.indices) m.bits[i] = bits[i].inv() }
}

internal object Binarizer {
    private const val BLOCK_SIZE_POWER = 3
    private const val BLOCK_SIZE = 1 shl BLOCK_SIZE_POWER
    private const val BLOCK_SIZE_MASK = BLOCK_SIZE - 1
    private const val MINIMUM_DIMENSION = BLOCK_SIZE * 5
    private const val MIN_DYNAMIC_RANGE = 24

    /**
     * HybridBinarizer: a threshold per 8×8 block, the average of the 5×5 blocks around it, which
     * copes with shadows and gradients across the picture. Small images fall back to the global
     * histogram threshold.
     */
    fun hybrid(img: LumaImage): BitMatrix? {
        val width = img.width; val height = img.height
        if (width < MINIMUM_DIMENSION || height < MINIMUM_DIMENSION) return global(img)
        val lum = img.luma
        var subWidth = width shr BLOCK_SIZE_POWER
        if (width and BLOCK_SIZE_MASK != 0) subWidth++
        var subHeight = height shr BLOCK_SIZE_POWER
        if (height and BLOCK_SIZE_MASK != 0) subHeight++
        val blackPoints = blackPoints(lum, subWidth, subHeight, width, height)
        val matrix = BitMatrix(width, height)
        val maxYOffset = height - BLOCK_SIZE
        val maxXOffset = width - BLOCK_SIZE
        for (y in 0 until subHeight) {
            val yoffset = min(y shl BLOCK_SIZE_POWER, maxYOffset)
            val top = cap(y, subHeight - 3)
            for (x in 0 until subWidth) {
                val xoffset = min(x shl BLOCK_SIZE_POWER, maxXOffset)
                val left = cap(x, subWidth - 3)
                var sum = 0
                for (z in -2..2) {
                    val row = blackPoints[top + z]
                    sum += row[left - 2] + row[left - 1] + row[left] + row[left + 1] + row[left + 2]
                }
                val average = sum / 25
                var offset = yoffset * width + xoffset
                for (yy in 0 until BLOCK_SIZE) {
                    for (xx in 0 until BLOCK_SIZE) if ((lum[offset + xx].toInt() and 0xFF) <= average) matrix.set(xoffset + xx, yoffset + yy)
                    offset += width
                }
            }
        }
        return matrix
    }

    private fun cap(value: Int, max: Int) = if (value < 2) 2 else min(value, max)

    private fun blackPoints(lum: ByteArray, subWidth: Int, subHeight: Int, width: Int, height: Int): Array<IntArray> {
        val maxYOffset = height - BLOCK_SIZE
        val maxXOffset = width - BLOCK_SIZE
        val blackPoints = Array(subHeight) { IntArray(subWidth) }
        for (y in 0 until subHeight) {
            val yoffset = min(y shl BLOCK_SIZE_POWER, maxYOffset)
            for (x in 0 until subWidth) {
                val xoffset = min(x shl BLOCK_SIZE_POWER, maxXOffset)
                var sum = 0
                var mn = 0xFF
                var mx = 0
                var yy = 0
                var offset = yoffset * width + xoffset
                while (yy < BLOCK_SIZE) {
                    for (xx in 0 until BLOCK_SIZE) {
                        val pixel = lum[offset + xx].toInt() and 0xFF
                        sum += pixel
                        if (pixel < mn) mn = pixel
                        if (pixel > mx) mx = pixel
                    }
                    // Short-circuit min/max tests once dynamic range is met.
                    if (mx - mn > MIN_DYNAMIC_RANGE) {
                        yy++; offset += width
                        while (yy < BLOCK_SIZE) {
                            for (xx in 0 until BLOCK_SIZE) sum += lum[offset + xx].toInt() and 0xFF
                            yy++; offset += width
                        }
                        break
                    }
                    yy++; offset += width
                }
                var average = sum shr (BLOCK_SIZE_POWER * 2)
                if (mx - mn <= MIN_DYNAMIC_RANGE) {
                    // A flat block: assume it is background, unless its neighbours say otherwise.
                    average = mn / 2
                    if (y > 0 && x > 0) {
                        val neighbours = (blackPoints[y - 1][x] + 2 * blackPoints[y][x - 1] + blackPoints[y - 1][x - 1]) / 4
                        if (mn < neighbours) average = neighbours
                    }
                }
                blackPoints[y][x] = average
            }
        }
        return blackPoints
    }

    private const val LUMINANCE_SHIFT = 3
    private const val LUMINANCE_BUCKETS = 32

    /** GlobalHistogramBinarizer: one threshold from a histogram of four sample rows. */
    fun global(img: LumaImage): BitMatrix? {
        val width = img.width; val height = img.height
        val buckets = IntArray(LUMINANCE_BUCKETS)
        for (y in 1 until 5) {
            val row = height * y / 5
            for (x in width / 5 until width * 4 / 5) buckets[img.at(x, row) shr LUMINANCE_SHIFT]++
        }
        val blackPoint = estimateBlackPoint(buckets) ?: return null
        val matrix = BitMatrix(width, height)
        for (y in 0 until height) for (x in 0 until width) if (img.at(x, y) < blackPoint) matrix.set(x, y)
        return matrix
    }

    private fun estimateBlackPoint(buckets: IntArray): Int? {
        val n = buckets.size
        var maxBucketCount = 0
        var firstPeak = 0
        var firstPeakSize = 0
        for (x in 0 until n) {
            if (buckets[x] > firstPeakSize) { firstPeak = x; firstPeakSize = buckets[x] }
            if (buckets[x] > maxBucketCount) maxBucketCount = buckets[x]
        }
        var secondPeak = 0
        var secondPeakScore = 0
        for (x in 0 until n) {
            val d = x - firstPeak
            val score = buckets[x] * d * d
            if (score > secondPeakScore) { secondPeak = x; secondPeakScore = score }
        }
        if (firstPeak > secondPeak) { val t = firstPeak; firstPeak = secondPeak; secondPeak = t }
        if (secondPeak - firstPeak <= n / 16) return null // too little contrast
        var bestValley = secondPeak - 1
        var bestValleyScore = -1
        for (x in secondPeak - 1 downTo firstPeak + 1) {
            val fromFirst = x - firstPeak
            val score = fromFirst * fromFirst * (secondPeak - x) * (maxBucketCount - buckets[x])
            if (score > bestValleyScore) { bestValley = x; bestValleyScore = score }
        }
        return bestValley shl LUMINANCE_SHIFT
    }
}

internal open class Point(val x: Double, val y: Double) {
    companion object {
        fun distance(a: Point, b: Point): Double { val dx = a.x - b.x; val dy = a.y - b.y; return sqrt(dx * dx + dy * dy) }
        private fun crossProductZ(a: Point, b: Point, c: Point) = (c.x - b.x) * (a.y - b.y) - (c.y - b.y) * (a.x - b.x)

        /** Orders three finder centres as bottom-left, top-left, top-right (ResultPoint.orderBestPatterns). */
        fun <T : Point> order(p: List<T>): List<T> {
            val d01 = distance(p[0], p[1]); val d12 = distance(p[1], p[2]); val d02 = distance(p[0], p[2])
            var a: T; val b: T; var c: T
            if (d12 >= d01 && d12 >= d02) { b = p[0]; a = p[1]; c = p[2] }
            else if (d02 >= d12 && d02 >= d01) { b = p[1]; a = p[0]; c = p[2] }
            else { b = p[2]; a = p[0]; c = p[1] }
            if (crossProductZ(a, b, c) < 0.0) { val t = a; a = c; c = t }
            return listOf(a, b, c)
        }
    }
}

internal class FinderPattern(x: Double, y: Double, val moduleSize: Double, val count: Int = 1) : Point(x, y) {
    fun aboutEquals(moduleSize: Double, i: Double, j: Double): Boolean {
        if (abs(i - y) <= moduleSize && abs(j - x) <= moduleSize) {
            val diff = abs(moduleSize - this.moduleSize)
            return diff <= 1.0 || diff <= this.moduleSize
        }
        return false
    }

    fun combine(i: Double, j: Double, newModuleSize: Double): FinderPattern {
        val n = count + 1
        return FinderPattern((count * x + j) / n, (count * y + i) / n, (count * moduleSize + newModuleSize) / n, n)
    }
}

/** Three finder centres of one symbol. */
internal class FinderInfo(val bottomLeft: FinderPattern, val topLeft: FinderPattern, val topRight: FinderPattern) {
    companion object {
        fun of(patterns: List<FinderPattern>): FinderInfo = Point.order(patterns).let { FinderInfo(it[0], it[1], it[2]) }
    }
}

/** FinderPatternFinder and MultiFinderPatternFinder: finder patterns, by their 1:1:3:1:1 runs. */
internal class FinderPatternFinder(private val image: BitMatrix) {
    private val possibleCenters = ArrayList<FinderPattern>()
    private var hasSkipped = false
    private val crossCheckStateCount = IntArray(5)

    /** The most plausible single symbol (FinderPatternFinder.find with TRY_HARDER). */
    fun find(): FinderInfo? {
        val maxI = image.height
        val maxJ = image.width
        var iSkip = MIN_SKIP
        var done = false
        val stateCount = IntArray(5)
        var i = iSkip - 1
        while (i < maxI && !done) {
            stateCount.fill(0)
            var currentState = 0
            var j = 0
            while (j < maxJ) {
                if (image[j, i]) {
                    if (currentState and 1 == 1) currentState++
                    stateCount[currentState]++
                } else if (currentState and 1 == 0) {
                    if (currentState == 4) {
                        if (foundPatternCross(stateCount)) {
                            val confirmed = handlePossibleCenter(stateCount, i, j)
                            if (confirmed) {
                                // Start examining every other line: finders are found.
                                iSkip = 2
                                if (hasSkipped) done = haveMultiplyConfirmedCenters()
                                else {
                                    val rowSkip = findRowSkip()
                                    if (rowSkip > stateCount[2]) {
                                        // Skip rows between row of lower confirmed center and top of presumed third.
                                        i += rowSkip - stateCount[2] - iSkip
                                        j = maxJ - 1
                                    }
                                }
                            } else {
                                shiftCounts2(stateCount)
                                currentState = 3
                                j++
                                continue
                            }
                            currentState = 0
                            stateCount.fill(0)
                        } else {
                            shiftCounts2(stateCount)
                            currentState = 3
                        }
                    } else stateCount[++currentState]++
                } else stateCount[currentState]++
                j++
            }
            if (foundPatternCross(stateCount) && handlePossibleCenter(stateCount, i, maxJ)) {
                iSkip = stateCount[0]
                if (hasSkipped) done = haveMultiplyConfirmedCenters()
            }
            i += iSkip
        }
        return selectBestPatterns()?.let { FinderInfo.of(it) }
    }

    /** Every plausible symbol (MultiFinderPatternFinder.findMulti with TRY_HARDER). */
    fun findMulti(): List<FinderInfo> {
        val maxI = image.height
        val maxJ = image.width
        val stateCount = IntArray(5)
        var i = MIN_SKIP - 1
        while (i < maxI) {
            stateCount.fill(0)
            var currentState = 0
            for (j in 0 until maxJ) {
                if (image[j, i]) {
                    if (currentState and 1 == 1) currentState++
                    stateCount[currentState]++
                } else if (currentState and 1 == 0) {
                    if (currentState == 4) {
                        if (foundPatternCross(stateCount) && handlePossibleCenter(stateCount, i, j)) {
                            currentState = 0
                            stateCount.fill(0)
                        } else {
                            shiftCounts2(stateCount)
                            currentState = 3
                        }
                    } else stateCount[++currentState]++
                } else stateCount[currentState]++
            }
            if (foundPatternCross(stateCount)) handlePossibleCenter(stateCount, i, maxJ)
            i += MIN_SKIP
        }
        return selectMultipleBestPatterns().map { FinderInfo.of(it) }
    }

    private fun shiftCounts2(s: IntArray) { s[0] = s[2]; s[1] = s[3]; s[2] = s[4]; s[3] = 1; s[4] = 0 }

    private fun centerFromEnd(s: IntArray, end: Int): Double = (end - s[4] - s[3]) - s[2] / 2.0

    private fun crossCheckDiagonal(centerI: Int, centerJ: Int): Boolean {
        val s = crossCheckStateCount.also { it.fill(0) }
        var i = 0
        while (centerI >= i && centerJ >= i && image[centerJ - i, centerI - i]) { s[2]++; i++ }
        if (s[2] == 0) return false
        while (centerI >= i && centerJ >= i && !image[centerJ - i, centerI - i]) { s[1]++; i++ }
        if (s[1] == 0) return false
        while (centerI >= i && centerJ >= i && image[centerJ - i, centerI - i]) { s[0]++; i++ }
        if (s[0] == 0) return false
        val maxI = image.height; val maxJ = image.width
        i = 1
        while (centerI + i < maxI && centerJ + i < maxJ && image[centerJ + i, centerI + i]) { s[2]++; i++ }
        while (centerI + i < maxI && centerJ + i < maxJ && !image[centerJ + i, centerI + i]) { s[3]++; i++ }
        if (s[3] == 0) return false
        while (centerI + i < maxI && centerJ + i < maxJ && image[centerJ + i, centerI + i]) { s[4]++; i++ }
        if (s[4] == 0) return false
        return foundPatternDiagonal(s)
    }

    /** Runs through (fixed, start) along one axis: [dark] reads the pixel at a coordinate on it. */
    private inline fun crossCheck(start: Int, limit: Int, maxCount: Int, originalTotal: Int, tolerance: Int, dark: (Int) -> Boolean): Double {
        val s = crossCheckStateCount.also { it.fill(0) }
        var i = start
        while (i >= 0 && dark(i)) { s[2]++; i-- }
        if (i < 0) return Double.NaN
        while (i >= 0 && !dark(i) && s[1] <= maxCount) { s[1]++; i-- }
        if (i < 0 || s[1] > maxCount) return Double.NaN
        while (i >= 0 && dark(i) && s[0] <= maxCount) { s[0]++; i-- }
        if (s[0] > maxCount) return Double.NaN
        i = start + 1
        while (i < limit && dark(i)) { s[2]++; i++ }
        if (i == limit) return Double.NaN
        while (i < limit && !dark(i) && s[3] < maxCount) { s[3]++; i++ }
        if (i == limit || s[3] >= maxCount) return Double.NaN
        while (i < limit && dark(i) && s[4] < maxCount) { s[4]++; i++ }
        if (s[4] >= maxCount) return Double.NaN
        // A run much longer or shorter than the first one seen: not the same pattern.
        val total = s[0] + s[1] + s[2] + s[3] + s[4]
        if (5 * abs(total - originalTotal) >= tolerance * originalTotal) return Double.NaN
        return if (foundPatternCross(s)) centerFromEnd(s, i) else Double.NaN
    }

    private fun handlePossibleCenter(s: IntArray, i: Int, j: Int): Boolean {
        val total = s[0] + s[1] + s[2] + s[3] + s[4]
        var centerJ = centerFromEnd(s, j)
        val cj = centerJ.toInt()
        val centerI = crossCheck(i, image.height, s[2], total, 2) { image[cj, it] }
        if (centerI.isNaN()) return false
        val ci = centerI.toInt()
        centerJ = crossCheck(cj, image.width, s[2], total, 1) { image[it, ci] }
        if (centerJ.isNaN() || !crossCheckDiagonal(ci, centerJ.toInt())) return false
        val estimatedModuleSize = total / 7.0
        val index = possibleCenters.indexOfFirst { it.aboutEquals(estimatedModuleSize, centerI, centerJ) }
        if (index >= 0) possibleCenters[index] = possibleCenters[index].combine(centerI, centerJ, estimatedModuleSize)
        else possibleCenters += FinderPattern(centerJ, centerI, estimatedModuleSize)
        return true
    }

    /** How far down to skip once two finders are confirmed: to where the third should start. */
    private fun findRowSkip(): Int {
        if (possibleCenters.size <= 1) return 0
        var first: FinderPattern? = null
        for (center in possibleCenters) if (center.count >= CENTER_QUORUM) {
            if (first == null) first = center
            else {
                hasSkipped = true
                return ((abs(first.x - center.x) - abs(first.y - center.y)) / 2).toInt()
            }
        }
        return 0
    }

    private fun haveMultiplyConfirmedCenters(): Boolean {
        var confirmed = 0
        var totalModuleSize = 0.0
        for (p in possibleCenters) if (p.count >= CENTER_QUORUM) { confirmed++; totalModuleSize += p.moduleSize }
        if (confirmed < 3) return false
        val average = totalModuleSize / possibleCenters.size
        val totalDeviation = possibleCenters.sumOf { abs(it.moduleSize - average) }
        return totalDeviation <= 0.05 * totalModuleSize
    }

    private fun squaredDistance(a: FinderPattern, b: FinderPattern): Double { val x = a.x - b.x; val y = a.y - b.y; return x * x + y * y }

    /** The three confirmed centres closest to a right isosceles triangle with similar module sizes. */
    private fun selectBestPatterns(): List<FinderPattern>? {
        if (possibleCenters.size < 3) return null
        val centers = possibleCenters.filter { it.count >= CENTER_QUORUM }.sortedBy { it.moduleSize }
        var distortion = Double.MAX_VALUE
        var best: List<FinderPattern>? = null
        for (i in 0 until centers.size - 2) {
            val fpi = centers[i]
            val minModuleSize = fpi.moduleSize
            for (j in i + 1 until centers.size - 1) {
                val fpj = centers[j]
                val squares0 = squaredDistance(fpi, fpj)
                for (k in j + 1 until centers.size) {
                    val fpk = centers[k]
                    if (fpk.moduleSize > minModuleSize * 1.4) continue
                    // Sort the three sides: a ≤ b ≤ c.
                    val sides = doubleArrayOf(squares0, squaredDistance(fpj, fpk), squaredDistance(fpi, fpk)).also { it.sort() }
                    val (a, b, c) = Triple(sides[0], sides[1], sides[2])
                    // c = 2a = 2b for a right isosceles triangle.
                    val d = abs(c - 2 * b) + abs(c - 2 * a)
                    if (d < distortion) { distortion = d; best = listOf(fpi, fpj, fpk) }
                }
            }
        }
        return best
    }

    private fun selectMultipleBestPatterns(): List<List<FinderPattern>> {
        var centers = possibleCenters.filter { it.count >= 2 }
        // Small codes far away may be seen on one line only: then use what there is.
        if (centers.size < 3) centers = possibleCenters.toList()
        val size = centers.size
        if (size < 3) return emptyList()
        if (size == 3) return listOf(centers)
        val sorted = centers.sortedByDescending { it.moduleSize }
        val results = ArrayList<List<FinderPattern>>()
        for (i1 in 0 until size - 2) {
            val p1 = sorted[i1]
            for (i2 in i1 + 1 until size - 1) {
                val p2 = sorted[i2]
                val vModSize12 = (p1.moduleSize - p2.moduleSize) / min(p1.moduleSize, p2.moduleSize)
                val vModSize12A = abs(p1.moduleSize - p2.moduleSize)
                if (vModSize12A > DIFF_MODSIZE_CUTOFF && vModSize12 >= DIFF_MODSIZE_CUTOFF_PERCENT) break
                for (i3 in i2 + 1 until size) {
                    val p3 = sorted[i3]
                    val vModSize23 = (p2.moduleSize - p3.moduleSize) / min(p2.moduleSize, p3.moduleSize)
                    val vModSize23A = abs(p2.moduleSize - p3.moduleSize)
                    if (vModSize23A > DIFF_MODSIZE_CUTOFF && vModSize23 >= DIFF_MODSIZE_CUTOFF_PERCENT) break
                    val info = FinderInfo.of(listOf(p1, p2, p3))
                    val dA = Point.distance(info.topLeft, info.bottomLeft)
                    val dC = Point.distance(info.topRight, info.bottomLeft)
                    val dB = Point.distance(info.topLeft, info.topRight)
                    val estimatedModuleCount = (dA + dB) / (p1.moduleSize * 2.0)
                    if (estimatedModuleCount > MAX_MODULE_COUNT_PER_EDGE || estimatedModuleCount < MIN_MODULE_COUNT_PER_EDGE) continue
                    // The two sides at the top-left corner are about equal…
                    if (abs((dA - dB) / min(dA, dB)) >= 0.1) continue
                    // …and the third is their hypotenuse.
                    val dCpy = sqrt(dA * dA + dB * dB)
                    if (abs((dC - dCpy) / min(dC, dCpy)) >= 0.1) continue
                    results += listOf(p1, p2, p3)
                }
            }
        }
        return results
    }

    companion object {
        private const val CENTER_QUORUM = 2
        private const val MIN_SKIP = 3
        private const val MAX_MODULE_COUNT_PER_EDGE = 180.0
        private const val MIN_MODULE_COUNT_PER_EDGE = 9.0
        private const val DIFF_MODSIZE_CUTOFF_PERCENT = 0.05
        private const val DIFF_MODSIZE_CUTOFF = 0.5

        fun foundPatternCross(s: IntArray): Boolean = ratios(s, 2.0)
        private fun foundPatternDiagonal(s: IntArray): Boolean = ratios(s, 1.333)

        private fun ratios(s: IntArray, divisor: Double): Boolean {
            var total = 0
            for (c in s) { if (c == 0) return false; total += c }
            if (total < 7) return false
            val moduleSize = total / 7.0
            val maxVariance = moduleSize / divisor
            return abs(moduleSize - s[0]) < maxVariance && abs(moduleSize - s[1]) < maxVariance &&
                abs(3.0 * moduleSize - s[2]) < 3 * maxVariance &&
                abs(moduleSize - s[3]) < maxVariance && abs(moduleSize - s[4]) < maxVariance
        }
    }
}

/** AlignmentPatternFinder: the 1:1:1 pattern near where the bottom-right alignment pattern should be. */
internal class AlignmentPatternFinder(
    private val image: BitMatrix, private val startX: Int, private val startY: Int,
    private val width: Int, private val height: Int, private val moduleSize: Double,
) {
    private class Candidate(x: Double, y: Double, val moduleSize: Double) : Point(x, y)
    private val possibleCenters = ArrayList<Candidate>(5)
    private val crossCheckStateCount = IntArray(3)

    fun find(): Point? {
        val maxJ = startX + width
        val middleI = startY + height / 2
        val stateCount = IntArray(3)
        for (iGen in 0 until height) {
            // Search from middle outwards.
            val i = middleI + if (iGen and 1 == 0) (iGen + 1) / 2 else -((iGen + 1) / 2)
            stateCount.fill(0)
            var j = startX
            // Burn off leading white pixels before anything else.
            while (j < maxJ && !image[j, i]) j++
            var currentState = 0
            while (j < maxJ) {
                if (image[j, i]) {
                    if (currentState == 1) stateCount[1]++
                    else if (currentState == 2) {
                        if (foundPatternCross(stateCount)) handlePossibleCenter(stateCount, i, j)?.let { return it }
                        stateCount[0] = stateCount[2]; stateCount[1] = 1; stateCount[2] = 0
                        currentState = 1
                    } else stateCount[++currentState]++
                } else {
                    if (currentState == 1) currentState++
                    stateCount[currentState]++
                }
                j++
            }
            if (foundPatternCross(stateCount)) handlePossibleCenter(stateCount, i, maxJ)?.let { return it }
        }
        // Nothing confirmed twice: the best guess is the first one seen.
        return possibleCenters.firstOrNull()
    }

    private fun centerFromEnd(s: IntArray, end: Int) = (end - s[2]) - s[1] / 2.0

    private fun foundPatternCross(s: IntArray): Boolean {
        val maxVariance = moduleSize / 2.0
        for (i in 0 until 3) if (abs(moduleSize - s[i]) >= maxVariance) return false
        return true
    }

    private fun crossCheckVertical(startI: Int, centerJ: Int, maxCount: Int, originalTotal: Int): Double {
        val maxI = image.height
        val s = crossCheckStateCount.also { it.fill(0) }
        var i = startI
        while (i >= 0 && image[centerJ, i] && s[1] <= maxCount) { s[1]++; i-- }
        if (i < 0 || s[1] > maxCount) return Double.NaN
        while (i >= 0 && !image[centerJ, i] && s[0] <= maxCount) { s[0]++; i-- }
        if (s[0] > maxCount) return Double.NaN
        i = startI + 1
        while (i < maxI && image[centerJ, i] && s[1] <= maxCount) { s[1]++; i++ }
        if (i == maxI || s[1] > maxCount) return Double.NaN
        while (i < maxI && !image[centerJ, i] && s[2] <= maxCount) { s[2]++; i++ }
        if (s[2] > maxCount) return Double.NaN
        val total = s[0] + s[1] + s[2]
        if (5 * abs(total - originalTotal) >= 2 * originalTotal) return Double.NaN
        return if (foundPatternCross(s)) centerFromEnd(s, i) else Double.NaN
    }

    private fun handlePossibleCenter(s: IntArray, i: Int, j: Int): Point? {
        val total = s[0] + s[1] + s[2]
        val centerJ = centerFromEnd(s, j)
        val centerI = crossCheckVertical(i, centerJ.toInt(), 2 * s[1], total)
        if (centerI.isNaN()) return null
        val estimatedModuleSize = total / 3.0
        for (c in possibleCenters) {
            if (abs(centerI - c.y) <= estimatedModuleSize && abs(centerJ - c.x) <= estimatedModuleSize) {
                val diff = abs(estimatedModuleSize - c.moduleSize)
                if (diff <= 1.0 || diff <= c.moduleSize) return Point((c.x + centerJ) / 2, (c.y + centerI) / 2)
            }
        }
        possibleCenters += Candidate(centerJ, centerI, estimatedModuleSize)
        return null
    }
}

/** A plane projective transform (PerspectiveTransform), in doubles. */
internal class PerspectiveTransform private constructor(
    private val a11: Double, private val a21: Double, private val a31: Double,
    private val a12: Double, private val a22: Double, private val a32: Double,
    private val a13: Double, private val a23: Double, private val a33: Double,
) {
    fun transform(points: DoubleArray) {
        var i = 0
        while (i < points.size - 1) {
            val x = points[i]; val y = points[i + 1]
            val d = a13 * x + a23 * y + a33
            points[i] = (a11 * x + a21 * y + a31) / d
            points[i + 1] = (a12 * x + a22 * y + a32) / d
            i += 2
        }
    }

    private fun adjoint() = PerspectiveTransform(
        a22 * a33 - a23 * a32, a23 * a31 - a21 * a33, a21 * a32 - a22 * a31,
        a13 * a32 - a12 * a33, a11 * a33 - a13 * a31, a12 * a31 - a11 * a32,
        a12 * a23 - a13 * a22, a13 * a21 - a11 * a23, a11 * a22 - a12 * a21,
    )

    private fun times(o: PerspectiveTransform) = PerspectiveTransform(
        a11 * o.a11 + a21 * o.a12 + a31 * o.a13, a11 * o.a21 + a21 * o.a22 + a31 * o.a23, a11 * o.a31 + a21 * o.a32 + a31 * o.a33,
        a12 * o.a11 + a22 * o.a12 + a32 * o.a13, a12 * o.a21 + a22 * o.a22 + a32 * o.a23, a12 * o.a31 + a22 * o.a32 + a32 * o.a33,
        a13 * o.a11 + a23 * o.a12 + a33 * o.a13, a13 * o.a21 + a23 * o.a22 + a33 * o.a23, a13 * o.a31 + a23 * o.a32 + a33 * o.a33,
    )

    companion object {
        fun quadToQuad(
            x0: Double, y0: Double, x1: Double, y1: Double, x2: Double, y2: Double, x3: Double, y3: Double,
            x0p: Double, y0p: Double, x1p: Double, y1p: Double, x2p: Double, y2p: Double, x3p: Double, y3p: Double,
        ): PerspectiveTransform = squareToQuad(x0p, y0p, x1p, y1p, x2p, y2p, x3p, y3p).times(squareToQuad(x0, y0, x1, y1, x2, y2, x3, y3).adjoint())

        private fun squareToQuad(x0: Double, y0: Double, x1: Double, y1: Double, x2: Double, y2: Double, x3: Double, y3: Double): PerspectiveTransform {
            val dx3 = x0 - x1 + x2 - x3
            val dy3 = y0 - y1 + y2 - y3
            if (dx3 == 0.0 && dy3 == 0.0) {
                // Affine.
                return PerspectiveTransform(x1 - x0, x2 - x1, x0, y1 - y0, y2 - y1, y0, 0.0, 0.0, 1.0)
            }
            val dx1 = x1 - x2; val dx2 = x3 - x2; val dy1 = y1 - y2; val dy2 = y3 - y2
            val denominator = dx1 * dy2 - dx2 * dy1
            val a13 = (dx3 * dy2 - dx2 * dy3) / denominator
            val a23 = (dx1 * dy3 - dx3 * dy1) / denominator
            return PerspectiveTransform(x1 - x0 + a13 * x1, x3 - x0 + a23 * x3, x0, y1 - y0 + a13 * y1, y3 - y0 + a23 * y3, y0, a13, a23, 1.0)
        }
    }
}

/** Detector: from three finder centres to the grid of modules. */
internal class Detector(private val image: BitMatrix) {
    /** The symbol's modules, sampled; [dimensionDelta] tries a neighbouring size (±4). Null if it does not fit. */
    fun process(info: FinderInfo, dimensionDelta: Int = 0): QrMatrix? {
        val topLeft = info.topLeft; val topRight = info.topRight; val bottomLeft = info.bottomLeft
        val moduleSize = (moduleSizeOneWay(topLeft, topRight) + moduleSizeOneWay(topLeft, bottomLeft)) / 2.0
        if (moduleSize < 1.0 || moduleSize.isNaN()) return null
        val dimension = computeDimension(topLeft, topRight, bottomLeft, moduleSize) + dimensionDelta
        val version = QrVersion.forSize(dimension) ?: return null
        val modulesBetweenFPCenters = version.size - 7
        var alignment: Point? = null
        // Anything above version 1 has an alignment pattern.
        if (version.alignment.isNotEmpty()) {
            // Guess where a "bottom right" finder pattern would have been.
            val bottomRightX = topRight.x - topLeft.x + bottomLeft.x
            val bottomRightY = topRight.y - topLeft.y + bottomLeft.y
            // The alignment pattern is 3 modules closer to the top left than that.
            val correctionToTopLeft = 1.0 - 3.0 / modulesBetweenFPCenters
            val estX = (topLeft.x + correctionToTopLeft * (bottomRightX - topLeft.x)).toInt()
            val estY = (topLeft.y + correctionToTopLeft * (bottomRightY - topLeft.y)).toInt()
            var factor = 4
            while (factor <= 16 && alignment == null) { alignment = findAlignmentInRegion(moduleSize, estX, estY, factor.toDouble()); factor = factor shl 1 }
        }
        val transform = createTransform(topLeft, topRight, bottomLeft, alignment, dimension)
        return sampleGrid(transform, dimension)
    }

    private fun createTransform(topLeft: Point, topRight: Point, bottomLeft: Point, alignment: Point?, dimension: Int): PerspectiveTransform {
        val dimMinusThree = dimension - 3.5
        val bottomRightX: Double; val bottomRightY: Double; val sourceBottomRight: Double
        if (alignment != null) {
            bottomRightX = alignment.x; bottomRightY = alignment.y; sourceBottomRight = dimMinusThree - 3.0
        } else {
            // Don't have an alignment pattern, just make up the bottom-right point.
            bottomRightX = topRight.x - topLeft.x + bottomLeft.x
            bottomRightY = topRight.y - topLeft.y + bottomLeft.y
            sourceBottomRight = dimMinusThree
        }
        return PerspectiveTransform.quadToQuad(
            3.5, 3.5, dimMinusThree, 3.5, sourceBottomRight, sourceBottomRight, 3.5, dimMinusThree,
            topLeft.x, topLeft.y, topRight.x, topRight.y, bottomRightX, bottomRightY, bottomLeft.x, bottomLeft.y,
        )
    }

    /** DefaultGridSampler: the module centres through the transform; points just off the edge are nudged in. */
    private fun sampleGrid(transform: PerspectiveTransform, dimension: Int): QrMatrix? {
        val bits = QrMatrix(dimension)
        val points = DoubleArray(2 * dimension)
        for (y in 0 until dimension) {
            val iValue = y + 0.5
            for (x in 0 until dimension) { points[2 * x] = x + 0.5; points[2 * x + 1] = iValue }
            transform.transform(points)
            if (!checkAndNudgePoints(points)) return null
            for (x in 0 until dimension) {
                val px = points[2 * x].toInt(); val py = points[2 * x + 1].toInt()
                if (px !in 0 until image.width || py !in 0 until image.height) return null
                if (image[px, py]) bits[x, y] = true
            }
        }
        return bits
    }

    private fun checkAndNudgePoints(points: DoubleArray): Boolean {
        val width = image.width; val height = image.height
        fun nudge(offset: Int): Boolean? {
            val x = points[offset].toInt(); val y = points[offset + 1].toInt()
            if (x < -1 || x > width || y < -1 || y > height) return null
            var nudged = false
            if (x == -1) { points[offset] = 0.0; nudged = true } else if (x == width) { points[offset] = width - 1.0; nudged = true }
            if (y == -1) { points[offset + 1] = 0.0; nudged = true } else if (y == height) { points[offset + 1] = height - 1.0; nudged = true }
            return nudged
        }
        // Only the ends of the row can be off the image; stop at the first point that is inside.
        var offset = 0
        while (offset < points.size - 1) { val n = nudge(offset) ?: return false; if (!n) break; offset += 2 }
        offset = points.size - 2
        while (offset >= 0) { val n = nudge(offset) ?: return false; if (!n) break; offset -= 2 }
        return true
    }

    private fun computeDimension(topLeft: Point, topRight: Point, bottomLeft: Point, moduleSize: Double): Int {
        val tltr = Math.round(Point.distance(topLeft, topRight) / moduleSize).toInt()
        val tlbl = Math.round(Point.distance(topLeft, bottomLeft) / moduleSize).toInt()
        var dimension = (tltr + tlbl) / 2 + 7
        when (dimension and 0x03) { 0 -> dimension++; 2 -> dimension--; 3 -> dimension -= 2 }
        return dimension
    }

    /** Module size from the black-white-black run of the finder, measured towards the other finder and away from it. */
    private fun moduleSizeOneWay(pattern: Point, other: Point): Double {
        val est1 = runBothWays(pattern.x.toInt(), pattern.y.toInt(), other.x.toInt(), other.y.toInt())
        val est2 = runBothWays(other.x.toInt(), other.y.toInt(), pattern.x.toInt(), pattern.y.toInt())
        if (est1.isNaN()) return est2 / 7.0
        if (est2.isNaN()) return est1 / 7.0
        // Average them, and divide by 7 since we've counted the width of 3 black modules, and 1 white and 1 black module on either side.
        return (est1 + est2) / 14.0
    }

    private fun runBothWays(fromX: Int, fromY: Int, toX: Int, toY: Int): Double {
        var result = blackWhiteBlackRun(fromX, fromY, toX, toY)
        // Now count other way -- don't run off image though of course.
        var scale = 1.0
        var otherToX = fromX - (toX - fromX)
        if (otherToX < 0) { scale = fromX / (fromX - otherToX).toDouble(); otherToX = 0 }
        else if (otherToX >= image.width) { scale = (image.width - 1 - fromX) / (otherToX - fromX).toDouble(); otherToX = image.width - 1 }
        var otherToY = (fromY - (toY - fromY) * scale).toInt()
        scale = 1.0
        if (otherToY < 0) { scale = fromY / (fromY - otherToY).toDouble(); otherToY = 0 }
        else if (otherToY >= image.height) { scale = (image.height - 1 - fromY) / (otherToY - fromY).toDouble(); otherToY = image.height - 1 }
        otherToX = (fromX + (otherToX - fromX) * scale).toInt()
        result += blackWhiteBlackRun(fromX, fromY, otherToX, otherToY)
        // Middle pixel is double-counted this way; subtract 1.
        return result - 1.0
    }

    /** Bresenham from (from) towards (to): the distance to the end of the first black-white-black run. */
    private fun blackWhiteBlackRun(fromX0: Int, fromY0: Int, toX0: Int, toY0: Int): Double {
        var fromX = fromX0; var fromY = fromY0; var toX = toX0; var toY = toY0
        val steep = abs(toY - fromY) > abs(toX - fromX)
        if (steep) { var t = fromX; fromX = fromY; fromY = t; t = toX; toX = toY; toY = t }
        val dx = abs(toX - fromX); val dy = abs(toY - fromY)
        var error = -dx / 2
        val xstep = if (fromX < toX) 1 else -1
        val ystep = if (fromY < toY) 1 else -1
        // In black pixels, looking for white, first or second time.
        var state = 0
        val xLimit = toX + xstep
        var x = fromX; var y = fromY
        while (x != xLimit) {
            val realX = if (steep) y else x
            val realY = if (steep) x else y
            // Does current pixel mean we have moved white to black or vice versa?
            if (realX in 0 until image.width && realY in 0 until image.height && (state == 1) == image[realX, realY]) {
                if (state == 2) return distance(x, y, fromX, fromY)
                state++
            }
            error += dy
            if (error > 0) {
                if (y == toY) break
                y += ystep
                error -= dx
            }
            x += xstep
        }
        // Found black-white-black; give the benefit of the doubt that the next pixel outside the image is "white".
        if (state == 2) return distance(toX + xstep, toY, fromX, fromY)
        return Double.NaN
    }

    private fun distance(x1: Int, y1: Int, x2: Int, y2: Int): Double { val dx = (x1 - x2).toDouble(); val dy = (y1 - y2).toDouble(); return sqrt(dx * dx + dy * dy) }

    private fun findAlignmentInRegion(moduleSize: Double, estX: Int, estY: Int, allowanceFactor: Double): Point? {
        // Look for an alignment pattern (3 modules in size) around where it should be.
        val allowance = (allowanceFactor * moduleSize).toInt()
        val left = max(0, estX - allowance)
        val right = min(image.width - 1, estX + allowance)
        if (right - left < moduleSize * 3) return null
        val top = max(0, estY - allowance)
        val bottom = min(image.height - 1, estY + allowance)
        if (bottom - top < moduleSize * 3) return null
        return AlignmentPatternFinder(image, left, top, right - left, bottom - top, moduleSize).find()
    }
}
