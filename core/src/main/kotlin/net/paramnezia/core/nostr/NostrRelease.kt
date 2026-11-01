package net.paramnezia.core.nostr

import java.math.BigInteger
import java.security.MessageDigest

/**
 * App releases over Nostr. The release key publishes signed release manifests in a public NIP-78
 * slot; every trusted device re-publishes the newest one it verified in its own fleet slot, so an
 * update keeps spreading even if the release key's events are filtered by some relays.
 * Relays are only carriers: the app checks the release key's Ed25519 signature inside the content.
 */
object NostrRelease {
    const val TAG = "paramnezia-release-v1"

    /** Nostr secret for the release publisher, derived from the release Ed25519 seed. */
    fun secretFromSeed(releaseSeed: ByteArray): ByteArray {
        var counter = 0
        while (true) {
            val md = MessageDigest.getInstance("SHA-256")
            md.update("paramnezia-release-nostr-v1".toByteArray()); md.update(0)
            md.update(releaseSeed); md.update(counter.toByte())
            val h = md.digest()
            val v = BigInteger(1, h)
            if (v.signum() > 0 && v < ORDER) return h
            counter++
        }
    }

    fun publisherPubkey(releaseSeed: ByteArray): String = Schnorr.publicKey(secretFromSeed(releaseSeed)).toHex()

    fun event(signedRelease: String, secret: ByteArray, versionCode: Long, dTag: String = TAG): NostrEvent =
        NostrEvent.create(
            secret, NostrEvent.KIND_APP_DATA,
            listOf(listOf("d", dTag), listOf("v", versionCode.toString())),
            signedRelease,
        )

    /** Contents of valid events from [authors] in slot [dTag], newest version first. */
    fun candidates(events: Collection<NostrEvent>, authors: Set<String>, dTag: String = TAG): List<String> =
        events.asSequence()
            .filter { it.kind == NostrEvent.KIND_APP_DATA && it.tag("d") == dTag && it.pubkey in authors && it.isValid() }
            .sortedByDescending { it.tag("v")?.toLongOrNull() ?: 0 }
            .map { it.content }
            .distinct()
            .toList()

    private val ORDER = BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141", 16)
}
