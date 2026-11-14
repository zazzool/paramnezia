package net.paramnezia.deploy.ssh

import java.math.BigInteger
import java.security.MessageDigest

/**
 * bcrypt_pbkdf, the key derivation of passphrase-protected OpenSSH private keys, with the Blowfish
 * it is built on. Blowfish's initial state is the hexadecimal digits of π; rather than carry
 * 4 KB of constants, they are computed (Machin's formula) the first time a protected key is opened.
 */
internal object Bcrypt {
    private val PI_WORDS: IntArray by lazy { piWords(18 + 4 * 256) }

    /** The first [n] 32-bit words of π's fractional part in hexadecimal (0x243F6A88, …). */
    internal fun piWords(n: Int): IntArray {
        val frac = piScaled(n * 32).subtract(BigInteger.valueOf(3).shiftLeft(n * 32))
        return IntArray(n) { i -> frac.shiftRight((n - 1 - i) * 32).toInt() }
    }

    /** ⌊π·2^[bits]⌋, by Machin's formula with 64 guard bits. */
    internal fun piScaled(bits: Int): BigInteger {
        val prec = bits + 64
        val one = BigInteger.ONE.shiftLeft(prec)
        fun atanInv(x: Int): BigInteger {
            val x2 = BigInteger.valueOf(x.toLong() * x)
            var term = one.divide(BigInteger.valueOf(x.toLong()))
            var sum = term
            var k = 1L
            while (term.signum() != 0) {
                term = term.divide(x2)
                val t = term.divide(BigInteger.valueOf(2 * k + 1))
                sum = if (k % 2 == 1L) sum.subtract(t) else sum.add(t)
                k++
            }
            return sum
        }
        return atanInv(5).shiftLeft(4).subtract(atanInv(239).shiftLeft(2)).shiftRight(64)
    }

    private class Blowfish {
        val p = PI_WORDS.copyOfRange(0, 18)
        val s = PI_WORDS.copyOfRange(18, 18 + 1024)
        private var pos = 0

        private fun f(x: Int): Int =
            ((s[x ushr 24] + s[256 + ((x ushr 16) and 0xFF)]) xor s[512 + ((x ushr 8) and 0xFF)]) + s[768 + (x and 0xFF)]

        fun encipher(lr: IntArray, off: Int) {
            var l = lr[off]; var r = lr[off + 1]
            for (i in 0 until 16) { l = l xor p[i]; r = r xor f(l); val t = l; l = r; r = t }
            val t = l; l = r; r = t
            r = r xor p[16]; l = l xor p[17]
            lr[off] = l; lr[off + 1] = r
        }

        private fun word(data: ByteArray): Int {
            var w = 0
            repeat(4) { w = (w shl 8) or (data[pos].toInt() and 0xFF); pos = (pos + 1) % data.size }
            return w
        }

        fun expand(data: ByteArray?, key: ByteArray) {
            pos = 0
            for (i in 0 until 18) p[i] = p[i] xor word(key)
            pos = 0
            val lr = IntArray(2)
            fun next(): Unit = if (data != null) { lr[0] = lr[0] xor word(data); lr[1] = lr[1] xor word(data); encipher(lr, 0) } else encipher(lr, 0)
            var i = 0
            while (i < 18) { next(); p[i] = lr[0]; p[i + 1] = lr[1]; i += 2 }
            i = 0
            while (i < 1024) { next(); s[i] = lr[0]; s[i + 1] = lr[1]; i += 2 }
        }
    }

    private val MAGIC = "OxychromaticBlowfishSwatDynamite".toByteArray(Charsets.US_ASCII)

    private fun hash(sha2pass: ByteArray, sha2salt: ByteArray): ByteArray {
        val bf = Blowfish()
        bf.expand(sha2salt, sha2pass)
        repeat(64) { bf.expand(null, sha2salt); bf.expand(null, sha2pass) }
        val c = IntArray(8) { i -> (0 until 4).fold(0) { w, j -> (w shl 8) or (MAGIC[i * 4 + j].toInt() and 0xFF) } }
        repeat(64) { for (b in 0 until 4) bf.encipher(c, b * 2) }
        val out = ByteArray(32)
        for (i in 0 until 8) { out[4 * i + 3] = (c[i] ushr 24).toByte(); out[4 * i + 2] = (c[i] ushr 16).toByte(); out[4 * i + 1] = (c[i] ushr 8).toByte(); out[4 * i] = c[i].toByte() }
        return out
    }

    fun pbkdf(password: ByteArray, salt: ByteArray, keyLen: Int, rounds: Int): ByteArray {
        require(rounds >= 1 && keyLen in 1..1024)
        val sha = MessageDigest.getInstance("SHA-512")
        val sha2pass = sha.digest(password)
        val stride = (keyLen + 31) / 32
        var amt = (keyLen + stride - 1) / stride
        val key = ByteArray(keyLen)
        var remaining = keyLen
        var count = 1
        while (remaining > 0) {
            sha.update(salt)
            sha.update(byteArrayOf((count ushr 24).toByte(), (count ushr 16).toByte(), (count ushr 8).toByte(), count.toByte()))
            var tmp = hash(sha2pass, sha.digest())
            val out = tmp.copyOf()
            for (r in 1 until rounds) {
                tmp = hash(sha2pass, sha.digest(tmp))
                for (j in out.indices) out[j] = (out[j].toInt() xor tmp[j].toInt()).toByte()
            }
            amt = minOf(amt, remaining)
            var i = 0
            while (i < amt) {
                val dest = i * stride + (count - 1)
                if (dest >= keyLen) break
                key[dest] = out[i]
                i++
            }
            remaining -= i
            count++
        }
        return key
    }
}
