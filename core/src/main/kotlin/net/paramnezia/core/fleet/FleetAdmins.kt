package net.paramnezia.core.fleet

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import net.paramnezia.core.crypto.Curve25519
import net.paramnezia.core.crypto.WgKey
import net.paramnezia.core.profile.SshCredentials
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * A co-administrator: a family device the owner allowed to manage the family. It signs manifests
 * with its own key ([verifyKey]) and reaches the servers with its own SSH key ([sshPublic]), so the
 * owner can take the rights back from one device without touching anyone else.
 *
 * What a co-admin may not do (devices refuse such manifests): add or remove administrators, or
 * change their grants. Only the owner's key can.
 */
@Serializable
data class FleetCoAdmin(
    val name: String,
    /** The device's WireGuard public key, base64 (the device it was granted to). */
    val device: String,
    /** Ed25519 public key it signs manifests with, base64. */
    val verifyKey: String,
    /** OpenSSH public line of its key on the servers. */
    val sshPublic: String,
    val since: Long,
)

/** What a device receives to become a co-admin, readable only by that device (see [GrantBox]). */
@Serializable
data class AdminGrant(
    /** Ed25519 signing seed, base64. */
    val signingSeed: String,
    val verifyKey: String,
    /** SSH access to every server the owner manages, by fleet server id, with this device's own key. */
    val servers: Map<String, SshCredentials>,
    /** Host key fingerprints of those servers ("host:port" → "SHA256:…"): no trust-on-first-use gap. */
    val hostKeys: Map<String, String> = emptyMap(),
    /** The reserve channel, so a co-admin's changes reach devices when the servers do not. */
    val gistId: String? = null,
    val gistToken: String? = null,
    val issuedAt: Long = 0,
)

/** A grant sealed to one device's WireGuard key (X25519 + HKDF-SHA256 + AES-256-GCM). */
@Serializable
data class SealedGrant(
    /** Recipient: the device's WireGuard public key, base64. */
    val device: String,
    /** Ephemeral X25519 public key, base64. */
    val ephemeral: String,
    /** base64(nonce ‖ ciphertext ‖ tag). */
    val box: String,
)

/**
 * Seals secrets to a device's WireGuard key, in the manner of HPKE (RFC 9180) base mode with
 * DHKEM(X25519) and AES-256-GCM: a fresh ephemeral key per seal, the shared secret stretched by
 * HKDF-SHA256 over both public keys, and the recipient bound into the associated data. Every family
 * device can read the manifest; only the one device can open its grant.
 */
object GrantBox {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val random = SecureRandom()
    private const val INFO = "paramnezia-admin-grant-v1"

    fun seal(grant: AdminGrant, device: WgKey): SealedGrant {
        val eph = WgKey.generatePrivate()
        val ephPub = eph.publicKey()
        val key = derive(x25519(eph, device), ephPub, device)
        val nonce = ByteArray(12).also(random::nextBytes)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        c.updateAAD(device.bytes)
        val ct = c.doFinal(json.encodeToString(AdminGrant.serializer(), grant).toByteArray())
        return SealedGrant(device.toBase64(), ephPub.toBase64(), Base64.getEncoder().encodeToString(nonce + ct))
    }

    /** The grant, if [sealed] is addressed to [deviceKey] (this device's private key) and intact. */
    fun open(sealed: SealedGrant, deviceKey: WgKey): AdminGrant? = runCatching {
        val me = deviceKey.publicKey()
        if (sealed.device != me.toBase64()) return null
        val ephPub = WgKey.fromBase64(sealed.ephemeral)
        val key = derive(x25519(deviceKey, ephPub), ephPub, me)
        val raw = Base64.getDecoder().decode(sealed.box)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, raw, 0, 12))
        c.updateAAD(me.bytes)
        json.decodeFromString(AdminGrant.serializer(), String(c.doFinal(raw, 12, raw.size - 12)))
    }.getOrNull()

    private fun x25519(priv: WgKey, pub: WgKey): ByteArray {
        val out = ByteArray(32)
        Curve25519.eval(out, 0, priv.bytes, pub.bytes)
        require(out.any { it.toInt() != 0 }) { "degenerate key" }
        return out
    }

    /** HKDF-SHA256 (RFC 5869): extract with the two public keys as salt, expand to 32 bytes. */
    private fun derive(shared: ByteArray, ephPub: WgKey, recipient: WgKey): ByteArray {
        val extract = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(ephPub.bytes + recipient.bytes, "HmacSHA256")) }.doFinal(shared)
        val expand = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(extract, "HmacSHA256")) }
        return expand.doFinal(INFO.toByteArray() + byteArrayOf(1))
    }

    /** Stable comparison for "did a co-admin touch the grants". */
    internal fun digest(grants: List<SealedGrant>): String =
        Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(json.encodeToString(kotlinx.serialization.builtins.ListSerializer(SealedGrant.serializer()), grants).toByteArray()))
}
