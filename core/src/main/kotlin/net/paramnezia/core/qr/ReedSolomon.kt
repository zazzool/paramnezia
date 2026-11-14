/*
 * Ported from ZXing (com.google.zxing.common.reedsolomon: GenericGF, GenericGFPoly,
 * ReedSolomonEncoder, ReedSolomonDecoder), Copyright 2007-2008 ZXing authors,
 * licensed under the Apache License, Version 2.0 (http://www.apache.org/licenses/LICENSE-2.0).
 *
 * Changes: Kotlin; only the QR field GF(256)/0x11D; a decode failure is a -1 result, not an
 * exception (it happens on every unreadable camera frame); error positions outside the block
 * are refused before anything is written; the generator polynomials are cached thread-safely.
 */
package net.paramnezia.core.qr

/** GF(256) with the QR polynomial x^8 + x^4 + x^3 + x^2 + 1, generator base 0. */
internal object Gf256 {
    private val exp = IntArray(256)
    private val log = IntArray(256)

    init {
        var x = 1
        for (i in 0 until 256) {
            exp[i] = x
            x = x shl 1
            if (x >= 256) x = (x xor 0x11D) and 0xFF
        }
        for (i in 0 until 255) log[exp[i]] = i
    }

    fun exp(a: Int) = exp[a]
    fun log(a: Int): Int { require(a != 0); return log[a] }
    fun inverse(a: Int): Int { if (a == 0) throw ArithmeticException(); return exp[255 - log[a]] }
    fun multiply(a: Int, b: Int): Int = if (a == 0 || b == 0) 0 else exp[(log[a] + log[b]) % 255]

    val ZERO = Poly(intArrayOf(0))
    val ONE = Poly(intArrayOf(1))
    fun monomial(degree: Int, coefficient: Int): Poly {
        require(degree >= 0)
        if (coefficient == 0) return ZERO
        return Poly(IntArray(degree + 1).also { it[0] = coefficient })
    }

    /** A polynomial over GF(256), coefficients from the highest power down (leading zeros dropped). */
    class Poly(coefficients: IntArray) {
        val coefficients: IntArray = run {
            require(coefficients.isNotEmpty())
            if (coefficients.size > 1 && coefficients[0] == 0) {
                val first = coefficients.indexOfFirst { it != 0 }
                if (first < 0) intArrayOf(0) else coefficients.copyOfRange(first, coefficients.size)
            } else coefficients
        }
        val degree: Int get() = coefficients.size - 1
        val isZero: Boolean get() = coefficients[0] == 0
        fun coefficient(degree: Int) = coefficients[coefficients.size - 1 - degree]

        fun evaluateAt(a: Int): Int {
            if (a == 0) return coefficient(0)
            if (a == 1) return coefficients.fold(0) { r, c -> r xor c }
            var result = coefficients[0]
            for (i in 1 until coefficients.size) result = multiply(a, result) xor coefficients[i]
            return result
        }

        fun plus(other: Poly): Poly {
            if (isZero) return other
            if (other.isZero) return this
            var small = coefficients; var large = other.coefficients
            if (small.size > large.size) { val t = small; small = large; large = t }
            val sum = IntArray(large.size)
            val diff = large.size - small.size
            System.arraycopy(large, 0, sum, 0, diff)
            for (i in diff until large.size) sum[i] = small[i - diff] xor large[i]
            return Poly(sum)
        }

        fun times(other: Poly): Poly {
            if (isZero || other.isZero) return ZERO
            val a = coefficients; val b = other.coefficients
            val product = IntArray(a.size + b.size - 1)
            for (i in a.indices) for (j in b.indices) product[i + j] = product[i + j] xor multiply(a[i], b[j])
            return Poly(product)
        }

        fun times(scalar: Int): Poly = when (scalar) {
            0 -> ZERO
            1 -> this
            else -> Poly(IntArray(coefficients.size) { multiply(coefficients[it], scalar) })
        }

        fun timesMonomial(degree: Int, coefficient: Int): Poly {
            require(degree >= 0)
            if (coefficient == 0) return ZERO
            val product = IntArray(coefficients.size + degree)
            for (i in coefficients.indices) product[i] = multiply(coefficients[i], coefficient)
            return Poly(product)
        }

        /** Quotient and remainder. */
        fun divide(other: Poly): Pair<Poly, Poly> {
            require(!other.isZero) { "Divide by 0" }
            var quotient = ZERO
            var remainder = this
            val inverseLeading = inverse(other.coefficient(other.degree))
            while (remainder.degree >= other.degree && !remainder.isZero) {
                val degreeDifference = remainder.degree - other.degree
                val scale = multiply(remainder.coefficient(remainder.degree), inverseLeading)
                quotient = quotient.plus(monomial(degreeDifference, scale))
                remainder = remainder.plus(other.timesMonomial(degreeDifference, scale))
            }
            return quotient to remainder
        }
    }
}

internal object ReedSolomon {
    private val generators = ArrayList<Gf256.Poly>().apply { add(Gf256.ONE) }

    private fun generator(degree: Int): Gf256.Poly = synchronized(generators) {
        while (generators.size <= degree) {
            val last = generators.last()
            generators += last.times(Gf256.Poly(intArrayOf(1, Gf256.exp(generators.size - 1))))
        }
        generators[degree]
    }

    /** The [n] error correction codewords for [data] (ReedSolomonEncoder.encode). */
    fun encode(data: IntArray, n: Int): IntArray {
        require(n > 0 && data.isNotEmpty())
        val info = Gf256.Poly(data.copyOf()).timesMonomial(n, 1)
        val remainder = info.divide(generator(n)).second.coefficients
        val out = IntArray(n)
        System.arraycopy(remainder, 0, out, n - remainder.size, remainder.size)
        return out
    }

    /**
     * Corrects [codewords] (data followed by [n] error correction codewords) in place
     * (ReedSolomonDecoder.decodeWithECCount). The number of corrected codewords, or -1 when there
     * are more errors than the code can correct.
     */
    fun decode(codewords: IntArray, n: Int): Int {
        val poly = Gf256.Poly(codewords)
        val syndromes = IntArray(n)
        var noError = true
        for (i in 0 until n) {
            val eval = poly.evaluateAt(Gf256.exp(i))
            syndromes[n - 1 - i] = eval
            if (eval != 0) noError = false
        }
        if (noError) return 0
        val (sigma, omega) = euclid(Gf256.monomial(n, 1), Gf256.Poly(syndromes), n) ?: return -1
        val locations = errorLocations(sigma) ?: return -1
        val magnitudes = errorMagnitudes(omega, locations)
        val positions = IntArray(locations.size) { codewords.size - 1 - Gf256.log(locations[it]) }
        if (positions.any { it < 0 }) return -1
        for (i in locations.indices) codewords[positions[i]] = codewords[positions[i]] xor magnitudes[i]
        return locations.size
    }

    private fun euclid(a0: Gf256.Poly, b0: Gf256.Poly, r: Int): Pair<Gf256.Poly, Gf256.Poly>? {
        var a = a0; var b = b0
        if (a.degree < b.degree) { val t = a; a = b; b = t }
        var rLast = a; var rCur = b
        var tLast = Gf256.ZERO; var t = Gf256.ONE
        while (2 * rCur.degree >= r) {
            val rLastLast = rLast; val tLastLast = tLast
            rLast = rCur; tLast = t
            if (rLast.isZero) return null
            rCur = rLastLast
            var q = Gf256.ZERO
            val dltInverse = Gf256.inverse(rLast.coefficient(rLast.degree))
            while (rCur.degree >= rLast.degree && !rCur.isZero) {
                val degreeDiff = rCur.degree - rLast.degree
                val scale = Gf256.multiply(rCur.coefficient(rCur.degree), dltInverse)
                q = q.plus(Gf256.monomial(degreeDiff, scale))
                rCur = rCur.plus(rLast.timesMonomial(degreeDiff, scale))
            }
            t = q.times(tLast).plus(tLastLast)
            if (rCur.degree >= rLast.degree) return null
        }
        val sigmaTildeAtZero = t.coefficient(0)
        if (sigmaTildeAtZero == 0) return null
        val inverse = Gf256.inverse(sigmaTildeAtZero)
        return t.times(inverse) to rCur.times(inverse)
    }

    private fun errorLocations(locator: Gf256.Poly): IntArray? {
        val numErrors = locator.degree
        if (numErrors == 1) return intArrayOf(locator.coefficient(1))
        val result = IntArray(numErrors)
        var e = 0
        var i = 1
        while (i < 256 && e < numErrors) {
            if (locator.evaluateAt(i) == 0) result[e++] = Gf256.inverse(i)
            i++
        }
        return if (e == numErrors) result else null
    }

    private fun errorMagnitudes(evaluator: Gf256.Poly, locations: IntArray): IntArray = IntArray(locations.size) { i ->
        val xiInverse = Gf256.inverse(locations[i])
        var denominator = 1
        for (j in locations.indices) if (i != j) {
            val term = Gf256.multiply(locations[j], xiInverse)
            denominator = Gf256.multiply(denominator, if (term and 1 == 0) term or 1 else term and 1.inv())
        }
        Gf256.multiply(evaluator.evaluateAt(xiInverse), Gf256.inverse(denominator))
    }
}
