package io.paramnezia.deploy

import io.paramnezia.core.profile.Protocol
import io.paramnezia.core.profile.SshCredentials
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
    val tunnelUrl = "http://10.8.1.1:8080/fleet.json"

    fun publishToServer(creds: SshCredentials, manifest: String, protocol: Protocol = Protocol.AWG) {
        SshConnection.open(creds, hostKeys).use { ssh ->
            val r = ssh.run(
                """
                set -eu
                if [ "${'$'}(id -u)" -eq 0 ]; then SUDO=""; else SUDO="sudo -n"; fi
                printf '%s' "${'$'}MANIFEST" | ${'$'}SUDO docker exec -i "${'$'}CONTAINER" sh -c \
                  'mkdir -p /opt/amnezia/paramnezia/www && cat > /opt/amnezia/paramnezia/www/fleet.json.new && mv /opt/amnezia/paramnezia/www/fleet.json.new /opt/amnezia/paramnezia/www/fleet.json'
                echo "::result::ok=1"
                """.trimIndent(),
                env = mapOf("MANIFEST" to manifest, "CONTAINER" to protocol.amneziaContainer),
            )
            if (r.results["ok"] != "1") throw SshException("publish_failed", "Could not store manifest: ${r.stderr.takeLast(1000)}")
        }
    }

    /**
     * Puts an app release on the server, so devices can update through the tunnel even when every
     * public mirror is blocked: the APK under `apk/<sha256>` and the signed release as `release.json`.
     * Older APKs are removed. Devices find it at [tunnelApkMirror].
     */
    fun publishRelease(creds: SshCredentials, signedRelease: String, sha256: String, apk: java.io.InputStream, protocol: Protocol = Protocol.AWG) {
        require(sha256.matches(Regex("[0-9a-f]{64}"))) { "bad sha256" }
        SshConnection.open(creds, hostKeys).use { ssh ->
            val sudo = "if [ \"\$(id -u)\" -eq 0 ]; then S=''; else S='sudo -n'; fi"
            val www = "/opt/amnezia/paramnezia/www"
            val c = protocol.amneziaContainer
            val up = ssh.pipe(
                "$sudo; \$S docker exec -i $c sh -c 'mkdir -p $www/apk && cat > $www/apk/$sha256.new && " +
                    "find $www/apk -type f ! -name $sha256.new -delete && mv $www/apk/$sha256.new $www/apk/$sha256' && echo ok",
                apk,
            )
            if (up.stdout.trim() != "ok") throw SshException("publish_failed", "Could not upload APK: ${up.stderr.takeLast(1000)}")
            val r = ssh.pipe(
                "$sudo; \$S docker exec -i $c sh -c 'cat > $www/release.json.new && mv $www/release.json.new $www/release.json' && echo ok",
                signedRelease.byteInputStream(),
            )
            if (r.stdout.trim() != "ok") throw SshException("publish_failed", "Could not store release: ${r.stderr.takeLast(1000)}")
        }
    }

    /** In-tunnel mirror base for APKs; the hash is appended. */
    val tunnelApkMirror = "http://10.8.1.1:8080/apk"

    /** In-tunnel URL of the newest signed release. */
    val tunnelReleaseUrl = "http://10.8.1.1:8080/release.json"

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
}
