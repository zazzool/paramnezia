package io.paramnezia.core.nostr

import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * BIP-340 Schnorr signatures over secp256k1, as Nostr uses them. Plain BigInteger arithmetic: we sign
 * a handful of events per day, so clarity beats speed and no native library is needed.
 */
object Schnorr {
    private val P = BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEFFFFFC2F", 16)
    private val N = BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141", 16)
    private val G = Point(
        BigInteger("79BE667EF9DCBBAC55A06295CE870B07029BFCDB2DCE28D959F2815B16F81798", 16),
        BigInteger("483ADA7726A3C4655DA4FBFC0E1108A8FD17B448A68554199C47D08FFB10D4B8", 16),
    )
    private val SEVEN = BigInteger.valueOf(7)
    private val THREE = BigInteger.valueOf(3)
    private val TWO = BigInteger.valueOf(2)

    private data class Point(val x: BigInteger, val y: BigInteger)

    private fun add(a: Point?, b: Point?): Point? {
        if (a == null) return b
        if (b == null) return a
        if (a.x == b.x && a.y != b.y) return null
        val lam = if (a == b) {
            THREE * a.x * a.x * (TWO * a.y).modInverse(P)
        } else {
            (b.y - a.y) * (b.x - a.x).modInverse(P)
        }.mod(P)
        val x = (lam * lam - a.x - b.x).mod(P)
        return Point(x, (lam * (a.x - x) - a.y).mod(P))
    }

    private fun mul(p: Point?, k: BigInteger): Point? {
        var r: Point? = null
        var q = p
        for (i in 0 until k.bitLength()) {
            if (k.testBit(i)) r = add(r, q)
            q = add(q, q)
        }
        return r
    }

    private fun liftX(x: BigInteger): Point? {
        if (x >= P) return null
        val c = (x.modPow(THREE, P) + SEVEN).mod(P)
        val y = c.modPow((P + BigInteger.ONE).shiftRight(2), P)
        if (y.modPow(TWO, P) != c) return null
        return Point(x, if (y.testBit(0)) P - y else y)
    }

    private fun bytes32(v: BigInteger): ByteArray {
        val raw = v.toByteArray()
        val out = ByteArray(32)
        val n = minOf(raw.size, 32)
        System.arraycopy(raw, raw.size - n, out, 32 - n, n)
        return out
    }

    private fun int(b: ByteArray) = BigInteger(1, b)

    private fun taggedHash(tag: String, vararg parts: ByteArray): ByteArray {
        val t = MessageDigest.getInstance("SHA-256").digest(tag.toByteArray())
        val md = MessageDigest.getInstance("SHA-256")
        md.update(t); md.update(t)
        parts.forEach(md::update)
        return md.digest()
    }

    fun generateSecret(): ByteArray {
        val rnd = SecureRandom()
        while (true) {
            val k = ByteArray(32).also(rnd::nextBytes)
            val v = int(k)
            if (v.signum() > 0 && v < N) return k
        }
    }

    /** x-only public key. */
    fun publicKey(secret: ByteArray): ByteArray {
        val d = int(secret)
        require(d.signum() > 0 && d < N) { "Invalid secret key" }
        return bytes32(mul(G, d)!!.x)
    }

    fun sign(secret: ByteArray, message: ByteArray, auxRand: ByteArray = ByteArray(32).also(SecureRandom()::nextBytes)): ByteArray {
        val d0 = int(secret)
        require(d0.signum() > 0 && d0 < N) { "Invalid secret key" }
        val pt = mul(G, d0)!!
        val d = if (pt.y.testBit(0)) N - d0 else d0
        val t = bytes32(d).also { val h = taggedHash("BIP0340/aux", auxRand); for (i in 0 until 32) it[i] = (it[i].toInt() xor h[i].toInt()).toByte() }
        val px = bytes32(pt.x)
        val k0 = int(taggedHash("BIP0340/nonce", t, px, message)).mod(N)
        require(k0.signum() != 0)
        val r = mul(G, k0)!!
        val k = if (r.y.testBit(0)) N - k0 else k0
        val rx = bytes32(r.x)
        val e = int(taggedHash("BIP0340/challenge", rx, px, message)).mod(N)
        return rx + bytes32((k + e * d).mod(N))
    }

    fun verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean {
        if (publicKey.size != 32 || signature.size != 64) return false
        val p = liftX(int(publicKey)) ?: return false
        val r = int(signature.copyOfRange(0, 32))
        val s = int(signature.copyOfRange(32, 64))
        if (r >= P || s >= N) return false
        val e = int(taggedHash("BIP0340/challenge", signature.copyOfRange(0, 32), publicKey, message)).mod(N)
        val rp = add(mul(G, s), mul(p, N - e)) ?: return false
        return !rp.y.testBit(0) && rp.x == r
    }
}
