package net.paramnezia.core.routing

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.paramnezia.core.fleet.Ed25519
import java.security.MessageDigest
import java.util.Base64

/**
 * Bypass lists between app releases. The weekly refresh (tools/bypass-lists, merged into main) is
 * published as one text bundle plus a small `lists.json`, signed by the same release key the app
 * already pins for updates, under its own signature domain (a signed release can never pass for
 * lists, or the other way round). Devices read `lists.json` once a day; only a newer version makes
 * them fetch the bundle (~210 KB), which must match the signed size and SHA-256 and pass the same
 * sanity checks as the bundled files before it replaces them.
 */
@Serializable
data class ListsRelease(
    /** The `version` of meta.json (yyyymmdd); a device takes only a newer one than it has. */
    val version: Long,
    /** Lowercase hex SHA-256 and size of the bundle's UTF-8 bytes. */
    val sha256: String,
    val size: Long,
    val urls: List<String>,
    val publishedAt: Long = 0,
)

@Serializable
data class SignedLists(val body: String, val sig: String) {
    fun signedBytes(): ByteArray = "$DOMAIN\n$body".toByteArray()

    companion object {
        const val DOMAIN = "paramnezia-lists-v1"
    }
}

object ListsCodec {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun sign(r: ListsRelease, seed: ByteArray, ed: Ed25519): SignedLists {
        val unsigned = SignedLists(json.encodeToString(ListsRelease.serializer(), r), "")
        return unsigned.copy(sig = Base64.getEncoder().encodeToString(ed.sign(seed, unsigned.signedBytes())))
    }

    fun encode(s: SignedLists): String = json.encodeToString(SignedLists.serializer(), s)
    fun decode(text: String): SignedLists? = runCatching { json.decodeFromString(SignedLists.serializer(), text) }.getOrNull()

    /** The release, if [s] is signed by one of [pinnedKeys] (base64 Ed25519); else null. */
    fun open(s: SignedLists, pinnedKeys: Collection<String>, ed: Ed25519): ListsRelease? {
        val sig = runCatching { Base64.getDecoder().decode(s.sig) }.getOrNull() ?: return null
        val ok = pinnedKeys.filter { it.isNotEmpty() }.any { k ->
            runCatching { ed.verify(Base64.getDecoder().decode(k), s.signedBytes(), sig) }.getOrDefault(false)
        }
        if (!ok) return null
        return runCatching { json.decodeFromString(ListsRelease.serializer(), s.body) }.getOrNull()
            ?.takeIf { it.sha256.matches(HEX64) && it.size in 1..MAX_BYTES }
    }

    private val HEX64 = Regex("[0-9a-f]{64}")
    const val MAX_BYTES = 4L shl 20
}

/** The bundle: every list file in one text, each as `=== <name> <chars>` followed by its content. */
object ListsBundle {
    /** Files a bundle must carry (assets/bypass). */
    val FILES = listOf("ipv4.txt", "ipv6.txt", "domains.txt", "blocklist.txt", "allow.txt", "apps.txt", "meta.json")
    private const val MAGIC = "#paramnezia-lists v1\n"

    fun pack(files: Map<String, String>): String = buildString {
        append(MAGIC)
        for (name in FILES) {
            val text = files[name] ?: error("missing $name")
            append("=== ").append(name).append(' ').append(text.length).append('\n').append(text).append('\n')
        }
    }

    fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    /** yyyymmdd from meta.json, or null. */
    fun version(metaJson: String): Long? = runCatching {
        Json.parseToJsonElement(metaJson).jsonObject["version"]?.jsonPrimitive?.content?.toLong()
    }.getOrNull()

    /**
     * The files of [text] if it is exactly what [r] signed (size, SHA-256) and looks like real lists:
     * every file present, meta.json of the same version, enough Russian networks to route by.
     * Anything else (truncated download, a mirror's mistake) is null and the lists in use stay.
     */
    fun unpack(text: String, r: ListsRelease): Map<String, String>? {
        val bytes = text.toByteArray()
        if (bytes.size.toLong() != r.size || sha256(text) != r.sha256) return null
        if (!text.startsWith(MAGIC)) return null
        val out = LinkedHashMap<String, String>()
        var i = MAGIC.length
        while (i < text.length) {
            val nl = text.indexOf('\n', i).takeIf { it > 0 } ?: return null
            val header = text.substring(i, nl).split(' ')
            if (header.size != 3 || header[0] != "===" || header[1] !in FILES) return null
            val len = header[2].toIntOrNull()?.takeIf { it >= 0 } ?: return null
            val start = nl + 1
            if (start + len + 1 > text.length || text[start + len] != '\n') return null
            out[header[1]] = text.substring(start, start + len)
            i = start + len + 1
        }
        if (out.keys != FILES.toSet()) return null
        if (version(out.getValue("meta.json")) != r.version) return null
        val v4 = runCatching { BypassLists.parsePrefixes(out.getValue("ipv4.txt")) }.getOrNull() ?: return null
        if (v4.size < MIN_V4 || runCatching { BypassLists.parsePrefixes(out.getValue("ipv6.txt")) }.isFailure) return null
        if (out.getValue("domains.txt").lineSequence().count { it.isNotBlank() } < MIN_DOMAINS) return null
        return out
    }

    /** Far below the real lists (8669 networks, 860 domains): a list this small is broken, not new. */
    private const val MIN_V4 = 1000
    private const val MIN_DOMAINS = 100
}
