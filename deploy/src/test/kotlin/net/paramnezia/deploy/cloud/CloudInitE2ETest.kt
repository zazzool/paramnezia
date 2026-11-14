package net.paramnezia.deploy.cloud

import net.paramnezia.core.profile.SshCredentials
import net.paramnezia.deploy.MemoryHostKeyStore
import net.paramnezia.deploy.SshConnection
import net.paramnezia.deploy.SshException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * Feeds our cloud-init user data to real cloud-init on Ubuntu 24.04 (NoCloud seed, in Docker), then
 * logs in as root with the admin key over the pinned host key, exactly as the app does with a VPS a
 * provider has just created. Catches schema mistakes that a provider would silently ignore (and
 * that would leave the server unreachable). Needs Docker; run with PN_E2E=1.
 */
class CloudInitE2ETest {
    private val name = "pn-cloudinit-e2e"
    private lateinit var seed: File

    @Before fun setUp() {
        assumeTrue("set PN_E2E=1 to run", System.getenv("PN_E2E") == "1")
        sh("docker rm -f $name >/dev/null 2>&1 || true")
        seed = Files.createTempDirectory("pn-seed").toFile()
    }

    @After fun tearDown() {
        if (::seed.isInitialized) { sh("docker rm -f $name >/dev/null 2>&1 || true"); seed.deleteRecursively() }
    }

    @Test fun hostKeyIsPinnedAndAdminKeyLetsRootIn() {
        val admin = SshKeyPair.generate("admin")
        val host = SshKeyPair.generate("host")
        File(seed, "user-data").writeText(CloudProvisioner.cloudInit(host, admin.publicLine))
        File(seed, "meta-data").writeText("instance-id: pn-e2e\nlocal-hostname: pn-e2e\n")
        File(seed, "Dockerfile").writeText(
            "FROM ubuntu:24.04\nRUN apt-get update -qq && DEBIAN_FRONTEND=noninteractive apt-get install -yqq cloud-init openssh-server >/dev/null && mkdir -p /run/sshd\n",
        )
        assertEquals(0, sh("docker build -q -t pn-cloudinit-e2e ${seed.absolutePath}"))
        val boot = "mkdir -p /var/lib/cloud/seed/nocloud && cp /seed/user-data /seed/meta-data /var/lib/cloud/seed/nocloud/ && " +
            "cloud-init init --local >/dev/null 2>&1; cloud-init init >/dev/null 2>&1; cloud-init modules --mode config >/dev/null 2>&1; cloud-init modules --mode final >/dev/null 2>&1; " +
            "cloud-init schema --system >/tmp/schema 2>&1 || { cat /tmp/schema; exit 3; }; " +
            "test \"\$(ls /etc/ssh/ssh_host_*_key | wc -l)\" = 1 || { ls /etc/ssh; exit 4; }; " +
            // Without systemd, `cloud-init status --wait` never sees the boot finish; on a VPS it does.
            "mv /usr/bin/cloud-init /usr/bin/cloud-init.done; exec /usr/sbin/sshd -D -e -p 22"
        assertEquals(0, sh("docker run -d --name $name -p 127.0.0.1::22 -v ${seed.absolutePath}:/seed pn-cloudinit-e2e bash -c '$boot'"))
        val port = out("docker port $name 22").trim().substringAfterLast(':').toInt()

        val creds = SshCredentials("127.0.0.1", port, "root", privateKey = admin.privatePem)
        val store = MemoryHostKeyStore().apply { put("127.0.0.1", port, host.fingerprint) }
        CloudProvisioner.waitForCloudInit(creds, store, sleep = { Thread.sleep(1_000) })
        val who = SshConnection.open(creds, store).use { it.run("id -u; ls /etc/ssh/ssh_host_*_key") }
        assertTrue(who.stdout, who.stdout.startsWith("0\n"))
        assertTrue(who.stdout, "ssh_host_ed25519_key" in who.stdout)

        // Another pinned key: the connection is refused, as for a server that is not ours.
        val other = MemoryHostKeyStore().apply { put("127.0.0.1", port, SshKeyPair.generate("x").fingerprint) }
        try { SshConnection.open(creds, other).close(); fail("connected despite a wrong host key") }
        catch (e: SshException) { assertEquals("host_key_changed", e.code) }
    }

    private fun sh(cmd: String): Int {
        val p = ProcessBuilder("bash", "-c", cmd).inheritIO().start()
        if (!p.waitFor(15, TimeUnit.MINUTES)) { p.destroyForcibly(); return -1 }
        return p.exitValue()
    }

    private fun out(cmd: String): String {
        val p = ProcessBuilder("bash", "-c", cmd).redirectErrorStream(true).start()
        val text = p.inputStream.bufferedReader().readText()
        p.waitFor(1, TimeUnit.MINUTES)
        return text
    }
}
