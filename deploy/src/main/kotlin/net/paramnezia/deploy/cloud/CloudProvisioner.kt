package net.paramnezia.deploy.cloud

import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import net.paramnezia.core.profile.SshCredentials
import net.paramnezia.deploy.HostKeyStore
import net.paramnezia.deploy.SshConnection
import net.paramnezia.deploy.SshException
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** An SSH key pair: OpenSSH public line and PEM private key. */
data class SshKeyPair(val privatePem: String, val publicLine: String) {
    /** `SHA256:…` over the public key blob, the form [net.paramnezia.deploy.HostKeyStore] keeps. */
    val fingerprint: String get() {
        val blob = Base64.getDecoder().decode(publicLine.trim().split(' ')[1])
        return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(blob))
    }

    companion object {
        /** RSA-3072: the one type every sshd, cloud-init and JSch on every Android version all accept. */
        fun generate(comment: String): SshKeyPair {
            val kp = KeyPair.genKeyPair(JSch(), KeyPair.RSA, 3072)
            val priv = ByteArrayOutputStream().also { kp.writePrivateKey(it) }.toString(Charsets.US_ASCII.name())
            val pub = ByteArrayOutputStream().also { kp.writePublicKey(it, comment) }.toString(Charsets.US_ASCII.name()).trim()
            kp.dispose()
            return SshKeyPair(priv, pub)
        }
    }
}

/**
 * Buys a VPS and hands back SSH access to it, ready for [net.paramnezia.deploy.ServerManager.install].
 *
 * The server's SSH host key is generated here and installed by cloud-init, and its fingerprint is
 * pinned before the first connection: the app never has to trust whatever key answers first.
 * No password exists anywhere: root logs in with the admin's key, which the app also generated.
 */
class CloudProvisioner(
    private val hostKeys: HostKeyStore,
    private val sleep: (Long) -> Unit = Thread::sleep,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Waits until root can log in over SSH and cloud-init has finished; replaced in tests. */
    private val sshReady: ((SshCredentials) -> Unit)? = null,
) {
    enum class Stage { CREATE, BOOT, SSH }

    /**
     * Creates a server called [name]. [onCreated] gets the provider's id as soon as billing has
     * started, so the caller can keep it (and offer to delete the server) whatever happens next.
     */
    fun provision(
        provider: CloudProvider,
        region: String,
        plan: CloudPlan,
        adminKey: SshKeyPair,
        name: String = newName(),
        onStage: (Stage) -> Unit = {},
        onCreated: (serverId: String) -> Unit = {},
    ): SshCredentials {
        onStage(Stage.CREATE)
        val hostKey = SshKeyPair.generate("paramnezia-host")
        val userData = cloudInit(hostKey, adminKey.publicLine)
        val idempotency = newToken()
        val created = try {
            provider.create(name, region, plan, adminKey.publicLine, userData, idempotency)
        } catch (e: CloudException) {
            if (e.code != "network") throw e
            // The answer was lost: the server may exist already. Look before buying again.
            sleep(5_000)
            provider.find(name) ?: provider.create(name, region, plan, adminKey.publicLine, userData, idempotency)
        }
        onCreated(created.id)

        onStage(Stage.BOOT)
        val ip = waitForIp(provider, created)
        hostKeys.put(ip, 22, hostKey.fingerprint)

        onStage(Stage.SSH)
        val creds = SshCredentials(host = ip, port = 22, user = "root", privateKey = adminKey.privatePem)
        (sshReady ?: { waitForCloudInit(it, hostKeys, sleep) })(creds)
        return creds
    }

    private fun waitForIp(provider: CloudProvider, first: CloudServer): String {
        val deadline = clock() + BOOT_TIMEOUT_MS
        var s = first
        while (true) {
            when (s.state) {
                CloudServer.State.READY -> if (s.ipv4 != null) return s.ipv4!!
                CloudServer.State.FAILED -> throw CloudException("create_failed", "Хостер не смог создать сервер: ${s.detail}")
                CloudServer.State.GONE -> throw CloudException("create_failed", "Сервер удалён у хостера")
                CloudServer.State.PENDING -> {}
            }
            if (clock() > deadline) throw CloudException("boot_timeout", "Сервер не запустился за ${BOOT_TIMEOUT_MS / 60_000} минут")
            sleep(5_000)
            s = runCatching { provider.get(s.id) }.getOrElse { if (it is CloudException && it.code == "auth_failed") throw it; s }
        }
    }

    companion object {
        const val BOOT_TIMEOUT_MS = 10 * 60_000L
        const val SSH_TIMEOUT_MS = 8 * 60_000L
        private val random = SecureRandom()

        fun newName() = "paramnezia-" + newToken().take(6).lowercase()

        private fun newToken(): String {
            val b = ByteArray(18).also(random::nextBytes)
            return Base64.getUrlEncoder().withoutPadding().encodeToString(b).replace('-', 'x').replace('_', 'y')
        }

        /**
         * cloud-init user data: our host key replaces the image's (and no other types are made), and
         * root accepts the admin key. The provider also installs the admin key through its own API;
         * either way is enough.
         */
        fun cloudInit(hostKey: SshKeyPair, adminPublic: String): String = buildString {
            append("#cloud-config\n")
            append("ssh_deletekeys: true\n")
            append("ssh_genkeytypes: [rsa]\n")
            append("ssh_keys:\n")
            append("  rsa_private: |\n")
            for (line in hostKey.privatePem.trim().lines()) append("    ").append(line).append('\n')
            append("  rsa_public: ").append(hostKey.publicLine).append('\n')
            append("disable_root: false\n")
            append("ssh_authorized_keys:\n  - ").append(adminPublic).append('\n')
            append("write_files:\n")
            append("  - path: /root/.ssh/authorized_keys\n")
            append("    permissions: '0600'\n")
            append("    append: true\n")
            append("    content: |\n      ").append(adminPublic).append('\n')
        }

        /** SSH codes that mean "not up yet" on a server that is still booting. */
        private val BOOTING = setOf("unreachable", "connect_failed", "auth_failed", "host_key_changed")

        /**
         * Retries SSH until the server lets root in with the pinned host key, then waits for
         * cloud-init to finish (it holds the apt lock the installer needs). A key that is still
         * wrong after [SSH_TIMEOUT_MS] is reported as it is: a server answering with another host
         * key is exactly what pinning is for.
         */
        fun waitForCloudInit(creds: SshCredentials, hostKeys: HostKeyStore, sleep: (Long) -> Unit = Thread::sleep) {
            val deadline = System.currentTimeMillis() + SSH_TIMEOUT_MS
            while (true) {
                try {
                    SshConnection.open(creds, hostKeys).use { ssh ->
                        ssh.run("command -v cloud-init >/dev/null 2>&1 && timeout 300 cloud-init status --wait >/dev/null 2>&1; true", timeoutMs = 6 * 60_000)
                    }
                    return
                } catch (e: SshException) {
                    if (e.code !in BOOTING || System.currentTimeMillis() > deadline) throw e
                    sleep(10_000)
                }
            }
        }
    }
}
