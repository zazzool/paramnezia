package net.paramnezia.releasetool

import net.paramnezia.core.fleet.Ed25519
import net.paramnezia.core.ota.Release
import net.paramnezia.core.ota.ReleaseCodec
import net.paramnezia.core.routing.ListsBundle
import net.paramnezia.core.routing.ListsCodec
import net.paramnezia.core.routing.ListsRelease
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import kotlin.system.exitProcess

/**
 * Release tooling, run by CI (docs/RELEASE.md):
 *
 *   keygen                       new release key (keep the seed secret, pin the public key in the app)
 *   pubkey                       public half of PN_RELEASE_SEED, as a gradle.properties line (release check)
 *   sign --apk app.apk ...       writes the signed release.json for that APK
 *   sign-lists --dir D ...       bundles the bypass lists of D and writes the bundle and lists.json
 *
 * The APK is published exactly as apksigner produced it: no tool here changes APK bytes.
 * The seed comes from the PN_RELEASE_SEED environment variable (base64), never from the command line.
 */
fun main(args: Array<String>) {
    when (args.firstOrNull()) {
        "keygen" -> keygen()
        "pubkey" -> pubkey()
        "sign" -> sign(Args(args.drop(1)))
        "sign-lists" -> signLists(Args(args.drop(1)))
        else -> { System.err.println("usage: keygen | pubkey | sign --apk F --version-code N --version-name S --url URL... [--signer-sha256 HEX] [--notes S] --out F" +
            " | sign-lists --dir D --url-base URL --out-dir D"); exitProcess(2) }
    }
}

private val b64 = Base64.getEncoder()

private fun seed(): ByteArray = Base64.getDecoder().decode(System.getenv("PN_RELEASE_SEED") ?: fail("PN_RELEASE_SEED is not set"))

private fun keygen() {
    val (seed, pub) = Ed25519.Jca.generate()
    println("PN_RELEASE_SEED=${b64.encodeToString(seed)}")
    println("paramnezia.releaseKey=${b64.encodeToString(pub)}")
}

private fun pubkey() {
    println("paramnezia.releaseKey=${b64.encodeToString(Ed25519.Jca.publicFromSeed(seed()))}")
}

private fun sign(a: Args) {
    val seed = seed()
    val bytes = File(a.one("apk")).readBytes()
    val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    val urls = a.many("url")
    if (urls.none { it.startsWith("https://") }) fail("at least one https --url is required")
    val release = Release(
        packageName = a.opt("package") ?: "net.paramnezia",
        versionCode = a.one("version-code").toLong(),
        versionName = a.one("version-name"),
        minSdk = a.opt("min-sdk")?.toInt() ?: 26,
        sha256 = sha,
        size = bytes.size.toLong(),
        urls = urls,
        signerSha256 = a.opt("signer-sha256")?.lowercase()?.replace(":", "").orEmpty(),
        notes = a.opt("notes").orEmpty(),
        publishedAt = System.currentTimeMillis() / 1000,
    )
    File(a.one("out")).writeText(ReleaseCodec.encode(ReleaseCodec.sign(release, seed, Ed25519.Jca)))
    println("release ${release.versionName} (${release.versionCode}) sha256=$sha")
}

/** Writes `lists-<version>.txt` and the signed `lists.json` pointing at it (docs/RELEASE.md). */
private fun signLists(a: Args) {
    val seed = seed()
    val dir = File(a.one("dir"))
    val files = ListsBundle.FILES.associateWith { File(dir, it).readText() }
    val version = ListsBundle.version(files.getValue("meta.json")) ?: fail("no version in meta.json")
    val text = ListsBundle.pack(files)
    val name = "lists-$version.txt"
    val base = a.one("url-base").trimEnd('/')
    if (!base.startsWith("https://")) fail("--url-base must be https")
    val release = ListsRelease(version, ListsBundle.sha256(text), text.toByteArray().size.toLong(), listOf("$base/$name"), System.currentTimeMillis() / 1000)
    // The tool refuses to publish what a device would refuse to take.
    ListsBundle.unpack(text, release) ?: fail("the lists do not pass the device checks")
    val out = File(a.one("out-dir")).apply { mkdirs() }
    File(out, name).writeText(text)
    File(out, "lists.json").writeText(ListsCodec.encode(ListsCodec.sign(release, seed, Ed25519.Jca)))
    println("lists $version: $name ${release.size} bytes sha256=${release.sha256}")
}

private fun fail(msg: String): Nothing { System.err.println(msg); exitProcess(1) }

private class Args(list: List<String>) {
    private val map = HashMap<String, MutableList<String>>()
    init {
        var i = 0
        while (i < list.size) {
            val k = list[i].removePrefix("--")
            map.getOrPut(k) { mutableListOf() }.add(list.getOrElse(i + 1) { fail("missing value for --$k") })
            i += 2
        }
    }
    fun opt(k: String) = map[k]?.lastOrNull()
    fun one(k: String) = opt(k) ?: fail("--$k is required")
    fun many(k: String): List<String> = map[k].orEmpty()
}
