package net.paramnezia.deploy.ssh

import java.math.BigInteger
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECFieldFp
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPrivateKeySpec
import java.security.spec.ECPublicKeySpec
import java.security.spec.RSAPrivateCrtKeySpec
import java.security.spec.RSAPublicKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** A private key that cannot be used: [code] is "bad_key" (format) or "bad_passphrase". */
internal class SshKeyException(val code: String, message: String, cause: Throwable? = null) : Exception(message, cause)

/** A user key for public key authentication. */
internal sealed class SshPrivateKey {
    abstract val type: String
    abstract val publicBlob: ByteArray
    /** Signature algorithms to offer, best first, given the server's `server-sig-algs` (null: not sent). */
    open fun algorithms(serverSigAlgs: Set<String>?): List<String> = listOf(type)
    /** An SSH signature blob (string algorithm, string signature) over [data]. */
    abstract fun sign(algorithm: String, data: ByteArray): ByteArray

    class Ed25519(private val seed: ByteArray, val pub: ByteArray) : SshPrivateKey() {
        override val type = "ssh-ed25519"
        override val publicBlob: ByteArray = SshWriter().string(type).string(pub).bytes()
        override fun sign(algorithm: String, data: ByteArray): ByteArray =
            SshWriter().string(type).string(Curve25519.edSign(seed, pub, data)).bytes()
    }

    class Rsa(private val key: PrivateKey, n: BigInteger, e: BigInteger) : SshPrivateKey() {
        override val type = "ssh-rsa"
        override val publicBlob: ByteArray = SshWriter().string(type).mpint(e).mpint(n).bytes()
        override fun algorithms(serverSigAlgs: Set<String>?): List<String> = when (serverSigAlgs) {
            // No list: an older server. SHA-2 first, and SHA-1 only as the last resort it may need.
            null -> listOf("rsa-sha2-256", "rsa-sha2-512", "ssh-rsa")
            else -> listOf("rsa-sha2-512", "rsa-sha2-256").filter { it in serverSigAlgs }.ifEmpty { listOf("ssh-rsa") }
        }
        override fun sign(algorithm: String, data: ByteArray): ByteArray {
            val jca = when (algorithm) { "rsa-sha2-512" -> "SHA512withRSA"; "rsa-sha2-256" -> "SHA256withRSA"; else -> "SHA1withRSA" }
            val s = Signature.getInstance(jca).apply { initSign(key); update(data) }.sign()
            return SshWriter().string(algorithm).string(s).bytes()
        }
    }

    class Ecdsa(private val curve: EcCurve, private val key: PrivateKey, point: ByteArray) : SshPrivateKey() {
        override val type = curve.sshName
        override val publicBlob: ByteArray = SshWriter().string(type).string(curve.id).string(point).bytes()
        override fun sign(algorithm: String, data: ByteArray): ByteArray {
            val der = Signature.getInstance(curve.jcaSignature).apply { initSign(key); update(data) }.sign()
            val (r, s) = Der.parse(der).children().map { it.integer() }
            return SshWriter().string(type).string(SshWriter().mpint(r).mpint(s).bytes()).bytes()
        }
    }
}

/** The NIST curves SSH uses, with the bits of elliptic curve arithmetic JCA does not expose. */
internal enum class EcCurve(val id: String, val jcaName: String, val oid: String, val jcaSignature: String, val digest: String) {
    P256("nistp256", "secp256r1", "1.2.840.10045.3.1.7", "SHA256withECDSA", "SHA-256"),
    P384("nistp384", "secp384r1", "1.3.132.0.34", "SHA384withECDSA", "SHA-384"),
    P521("nistp521", "secp521r1", "1.3.132.0.35", "SHA512withECDSA", "SHA-512");

    val sshName get() = "ecdsa-sha2-$id"
    val params: ECParameterSpec by lazy {
        (KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec(jcaName)) }.generateKeyPair().public as ECPublicKey).params
    }
    private val p: BigInteger get() = (params.curve.field as ECFieldFp).p
    val fieldBytes: Int get() = (p.bitLength() + 7) / 8

    fun encode(q: ECPoint): ByteArray = byteArrayOf(4) + fixed(q.affineX) + fixed(q.affineY)
    private fun fixed(v: BigInteger): ByteArray {
        val b = v.toByteArray().let { if (it.size > fieldBytes) it.copyOfRange(it.size - fieldBytes, it.size) else it }
        return ByteArray(fieldBytes - b.size) + b
    }

    /** An uncompressed point, checked to lie on the curve. */
    fun decode(b: ByteArray): ECPoint {
        if (b.size != 1 + 2 * fieldBytes || b[0].toInt() != 4) throw GeneralSecurityException("bad EC point")
        val x = BigInteger(1, b.copyOfRange(1, 1 + fieldBytes)); val y = BigInteger(1, b.copyOfRange(1 + fieldBytes, b.size))
        val c = params.curve
        if (x >= p || y >= p || y.multiply(y).subtract(x.pow(3).add(c.a.multiply(x)).add(c.b)).mod(p).signum() != 0)
            throw GeneralSecurityException("EC point not on curve")
        return ECPoint(x, y)
    }

    /** d·G, for keys stored without their public half. Affine double-and-add: slow but rare. */
    fun multiplyBase(d: BigInteger): ECPoint {
        val a = params.curve.a
        fun add(p1: ECPoint?, p2: ECPoint?): ECPoint? {
            if (p1 == null) return p2
            if (p2 == null) return p1
            val l = if (p1.affineX == p2.affineX) {
                if ((p1.affineY + p2.affineY).mod(p).signum() == 0) return null
                p1.affineX.pow(2).multiply(BigInteger.valueOf(3)).add(a).multiply(p1.affineY.shiftLeft(1).modInverse(p))
            } else (p2.affineY - p1.affineY).multiply((p2.affineX - p1.affineX).modInverse(p))
            val x = (l.multiply(l) - p1.affineX - p2.affineX).mod(p)
            return ECPoint(x, (l.multiply(p1.affineX - x) - p1.affineY).mod(p))
        }
        var r: ECPoint? = null
        var q: ECPoint? = params.generator
        for (i in 0 until d.bitLength()) { if (d.testBit(i)) r = add(r, q); q = add(q, q) }
        return r ?: throw GeneralSecurityException("bad EC key")
    }

    companion object {
        fun byId(id: String) = entries.firstOrNull { it.id == id }
        fun byOid(oid: String) = entries.firstOrNull { it.oid == oid }
    }
}

/** Just enough DER for private key files and ECDSA signatures. */
internal class Der(val tag: Int, val content: ByteArray) {
    fun children(): List<Der> {
        val out = ArrayList<Der>()
        var pos = 0
        while (pos < content.size) { val (d, next) = read(content, pos); out += d; pos = next }
        return out
    }
    fun integer(): BigInteger = if (content.isEmpty()) BigInteger.ZERO else BigInteger(content)
    fun oid(): String {
        val parts = ArrayList<Long>()
        var v = 0L
        for (b in content) { v = (v shl 7) or (b.toLong() and 0x7F); if (b.toInt() and 0x80 == 0) { parts += v; v = 0 } }
        if (parts.isEmpty()) return ""
        val first = parts[0]
        val head = if (first < 80) listOf(first / 40, first % 40) else listOf(2, first - 80)
        return (head + parts.drop(1)).joinToString(".")
    }

    companion object {
        fun parse(b: ByteArray): Der = read(b, 0).first

        private fun read(b: ByteArray, start: Int): Pair<Der, Int> {
            var pos = start
            fun next(): Int { if (pos >= b.size) throw GeneralSecurityException("truncated DER"); return b[pos++].toInt() and 0xFF }
            val tag = next()
            var len = next()
            if (len and 0x80 != 0) {
                val n = len and 0x7F
                if (n == 0 || n > 4) throw GeneralSecurityException("bad DER length")
                len = 0
                repeat(n) { len = (len shl 8) or next() }
            }
            if (len < 0 || len > b.size - pos) throw GeneralSecurityException("truncated DER")
            return Der(tag, b.copyOfRange(pos, pos + len)) to pos + len
        }
    }
}

internal object SshKeys {
    private val random = SecureRandom()

    fun fingerprint(blob: ByteArray): String =
        "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(blob))

    // ---- host key signatures ----

    /** Host key algorithm families: the key types a server may hold, each with the signatures we accept. */
    val HOST_KEY_FAMILIES: List<List<String>> = listOf(
        listOf("ssh-ed25519"),
        listOf("ecdsa-sha2-nistp256"),
        listOf("ecdsa-sha2-nistp384"),
        listOf("ecdsa-sha2-nistp521"),
        listOf("rsa-sha2-512", "rsa-sha2-256"),
    )

    /** Whether [signatureBlob] is a valid [algorithm] signature of [data] by the host key [keyBlob]. */
    fun verify(algorithm: String, keyBlob: ByteArray, signatureBlob: ByteArray, data: ByteArray): Boolean = try {
        val key = SshReader(keyBlob)
        val keyType = key.text()
        val sig = SshReader(signatureBlob)
        val sigType = sig.text()
        val sigBytes = sig.string()
        if (sigType != algorithm || sig.remaining != 0) false
        else when (algorithm) {
            "ssh-ed25519" -> keyType == algorithm && Curve25519.edVerify(key.string(), data, sigBytes)
            "rsa-sha2-256", "rsa-sha2-512" -> {
                if (keyType != "ssh-rsa") false else {
                    val e = key.mpint(); val n = key.mpint()
                    if (n.bitLength() < 1024) throw GeneralSecurityException("RSA host key too small")
                    val pub = KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(n, e))
                    // Some servers drop leading zero bytes; JCA wants exactly the modulus length.
                    val len = (n.bitLength() + 7) / 8
                    val s = if (sigBytes.size < len) ByteArray(len - sigBytes.size) + sigBytes else sigBytes
                    Signature.getInstance(if (algorithm == "rsa-sha2-512") "SHA512withRSA" else "SHA256withRSA")
                        .apply { initVerify(pub); update(data) }.verify(s)
                }
            }
            else -> {
                val curve = EcCurve.entries.firstOrNull { it.sshName == algorithm }
                if (curve == null || keyType != algorithm || key.text() != curve.id) false else {
                    val q = curve.decode(key.string())
                    val pub = KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(q, curve.params))
                    val rs = SshReader(sigBytes)
                    val der = derSequence(derInteger(rs.mpint()), derInteger(rs.mpint()))
                    Signature.getInstance(curve.jcaSignature).apply { initVerify(pub); update(data) }.verify(der)
                }
            }
        }
    } catch (e: GeneralSecurityException) { false } catch (e: SshProtocolException) { false }

    private fun derLength(n: Int): ByteArray = when {
        n < 0x80 -> byteArrayOf(n.toByte())
        n < 0x100 -> byteArrayOf(0x81.toByte(), n.toByte())
        else -> byteArrayOf(0x82.toByte(), (n ushr 8).toByte(), n.toByte())
    }
    private fun derInteger(v: BigInteger): ByteArray { val b = v.toByteArray(); return byteArrayOf(2) + derLength(b.size) + b }
    private fun derSequence(vararg parts: ByteArray): ByteArray { val body = parts.fold(ByteArray(0)) { a, p -> a + p }; return byteArrayOf(0x30) + derLength(body.size) + body }

    // ---- key generation ----

    /** A new Ed25519 key: OpenSSH private key text (unencrypted) and the public line. Instant. */
    fun generateEd25519(comment: String): Pair<String, String> {
        val seed = Curve25519.edGenerateSeed(random)
        val pub = Curve25519.edPublic(seed)
        val blob = SshWriter().string("ssh-ed25519").string(pub).bytes()
        val check = random.nextInt()
        val priv = SshWriter().uint32(check).uint32(check).string("ssh-ed25519").string(pub).string(seed + pub).string(comment)
        var pad = 1
        while (priv.size % 8 != 0) priv.byte(pad++)
        val body = SshWriter().raw(OPENSSH_MAGIC).string("none").string("none").string(ByteArray(0)).uint32(1)
            .string(blob).string(priv.bytes()).bytes()
        val text = "-----BEGIN OPENSSH PRIVATE KEY-----\n" +
            Base64.getEncoder().encodeToString(body).chunked(70).joinToString("\n") +
            "\n-----END OPENSSH PRIVATE KEY-----\n"
        val line = "ssh-ed25519 " + Base64.getEncoder().encodeToString(blob) + if (comment.isEmpty()) "" else " $comment"
        return text to line
    }

    // ---- private key files ----

    private val OPENSSH_MAGIC = "openssh-key-v1\u0000".toByteArray(Charsets.US_ASCII)

    /**
     * Reads a private key in any form an admin is likely to paste: OpenSSH (`BEGIN OPENSSH PRIVATE
     * KEY`, bcrypt-protected or not), PEM (PKCS#1 RSA, SEC1 EC, PKCS#8, legacy-encrypted or
     * PKCS#8-encrypted) and PuTTY `.ppk` (v2, and v3 without a passphrase). Line breaks may have
     * been turned into spaces by a text field on the way.
     */
    fun parse(text: String, passphrase: String): SshPrivateKey {
        val t = text.replace("\r", "").trim()
        try {
            return when {
                t.startsWith("PuTTY-User-Key-File-") -> parsePpk(t, passphrase)
                "-----BEGIN OPENSSH PRIVATE KEY-----" in t -> parseOpenSsh(pemBody(t, "OPENSSH PRIVATE KEY").second, passphrase)
                "-----BEGIN RSA PRIVATE KEY-----" in t -> pkcs1(decryptLegacy(pemBody(t, "RSA PRIVATE KEY"), passphrase))
                "-----BEGIN EC PRIVATE KEY-----" in t -> sec1(decryptLegacy(pemBody(t, "EC PRIVATE KEY"), passphrase), null)
                "-----BEGIN PRIVATE KEY-----" in t -> pkcs8(pemBody(t, "PRIVATE KEY").second)
                "-----BEGIN ENCRYPTED PRIVATE KEY-----" in t -> pkcs8(decryptPkcs8(pemBody(t, "ENCRYPTED PRIVATE KEY").second, passphrase))
                else -> throw SshKeyException("bad_key", "Not a private key (expected OpenSSH, PEM or PuTTY format)")
            }
        } catch (e: SshKeyException) { throw e } catch (e: Exception) {
            throw SshKeyException("bad_key", "Could not read the private key: ${e.message}", e)
        }
    }

    /** Headers and base64 body between the PEM markers; tolerant of spaces in place of newlines. */
    private fun pemBody(t: String, label: String): Pair<Map<String, String>, ByteArray> {
        val begin = "-----BEGIN $label-----"; val end = "-----END $label-----"
        var inner = t.substringAfter(begin).substringBefore(end)
        val headers = HashMap<String, String>()
        for (h in listOf("Proc-Type", "DEK-Info")) {
            val m = Regex("$h:\\s*(\\S+)").find(inner) ?: continue
            headers[h] = m.groupValues[1]
            inner = inner.removeRange(m.range)
        }
        return headers to Base64.getMimeDecoder().decode(inner.filterNot { it.isWhitespace() })
    }

    private fun badPassphrase(empty: Boolean): Nothing =
        throw SshKeyException("bad_passphrase", if (empty) "The private key is protected by a passphrase" else "Wrong private key passphrase")

    // OpenSSH's own format.
    private fun parseOpenSsh(body: ByteArray, passphrase: String): SshPrivateKey {
        if (body.size < OPENSSH_MAGIC.size || !body.copyOf(OPENSSH_MAGIC.size).contentEquals(OPENSSH_MAGIC)) throw SshKeyException("bad_key", "Not an OpenSSH key")
        val r = SshReader(body, OPENSSH_MAGIC.size)
        val cipher = r.text(); val kdf = r.text(); val kdfOptions = r.string()
        if (r.uint32() != 1) throw SshKeyException("bad_key", "Key files with several keys are not supported")
        r.string() // public key; the private section repeats it
        val encrypted = r.string()
        val plain = if (cipher == "none") encrypted else {
            if (kdf != "bcrypt") throw SshKeyException("bad_key", "Unsupported key derivation $kdf")
            if (passphrase.isEmpty()) badPassphrase(true)
            val o = SshReader(kdfOptions); val salt = o.string(); val rounds = o.uint32()
            if (rounds !in 1..10_000) throw SshKeyException("bad_key", "Unreasonable bcrypt rounds $rounds")
            val spec = KEY_CIPHERS[cipher] ?: throw SshKeyException("bad_key", "Unsupported key cipher $cipher")
            val km = Bcrypt.pbkdf(passphrase.toByteArray(Charsets.UTF_8), salt, spec.keyLen + spec.ivLen, rounds)
            val key = SecretKeySpec(km, 0, spec.keyLen, "AES")
            val iv = km.copyOfRange(spec.keyLen, spec.keyLen + spec.ivLen)
            if (spec.gcm) {
                val tag = r.raw(16)
                try {
                    Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key, javax.crypto.spec.GCMParameterSpec(128, iv)) }.doFinal(encrypted + tag)
                } catch (e: GeneralSecurityException) { badPassphrase(false) }
            } else Cipher.getInstance(spec.jca).apply { init(Cipher.DECRYPT_MODE, key, IvParameterSpec(iv)) }.doFinal(encrypted)
        }
        val p = SshReader(plain)
        if (p.uint32() != p.uint32()) badPassphrase(passphrase.isEmpty())
        return when (val type = p.text()) {
            "ssh-ed25519" -> {
                val pub = p.string(); val sk = p.string()
                if (pub.size != 32 || sk.size != 64) throw SshKeyException("bad_key", "Bad Ed25519 key")
                SshPrivateKey.Ed25519(sk.copyOf(32), pub)
            }
            "ssh-rsa" -> {
                val n = p.mpint(); val e = p.mpint(); val d = p.mpint(); val iqmp = p.mpint(); val pp = p.mpint(); val q = p.mpint()
                rsa(n, e, d, pp, q, iqmp)
            }
            else -> {
                val curve = EcCurve.entries.firstOrNull { it.sshName == type } ?: throw SshKeyException("bad_key", "Unsupported key type $type")
                if (p.text() != curve.id) throw SshKeyException("bad_key", "Bad ECDSA key")
                val q = p.string(); val d = p.mpint()
                ecdsa(curve, d, q)
            }
        }
    }

    private class KeyCipher(val jca: String, val keyLen: Int, val ivLen: Int, val gcm: Boolean = false)
    private val KEY_CIPHERS = mapOf(
        "aes128-ctr" to KeyCipher("AES/CTR/NoPadding", 16, 16), "aes192-ctr" to KeyCipher("AES/CTR/NoPadding", 24, 16),
        "aes256-ctr" to KeyCipher("AES/CTR/NoPadding", 32, 16), "aes128-cbc" to KeyCipher("AES/CBC/NoPadding", 16, 16),
        "aes192-cbc" to KeyCipher("AES/CBC/NoPadding", 24, 16), "aes256-cbc" to KeyCipher("AES/CBC/NoPadding", 32, 16),
        "aes128-gcm@openssh.com" to KeyCipher("", 16, 12, gcm = true), "aes256-gcm@openssh.com" to KeyCipher("", 32, 12, gcm = true),
    )

    private fun rsa(n: BigInteger, e: BigInteger, d: BigInteger, p: BigInteger, q: BigInteger, iqmp: BigInteger?): SshPrivateKey {
        val crt = iqmp ?: q.modInverse(p)
        val spec = RSAPrivateCrtKeySpec(n, e, d, p, q, d.mod(p - BigInteger.ONE), d.mod(q - BigInteger.ONE), crt)
        return SshPrivateKey.Rsa(KeyFactory.getInstance("RSA").generatePrivate(spec), n, e)
    }

    private fun ecdsa(curve: EcCurve, d: BigInteger, point: ByteArray?): SshPrivateKey {
        val q = point?.let { curve.decode(it); it } ?: curve.encode(curve.multiplyBase(d))
        return SshPrivateKey.Ecdsa(curve, KeyFactory.getInstance("EC").generatePrivate(ECPrivateKeySpec(d, curve.params)), q)
    }

    // PEM: PKCS#1, SEC1, PKCS#8.
    private fun pkcs1(der: ByteArray): SshPrivateKey {
        val f = Der.parse(der).children().map { it.integer() }
        if (f.size < 9) throw SshKeyException("bad_key", "Bad RSA key")
        return rsa(f[1], f[2], f[3], f[4], f[5], f[8])
    }

    private fun sec1(der: ByteArray, curveFromPkcs8: EcCurve?): SshPrivateKey {
        val items = Der.parse(der).children()
        val d = BigInteger(1, items[1].content)
        var curve = curveFromPkcs8
        var pub: ByteArray? = null
        for (it in items.drop(2)) when (it.tag) {
            0xA0 -> curve = EcCurve.byOid(Der.parse(it.content).oid()) ?: curve
            0xA1 -> pub = Der.parse(it.content).content.let { bits -> bits.copyOfRange(1, bits.size) }
        }
        return ecdsa(curve ?: throw SshKeyException("bad_key", "Unsupported EC curve"), d, pub)
    }

    private fun pkcs8(der: ByteArray): SshPrivateKey {
        val items = Der.parse(der).children()
        val alg = items[1].children()
        val inner = items[2].content
        return when (val oid = alg[0].oid()) {
            "1.2.840.113549.1.1.1" -> pkcs1(inner)
            "1.2.840.10045.2.1" -> sec1(inner, EcCurve.byOid(alg[1].oid()))
            "1.3.101.112" -> {
                val seed = Der.parse(inner).content
                if (seed.size != 32) throw SshKeyException("bad_key", "Bad Ed25519 key")
                SshPrivateKey.Ed25519(seed, Curve25519.edPublic(seed))
            }
            else -> throw SshKeyException("bad_key", "Unsupported key algorithm $oid")
        }
    }

    /** "Proc-Type: 4,ENCRYPTED" PEM: OpenSSL's EVP_BytesToKey(MD5) and CBC. */
    private fun decryptLegacy(pem: Pair<Map<String, String>, ByteArray>, passphrase: String): ByteArray {
        val (headers, body) = pem
        if (headers["Proc-Type"]?.contains("ENCRYPTED") != true) return body
        if (passphrase.isEmpty()) badPassphrase(true)
        val (alg, ivHex) = (headers["DEK-Info"] ?: throw SshKeyException("bad_key", "No DEK-Info")).split(',', limit = 2)
        val iv = ivHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val (jca, keyLen) = when (alg.uppercase()) {
            "AES-128-CBC" -> "AES" to 16; "AES-192-CBC" -> "AES" to 24; "AES-256-CBC" -> "AES" to 32
            "DES-EDE3-CBC" -> "DESede" to 24
            else -> throw SshKeyException("bad_key", "Unsupported PEM cipher $alg")
        }
        val pass = passphrase.toByteArray(Charsets.UTF_8)
        val md5 = MessageDigest.getInstance("MD5")
        var key = ByteArray(0); var prev = ByteArray(0)
        while (key.size < keyLen) { md5.update(prev); md5.update(pass); md5.update(iv, 0, 8); prev = md5.digest(); key += prev }
        val plain = try {
            Cipher.getInstance("$jca/CBC/PKCS5Padding").apply { init(Cipher.DECRYPT_MODE, SecretKeySpec(key, 0, keyLen, jca), IvParameterSpec(iv)) }.doFinal(body)
        } catch (e: GeneralSecurityException) { badPassphrase(false) }
        // Right padding by chance (1 in 256) with the wrong passphrase still is not a key.
        if (runCatching { Der.parse(plain).children() }.isFailure) badPassphrase(false)
        return plain
    }

    /** PKCS#8 "ENCRYPTED PRIVATE KEY": PBES2 with PBKDF2 and AES or 3DES in CBC mode. */
    private fun decryptPkcs8(der: ByteArray, passphrase: String): ByteArray {
        if (passphrase.isEmpty()) badPassphrase(true)
        val (algId, data) = Der.parse(der).children()
        val alg = algId.children()
        if (alg[0].oid() != "1.2.840.113549.1.5.13") throw SshKeyException("bad_key", "Unsupported PKCS#8 encryption (PBES2 only)")
        val (kdf, enc) = alg[1].children()
        val kdfParts = kdf.children()
        if (kdfParts[0].oid() != "1.2.840.113549.1.5.12") throw SshKeyException("bad_key", "Unsupported PKCS#8 key derivation")
        val kp = kdfParts[1].children()
        val salt = kp[0].content
        val iterations = kp[1].integer().toInt()
        val prf = kp.drop(2).firstOrNull { it.tag == 0x30 }?.children()?.get(0)?.oid()
        val mac = when (prf) { null, "1.2.840.113549.2.7" -> "HmacSHA1"; "1.2.840.113549.2.9" -> "HmacSHA256"; "1.2.840.113549.2.10" -> "HmacSHA384"; "1.2.840.113549.2.11" -> "HmacSHA512"; else -> throw SshKeyException("bad_key", "Unsupported PRF $prf") }
        val encParts = enc.children()
        val (jca, keyLen) = when (val o = encParts[0].oid()) {
            "2.16.840.1.101.3.4.1.2" -> "AES" to 16; "2.16.840.1.101.3.4.1.22" -> "AES" to 24; "2.16.840.1.101.3.4.1.42" -> "AES" to 32
            "1.2.840.113549.3.7" -> "DESede" to 24
            else -> throw SshKeyException("bad_key", "Unsupported PKCS#8 cipher $o")
        }
        if (iterations !in 1..10_000_000) throw SshKeyException("bad_key", "Unreasonable PBKDF2 iterations")
        val key = pbkdf2(mac, passphrase.toByteArray(Charsets.UTF_8), salt, iterations, keyLen)
        val plain = try {
            Cipher.getInstance("$jca/CBC/PKCS5Padding").apply { init(Cipher.DECRYPT_MODE, SecretKeySpec(key, jca), IvParameterSpec(encParts[1].content)) }.doFinal(data.content)
        } catch (e: GeneralSecurityException) { badPassphrase(false) }
        if (runCatching { Der.parse(plain).children() }.isFailure) badPassphrase(false)
        return plain
    }

    private fun pbkdf2(alg: String, password: ByteArray, salt: ByteArray, iterations: Int, len: Int): ByteArray {
        val mac = Mac.getInstance(alg).apply { init(SecretKeySpec(password, alg)) }
        val out = ByteArray(len)
        var block = 1; var off = 0
        while (off < len) {
            mac.update(salt); mac.update(byteArrayOf((block ushr 24).toByte(), (block ushr 16).toByte(), (block ushr 8).toByte(), block.toByte()))
            var u = mac.doFinal(); val t = u.copyOf()
            for (i in 1 until iterations) { u = mac.doFinal(u); for (j in t.indices) t[j] = (t[j].toInt() xor u[j].toInt()).toByte() }
            val n = minOf(t.size, len - off); t.copyInto(out, off, 0, n); off += n; block++
        }
        return out
    }

    // PuTTY.
    private fun parsePpk(t: String, passphrase: String): SshPrivateKey {
        val lines = t.lines()
        val fields = HashMap<String, String>()
        var i = 0
        fun block(count: Int): ByteArray = Base64.getDecoder().decode(lines.subList(i, i + count).joinToString("") { it.trim() }).also { i += count }
        var pub = ByteArray(0); var priv = ByteArray(0)
        while (i < lines.size) {
            val line = lines[i++]
            val key = line.substringBefore(':').trim(); val value = line.substringAfter(':', "").trim()
            when (key) {
                "Public-Lines" -> pub = block(value.toInt())
                "Private-Lines" -> priv = block(value.toInt())
                else -> fields[key] = value
            }
        }
        val version = fields.keys.firstOrNull { it.startsWith("PuTTY-User-Key-File-") }?.removePrefix("PuTTY-User-Key-File-")?.toIntOrNull()
            ?: throw SshKeyException("bad_key", "Bad PuTTY key")
        val type = fields["PuTTY-User-Key-File-$version"]!!
        val encryption = fields["Encryption"] ?: "none"
        val comment = fields["Comment"] ?: ""
        val pass = passphrase.toByteArray(Charsets.UTF_8)
        val macKey: ByteArray
        val macAlg: String
        when (version) {
            2 -> {
                if (encryption == "aes256-cbc") {
                    if (passphrase.isEmpty()) badPassphrase(true)
                    val sha = MessageDigest.getInstance("SHA-1")
                    val k = sha.digest(byteArrayOf(0, 0, 0, 0) + pass) + sha.digest(byteArrayOf(0, 0, 0, 1) + pass)
                    priv = Cipher.getInstance("AES/CBC/NoPadding").apply { init(Cipher.DECRYPT_MODE, SecretKeySpec(k, 0, 32, "AES"), IvParameterSpec(ByteArray(16))) }.doFinal(priv)
                } else if (encryption != "none") throw SshKeyException("bad_key", "Unsupported PuTTY encryption $encryption")
                macKey = MessageDigest.getInstance("SHA-1").digest("putty-private-key-file-mac-key".toByteArray() + if (encryption == "none") ByteArray(0) else pass)
                macAlg = "HmacSHA1"
            }
            3 -> {
                if (encryption != "none") throw SshKeyException("bad_key", "PuTTY keys with a passphrase (format 3, Argon2) are not supported: in PuTTYgen use Conversions → Export OpenSSH key")
                macKey = ByteArray(0); macAlg = "HmacSHA256"
            }
            else -> throw SshKeyException("bad_key", "Unsupported PuTTY key version $version")
        }
        val macData = SshWriter().string(type).string(encryption).string(comment).string(pub).string(priv).bytes()
        val mac = Mac.getInstance(macAlg).apply { init(if (macKey.isEmpty()) EmptyKey(macAlg) else SecretKeySpec(macKey, macAlg)) }.doFinal(macData)
        val expected = (fields["Private-MAC"] ?: "").chunked(2).mapNotNull { it.toIntOrNull(16)?.toByte() }.toByteArray()
        if (!MessageDigest.isEqual(mac, expected)) { if (encryption != "none") badPassphrase(false) else throw SshKeyException("bad_key", "PuTTY key is damaged (MAC mismatch)") }
        val pr = SshReader(pub); val sr = SshReader(priv)
        pr.text()
        return when (type) {
            "ssh-ed25519" -> { val pk = pr.string(); val seed = sr.string(); SshPrivateKey.Ed25519(seed, pk) }
            "ssh-rsa" -> { val e = pr.mpint(); val n = pr.mpint(); rsa(n, e, sr.mpint(), sr.mpint(), sr.mpint(), sr.mpint()) }
            else -> {
                val curve = EcCurve.entries.firstOrNull { it.sshName == type } ?: throw SshKeyException("bad_key", "Unsupported key type $type")
                pr.text(); val q = pr.string()
                ecdsa(curve, sr.mpint(), q)
            }
        }
    }

    /** HMAC with an empty key (PuTTY v3 unencrypted): SecretKeySpec refuses empty arrays. */
    private class EmptyKey(private val alg: String) : javax.crypto.SecretKey {
        override fun getAlgorithm() = alg
        override fun getFormat() = "RAW"
        override fun getEncoded() = ByteArray(0)
    }
}
