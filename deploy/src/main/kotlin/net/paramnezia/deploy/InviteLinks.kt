package net.paramnezia.deploy

import net.paramnezia.core.amnezia.AmneziaFormat
import net.paramnezia.core.amnezia.AmneziaKey
import net.paramnezia.core.config.WgQuick
import net.paramnezia.core.fleet.Invite
import net.paramnezia.core.profile.SshCredentials
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.security.SecureRandom
import java.util.Base64

/**
 * One-time invite links served by the VPS itself: `http://<server>/#<token>`.
 *
 * The page looks at the device that opened it:
 *  - Android: step 1 downloads the official, unmodified release APK (the same bytes for everyone,
 *    from public HTTPS mirrors or this server's copy); step 2 opens the invite in the installed app.
 *    Nothing is personalised inside the APK and nothing downloads by itself: per-user APKs and
 *    drive-by downloads are exactly what Play Protect treats as malware distribution.
 *  - iPhone: the plain key and config for the original AmneziaVPN.
 *  - anything else: a choice.
 * The public part of the page links the newest releases for Android and Windows: GitHub's
 * `releases/latest/download/...` addresses (never out of date) and this server's own verified copies
 * (reachable whenever the page is, even where GitHub is not).
 * The first fetch of the invite claims the link (see `invite/get.cgi`).
 */
class InviteLinks(private val hostKeys: HostKeyStore) {

    /**
     * Where people without the app get it: the signed Android release the admin itself runs and,
     * if known, the newest signed Windows installer ([windows]).
     */
    data class AppDownload(
        val versionName: String,
        val sha256: String,
        val urls: List<String>,
        val windows: AppDownload? = null,
    ) {
        init { require(sha256.matches(HEX64)) { "bad sha256" } }

        internal fun json(): String = obj().toString()

        private fun obj(): JsonObject = buildJsonObject {
            put("version", versionName)
            put("sha256", sha256)
            putJsonArray("urls") { urls.filter { it.startsWith("https://") }.forEach { add(it) } }
            windows?.let { put("windows", it.obj()) }
        }
    }

    data class Link(val url: String, val token: String, val expiresAt: Long)

    /**
     * Stores an invite on the server and returns its link. [invite] is what Paramnezia imports
     * (a fleet invite or any `vpn://` key); AmneziaVPN gets it without the fleet part.
     */
    fun create(
        creds: SshCredentials,
        invite: String,
        name: String,
        app: AppDownload?,
        ttlMs: Long = 7 * DAY_MS,
        preferredPort: Int = 80,
        now: Long = System.currentTimeMillis(),
        /** The page was prepared earlier in this run of the app: skip re-sending it. */
        fast: Boolean = false,
    ): Link {
        val token = newToken()
        val key = Invite.plainKey(invite)
        val conf = AmneziaFormat.import(AmneziaKey.decode(key)).firstNotNullOfOrNull { it.tunnel }?.let(WgQuick::write).orEmpty()
        val expiresAt = now + ttlMs
        SshConnection.open(creds, hostKeys).use { ssh ->
            // The page is usually ready already (prepare() ran in the background): then only the
            // container's port is read and the container started if a reboot stopped it, instead of
            // sending the whole page and its pictures again. Anything missing: the full setup.
            val port = (if (fast) quickPort(ssh) else null) ?: ensurePage(ssh, preferredPort, app)
            val r = ssh.run(
                """
                set -eu
                $SUDO
                T=$token; D=$DATA; tmp=${'$'}D/t/.new-${'$'}T
                ${'$'}S mkdir -p "${'$'}tmp"
                printf '%s' "${'$'}PN_APP" | ${'$'}S tee "${'$'}tmp/app" >/dev/null
                printf '%s' "${'$'}PN_KEY" | ${'$'}S tee "${'$'}tmp/key" >/dev/null
                printf '%s' "${'$'}PN_CONF" | ${'$'}S tee "${'$'}tmp/conf" >/dev/null
                printf '%s' "${'$'}PN_NAME" | ${'$'}S tee "${'$'}tmp/name" >/dev/null
                printf '%s' "${'$'}PN_EXPIRES" | ${'$'}S tee "${'$'}tmp/expires" >/dev/null
                ${'$'}S chown -R 65534:65534 "${'$'}tmp"
                ${'$'}S mv "${'$'}tmp" "${'$'}D/t/${'$'}T"
                echo "::result::ok=1"
                """.trimIndent(),
                env = mapOf("PN_APP" to invite, "PN_KEY" to key, "PN_CONF" to conf, "PN_NAME" to name, "PN_EXPIRES" to (expiresAt / 1000).toString()),
                timeoutMs = 60_000,
            )
            if (r.results["ok"] != "1") throw SshException("invite_failed", "Could not store invite: ${r.stderr.takeLast(1000)}")
            return Link(url(creds.host, port, token), token, expiresAt)
        }
    }

    /** Gets a server ready for links ahead of time (page container, image pull, download info). */
    fun prepare(creds: SshCredentials, app: AppDownload?, preferredPort: Int = 80): String =
        SshConnection.open(creds, hostKeys).use { ssh -> pageUrl(creds.host, ensurePage(ssh, preferredPort, app)) }

    /** Port of the running page container (started if it was stopped), or null if there is none. */
    private fun quickPort(ssh: SshConnection): Int? {
        val r = runCatching {
            ssh.run(
                """
                $SUDO
                N=paramnezia-invite
                [ "${'$'}(${'$'}S docker inspect -f '{{.State.Running}}' ${'$'}N 2>/dev/null)" = true ] || ${'$'}S docker start ${'$'}N >/dev/null 2>&1 || exit 0
                p=${'$'}(${'$'}S docker inspect -f '{{range ${'$'}p, ${'$'}b := .HostConfig.PortBindings}}{{(index ${'$'}b 0).HostPort}}{{end}}' ${'$'}N 2>/dev/null)
                [ -d $DATA/t ] && [ -n "${'$'}p" ] && echo "::result::port=${'$'}p"
                """.trimIndent(),
                timeoutMs = 30_000,
            )
        }.getOrNull() ?: return null
        return r.results["port"]?.toIntOrNull()
    }

    private fun ensurePage(ssh: SshConnection, preferredPort: Int, app: AppDownload?): Int {
        val page = ssh.run(
            ServerManager.script("invite-server.sh"),
            env = mapOf(
                "PN_INVITE_PORT" to preferredPort.toString(),
                "PN_INVITE_INDEX" to ServerManager.script("invite/index.html"),
                "PN_INVITE_CGI" to ServerManager.script("invite/get.cgi"),
                "PN_INVITE_ASSETS" to PAGE_ASSETS.joinToString("\n") { "$it ${asset(it)}" },
                "PN_APP_JSON" to (app?.json() ?: ""),
            ),
            timeoutMs = 5 * 60_000,
        )
        return page.results["port"]?.toIntOrNull()
            ?: throw SshException("invite_failed", "Could not start the invite page: ${page.error ?: ""} ${page.stderr.takeLast(1000)}")
    }

    /** Which of [tokens] have been used already. */
    fun claimed(creds: SshCredentials, tokens: Collection<String>): Set<String> {
        if (tokens.isEmpty()) return emptySet()
        SshConnection.open(creds, hostKeys).use { ssh ->
            val r = ssh.run("$SUDO; \$S ls -1 $DATA/c 2>/dev/null || true", timeoutMs = 30_000)
            val used = r.stdout.lines().map { it.trim() }.toSet()
            return tokens.filter { it in used }.toSet()
        }
    }

    /** Takes back an unused link. */
    fun revoke(creds: SshCredentials, token: String) {
        require(token.matches(TOKEN)) { "bad token" }
        SshConnection.open(creds, hostKeys).use { ssh -> ssh.run("$SUDO; \$S rm -rf $DATA/t/$token", timeoutMs = 30_000) }
    }

    companion object {
        const val DAY_MS = 24 * 3600_000L
        private const val DATA = "/opt/amnezia/paramnezia/invite/data"
        private const val SUDO = "if [ \"\$(id -u)\" -eq 0 ]; then S=''; else S='sudo -n'; fi"
        private val TOKEN = Regex("[A-Za-z0-9_-]{16,64}")
        private val HEX64 = Regex("[0-9a-f]{64}")
        private val random = SecureRandom()

        /** Screenshots on the page (from the CI emulator run), shipped with the deploy module. */
        val PAGE_ASSETS = listOf("shot-off.webp", "shot-on.webp")

        private fun asset(name: String): String = Base64.getEncoder().encodeToString(
            InviteLinks::class.java.getResourceAsStream("/net/paramnezia/deploy/invite/$name")!!.readBytes(),
        )

        /** The page to share with family and friends: download, screenshots, how to connect. */
        fun pageUrl(host: String, port: Int): String {
            val h = if (':' in host && !host.startsWith("[")) "[$host]" else host
            return "http://$h" + (if (port == 80) "" else ":$port") + "/"
        }

        fun newToken(): String = ByteArray(16).also(random::nextBytes).let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }

        fun url(host: String, port: Int, token: String): String {
            val h = if (':' in host && !host.startsWith("[")) "[$host]" else host
            return "http://$h" + (if (port == 80) "" else ":$port") + "/#$token"
        }
    }
}
