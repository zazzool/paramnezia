package net.paramnezia.deploy

import net.paramnezia.core.profile.Protocol
import net.paramnezia.core.profile.SshCredentials
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Puts a signed fleet manifest where devices look for it. Mirrors are untrusted by design: the
 * manifest is encrypted and signed, so any static host will do.
 */
class FleetPublisher(private val hostKeys: HostKeyStore) {

    /** In-tunnel URL every Paramnezia server answers on (the server is always .1 of the subnet). */
    val tunnelUrl = TUNNEL_FLEET_URL

    /**
     * Stores [manifest] (null: only checks) on the server and makes sure its in-tunnel web server
     * answers, also on containers AmneziaVPN installed and after a reboot. Returns false when the
     * server stored the manifest but the in-tunnel web server could not be started.
     */
    fun publishToServer(creds: SshCredentials, manifest: String?, protocol: Protocol = Protocol.AWG): Boolean {
        SshConnection.open(creds, hostKeys).use { ssh ->
            val r = ssh.run(
                ServerManager.script("fleet-www.sh"),
                env = mapOf("CONTAINER" to protocol.amneziaContainer) + (manifest?.let { mapOf("MANIFEST" to it) } ?: emptyMap()),
                timeoutMs = 2 * 60_000,
            )
            r.error?.let { throw SshException("publish_failed", "Could not store manifest: $it") }
            if (r.exitCode != 0 || (manifest != null && r.results["stored"] != "1")) {
                throw SshException("publish_failed", "Could not store manifest: ${r.stderr.takeLast(1000)}")
            }
            return r.results["www"] == "ok"
        }
    }

    /**
     * Has the server fetch an app release itself (from [urls], HTTPS only) and verify it against
     * [sha256]; then serves it inside the tunnel next to [signedRelease] (devices update even when
     * every public host is blocked) and, if [forInvites], on the invite page. The phone uploads
     * nothing: the server downloads the same public release everyone gets.
     */
    fun mirrorRelease(
        creds: SshCredentials,
        sha256: String,
        urls: List<String>,
        signedRelease: String?,
        forInvites: Boolean,
        protocol: Protocol? = Protocol.AWG,
    ) {
        require(sha256.matches(Regex("[0-9a-f]{64}"))) { "bad sha256" }
        SshConnection.open(creds, hostKeys).use { ssh ->
            val env = buildMap {
                put("PN_SHA256", sha256)
                put("PN_URLS", urls.filter { it.startsWith("https://") }.joinToString("\n"))
                signedRelease?.let { put("PN_RELEASE", it) }
                protocol?.let { put("CONTAINER", it.amneziaContainer) }
                put("PN_INVITE", if (forInvites) "1" else "0")
            }
            val r = ssh.run(ServerManager.script("release-mirror.sh"), env = env, timeoutMs = 10 * 60_000)
            r.error?.let { throw SshException("mirror_failed", "Server could not get the app: $it") }
            if (r.exitCode != 0) throw SshException("mirror_failed", "Server could not get the app: ${r.stderr.takeLast(1000)}")
        }
    }

    /** In-tunnel mirror base for APKs; the hash is appended. */
    val tunnelApkMirror = TUNNEL_APK_MIRROR

    /** In-tunnel URL of the newest signed release. */
    val tunnelReleaseUrl = TUNNEL_RELEASE_URL

    /** Where a gist can be read without credentials: the raw file and the API (two hosts). */
    data class Gist(val id: String, val owner: String) {
        val rawUrl get() = "https://gist.githubusercontent.com/$owner/$id/raw/fleet.json"
        val apiUrl get() = "https://api.github.com/gists/$id"
        val mirrors get() = listOf(rawUrl, apiUrl)
    }

    /**
     * Stores [manifest] in a secret GitHub gist: updates [gistId], or creates a new gist when it is
     * null or gone. The token needs only the `gist` scope. The manifest is encrypted and signed, so
     * GitHub learns nothing and cannot change it.
     */
    fun publishToGist(token: String, gistId: String?, manifest: String): Gist {
        val body = buildJsonObject {
            put("description", "Paramnezia fleet (encrypted)")
            if (gistId == null) put("public", false)
            putJsonObject("files") { putJsonObject("fleet.json") { put("content", manifest) } }
        }.toString()
        if (gistId != null) {
            val (code, text) = github(token, "https://api.github.com/gists/$gistId", "PATCH", body)
            if (code in 200..299) return gistOf(text) ?: Gist(gistId, "-")
            if (code != 404) throw SshException("gist_failed", gistError(code))
        }
        val (code, text) = github(token, "https://api.github.com/gists", "POST", body)
        if (code !in 200..299) throw SshException("gist_failed", gistError(code))
        return gistOf(text) ?: throw SshException("gist_failed", "GitHub не вернул адрес gist")
    }

    private fun gistOf(text: String): Gist? = runCatching {
        val o = Json.parseToJsonElement(text).jsonObject
        Gist(o["id"]!!.jsonPrimitive.content, o["owner"]?.jsonObject?.get("login")?.jsonPrimitive?.content ?: "-")
    }.getOrNull()

    private fun gistError(code: Int) = when (code) {
        401 -> "GitHub не принял токен (неверный или истёк)"
        403, 404 -> "У токена нет права «gist»"
        else -> "GitHub ответил $code"
    }

    private fun github(token: String, url: String, method: String, body: String): Pair<Int, String> {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            // Android's HttpURLConnection speaks PATCH; the desktop JDK one does not.
            runCatching { requestMethod = method }.onFailure {
                requestMethod = "POST"
                setRequestProperty("X-HTTP-Method-Override", method)
            }
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("User-Agent", "paramnezia")
            connectTimeout = 15_000; readTimeout = 30_000
            doOutput = true
        }
        try {
            conn.outputStream.use { it.write(body.toByteArray()) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.readText().orEmpty()
            return code to text
        } finally { conn.disconnect() }
    }

    companion object {
        const val TUNNEL_FLEET_URL = "http://10.8.1.1:8080/fleet.json"
        const val TUNNEL_APK_MIRROR = "http://10.8.1.1:8080/apk"
        const val TUNNEL_RELEASE_URL = "http://10.8.1.1:8080/release.json"
    }
}
