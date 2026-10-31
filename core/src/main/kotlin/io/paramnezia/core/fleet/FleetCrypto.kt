package io.paramnezia.core.fleet

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.KeyFactory
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Ed25519 with raw 32-byte keys. Android below 13 has no JCA Ed25519, so the app plugs in the Go one. */
interface Ed25519 {
    /** Returns (seed, publicKey). */
    fun generate(): Pair<ByteArray, ByteArray>
    fun sign(seed: ByteArray, message: ByteArray): ByteArray
    fun verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean

    /** JCA implementation (JDK 15+, Android 13+). */
    object Jca : Ed25519 {
        private val PKCS8_PREFIX = hex("302e020100300506032b657004220420")
        private val X509_PREFIX = hex("302a300506032b6570032100")

        override fun generate(): Pair<ByteArray, ByteArray> {
            val seed = ByteArray(32).also(SecureRandom()::nextBytes)
            return seed to publicFromSeed(seed)
        }

        fun publicFromSeed(seed: ByteArray): ByteArray {
            // JCA derives public keys only inside a KeyPairGenerator, which takes its seed from the
            // supplied random source: feed it the seed we already have.
            val kpg = java.security.KeyPairGenerator.getInstance("Ed25519")
            kpg.initialize(255, FixedSeed(seed))
            val pub = kpg.generateKeyPair().public.encoded
            return pub.copyOfRange(pub.size - 32, pub.size)
        }

        override fun sign(seed: ByteArray, message: ByteArray): ByteArray {
            val key = KeyFactory.getInstance("Ed25519").generatePrivate(PKCS8EncodedKeySpec(PKCS8_PREFIX + seed))
            return Signature.getInstance("Ed25519").run { initSign(key); update(message); sign() }
        }

        override fun verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean = runCatching {
            val key = KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(X509_PREFIX + publicKey))
            Signature.getInstance("Ed25519").run { initVerify(key); update(message); verify(signature) }
        }.getOrDefault(false)

        private class FixedSeed(private val seed: ByteArray) : SecureRandom() {
            override fun nextBytes(bytes: ByteArray) = System.arraycopy(seed, 0, bytes, 0, bytes.size)
        }

        private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
}

/**
 * What a device needs to trust a fleet: the admin's verify key and the symmetric fleet key.
 * Handed over once, when the device joins.
 */
@Serializable
data class FleetTrust(
    val fleetId: String,
    /** Ed25519 public key, base64. */
    val verifyKey: String,
    /** AES-256 key, base64. */
    val fleetKey: String,
)

/** Wire form of a manifest: AES-GCM ciphertext signed by the admin. */
@Serializable
data class SignedManifest(
    val fleetId: String,
    val version: Long,
    /** base64(nonce || ciphertext || tag). */
    val body: String,
    /** base64 Ed25519 signature over [signedBytes]. */
    val sig: String,
) {
    /** The signature covers the fleet, the version and the ciphertext, so none can be swapped. */
    fun signedBytes(): ByteArray = "paramnezia-fleet-v1\n$fleetId\n$version\n$body".toByteArray()
}

object FleetCrypto {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val random = SecureRandom()

    fun newFleetKey(): String = Base64.getEncoder().encodeToString(ByteArray(32).also(random::nextBytes))

    fun seal(manifest: FleetManifest, fleetKey: String, signingSeed: ByteArray, ed: Ed25519): SignedManifest {
        val nonce = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(Base64.getDecoder().decode(fleetKey), "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(aad(manifest.fleetId, manifest.version))
        val ct = cipher.doFinal(json.encodeToString(FleetManifest.serializer(), manifest).toByteArray())
        val body = Base64.getEncoder().encodeToString(nonce + ct)
        val unsigned = SignedManifest(manifest.fleetId, manifest.version, body, "")
        return unsigned.copy(sig = Base64.getEncoder().encodeToString(ed.sign(signingSeed, unsigned.signedBytes())))
    }

    /** Verifies and decrypts; returns null for anything forged, foreign, corrupt or mislabeled. */
    fun open(signed: SignedManifest, trust: FleetTrust, ed: Ed25519): FleetManifest? {
        if (signed.fleetId != trust.fleetId) return null
        val sigOk = runCatching {
            ed.verify(Base64.getDecoder().decode(trust.verifyKey), signed.signedBytes(), Base64.getDecoder().decode(signed.sig))
        }.getOrDefault(false)
        if (!sigOk) return null
        return runCatching {
            val raw = Base64.getDecoder().decode(signed.body)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(Base64.getDecoder().decode(trust.fleetKey), "AES"), GCMParameterSpec(128, raw, 0, 12))
            cipher.updateAAD(aad(signed.fleetId, signed.version))
            val m = json.decodeFromString(FleetManifest.serializer(), cipher.doFinal(raw, 12, raw.size - 12).decodeToString())
            m.takeIf { it.fleetId == signed.fleetId && it.version == signed.version }
        }.getOrNull()
    }

    fun encode(signed: SignedManifest): String = json.encodeToString(SignedManifest.serializer(), signed)
    fun decode(text: String): SignedManifest? = runCatching { json.decodeFromString(SignedManifest.serializer(), text) }.getOrNull()

    private fun aad(fleetId: String, version: Long) = "$fleetId:$version".toByteArray()
}
