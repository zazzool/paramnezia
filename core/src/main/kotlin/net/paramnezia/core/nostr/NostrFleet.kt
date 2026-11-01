package net.paramnezia.core.nostr

import net.paramnezia.core.fleet.FleetManifest
import net.paramnezia.core.fleet.FleetTrust
import java.math.BigInteger
import java.security.MessageDigest
import java.util.Base64

/**
 * Decentralized fleet sync over Nostr relays. Every trusted device publishes the newest manifest it
 * knows under its own Nostr key, in a NIP-78 replaceable slot, to several independent relays.
 * Any device can then read the slots of all devices and keep the newest valid manifest.
 *
 * Relays see only opaque tags and our already encrypted and signed manifest. Keys are derived from
 * the fleet key, so devices need nothing beyond what the invite gave them.
 */
class NostrFleet(private val trust: FleetTrust) {

    /** Opaque d-tag shared by the fleet; reveals nothing about it. */
    val tag: String = sha256("paramnezia-fleet-tag-v1", trust.fleetId.toByteArray(), key()).toHex().take(32)

    /** Slot in which fleet devices re-publish the newest app release they verified. */
    val releaseTag: String get() = "$tag-r"

    /** The Nostr secret key of the device with WireGuard public key [devicePublicKey] (base64). */
    fun secretFor(devicePublicKey: String): ByteArray {
        var counter = 0
        while (true) {
            val h = sha256("paramnezia-nostr-v1", key(), devicePublicKey.toByteArray(), byteArrayOf(counter.toByte()))
            val v = BigInteger(1, h)
            if (v.signum() > 0 && v < ORDER) return h
            counter++
        }
    }

    fun pubkeyFor(devicePublicKey: String): String = Schnorr.publicKey(secretFor(devicePublicKey)).toHex()

    /** Nostr authors to query: every device of the manifest, plus the admin slot. */
    fun authors(manifest: FleetManifest): List<String> =
        (manifest.devices.map { it.publicKey } + ADMIN).distinct().map(::pubkeyFor)

    /** Event carrying [signedManifest] (FleetCrypto wire form), published as [devicePublicKey] or the admin. */
    fun manifestEvent(signedManifest: String, devicePublicKey: String = ADMIN, version: Long): NostrEvent =
        NostrEvent.create(
            secretFor(devicePublicKey),
            NostrEvent.KIND_APP_DATA,
            listOf(listOf("d", tag), listOf("v", version.toString())),
            signedManifest,
        )

    /** Valid events from fleet authors, newest version first (by the `v` tag; real check is the manifest signature). */
    fun candidates(events: Collection<NostrEvent>, manifest: FleetManifest?): List<String> {
        val allowed = manifest?.let { authors(it).toSet() } ?: setOf(pubkeyFor(ADMIN))
        return events.asSequence()
            .filter { it.kind == NostrEvent.KIND_APP_DATA && it.tag("d") == tag && it.pubkey in allowed && it.isValid() }
            .sortedByDescending { it.tag("v")?.toLongOrNull() ?: 0 }
            .map { it.content }
            .distinct()
            .toList()
    }

    private fun key() = Base64.getDecoder().decode(trust.fleetKey)

    companion object {
        /** Slot name for the admin's own publications, so even an empty fleet has one author. */
        const val ADMIN = "admin"

        /** Well-run public relays in different jurisdictions; users can add their own. */
        val DEFAULT_RELAYS = listOf(
            "wss://relay.damus.io",
            "wss://nos.lol",
            "wss://relay.nostr.band",
            "wss://nostr.mom",
            "wss://relay.primal.net",
            "wss://offchain.pub",
        )

        private val ORDER = BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141", 16)

        private fun sha256(domain: String, vararg parts: ByteArray): ByteArray {
            val md = MessageDigest.getInstance("SHA-256")
            md.update(domain.toByteArray()); md.update(0)
            parts.forEach { md.update(it); md.update(0) }
            return md.digest()
        }
    }
}
