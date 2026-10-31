package io.paramnezia.deploy

import io.paramnezia.core.amnezia.AmneziaFormat
import io.paramnezia.core.amnezia.AmneziaKey
import io.paramnezia.core.apk.ApkSlot
import io.paramnezia.core.config.WgQuick
import io.paramnezia.core.fleet.Invite
import io.paramnezia.core.profile.SshCredentials
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * One-time invite links served by the VPS itself: `http://<server>/#<token>`.
 *
 * The page looks at the device that opened it. Android gets Paramnezia with the invite already
 * inside the APK (see [ApkSlot]), so the app is set up the moment it starts; iPhone gets the plain
 * key and config for the original AmneziaVPN; anything else is offered a choice. The first download
 * claims the link (see `invite/get.cgi`).
 */
class InviteLinks(private val hostKeys: HostKeyStore) {

    /** An APK prepared with [ApkSlot.prepare]: the template the server personalizes per download. */
    class ApkTemplate(val file: File, val slotOffset: Long) {
        val sha256: String by lazy { sha256(file) }
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
        apk: ApkTemplate?,
        ttlMs: Long = 7 * DAY_MS,
        preferredPort: Int = 80,
        now: Long = System.currentTimeMillis(),
    ): Link {
        val token = newToken()
        val key = Invite.plainKey(invite)
        val conf = AmneziaFormat.import(AmneziaKey.decode(key)).firstNotNullOfOrNull { it.tunnel }?.let(WgQuick::write).orEmpty()
        val expiresAt = now + ttlMs
        SshConnection.open(creds, hostKeys).use { ssh ->
            val port = ensurePage(ssh, preferredPort)
            if (apk != null) uploadTemplate(ssh, apk)
            // One round trip: the slot comes in on stdin, the texts in the environment.
            val slot = if (apk != null && invite.toByteArray().size <= ApkSlot.MAX_PAYLOAD) ApkSlot.encode(invite) else ByteArray(0)
            val env = mapOf("PN_APP" to invite, "PN_KEY" to key, "PN_CONF" to conf, "PN_NAME" to name, "PN_EXPIRES" to (expiresAt / 1000).toString())
            val r = ssh.pipe(
                env.entries.joinToString("") { (k, v) -> "export $k=${SshConnection.shellQuote(v)}; " } +
                """
                set -eu
                $SUDO
                T=$token; D=$DATA; tmp=${'$'}D/t/.new-${'$'}T
                ${'$'}S mkdir -p "${'$'}tmp"
                ${'$'}S sh -c "cat > ${'$'}tmp/slot"
                [ -s "${'$'}tmp/slot" ] || ${'$'}S rm -f "${'$'}tmp/slot"
                printf '%s' "${'$'}PN_APP" | ${'$'}S tee "${'$'}tmp/app" >/dev/null
                printf '%s' "${'$'}PN_KEY" | ${'$'}S tee "${'$'}tmp/key" >/dev/null
                printf '%s' "${'$'}PN_CONF" | ${'$'}S tee "${'$'}tmp/conf" >/dev/null
                printf '%s' "${'$'}PN_NAME" | ${'$'}S tee "${'$'}tmp/name" >/dev/null
                printf '%s' "${'$'}PN_EXPIRES" | ${'$'}S tee "${'$'}tmp/expires" >/dev/null
                ${'$'}S chown -R 65534:65534 "${'$'}tmp"
                ${'$'}S mv "${'$'}tmp" "${'$'}D/t/${'$'}T"
                echo ok
                """.trimIndent(),
                slot.inputStream(),
            )
            if (r.stdout.trim() != "ok") throw SshException("invite_failed", "Could not store invite: ${r.stderr.takeLast(1000)}")
            return Link(url(creds.host, port, token), token, expiresAt)
        }
    }

    /**
     * Gets a server ready for links ahead of time (page container, image pull, this version of the
     * app), so creating a link later is a single quick round trip.
     */
    fun prepare(creds: SshCredentials, apk: ApkTemplate?, preferredPort: Int = 80) {
        SshConnection.open(creds, hostKeys).use { ssh ->
            ensurePage(ssh, preferredPort)
            if (apk != null) uploadTemplate(ssh, apk)
        }
    }

    private fun ensurePage(ssh: SshConnection, preferredPort: Int): Int {
        val page = ssh.run(
            ServerManager.script("invite-server.sh"),
            env = mapOf(
                "PN_INVITE_PORT" to preferredPort.toString(),
                "PN_INVITE_INDEX" to ServerManager.script("invite/index.html"),
                "PN_INVITE_CGI" to ServerManager.script("invite/get.cgi"),
            ),
        )
        return page.results["port"]?.toIntOrNull()
            ?: throw SshException("invite_failed", "Could not start the invite page: ${page.stderr.takeLast(1000)} ${page.stdout.takeLast(500)}")
    }

    /** Which of [tokens] have been used already. */
    fun claimed(creds: SshCredentials, tokens: Collection<String>): Set<String> {
        if (tokens.isEmpty()) return emptySet()
        SshConnection.open(creds, hostKeys).use { ssh ->
            val r = ssh.run("$SUDO; \$S ls -1 $DATA/c 2>/dev/null || true")
            val used = r.stdout.lines().map { it.trim() }.toSet()
            return tokens.filter { it in used }.toSet()
        }
    }

    /** Takes back an unused link. */
    fun revoke(creds: SshCredentials, token: String) {
        require(token.matches(TOKEN)) { "bad token" }
        SshConnection.open(creds, hostKeys).use { ssh -> ssh.run("$SUDO; \$S rm -rf $DATA/t/$token") }
    }

    private fun uploadTemplate(ssh: SshConnection, apk: ApkTemplate) {
        val current = ssh.run("$SUDO; \$S cat $DATA/app.sha 2>/dev/null || true").stdout.trim()
        if (current == apk.sha256) return
        val up = ssh.pipe(
            "$SUDO; \$S mkdir -p $DATA && \$S sh -c 'cat > $DATA/app.apk.new' && " +
                "printf '%s %s\\n' ${apk.slotOffset} ${ApkSlot.SIZE} | \$S tee $DATA/app.slot.new >/dev/null && " +
                "\$S mv $DATA/app.apk.new $DATA/app.apk && \$S mv $DATA/app.slot.new $DATA/app.slot && " +
                "printf '%s' ${apk.sha256} | \$S tee $DATA/app.sha >/dev/null && echo ok",
            apk.file.inputStream().buffered(),
        )
        if (up.stdout.trim() != "ok") throw SshException("invite_failed", "Could not upload the app: ${up.stderr.takeLast(1000)}")
    }

    companion object {
        const val DAY_MS = 24 * 3600_000L
        private const val DATA = "/opt/amnezia/paramnezia/invite/data"
        private const val SUDO = "if [ \"\$(id -u)\" -eq 0 ]; then S=''; else S='sudo -n'; fi"
        private val TOKEN = Regex("[A-Za-z0-9_-]{16,64}")
        private val random = SecureRandom()

        fun newToken(): String = ByteArray(16).also(random::nextBytes).let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }

        fun url(host: String, port: Int, token: String): String {
            val h = if (':' in host && !host.startsWith("[")) "[$host]" else host
            return "http://$h" + (if (port == 80) "" else ":$port") + "/#$token"
        }

        private fun sha256(f: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            f.inputStream().use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
