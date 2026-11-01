package net.paramnezia.releasetool

import net.paramnezia.core.fleet.Ed25519
import net.paramnezia.core.nostr.NostrEvent
import net.paramnezia.core.nostr.NostrFleet
import net.paramnezia.core.nostr.NostrRelease
import net.paramnezia.core.nostr.RelayFrame
import net.paramnezia.core.ota.Release
import net.paramnezia.core.ota.ReleaseCodec
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.WebSocket
import java.security.MessageDigest
import java.time.Duration
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/**
 * Signs and spreads an app release:
 *
 *   keygen                       new release key (keep the seed secret, pin the public key in the app)
 *   pubkey                       public halves of PN_RELEASE_SEED, as gradle.properties lines (release check)
 *   publish --apk app.apk ...    sign the release, upload the APK to Blossom servers, post it to relays
 *
 * The APK is published exactly as apksigner produced it: no tool here changes APK bytes.
 *
 * The seed comes from the PN_RELEASE_SEED environment variable (base64), never from the command line.
 */
fun main(args: Array<String>) {
    when (args.firstOrNull()) {
        "keygen" -> keygen()
        "pubkey" -> pubkey()
        "publish" -> publish(Args(args.drop(1)))
        else -> { System.err.println("usage: keygen | pubkey | publish --apk F --version-code N --version-name S [--blossom URL]... [--relay URL]... [--url URL]... [--signer-sha256 HEX] [--notes S] [--out F]"); exitProcess(2) }
    }
}

private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).followRedirects(HttpClient.Redirect.NORMAL).build()
private val b64 = Base64.getEncoder()

private fun keygen() {
    val (seed, pub) = Ed25519.Jca.generate()
    println("PN_RELEASE_SEED=${b64.encodeToString(seed)}")
    println("release public key (pin in app): ${b64.encodeToString(pub)}")
    println("release nostr pubkey (pin in app): ${NostrRelease.publisherPubkey(seed)}")
}

private fun pubkey() {
    val seed = Base64.getDecoder().decode(System.getenv("PN_RELEASE_SEED") ?: fail("PN_RELEASE_SEED is not set"))
    println("paramnezia.releaseKey=${b64.encodeToString(Ed25519.Jca.publicFromSeed(seed))}")
    println("paramnezia.releaseNostr=${NostrRelease.publisherPubkey(seed)}")
}

private fun publish(a: Args) {
    val seed = Base64.getDecoder().decode(System.getenv("PN_RELEASE_SEED") ?: fail("PN_RELEASE_SEED is not set"))
    val apk = File(a.one("apk"))
    val bytes = apk.readBytes()
    val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    val nostrSecret = NostrRelease.secretFromSeed(seed)

    val blossom = a.many("blossom").ifEmpty { DEFAULT_BLOSSOM }
    val stored = blossom.filter { server -> runCatching { upload(server, bytes, sha, nostrSecret) }.onFailure { System.err.println("blossom $server: ${it.message}") }.getOrDefault(false) }
    if (stored.isEmpty() && a.many("url").isEmpty()) fail("APK is not reachable anywhere: no Blossom upload succeeded and no --url given")

    val release = Release(
        packageName = a.opt("package") ?: "net.paramnezia",
        versionCode = a.one("version-code").toLong(),
        versionName = a.one("version-name"),
        minSdk = a.opt("min-sdk")?.toInt() ?: 26,
        sha256 = sha,
        size = bytes.size.toLong(),
        urls = a.many("url"),
        blossom = stored,
        signerSha256 = a.opt("signer-sha256")?.lowercase()?.replace(":", "").orEmpty(),
        notes = a.opt("notes").orEmpty(),
        publishedAt = System.currentTimeMillis() / 1000,
    )
    val signed = ReleaseCodec.encode(ReleaseCodec.sign(release, seed, Ed25519.Jca))
    a.opt("out")?.let { File(it).writeText(signed) }

    val event = NostrRelease.event(signed, nostrSecret, release.versionCode)
    val relays = a.many("relay").ifEmpty { NostrFleet.DEFAULT_RELAYS }
    val accepted = relays.count { relay -> runCatching { post(relay, event) }.onFailure { System.err.println("relay $relay: ${it.message}") }.getOrDefault(false) }
    println("release ${release.versionName} (${release.versionCode}) sha256=$sha blossom=${stored.size}/${blossom.size} relays=$accepted/${relays.size}")
    if (accepted == 0) fail("no relay accepted the release event")
}

/** BUD-02 upload, authorized by a kind 24242 event. Returns true when the server stored our hash. */
private fun upload(server: String, bytes: ByteArray, sha: String, secret: ByteArray): Boolean {
    val now = System.currentTimeMillis() / 1000
    val auth = NostrEvent.create(
        secret, 24242,
        listOf(listOf("t", "upload"), listOf("x", sha), listOf("expiration", (now + 600).toString())),
        "Upload Paramnezia APK",
    )
    val req = HttpRequest.newBuilder(URI.create(server.trimEnd('/') + "/upload"))
        .timeout(Duration.ofMinutes(5))
        .header("Authorization", "Nostr " + b64.encodeToString(auth.toJson().toByteArray()))
        .header("Content-Type", "application/vnd.android.package-archive")
        .PUT(HttpRequest.BodyPublishers.ofByteArray(bytes))
        .build()
    val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
    if (resp.statusCode() !in 200..299) error("HTTP ${resp.statusCode()} ${resp.headers().firstValue("X-Reason").orElse("")}")
    return sha in resp.body()
}

/** Sends one event and waits for the relay's OK. */
private fun post(relay: String, event: NostrEvent): Boolean {
    val result = CompletableFuture<Boolean>()
    val listener = object : WebSocket.Listener {
        val buf = StringBuilder()
        override fun onText(ws: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
            buf.append(data)
            if (last) {
                val f = NostrEvent.parseRelayFrame(buf.toString()); buf.setLength(0)
                if (f is RelayFrame.Ok && f.eventId == event.id) result.complete(f.accepted)
            }
            ws.request(1)
            return null
        }
        override fun onError(ws: WebSocket, error: Throwable) { result.completeExceptionally(error) }
    }
    val ws = http.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(15)).buildAsync(URI.create(relay), listener).get(20, TimeUnit.SECONDS)
    ws.sendText(NostrEvent.publishFrame(event), true)
    return try { result.get(20, TimeUnit.SECONDS) } finally { ws.sendClose(WebSocket.NORMAL_CLOSURE, "") }
}

/** Public Blossom servers that accept uploads from any key. */
private val DEFAULT_BLOSSOM = listOf("https://blossom.primal.net", "https://cdn.satellite.earth", "https://blossom.band")

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
