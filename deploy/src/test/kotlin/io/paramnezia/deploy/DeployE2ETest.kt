package io.paramnezia.deploy

import io.paramnezia.core.amnezia.AmneziaFormat
import io.paramnezia.core.config.AwgParam
import io.paramnezia.core.config.WgQuick
import io.paramnezia.core.crypto.WgKey
import io.paramnezia.core.net.IpPrefix
import io.paramnezia.core.profile.SshCredentials
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.apache.sshd.server.shell.ProcessShellCommandFactory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * Installs a real AmneziaWG server through our SSH client and scripts, then brings up a real
 * amneziawg-go client in another container with the generated config and pings the server.
 *
 * The "VPS" is this machine: an in-process SSH server runs commands locally, so the scripts drive the
 * local Docker daemon exactly as they would on a VPS. Needs root and Docker; run with PN_E2E=1.
 */
class DeployE2ETest {
    private lateinit var sshd: SshServer
    private val hostKeys = MemoryHostKeyStore()

    @Before
    fun setUp() {
        assumeTrue("set PN_E2E=1 to run", System.getenv("PN_E2E") == "1")
        sshd = SshServer.setUpDefaultServer().apply {
            port = 0
            keyPairProvider = SimpleGeneratorHostKeyProvider(Files.createTempFile("hostkey", ".ser"))
            setPasswordAuthenticator { user, pass, _ -> user == "root" && pass == "test-pass" }
            commandFactory = ProcessShellCommandFactory.INSTANCE
            start()
        }
        sh("docker rm -f amnezia-awg2 pn-e2e-client paramnezia-invite >/dev/null 2>&1 || true")
    }

    @After
    fun tearDown() {
        if (::sshd.isInitialized) {
            sh("docker rm -f amnezia-awg2 pn-e2e-client paramnezia-invite >/dev/null 2>&1 || true")
            sshd.stop(true)
        }
    }

    private val creds get() = SshCredentials("127.0.0.1", sshd.port, "root", "test-pass")

    @Test
    fun installAddClientAndHandshake() {
        for (profile in listOf(ObfuscationProfile.AWG_3, ObfuscationProfile.AWG_2)) {
            val manager = ServerManager(hostKeys)
            val steps = ArrayList<ServerManager.Step>()
            val port = 41000 + profile.ordinal
            val params = manager.install(creds, ServerManager.InstallOptions(port = port, profile = profile, reinstall = true)) { steps += it }
            assertEquals(ServerManager.Step.DONE, steps.last())
            assertEquals(io.paramnezia.core.profile.Protocol.AWG, manager.inspect(creds).awgContainer)

            val clientKey = WgKey.generatePrivate()
            val tunnel = manager.addClient(creds, clientKey, "e2e-phone")
            assertEquals("10.8.1.2/32", tunnel.iface.addresses.single().toString())
            assertEquals(params[AwgParam.JC], tunnel.iface.awg[AwgParam.JC])
            assertEquals(params[AwgParam.I1], tunnel.iface.awg[AwgParam.I1])
            // Same key again: idempotent, same address.
            assertEquals(tunnel.iface.addresses, manager.addClient(creds, clientKey, "e2e-phone").iface.addresses)
            assertEquals("10.8.1.3/32", manager.addClient(creds, WgKey.generatePrivate(), "second").iface.addresses.single().toString())

            // The exported vpn:// key must import back to the same tunnel.
            val reimported = AmneziaFormat.import(AmneziaFormat.export("e2e", tunnel)).single().tunnel!!
            assertEquals(WgQuick.write(tunnel), WgQuick.write(reimported))

            FleetPublisher(hostKeys).publishToServer(creds, "{\"fleetId\":\"e2e\"}")
            assertEquals("{\"fleetId\":\"e2e\"}", sh("docker exec amnezia-awg2 cat /opt/amnezia/paramnezia/www/fleet.json"))

            handshake(tunnel, port)
            println("e2e ok for $profile")
        }
    }

    /** The invite page on a real busybox container: per-download APK, plain key, one-time claim. */
    @Test
    fun oneTimeInviteLink() {
        val links = InviteLinks(hostKeys)
        val tunnel = WgQuick.parse(
            """
            [Interface]
            PrivateKey = ${WgKey.generatePrivate().toBase64()}
            Address = 10.8.1.5/32
            Jc = 4
            [Peer]
            PublicKey = ${WgKey.generatePrivate().publicKey().toBase64()}
            Endpoint = 203.0.113.7:41000
            AllowedIPs = 0.0.0.0/0
            """.trimIndent(),
        )
        val plain = io.paramnezia.core.amnezia.AmneziaKey.encode(AmneziaFormat.export("Мама", tunnel))
        // A fleet invite is a plain key plus our field; the page must hand AmneziaVPN the plain part.
        val invite = io.paramnezia.core.amnezia.AmneziaKey.encode(kotlinx.serialization.json.JsonObject(
            io.paramnezia.core.amnezia.AmneziaKey.decode(plain) + (io.paramnezia.core.fleet.INVITE_KEY to kotlinx.serialization.json.JsonPrimitive("x"))))

        val apk = File.createTempFile("app", ".apk").apply { deleteOnExit() }
        val tmpl = File.createTempFile("tmpl", ".apk").apply { deleteOnExit() }
        apk.writeBytes(fakeSignedApk())
        val template = InviteLinks.ApkTemplate(tmpl, io.paramnezia.core.apk.ApkSlot.prepare(apk, tmpl))

        val link = links.create(creds, invite, "Мама", template, preferredPort = 18080)
        assertTrue(link.url, link.url.startsWith("http://127.0.0.1:18080/#"))
        val base = "http://127.0.0.1:18080"
        assertTrue("Вас пригласили" in http("$base/").second)
        assertEquals(setOf<String>(), links.claimed(creds, listOf(link.token)))

        val (code, key) = http("$base/cgi-bin/get?${link.token}&key")
        assertEquals(200, code)
        assertEquals(plain, key.trim())
        assertEquals(setOf(link.token), links.claimed(creds, listOf(link.token)))
        val got = File.createTempFile("got", ".apk").apply { deleteOnExit() }
        java.net.URI("$base/cgi-bin/get?${link.token}&apk").toURL().openStream().use { got.outputStream().use { o -> it.copyTo(o) } }
        assertEquals(tmpl.length(), got.length())
        assertEquals(invite, io.paramnezia.core.apk.ApkSlot.read(got))
        assertTrue("[Interface]" in http("$base/cgi-bin/get?${link.token}&conf").second)

        // Unknown, expired and revoked links are gone; the template is uploaded only once.
        assertEquals(410, http("$base/cgi-bin/get?AAAAAAAAAAAAAAAAAAAAAA&key").first)
        val old = links.create(creds, invite, "old", template, ttlMs = -1000)
        assertEquals(410, http("$base/cgi-bin/get?${old.token}&key").first)
        val revoked = links.create(creds, invite, "r", template)
        links.revoke(creds, revoked.token)
        assertEquals(410, http("$base/cgi-bin/get?${revoked.token}&app").first)
        assertEquals(18080, java.net.URI(links.create(creds, invite, "again", null, preferredPort = 80).url).port)
    }

    private fun http(url: String): Pair<Int, String> {
        val c = java.net.URI(url).toURL().openConnection() as java.net.HttpURLConnection
        val code = c.responseCode
        val body = (if (code < 400) c.inputStream else c.errorStream)?.use { it.readBytes().decodeToString() }.orEmpty()
        return code to body
    }

    private fun handshake(tunnel: io.paramnezia.core.config.TunnelConfig, port: Int) {
        val gateway = sh("docker network inspect bridge --format '{{(index .IPAM.Config 0).Gateway}}'").trim()
        val clientConf = tunnel.copy(
            iface = tunnel.iface.copy(dns = emptyList()),
            peers = tunnel.peers.map { it.copy(endpoint = "$gateway:$port", allowedIps = listOf(IpPrefix.parse("10.8.1.0/24"))) },
        )
        val dir = Files.createTempDirectory("pn-e2e").toFile()
        File(dir, "awg0.conf").writeText(WgQuick.write(clientConf))
        sh(
            "docker run -d --name pn-e2e-client --cap-add NET_ADMIN --device /dev/net/tun -v ${dir.absolutePath}:/cfg " +
                "--entrypoint sh amneziavpn/amneziawg-go:latest -c 'awg-quick up /cfg/awg0.conf && tail -f /dev/null'",
        )
        val ping = sh("sleep 2; docker exec pn-e2e-client ping -c 2 -W 2 10.8.1.1 >/dev/null; docker exec pn-e2e-client ping -c 3 -W 2 10.8.1.1; docker exec pn-e2e-client awg show awg0", check = false)
        sh("docker rm -f pn-e2e-client >/dev/null 2>&1 || true")
        assertTrue("ping through tunnel failed:\n$ping", " 0% packet loss" in ping)
        assertTrue("no handshake:\n$ping", "latest handshake" in ping)
    }

    private fun sh(cmd: String, check: Boolean = true): String {
        val p = ProcessBuilder("bash", "-c", cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor(5, TimeUnit.MINUTES)
        if (check && p.exitValue() != 0) error("`$cmd` failed:\n$out")
        return out
    }
}
