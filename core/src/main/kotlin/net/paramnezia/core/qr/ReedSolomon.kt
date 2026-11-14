package net.paramnezia.core.qr

/**
 * Reed–Solomon over GF(256) with the QR field polynomial x^8 + x^4 + x^3 + x^2 + 1 (0x11D) and
 * generator roots α^0 … α^(n−1). Codewords are big-endian polynomials: index 0 is the highest power.
 *
 * Decoding: syndromes, Berlekamp–Massey for the error locator, Chien search for the positions and
 * Forney's formula for the values. Corrects up to n/2 wrong codewords; more is reported (null),
 * never "corrected" into garbage silently, as long as the locator's degree and its root count disagree.
 */
internal object ReedSolomon {
    private val exp = IntArray(512)
    private val log = IntArray(256)

    init {
        var x = 1
        for (i in 0 until 255) {
            exp[i] = x
            log[x] = i
            x = x shl 1
            if (x and 0x100 != 0) x = x xor 0x11D
        }
        for (i in 255 until 512) exp[i] = exp[i - 255]
    }

    private fun mul(a: Int, b: Int): Int = if (a == 0 || b == 0) 0 else exp[log[a] + log[b]]
    private fun div(a: Int, b: Int): Int {
        require(b != 0)
        return if (a == 0) 0 else exp[(log[a] + 255 - log[b]) % 255]
    }
    private fun pow(e: Int): Int = exp[((e % 255) + 255) % 255]

    private val generators = HashMap<Int, IntArray>()

    /** (x − α^0)(x − α^1)…(x − α^(n−1)), big-endian. */
    private fun generator(n: Int): IntArray = synchronized(generators) {
        generators.getOrPut(n) {
            var g = intArrayOf(1)
            for (i in 0 until n) {
                val next = IntArray(g.size + 1)
                for (j in g.indices) {
                    next[j] = next[j] xor g[j]
                    next[j + 1] = next[j + 1] xor mul(g[j], pow(i))
                }
                g = next
            }
            g
        }
    }

    /** The [n] error correction codewords for [data]. */
    fun encode(data: IntArray, n: Int): IntArray {
        val g = generator(n)
        val r = IntArray(n)
        for (d in data) {
            val factor = d xor r[0]
            System.arraycopy(r, 1, r, 0, n - 1)
            r[n - 1] = 0
            if (factor != 0) for (j in 0 until n) r[j] = r[j] xor mul(g[j + 1], factor)
        }
        return r
    }

    /**
     * Corrects [codewords] (data followed by [n] error correction codewords) in place. Returns the
     * number of corrected codewords, or -1 if there are too many errors to correct.
     */
    fun decode(codewords: IntArray, n: Int): Int {
        val len = codewords.size
        // Syndromes S_j = r(α^j).
        val s = IntArray(n)
        var clean = true
        for (j in 0 until n) {
            var v = 0
            val a = pow(j)
            for (c in codewords) v = mul(v, a) xor c
            s[j] = v
            if (v != 0) clean = false
        }
        if (clean) return 0

        // Berlekamp–Massey: Λ(x) little-endian (index = power).
        var lambda = IntArray(n + 1).also { it[0] = 1 }
        var prev = IntArray(n + 1).also { it[0] = 1 }
        var l = 0
        var m = 1
        var b = 1
        for (k in 0 until n) {
            var d = s[k]
            for (i in 1..l) d = d xor mul(lambda[i], s[k - i])
            if (d == 0) { m++; continue }
            val t = lambda.copyOf()
            val coef = div(d, b)
            for (i in 0..n - m) lambda[i + m] = lambda[i + m] xor mul(coef, prev[i])
            if (2 * l <= k) {
                l = k + 1 - l
                prev = t
                b = d
                m = 1
            } else m++
        }
        if (l == 0 || 2 * l > n) return -1

        // Chien search over every codeword position p (power of x): Λ(α^−p) = 0.
        val positions = ArrayList<Int>()
        for (p in 0 until len) {
            var v = 0
            val inv = pow(-p)
            for (i in l downTo 0) v = mul(v, inv) xor lambda[i]
            if (v == 0) positions += p
        }
        if (positions.size != l) return -1

        // Ω(x) = S(x)·Λ(x) mod x^n.
        val omega = IntArray(n)
        for (i in 0 until n) {
            var v = 0
            for (j in 0..minOf(i, l)) v = v xor mul(lambda[j], s[i - j])
            omega[i] = v
        }
        // Forney (generator roots from α^0): e = X·Ω(X^−1) / Λ'(X^−1).
        for (p in positions) {
            val x = pow(p)
            val xInv = pow(-p)
            var om = 0
            for (i in n - 1 downTo 0) om = mul(om, xInv) xor omega[i]
            var dl = 0
            // Formal derivative: odd powers only (characteristic 2).
            var i = 1
            var xp = 1
            while (i <= l) {
                dl = dl xor mul(lambda[i], xp)
                xp = mul(xp, mul(xInv, xInv))
                i += 2
            }
            if (dl == 0) return -1
            val e = mul(x, div(om, dl))
            val idx = len - 1 - p
            codewords[idx] = codewords[idx] xor e
        }
        return positions.size
    }
}
