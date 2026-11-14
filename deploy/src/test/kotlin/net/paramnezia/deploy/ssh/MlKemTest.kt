package net.paramnezia.deploy.ssh

import org.bouncycastle.crypto.params.MLKEMParameters
import org.bouncycastle.crypto.params.MLKEMPrivateKeyParameters
import org.bouncycastle.crypto.params.MLKEMPublicKeyParameters
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.spec.X509EncodedKeySpec

class MlKemTest {
    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private val random = SecureRandom()

    @Test fun keccakMatchesFips202() {
        assertArrayEquals(hex("a7ffc6f8bf1ed76651c14756a061d662f580ff4de43b49fa82d80a4b80f8434a"), Keccak.sha3_256().digest(32))
        assertArrayEquals(hex("3a985da74fe225b2045c172d6bd390bd855f086e3e9d525b46bfe24511431532"), Keccak.sha3_256().update("abc".toByteArray()).digest(32))
        assertArrayEquals(hex("7f9c2ba4e88f827d616045507605853ed73b8093f6efbc88eb1a6eacfa66ef26"), Keccak.shake128().digest(32))
        assertArrayEquals(hex("46b9dd2b0ba88d13233b3feb743eeb243fcd52ea62b81b82b50c27646ed5762f"), Keccak.shake256().digest(32))
        // Against the JDK's SHA3 for every length around the block sizes.
        for (len in listOf(0, 1, 71, 72, 73, 135, 136, 137, 271, 272, 1000)) {
            val m = ByteArray(len).also(random::nextBytes)
            assertArrayEquals("256/$len", MessageDigest.getInstance("SHA3-256").digest(m), Keccak.sha3_256().update(m).digest(32))
            assertArrayEquals("512/$len", MessageDigest.getInstance("SHA3-512").digest(m), Keccak.sha3_512().update(m).digest(64))
        }
        // Squeezing in pieces equals squeezing at once.
        val whole = Keccak.shake128().update(byteArrayOf(1, 2, 3)).digest(1000)
        val k = Keccak.shake128().update(byteArrayOf(1, 2, 3))
        val parts = ByteArray(1000)
        var off = 0
        for (n in listOf(1, 167, 168, 169, 495)) { k.squeeze(parts, off, n); off += n }
        assertArrayEquals(whole, parts)
    }

    /** From the same seed (d, z), Bouncy Castle and we derive byte-identical keys. */
    @Test fun keysMatchBouncyCastle() {
        repeat(20) {
            val d = ByteArray(32).also(random::nextBytes); val z = ByteArray(32).also(random::nextBytes)
            val (ek, dk) = MlKemKotlin.keyPairInternal(d, z)
            val bc = MLKEMPrivateKeyParameters(MLKEMParameters.ml_kem_768, d + z)
            assertArrayEquals(bc.publicKeyParameters.encoded, ek)
            assertArrayEquals(bc.encoded, dk)
        }
    }

    /** Encapsulate with Bouncy Castle (and the JDK where it has ML-KEM), decapsulate with ours, and back. */
    @Test fun interoperatesBothWays() {
        repeat(20) {
            val (ek, dk) = MlKemKotlin.keyPair()
            val enc = org.bouncycastle.crypto.kems.MLKEMGenerator(random).generateEncapsulated(MLKEMPublicKeyParameters(MLKEMParameters.ml_kem_768, ek))
            assertArrayEquals(enc.secret, MlKemKotlin.decapsulate(dk, enc.encapsulation))
            // Ours encapsulating to a Bouncy Castle key.
            val bcKey = MLKEMPrivateKeyParameters(MLKEMParameters.ml_kem_768, ByteArray(64).also(random::nextBytes))
            val (secret, ct) = MlKemKotlin.encapsulate(bcKey.publicKeyParameters.encoded, ByteArray(32).also(random::nextBytes))
            assertArrayEquals(secret, org.bouncycastle.crypto.kems.MLKEMExtractor(bcKey).extractSecret(ct))
        }
        if (MlKem768.jdk) {
            val (ek, dk) = MlKemKotlin.keyPair()
            val spki = hex("308204b2300b0609608648016503040402038204a100") + ek
            val pub = KeyFactory.getInstance("ML-KEM").generatePublic(X509EncodedKeySpec(spki))
            val e = javax.crypto.KEM.getInstance("ML-KEM").newEncapsulator(pub).encapsulate()
            assertArrayEquals(e.key().encoded, MlKemKotlin.decapsulate(dk, e.encapsulation()))
        }
    }

    /** A tampered ciphertext gives a different, deterministic key (implicit rejection), never an error. */
    @Test fun tamperedCiphertextIsRejectedImplicitly() {
        val (ek, dk) = MlKemKotlin.keyPair()
        val (secret, ct) = MlKemKotlin.encapsulate(ek, ByteArray(32).also(random::nextBytes))
        val bad = ct.copyOf().also { it[100] = (it[100] + 1).toByte() }
        val k1 = MlKemKotlin.decapsulate(dk, bad)
        assertFalse(k1.contentEquals(secret))
        assertArrayEquals(k1, MlKemKotlin.decapsulate(dk, bad))
        assertArrayEquals(Keccak.shake256().update(dk, 2400 - 32, 32).update(bad).digest(32), k1)
        assertEquals(32, k1.size)
    }
}
