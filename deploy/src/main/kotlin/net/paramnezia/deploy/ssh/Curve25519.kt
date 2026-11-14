package net.paramnezia.deploy.ssh

import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * X25519 (RFC 7748) and Ed25519 (RFC 8032) in constant-time limb arithmetic, after TweetNaCl.
 *
 * Neither is in the JDK on every platform we run on (Android only gained them in recent versions),
 * and they are the strongest choices SSH has: curve25519-sha256 key exchange and ssh-ed25519 keys.
 * A field element is 16 limbs of 16 bits (in longs, so products never overflow). The curve
 * constants are derived at load time from their definitions instead of being typed in.
 */
internal object Curve25519 {
    private fun gf(vararg v: Long) = LongArray(16).also { v.copyInto(it) }

    private val P: BigInteger = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19))
    private val ORDER: BigInteger = BigInteger.ONE.shiftLeft(252).add(BigInteger("27742317777372353535851937790883648493"))

    private fun fromBig(v: BigInteger): LongArray {
        val o = LongArray(16)
        var x = v.mod(P)
        for (i in 0 until 16) { o[i] = x.and(BigInteger.valueOf(0xFFFF)).toLong(); x = x.shiftRight(16) }
        return o
    }

    private val D: LongArray
    private val D2: LongArray
    private val SQRT_M1: LongArray
    private val BX: LongArray
    private val BY: LongArray
    private val A24 = gf(0xDB41, 1) // 121665
    private val L: LongArray = LongArray(32).also { l -> val b = ORDER.toByteArray().reversedArray(); for (i in 0 until 32) l[i] = if (i < b.size) (b[i].toLong() and 0xFF) else 0 }

    init {
        val d = BigInteger.valueOf(-121665).multiply(BigInteger.valueOf(121666).modInverse(P)).mod(P)
        val sqrtM1 = BigInteger.valueOf(2).modPow(P.subtract(BigInteger.ONE).shiftRight(2), P)
        // Base point: y = 4/5, x the even root of (y² − 1)/(d·y² + 1).
        val y = BigInteger.valueOf(4).multiply(BigInteger.valueOf(5).modInverse(P)).mod(P)
        val y2 = y.multiply(y).mod(P)
        val xx = y2.subtract(BigInteger.ONE).multiply(d.multiply(y2).add(BigInteger.ONE).modInverse(P)).mod(P)
        var x = xx.modPow(P.add(BigInteger.valueOf(3)).shiftRight(3), P)
        if (x.multiply(x).subtract(xx).mod(P).signum() != 0) x = x.multiply(sqrtM1).mod(P)
        if (x.testBit(0)) x = P.subtract(x)
        D = fromBig(d); D2 = fromBig(d.shiftLeft(1)); SQRT_M1 = fromBig(sqrtM1); BX = fromBig(x); BY = fromBig(y)
    }

    // ---- field arithmetic mod 2^255 − 19 ----

    private fun car(o: LongArray) {
        for (i in 0 until 16) {
            o[i] += 1L shl 16
            val c = o[i] shr 16
            if (i < 15) o[i + 1] += c - 1 else o[0] += 38 * (c - 1)
            o[i] -= c shl 16
        }
    }

    private fun sel(p: LongArray, q: LongArray, b: Int) {
        val c = (b - 1).toLong().inv()
        for (i in 0 until 16) { val t = c and (p[i] xor q[i]); p[i] = p[i] xor t; q[i] = q[i] xor t }
    }

    private fun pack(o: ByteArray, n: LongArray) {
        val t = n.copyOf(); val m = LongArray(16)
        car(t); car(t); car(t)
        for (j in 0 until 2) {
            m[0] = t[0] - 0xFFED
            for (i in 1 until 15) { m[i] = t[i] - 0xFFFF - ((m[i - 1] shr 16) and 1); m[i - 1] = m[i - 1] and 0xFFFF }
            m[15] = t[15] - 0x7FFF - ((m[14] shr 16) and 1)
            val b = ((m[15] shr 16) and 1).toInt()
            m[14] = m[14] and 0xFFFF
            sel(t, m, 1 - b)
        }
        for (i in 0 until 16) { o[2 * i] = t[i].toByte(); o[2 * i + 1] = (t[i] shr 8).toByte() }
    }

    private fun unpack(o: LongArray, n: ByteArray) {
        for (i in 0 until 16) o[i] = (n[2 * i].toLong() and 0xFF) + ((n[2 * i + 1].toLong() and 0xFF) shl 8)
        o[15] = o[15] and 0x7FFF
    }

    private fun neq(a: LongArray, b: LongArray): Boolean {
        val c = ByteArray(32); val d = ByteArray(32)
        pack(c, a); pack(d, b)
        return !MessageDigest.isEqual(c, d)
    }

    private fun par(a: LongArray): Int { val d = ByteArray(32); pack(d, a); return d[0].toInt() and 1 }

    private fun add(o: LongArray, a: LongArray, b: LongArray) { for (i in 0 until 16) o[i] = a[i] + b[i] }
    private fun sub(o: LongArray, a: LongArray, b: LongArray) { for (i in 0 until 16) o[i] = a[i] - b[i] }

    private fun mul(o: LongArray, a: LongArray, b: LongArray) {
        val t = LongArray(31)
        for (i in 0 until 16) for (j in 0 until 16) t[i + j] += a[i] * b[j]
        for (i in 0 until 15) t[i] += 38 * t[i + 16]
        System.arraycopy(t, 0, o, 0, 16)
        car(o); car(o)
    }

    private fun sq(o: LongArray, a: LongArray) = mul(o, a, a)

    private fun inv(o: LongArray, i: LongArray) {
        val c = i.copyOf()
        for (a in 253 downTo 0) { sq(c, c); if (a != 2 && a != 4) mul(c, c, i) }
        c.copyInto(o)
    }

    private fun pow2523(o: LongArray, i: LongArray) {
        val c = i.copyOf()
        for (a in 250 downTo 0) { sq(c, c); if (a != 1) mul(c, c, i) }
        c.copyInto(o)
    }

    // ---- X25519 ----

    /** X25519(k, u): [scalar] and [point] are 32 bytes little-endian. */
    fun x25519(scalar: ByteArray, point: ByteArray): ByteArray {
        val z = scalar.copyOf(32)
        z[31] = ((z[31].toInt() and 127) or 64).toByte()
        z[0] = (z[0].toInt() and 248).toByte()
        val x = LongArray(16); unpack(x, point)
        val a = gf(1); val b = x.copyOf(); val c = LongArray(16); val d = gf(1)
        val e = LongArray(16); val f = LongArray(16)
        for (i in 254 downTo 0) {
            val r = (z[i ushr 3].toInt() ushr (i and 7)) and 1
            sel(a, b, r); sel(c, d, r)
            add(e, a, c); sub(a, a, c); add(c, b, d); sub(b, b, d); sq(d, e); sq(f, a); mul(a, c, a); mul(c, b, e)
            add(e, a, c); sub(a, a, c); sq(b, a); sub(c, d, f); mul(a, c, A24); add(a, a, d); mul(c, c, a); mul(a, d, f)
            mul(d, b, x); sq(b, e)
            sel(a, b, r); sel(c, d, r)
        }
        inv(c, c); mul(a, a, c)
        return ByteArray(32).also { pack(it, a) }
    }

    fun x25519Base(scalar: ByteArray): ByteArray = x25519(scalar, ByteArray(32).also { it[0] = 9 })

    // ---- Ed25519 ----

    private fun padd(p: Array<LongArray>, q: Array<LongArray>) {
        val a = LongArray(16); val b = LongArray(16); val c = LongArray(16); val d = LongArray(16)
        val t = LongArray(16); val e = LongArray(16); val f = LongArray(16); val g = LongArray(16); val h = LongArray(16)
        sub(a, p[1], p[0]); sub(t, q[1], q[0]); mul(a, a, t)
        add(b, p[0], p[1]); add(t, q[0], q[1]); mul(b, b, t)
        mul(c, p[3], q[3]); mul(c, c, D2)
        mul(d, p[2], q[2]); add(d, d, d)
        sub(e, b, a); sub(f, d, c); add(g, d, c); add(h, b, a)
        mul(p[0], e, f); mul(p[1], h, g); mul(p[2], g, f); mul(p[3], e, h)
    }

    private fun cswap(p: Array<LongArray>, q: Array<LongArray>, b: Int) { for (i in 0 until 4) sel(p[i], q[i], b) }

    private fun packPoint(r: ByteArray, p: Array<LongArray>) {
        val tx = LongArray(16); val ty = LongArray(16); val zi = LongArray(16)
        inv(zi, p[2]); mul(tx, p[0], zi); mul(ty, p[1], zi)
        pack(r, ty)
        r[31] = (r[31].toInt() xor (par(tx) shl 7)).toByte()
    }

    private fun scalarMult(p: Array<LongArray>, q: Array<LongArray>, s: ByteArray) {
        p[0] = LongArray(16); p[1] = gf(1); p[2] = gf(1); p[3] = LongArray(16)
        for (i in 255 downTo 0) {
            val b = (s[i / 8].toInt() ushr (i and 7)) and 1
            cswap(p, q, b); padd(q, p); padd(p, p); cswap(p, q, b)
        }
    }

    private fun scalarBase(p: Array<LongArray>, s: ByteArray) {
        val t = LongArray(16).also { mul(it, BX, BY) }
        scalarMult(p, arrayOf(BX.copyOf(), BY.copyOf(), gf(1), t), s)
    }

    private fun point() = Array(4) { LongArray(16) }

    private fun modL(r: ByteArray, x: LongArray) {
        for (i in 63 downTo 32) {
            var carry = 0L
            var j = i - 32
            while (j < i - 12) {
                x[j] += carry - 16 * x[i] * L[j - (i - 32)]
                carry = (x[j] + 128) shr 8
                x[j] -= carry shl 8
                j++
            }
            x[j] += carry
            x[i] = 0
        }
        var carry = 0L
        for (j in 0 until 32) { x[j] += carry - (x[31] shr 4) * L[j]; carry = x[j] shr 8; x[j] = x[j] and 255 }
        for (j in 0 until 32) x[j] -= carry * L[j]
        for (i in 0 until 32) { x[i + 1] += x[i] shr 8; r[i] = x[i].toByte() }
    }

    private fun reduce(h: ByteArray): ByteArray {
        val x = LongArray(64) { h[it].toLong() and 0xFF }
        return ByteArray(32).also { modL(it, x) }
    }

    private fun sha512(vararg parts: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-512").run { parts.forEach(::update); digest() }

    private fun expand(seed: ByteArray): ByteArray = sha512(seed).also {
        it[0] = (it[0].toInt() and 248).toByte()
        it[31] = ((it[31].toInt() and 127) or 64).toByte()
    }

    /** The public key of a 32-byte Ed25519 [seed]. */
    fun edPublic(seed: ByteArray): ByteArray {
        val p = point(); scalarBase(p, expand(seed))
        return ByteArray(32).also { packPoint(it, p) }
    }

    fun edGenerateSeed(random: SecureRandom = SecureRandom()): ByteArray = ByteArray(32).also(random::nextBytes)

    /** A 64-byte signature of [message] by the key with [seed] and public key [pub]. */
    fun edSign(seed: ByteArray, pub: ByteArray, message: ByteArray): ByteArray {
        val d = expand(seed)
        val r = reduce(sha512(d.copyOfRange(32, 64), message))
        val p = point(); scalarBase(p, r)
        val rEnc = ByteArray(32).also { packPoint(it, p) }
        val h = reduce(sha512(rEnc, pub, message))
        val x = LongArray(64)
        for (i in 0 until 32) x[i] = r[i].toLong() and 0xFF
        for (i in 0 until 32) for (j in 0 until 32) x[i + j] += (h[i].toLong() and 0xFF) * (d[j].toLong() and 0xFF)
        val s = ByteArray(32).also { modL(it, x) }
        return rEnc + s
    }

    private fun unpackNeg(r: Array<LongArray>, p: ByteArray): Boolean {
        val t = LongArray(16); val chk = LongArray(16); val num = LongArray(16); val den = LongArray(16)
        val den2 = LongArray(16); val den4 = LongArray(16); val den6 = LongArray(16)
        r[2] = gf(1)
        unpack(r[1], p)
        sq(num, r[1]); mul(den, num, D); sub(num, num, r[2]); add(den, r[2], den)
        sq(den2, den); sq(den4, den2); mul(den6, den4, den2); mul(t, den6, num); mul(t, t, den)
        pow2523(t, t); mul(t, t, num); mul(t, t, den); mul(t, t, den); mul(r[0], t, den)
        sq(chk, r[0]); mul(chk, chk, den)
        if (neq(chk, num)) mul(r[0], r[0], SQRT_M1)
        sq(chk, r[0]); mul(chk, chk, den)
        if (neq(chk, num)) return false
        if (par(r[0]) == (p[31].toInt() and 0xFF) shr 7) sub(r[0], LongArray(16), r[0])
        mul(r[3], r[0], r[1])
        return true
    }

    /** Verifies a 64-byte Ed25519 [sig] of [message] under the 32-byte public key [pub]. */
    fun edVerify(pub: ByteArray, message: ByteArray, sig: ByteArray): Boolean {
        if (pub.size != 32 || sig.size != 64) return false
        // S must be below the group order (RFC 8032 §5.1.7): no malleable signatures.
        if (BigInteger(1, sig.copyOfRange(32, 64).reversedArray()) >= ORDER) return false
        val q = point()
        if (!unpackNeg(q, pub)) return false
        val h = reduce(sha512(sig.copyOfRange(0, 32), pub, message))
        val p = point(); scalarMult(p, q, h)
        val b = point(); scalarBase(b, sig.copyOfRange(32, 64))
        padd(p, b)
        val t = ByteArray(32).also { packPoint(it, p) }
        return MessageDigest.isEqual(t, sig.copyOfRange(0, 32))
    }
}
