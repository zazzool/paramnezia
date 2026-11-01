package net.paramnezia.core.ota

import net.paramnezia.core.fleet.Ed25519
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.security.MessageDigest
import java.util.Base64

/**
 * One app release, as published in `release.json` (https://paramnezia.psync.ru/release.json and the
 * fleet servers' copies). Signed by the release key; the APK is identified by its SHA-256.
 */
@Serializable
data class Release(
    val packageName: String,
    val versionCode: Long,
    val versionName: String,
    val minSdk: Int = 26,
    /** Lowercase hex SHA-256 of the APK. */
    val sha256: String,
    val size: Long,
    /** Download URLs of the APK, preferred first. */
    val urls: List<String> = emptyList(),
    /** SHA-256 of the APK signing certificate, so a device can refuse a foreign build before downloading. */
    val signerSha256: String = "",
    val notes: String = "",
    val publishedAt: Long = 0,
) {
    /** URLs anyone can open in a browser (HTTPS only), for the update button and invite pages. */
    fun publicApkUrls(): List<String> = urls.filter { it.startsWith("https://") }
}

/** Wire form: the release JSON and an Ed25519 signature over it. */
@Serializable
data class SignedRelease(val body: String, val sig: String) {
    fun signedBytes(): ByteArray = "$DOMAIN\n$body".toByteArray()

    companion object {
        const val DOMAIN = "paramnezia-release-v1"
    }
}

object ReleaseCodec {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun sign(release: Release, seed: ByteArray, ed: Ed25519): SignedRelease {
        val body = json.encodeToString(Release.serializer(), release)
        val unsigned = SignedRelease(body, "")
        return unsigned.copy(sig = Base64.getEncoder().encodeToString(ed.sign(seed, unsigned.signedBytes())))
    }

    fun encode(s: SignedRelease): String = json.encodeToString(SignedRelease.serializer(), s)
    fun decode(text: String): SignedRelease? = runCatching { json.decodeFromString(SignedRelease.serializer(), text) }.getOrNull()

    /** Returns the release if it is signed by one of [pinnedKeys] (base64 Ed25519), else null. */
    fun open(s: SignedRelease, pinnedKeys: Collection<String>, ed: Ed25519): Release? {
        val sig = runCatching { Base64.getDecoder().decode(s.sig) }.getOrNull() ?: return null
        val ok = pinnedKeys.any { k ->
            runCatching { ed.verify(Base64.getDecoder().decode(k), s.signedBytes(), sig) }.getOrDefault(false)
        }
        if (!ok) return null
        return runCatching { json.decodeFromString(Release.serializer(), s.body) }.getOrNull()
    }
}

/**
 * Device-side decision logic. Keeps the newest verified release it has seen, from whatever channel
 * (the public release.json, the fleet server), and says whether it is worth installing.
 */
class UpdateChecker(
    private val packageName: String,
    private val installedVersionCode: Long,
    private val sdkInt: Int,
    private val pinnedKeys: Collection<String>,
    private val ed: Ed25519,
    /** SHA-256 of our own signing certificate; empty skips the check (debug builds). */
    private val ownSignerSha256: String = "",
) {
    var best: Pair<Release, SignedRelease>? = null
        private set

    /**
     * The signed release of the version that is installed now, if a channel brought it. The admin
     * needs it to hand the same APK to new devices (invite links, server mirrors) without ever
     * reading or uploading its own APK file.
     */
    var installed: Pair<Release, SignedRelease>? = null
        private set

    /** Offers a release from any channel. Returns true if it became the new best candidate. */
    fun offer(text: String): Boolean {
        val signed = ReleaseCodec.decode(text) ?: return false
        val r = ReleaseCodec.open(signed, pinnedKeys, ed) ?: return false
        if (r.packageName != packageName) return false
        if (!r.sha256.matches(HEX64)) return false
        if (r.versionCode == installedVersionCode && sameSigner(r)) { installed = r to signed; return false }
        if (r.versionCode <= installedVersionCode) return false
        if (r.minSdk > sdkInt) return false
        if (!sameSigner(r)) return false
        val current = best?.first
        if (current != null && current.versionCode >= r.versionCode) return false
        best = r to signed
        return true
    }

    private fun sameSigner(r: Release) =
        ownSignerSha256.isEmpty() || r.signerSha256.isEmpty() || r.signerSha256.equals(ownSignerSha256, true)

    companion object {
        private val HEX64 = Regex("[0-9a-f]{64}")
    }
}

/** Streams [input] to [sink] while hashing; fails if the size or hash differ from [release]. */
fun verifyingCopy(input: InputStream, sink: (ByteArray, Int) -> Unit, release: Release): Boolean {
    val md = MessageDigest.getInstance("SHA-256")
    val buf = ByteArray(64 * 1024)
    var total = 0L
    while (true) {
        val n = input.read(buf)
        if (n < 0) break
        total += n
        if (total > release.size) return false
        md.update(buf, 0, n)
        sink(buf, n)
    }
    return total == release.size && md.digest().joinToString("") { "%02x".format(it) } == release.sha256
}
