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

    /**
     * Updates a GitHub gist (create it once, secret is fine) and returns its raw URL, which devices
     * can read without any credentials.
     */
    fun publishToGist(token: String, gistId: String, manifest: String): String {
        val body = buildJsonObject {
            putJsonObject("files") { putJsonObject("fleet.json") { put("content", manifest) } }
        }.toString()
        val conn = (URL("https://api.github.com/gists/$gistId").openConnection() as HttpURLConnection).apply {
            // Android's HttpURLConnection speaks PATCH; the desktop JDK one does not.
            runCatching { requestMethod = "PATCH" }.onFailure {
                requestMethod = "POST"
                setRequestProperty("X-HTTP-Method-Override", "PATCH")
            }
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("Content-Type", "application/json")
            connectTimeout = 15_000; readTimeout = 30_000
            doOutput = true
        }
        conn.outputStream.use { it.write(body.toByteArray()) }
        val code = conn.responseCode
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.readText().orEmpty()
        if (code !in 200..299) throw SshException("gist_failed", "GitHub answered $code")
        val owner = runCatching {
            Json.parseToJsonElement(text).jsonObject["owner"]?.jsonObject?.get("login")?.jsonPrimitive?.content
        }.getOrNull()
        return "https://gist.githubusercontent.com/${owner ?: "-"}/$gistId/raw/fleet.json"
    }

    companion object {
        const val TUNNEL_FLEET_URL = "http://10.8.1.1:8080/fleet.json"
        const val TUNNEL_APK_MIRROR = "http://10.8.1.1:8080/apk"
        const val TUNNEL_RELEASE_URL = "http://10.8.1.1:8080/release.json"
    }
}
