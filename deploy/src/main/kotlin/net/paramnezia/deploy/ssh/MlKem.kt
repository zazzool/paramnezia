/*
 * ML-KEM-768 (FIPS 203), ported from Bouncy Castle (org.bouncycastle.crypto.kems.mlkem: MLKEMEngine,
 * MLKEMIndCpa, Poly, PolyVec, Ntt, Reduce, CBD; org.bouncycastle.crypto.digests.KeccakDigest),
 * Copyright (c) 2000-2026 The Legion of the Bouncy Castle Inc., MIT License
 * (https://www.bouncycastle.org/licence.html).
 *
 * Changes: Kotlin; only what a client of the hybrid SSH key exchange needs (ML-KEM-768 key
 * generation and decapsulation with implicit rejection); Keccak round constants and rotation offsets
 * are derived from their definitions instead of typed-in tables.
 */
package net.paramnezia.deploy.ssh

import java.security.SecureRandom

/** Keccak-f[1600] sponge: SHA3-256/512 and SHAKE128/256 (Android has none of them before API 33). */
internal class Keccak private constructor(private val rate: Int, private val pad: Int) {
    private val state = LongArray(25)
    private val buf = ByteArray(200)
    private var pos = 0
    private var squeezing = false

    fun update(b: ByteArray, off: Int = 0, len: Int = b.size - off): Keccak {
        check(!squeezing)
        for (i in off until off + len) {
            buf[pos++] = b[i]
            if (pos == rate) { absorbBlock(); pos = 0 }
        }
        return this
    }

    fun update(b: Int): Keccak = update(byteArrayOf(b.toByte()))

    private fun absorbBlock() {
        for (i in 0 until rate / 8) {
            var lane = 0L
            for (j in 0 until 8) lane = lane or ((buf[i * 8 + j].toLong() and 0xFF) shl (8 * j))
            state[i] = state[i] xor lane
        }
        permute(state)
    }

    private fun finish() {
        for (i in pos until rate) buf[i] = 0
        buf[pos] = (buf[pos].toInt() xor pad).toByte()
        buf[rate - 1] = (buf[rate - 1].toInt() xor 0x80).toByte()
        absorbBlock()
        squeezing = true
        pos = 0
        extract()
    }

    private fun extract() {
        for (i in 0 until rate / 8) for (j in 0 until 8) buf[i * 8 + j] = (state[i] ushr (8 * j)).toByte()
    }

    /** More output; may be called repeatedly (SHAKE as a stream). */
    fun squeeze(out: ByteArray, off: Int = 0, len: Int = out.size - off) {
        if (!squeezing) finish()
        for (i in off until off + len) {
            if (pos == rate) { permute(state); extract(); pos = 0 }
            out[i] = buf[pos++]
        }
    }

    fun digest(len: Int): ByteArray = ByteArray(len).also { squeeze(it) }

    companion object {
        fun sha3_256() = Keccak(136, 0x06)
        fun sha3_512() = Keccak(72, 0x06)
        fun shake128() = Keccak(168, 0x1F)
        fun shake256() = Keccak(136, 0x1F)

        private val RC = LongArray(24).also { rc ->
            var r = 1
            for (round in 0 until 24) {
                var c = 0L
                for (j in 0 until 7) {
                    if (r and 1 != 0) c = c xor (1L shl ((1 shl j) - 1))
                    r = if (r and 0x80 != 0) (r shl 1) xor 0x71 else r shl 1
                    r = r and 0xFF
                }
                rc[round] = c
            }
        }

        private val ROT = IntArray(25).also { rot ->
            var x = 1; var y = 0
            for (t in 0 until 24) {
                rot[x + 5 * y] = ((t + 1) * (t + 2) / 2) % 64
                val nx = y; val ny = (2 * x + 3 * y) % 5
                x = nx; y = ny
            }
        }

        fun permute(a: LongArray) {
            val c = LongArray(5)
            val b = LongArray(25)
            for (round in 0 until 24) {
                for (x in 0 until 5) c[x] = a[x] xor a[x + 5] xor a[x + 10] xor a[x + 15] xor a[x + 20]
                for (x in 0 until 5) {
                    val d = c[(x + 4) % 5] xor java.lang.Long.rotateLeft(c[(x + 1) % 5], 1)
                    for (y in 0 until 25 step 5) a[x + y] = a[x + y] xor d
                }
                for (x in 0 until 5) for (y in 0 until 5) {
                    b[y + 5 * ((2 * x + 3 * y) % 5)] = java.lang.Long.rotateLeft(a[x + 5 * y], ROT[x + 5 * y])
                }
                for (y in 0 until 25 step 5) for (x in 0 until 5) a[x + y] = b[x + y] xor (b[(x + 1) % 5 + y].inv() and b[(x + 2) % 5 + y])
                a[0] = a[0] xor RC[round]
            }
        }
    }
}

/** ML-KEM-768: k = 3, η1 = η2 = 2, du = 10, dv = 4. */
internal object MlKemKotlin {
    private const val N = 256
    private const val Q = 3329
    private const val QINV = 62209
    private const val K = 3
    private const val SYM = 32
    private const val POLY_BYTES = 384
    private const val POLYVEC_BYTES = K * POLY_BYTES
    const val PUBLIC_KEY = POLYVEC_BYTES + SYM // 1184
    private const val SECRET_KEY = POLYVEC_BYTES + PUBLIC_KEY + 2 * SYM // 2400
    const val CIPHERTEXT = K * 320 + 128 // 1088

    // ---- Reduce ----
    private fun montgomeryReduce(a: Int): Short {
        val u = (a * QINV).toShort()
        var t = u * Q
        t = a - t
        t = t shr 16
        return t.toShort()
    }

    private fun barrettReduce(a: Short): Short {
        val v = (((1L shl 26) + Q / 2) / Q).toInt()
        var t = ((v * a) shr 26).toShort()
        t = (t * Q).toShort()
        return (a - t).toShort()
    }

    private fun condSubQ(a0: Short): Short {
        var a = (a0 - Q).toShort()
        a = (a + ((a.toInt() shr 15) and Q)).toShort()
        return a
    }

    // ---- NTT ----
    private val ZETAS = shortArrayOf(
        2285, 2571, 2970, 1812, 1493, 1422, 287, 202, 3158, 622, 1577, 182, 962, 2127, 1855, 1468, 573, 2004, 264, 383, 2500, 1458,
        1727, 3199, 2648, 1017, 732, 608, 1787, 411, 3124, 1758, 1223, 652, 2777, 1015, 2036, 1491, 3047, 1785, 516, 3321, 3009, 2663,
        1711, 2167, 126, 1469, 2476, 3239, 3058, 830, 107, 1908, 3082, 2378, 2931, 961, 1821, 2604, 448, 2264, 677, 2054, 2226, 430,
        555, 843, 2078, 871, 1550, 105, 422, 587, 177, 3094, 3038, 2869, 1574, 1653, 3083, 778, 1159, 3182, 2552, 1483, 2727, 1119,
        1739, 644, 2457, 349, 418, 329, 3173, 3254, 817, 1097, 603, 610, 1322, 2044, 1864, 384, 2114, 3193, 1218, 1994, 2455, 220, 2142,
        1670, 2144, 1799, 2051, 794, 1819, 2475, 2459, 478, 3221, 3021, 996, 991, 958, 1869, 1522, 1628,
    )
    private val ZETAS_INV = shortArrayOf(
        1701, 1807, 1460, 2371, 2338, 2333, 308, 108, 2851, 870, 854, 1510, 2535, 1278, 1530, 1185, 1659, 1187, 3109, 874, 1335, 2111,
        136, 1215, 2945, 1465, 1285, 2007, 2719, 2726, 2232, 2512, 75, 156, 3000, 2911, 2980, 872, 2685, 1590, 2210, 602, 1846, 777, 147,
        2170, 2551, 246, 1676, 1755, 460, 291, 235, 3152, 2742, 2907, 3224, 1779, 2458, 1251, 2486, 2774, 2899, 1103, 1275, 2652, 1065,
        2881, 725, 1508, 2368, 398, 951, 247, 1421, 3222, 2499, 271, 90, 853, 1860, 3203, 1162, 1618, 666, 320, 8, 2813, 1544, 282, 1838,
        1293, 2314, 552, 2677, 2106, 1571, 205, 2918, 1542, 2721, 2597, 2312, 681, 130, 1602, 1871, 829, 2946, 3065, 1325, 2756, 1861,
        1474, 1202, 2367, 3147, 1752, 2707, 171, 3127, 3042, 1907, 1836, 1517, 359, 758, 1441,
    )

    private fun mulMont(a: Short, b: Short): Short = montgomeryReduce(a * b)

    private fun ntt(r: ShortArray) {
        var k = 1
        var len = 128
        while (len >= 2) {
            var start = 0
            while (start < 256) {
                val zeta = ZETAS[k++]
                for (j in start until start + len) {
                    val t = r[j]; val u = mulMont(zeta, r[j + len])
                    r[j + len] = (t - u).toShort()
                    r[j] = (t + u).toShort()
                }
                start += 2 * len
            }
            len = len shr 1
        }
    }

    private fun invNtt(r: ShortArray) {
        var k = 0
        var len = 2
        while (len <= 128) {
            var start = 0
            while (start < 256) {
                val zeta = ZETAS_INV[k++]
                for (j in start until start + len) {
                    val t = r[j]; val u = r[j + len]
                    r[j] = barrettReduce((t + u).toShort())
                    r[j + len] = mulMont(zeta, (t - u).toShort())
                }
                start += 2 * len
            }
            len = len shl 1
        }
        for (i in 0 until 256) r[i] = mulMont(r[i], ZETAS_INV[127])
    }

    private fun baseMult(r: ShortArray, off: Int, a0: Short, a1: Short, b0: Short, b1: Short, zeta: Short) {
        var o0 = mulMont(a1, b1)
        o0 = mulMont(o0, zeta)
        o0 = (o0 + mulMont(a0, b0)).toShort()
        r[off] = o0
        var o1 = mulMont(a0, b1)
        o1 = (o1 + mulMont(a1, b0)).toShort()
        r[off + 1] = o1
    }

    // ---- Poly ----
    private class Poly { val c = ShortArray(N) }

    private fun reduce(p: Poly) { for (i in 0 until N) p.c[i] = barrettReduce(p.c[i]) }
    private fun condSubQ(p: Poly) { for (i in 0 until N) p.c[i] = condSubQ(p.c[i]) }
    private fun polyNtt(p: Poly) { ntt(p.c); reduce(p) }
    private fun add(p: Poly, b: Poly) { for (i in 0 until N) p.c[i] = (p.c[i] + b.c[i]).toShort() }
    /** p = b − p (Poly.subtract). */
    private fun subtractFrom(p: Poly, b: Poly) { for (i in 0 until N) p.c[i] = (b.c[i] - p.c[i]).toShort() }

    private fun baseMultMontgomery(r: Poly, a: Poly, b: Poly) {
        for (i in 0 until N / 4) {
            val z = ZETAS[64 + i]
            baseMult(r.c, 4 * i, a.c[4 * i], a.c[4 * i + 1], b.c[4 * i], b.c[4 * i + 1], z)
            baseMult(r.c, 4 * i + 2, a.c[4 * i + 2], a.c[4 * i + 3], b.c[4 * i + 2], b.c[4 * i + 3], (-z).toShort())
        }
    }

    private fun convertToMont(p: Poly) {
        val f = ((1L shl 32) % Q).toInt()
        for (i in 0 until N) p.c[i] = montgomeryReduce(p.c[i] * f)
    }

    private fun toBytes(p: Poly, r: ByteArray, off: Int) {
        condSubQ(p)
        for (i in 0 until N / 2) {
            val t0 = p.c[2 * i].toInt(); val t1 = p.c[2 * i + 1].toInt()
            r[off + 3 * i] = t0.toByte()
            r[off + 3 * i + 1] = ((t0 shr 8) or (t1 shl 4)).toByte()
            r[off + 3 * i + 2] = (t1 shr 4).toByte()
        }
    }

    private fun fromBytes(p: Poly, b: ByteArray, off: Int) {
        for (i in 0 until N / 2) {
            val a0 = b[off + 3 * i].toInt() and 0xFF; val a1 = b[off + 3 * i + 1].toInt() and 0xFF; val a2 = b[off + 3 * i + 2].toInt() and 0xFF
            p.c[2 * i] = ((a0 or (a1 shl 8)) and 0xFFF).toShort()
            p.c[2 * i + 1] = (((a1 shr 4) or (a2 shl 4)) and 0xFFF).toShort()
        }
    }

    private fun compress128(p: Poly): ByteArray {
        condSubQ(p)
        val r = ByteArray(128)
        val t = IntArray(8)
        for (i in 0 until N / 8) {
            for (j in 0 until 8) {
                var tj = p.c[8 * i + j].toInt()
                tj = tj shl 4; tj += 1665; tj *= 80635; tj = tj shr 28; tj = tj and 15
                t[j] = tj
            }
            for (k in 0 until 4) r[4 * i + k] = (t[2 * k] or (t[2 * k + 1] shl 4)).toByte()
        }
        return r
    }

    private fun decompress128(p: Poly, b: ByteArray, off: Int) {
        for (i in 0 until N / 2) {
            val v = b[off + i].toInt() and 0xFF
            p.c[2 * i] = ((((v and 15) * Q) + 8) shr 4).toShort()
            p.c[2 * i + 1] = ((((v shr 4) * Q) + 8) shr 4).toShort()
        }
    }

    private fun toMsg(p: Poly, msg: ByteArray) {
        val lower = Q ushr 2; val upper = Q - lower
        condSubQ(p)
        for (i in 0 until N / 8) {
            var m = 0
            for (j in 0 until 8) {
                val cj = p.c[8 * i + j].toInt()
                val t = ((lower - cj) and (cj - upper)) ushr 31
                m = m or (t shl j)
            }
            msg[i] = m.toByte()
        }
    }

    private fun fromMsg(p: Poly, msg: ByteArray, off: Int) {
        for (i in 0 until N / 8) {
            val mi = msg[off + i].toInt() and 0xFF
            for (j in 0 until 8) {
                val mask = -((mi shr j) and 1)
                p.c[8 * i + j] = (mask and ((Q + 1) / 2)).toShort()
            }
        }
    }

    /** CBD η = 2 from PRF(seed, nonce) = SHAKE256(seed ‖ nonce). */
    private fun noiseEta2(p: Poly, seed: ByteArray, seedOff: Int, nonce: Int) {
        val buf = Keccak.shake256().update(seed, seedOff, SYM).update(nonce).digest(2 * N / 4)
        for (i in 0 until N / 8) {
            val t = (buf[4 * i].toInt() and 0xFF) or ((buf[4 * i + 1].toInt() and 0xFF) shl 8) or
                ((buf[4 * i + 2].toInt() and 0xFF) shl 16) or ((buf[4 * i + 3].toInt() and 0xFF) shl 24)
            var d = t and 0x55555555
            d += (t ushr 1) and 0x55555555
            for (j in 0 until 8) {
                val a = (d ushr (4 * j)) and 0x3
                val b = (d ushr (4 * j + 2)) and 0x3
                p.c[8 * i + j] = (a - b).toShort()
            }
        }
    }

    // ---- PolyVec ----
    private fun vec() = Array(K) { Poly() }
    private fun vecNtt(v: Array<Poly>) = v.forEach { polyNtt(it) }

    private fun pointwiseAcc(out: Poly, a: Array<Poly>, b: Array<Poly>) {
        val t = Poly()
        baseMultMontgomery(out, a[0], b[0])
        for (i in 1 until K) { baseMultMontgomery(t, a[i], b[i]); add(out, t) }
        reduce(out)
    }

    private fun compressVec(v: Array<Poly>, r: ByteArray, off: Int) {
        var pos = off
        v.forEach { condSubQ(it) }
        val t = IntArray(4)
        for (i in 0 until K) for (j in 0 until N / 4) {
            for (k in 0 until 4) {
                var tk = v[i].c[4 * j + k].toLong()
                tk = tk shl 10; tk += 1665; tk *= 1290167; tk = tk shr 32; tk = tk and 0x3ff
                t[k] = tk.toInt()
            }
            r[pos] = t[0].toByte()
            r[pos + 1] = ((t[0] shr 8) or (t[1] shl 2)).toByte()
            r[pos + 2] = ((t[1] shr 6) or (t[2] shl 4)).toByte()
            r[pos + 3] = ((t[2] shr 4) or (t[3] shl 6)).toByte()
            r[pos + 4] = (t[3] shr 2).toByte()
            pos += 5
        }
    }

    private fun decompressVec(v: Array<Poly>, b: ByteArray, off: Int) {
        var pos = off
        val t = IntArray(4)
        for (i in 0 until K) for (j in 0 until N / 4) {
            fun u(k: Int) = b[pos + k].toInt() and 0xFF
            t[0] = u(0) or (u(1) shl 8)
            t[1] = (u(1) shr 2) or (u(2) shl 6)
            t[2] = (u(2) shr 4) or (u(3) shl 4)
            t[3] = (u(3) shr 6) or (u(4) shl 2)
            pos += 5
            for (k in 0 until 4) v[i].c[4 * j + k] = ((((t[k] and 0x3FF) * Q) + 512) shr 10).toShort()
        }
    }

    // ---- IND-CPA ----
    private const val SHAKE128_RATE = 168
    private const val MATRIX_BLOCKS = (((12 * N / 8) shl 12) / Q + SHAKE128_RATE) / SHAKE128_RATE

    private fun generateMatrix(seed: ByteArray, transpose: Boolean): Array<Array<Poly>> {
        val a = Array(K) { vec() }
        val buf = ByteArray(MATRIX_BLOCKS * SHAKE128_RATE + 2)
        for (i in 0 until K) for (j in 0 until K) {
            val xof = Keccak.shake128().update(seed, 0, SYM)
            if (transpose) xof.update(i).update(j) else xof.update(j).update(i)
            var buflen = MATRIX_BLOCKS * SHAKE128_RATE
            xof.squeeze(buf, 0, buflen)
            var ctr = rejection(a[i][j], 0, N, buf, buflen)
            while (ctr < N) {
                val off = buflen % 3
                for (k in 0 until off) buf[k] = buf[buflen - off + k]
                xof.squeeze(buf, off, SHAKE128_RATE)
                buflen = off + SHAKE128_RATE
                ctr += rejection(a[i][j], ctr, N - ctr, buf, buflen)
            }
        }
        return a
    }

    private fun rejection(out: Poly, coeffOff: Int, len: Int, b: ByteArray, bufLen: Int): Int {
        var ctr = 0; var pos = 0
        while (ctr < len && pos + 3 <= bufLen) {
            val b0 = b[pos].toInt() and 0xFF; val b1 = b[pos + 1].toInt() and 0xFF; val b2 = b[pos + 2].toInt() and 0xFF
            val d1 = (b0 or (b1 shl 8)) and 0xFFF
            val d2 = ((b1 shr 4) or (b2 shl 4)) and 0xFFF
            pos += 3
            if (d1 < Q) { out.c[coeffOff + ctr] = d1.toShort(); ctr++ }
            if (ctr < len && d2 < Q) { out.c[coeffOff + ctr] = d2.toShort(); ctr++ }
        }
        return ctr
    }

    private fun hashG(input: ByteArray): ByteArray = Keccak.sha3_512().update(input).digest(64)
    private fun hashH(b: ByteArray, off: Int = 0, len: Int = b.size - off): ByteArray = Keccak.sha3_256().update(b, off, len).digest(32)

    private fun cpaKeyPair(d: ByteArray): Pair<ByteArray, ByteArray> {
        val buf = hashG(d + byteArrayOf(K.toByte()))
        val a = generateMatrix(buf.copyOf(SYM), transpose = false)
        val s = vec(); val e = vec()
        var nonce = 0
        for (i in 0 until K) noiseEta2(s[i], buf, SYM, nonce++)
        for (i in 0 until K) noiseEta2(e[i], buf, SYM, nonce++)
        vecNtt(s); vecNtt(e)
        val t = vec()
        for (i in 0 until K) { pointwiseAcc(t[i], a[i], s); convertToMont(t[i]) }
        for (i in 0 until K) add(t[i], e[i])
        t.forEach { reduce(it) }
        val pk = ByteArray(PUBLIC_KEY)
        for (i in 0 until K) toBytes(t[i], pk, i * POLY_BYTES)
        System.arraycopy(buf, 0, pk, POLYVEC_BYTES, SYM)
        val sk = ByteArray(POLYVEC_BYTES)
        for (i in 0 until K) toBytes(s[i], sk, i * POLY_BYTES)
        return pk to sk
    }

    private fun cpaEncrypt(pk: ByteArray, pkOff: Int, msg: ByteArray, coins: ByteArray, coinsOff: Int): ByteArray {
        val t = vec()
        for (i in 0 until K) fromBytes(t[i], pk, pkOff + i * POLY_BYTES)
        val seed = pk.copyOfRange(pkOff + POLYVEC_BYTES, pkOff + POLYVEC_BYTES + SYM)
        val m = Poly().also { fromMsg(it, msg, 0) }
        val at = generateMatrix(seed, transpose = true)
        val sp = vec(); val ep = vec(); val e2 = Poly()
        var nonce = 0
        for (i in 0 until K) noiseEta2(sp[i], coins, coinsOff, nonce++)
        for (i in 0 until K) noiseEta2(ep[i], coins, coinsOff, nonce++)
        noiseEta2(e2, coins, coinsOff, nonce)
        vecNtt(sp)
        val bp = vec()
        for (i in 0 until K) pointwiseAcc(bp[i], at[i], sp)
        val v = Poly()
        pointwiseAcc(v, t, sp)
        bp.forEach { invNtt(it.c) }
        invNtt(v.c)
        for (i in 0 until K) add(bp[i], ep[i])
        add(v, e2); add(v, m)
        bp.forEach { reduce(it) }; reduce(v)
        val out = ByteArray(CIPHERTEXT)
        compressVec(bp, out, 0)
        System.arraycopy(compress128(v), 0, out, K * 320, 128)
        return out
    }

    private fun cpaDecrypt(sk: ByteArray, ct: ByteArray): ByteArray {
        val bp = vec(); val v = Poly(); val s = vec()
        decompressVec(bp, ct, 0)
        decompress128(v, ct, K * 320)
        for (i in 0 until K) fromBytes(s[i], sk, i * POLY_BYTES)
        vecNtt(bp)
        val mp = Poly()
        pointwiseAcc(mp, s, bp)
        invNtt(mp.c)
        subtractFrom(mp, v)
        reduce(mp)
        return ByteArray(SYM).also { toMsg(mp, it) }
    }

    /** A key pair: (encapsulation key, decapsulation key) per FIPS 203 (dk = s ‖ ek ‖ H(ek) ‖ z). */
    fun keyPair(random: SecureRandom = SecureRandom()): Pair<ByteArray, ByteArray> {
        val d = ByteArray(SYM).also(random::nextBytes)
        val z = ByteArray(SYM).also(random::nextBytes)
        return keyPairInternal(d, z)
    }

    internal fun keyPairInternal(d: ByteArray, z: ByteArray): Pair<ByteArray, ByteArray> {
        val (pk, s) = cpaKeyPair(d)
        return pk to (s + pk + hashH(pk) + z)
    }

    /** The shared secret; a ciphertext that does not re-encrypt gives the implicit rejection key. */
    fun decapsulate(dk: ByteArray, ct: ByteArray): ByteArray {
        require(dk.size == SECRET_KEY && ct.size == CIPHERTEXT)
        val buf = ByteArray(2 * SYM)
        System.arraycopy(cpaDecrypt(dk, ct), 0, buf, 0, SYM)
        System.arraycopy(dk, SECRET_KEY - 2 * SYM, buf, SYM, SYM)
        val kr = hashG(buf)
        val cmp = cpaEncrypt(dk, POLYVEC_BYTES, buf.copyOf(SYM), kr, SYM)
        var fail = 0
        for (i in 0 until CIPHERTEXT) fail = fail or (ct[i].toInt() xor cmp[i].toInt())
        fail = fail and 0xFF
        val rejection = Keccak.shake256().update(dk, SECRET_KEY - SYM, SYM).update(ct).digest(SYM)
        val mask = (0 - fail) shr 24
        for (i in 0 until SYM) kr[i] = ((rejection[i].toInt() and mask) or (kr[i].toInt() and mask.inv())).toByte()
        return kr.copyOf(SYM)
    }

    /** Encapsulation (for tests: the server side). */
    internal fun encapsulate(ek: ByteArray, m: ByteArray): Pair<ByteArray, ByteArray> {
        val buf = m.copyOf(SYM) + hashH(ek)
        val kr = hashG(buf)
        val ct = cpaEncrypt(ek, 0, buf.copyOf(SYM), kr, SYM)
        return kr.copyOf(SYM) to ct
    }
}
