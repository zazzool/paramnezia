package net.paramnezia.core.fleet

import net.paramnezia.core.crypto.WgKey
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * A preshared key of each family device's own, instead of the one key every peer of a server shares
 * (AmneziaVPN's `wireguard_psk.key`).
 *
 * The PSK is WireGuard's post-quantum layer: a handshake recorded today cannot be decrypted later
 * by breaking X25519 alone. A key shared by everyone stops being secret as soon as any one config
 * leaks (an exported `vpn://` key, a lost phone); then every peer of the server is down to plain
 * X25519. With a key per device a leak exposes only that device's own traffic, and a removed device
 * takes its key with it.
 *
 * Nothing new has to be stored or sent: the key is derived from an administrators' secret and the
 * device's public key, so any administrator can put the device on a new server later, and the device
 * gets its key once, inside its invite.
 */
object DevicePsk {
    private const val SEED_INFO = "paramnezia-device-psk-seed-v1"
    private const val KEY_INFO = "paramnezia-device-psk-v1|"

    /** The administrators' secret, derived from the owner's signing seed (co-admins get it in their grant). */
    fun seed(ownerSigningSeed: ByteArray): ByteArray = hmac(ownerSigningSeed, SEED_INFO.toByteArray())

    /** The PSK of the device with WireGuard public key [devicePublicKey] (base64). */
    fun of(seed: ByteArray, devicePublicKey: String): WgKey {
        require(seed.size >= 32) { "short seed" }
        return WgKey(hmac(seed, (KEY_INFO + devicePublicKey.trim()).toByteArray()))
    }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(data)
}
