package net.paramnezia.deploy

import net.paramnezia.core.amnezia.AmneziaFormat
import net.paramnezia.core.config.AwgParam
import net.paramnezia.core.config.WgQuick
import net.paramnezia.core.crypto.WgKey
import net.paramnezia.core.net.IpPrefix
import net.paramnezia.core.profile.SshCredentials
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
import net.paramnezia.core.amnezia.AmneziaKey
import net.paramnezia.core.config.TunnelConfig
import net.paramnezia.core.fleet.INVITE_KEY
import net.paramnezia.core.profile.Protocol

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
            assertEquals(Protocol.AWG, manager.inspect(creds).awgContainer)

            val clientKey = WgKey.generatePrivate()
            val tunnel = manager.addClient(creds, clientKey, "e2e-phone")
            assertEquals("10.8.1.2/32", tunnel.iface.addresses.single().toString())
            assertEquals(params[AwgParam.JC], tunnel.iface.awg[AwgParam.JC])
            assertEquals(params[AwgParam.I1], tunnel.iface.awg[AwgParam.I1])
            // Same key again: idempotent, same address.
            assertEquals(tunnel.iface.addresses, manager.addClient(creds, clientKey, "e2e-phone").iface.addresses)
            assertEquals("10.8.1.3/32", manager.addClient(creds, WgKey.generatePrivate(), "second").iface.addresses.single().toString())
            assertEquals(setOf("10.8.1.2", "10.8.1.3"), manager.usedAddresses(creds))

            // The exported vpn:// key must import back to the same tunnel.
            val reimported = AmneziaFormat.import(AmneziaFormat.export("e2e", tunnel)).single().tunnel!!
            assertEquals(WgQuick.write(tunnel), WgQuick.write(reimported))

            val publisher = FleetPublisher(hostKeys)
            assertTrue("in-tunnel web server", publisher.publishToServer(creds, "{\"fleetId\":\"e2e\"}"))
            assertEquals("{\"fleetId\":\"e2e\"}", sh("docker exec amnezia-awg2 cat /opt/amnezia/paramnezia/www/fleet.json"))
            // A container without our web server (AmneziaVPN's, or after a crash) gets one started.
            sh("docker exec amnezia-awg2 sh -c 'pkill httpd || killall httpd || true'", check = false)
            assertTrue("web server restarted", publisher.publishToServer(creds, null))
            assertTrue("ok" in sh("docker exec amnezia-awg2 wget -q -O- http://10.8.1.1:8080/ok"))

            // Fleet devices registered in one go; again is a no-op; a taken address is reported, not fatal.
            val a = WgKey.generatePrivate().publicKey(); val b = WgKey.generatePrivate().publicKey()
            val specs = listOf(ServerManager.PeerSpec(a, "a", "10.8.1.20"), ServerManager.PeerSpec(b, "b", "10.8.1.21"))
            assertEquals(emptyMap<String, String>(), manager.ensurePeers(creds, specs))
            assertEquals(emptyMap<String, String>(), manager.ensurePeers(creds, specs))
            val clash = WgKey.generatePrivate().publicKey()
            assertEquals(setOf(clash.toBase64()), manager.ensurePeers(creds, listOf(ServerManager.PeerSpec(clash, "c", "10.8.1.20"))).keys)
            manager.removeClients(creds, listOf(a, b))
            assertEquals(setOf("10.8.1.2", "10.8.1.3"), manager.usedAddresses(creds))

            // The server mirrors a release it already holds into the tunnel.
            val apk = ByteArray(100_000) { (it * 7).toByte() }
            val sha = putInCache(apk)
            publisher.mirrorRelease(creds, sha, emptyList(), "{\"release\":1}", forInvites = false)
            assertEquals(sha, sh("docker exec amnezia-awg2 sha256sum /opt/amnezia/paramnezia/www/apk/$sha").substringBefore(' '))
            assertEquals("{\"release\":1}", sh("docker exec amnezia-awg2 cat /opt/amnezia/paramnezia/www/release.json"))

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
        val plain = AmneziaKey.encode(AmneziaFormat.export("Мама", tunnel))
        // A fleet invite is a plain key plus our field; the page must hand AmneziaVPN the plain part.
        val invite = AmneziaKey.encode(kotlinx.serialization.json.JsonObject(
            AmneziaKey.decode(plain) + (INVITE_KEY to kotlinx.serialization.json.JsonPrimitive("x"))))

        val bytes = ByteArray(300_000) { (it * 13).toByte() }
        val sha = putInCache(bytes)
        val app = InviteLinks.AppDownload("v9.9.9", sha, listOf("https://paramnezia.paramnezia.psync.ru-v9.9.9.apk", "http://plain.example/x.apk"))

        val link = links.create(creds, invite, "Мама", app, preferredPort = 18080)
        assertTrue(link.url, link.url.startsWith("http://127.0.0.1:18080/#"))
        val base = "http://127.0.0.1:18080"
        val page = http("$base/").second
        assertTrue("Вас пригласили" in page)
        // Nothing downloads by itself, and the APK is never personalised.
        assertTrue("no automatic download", "setTimeout" !in page)
        val appJson = http("$base/app.json").second
        assertTrue(appJson, sha in appJson && "https://paramnezia.psync.ru/" in appJson && "plain.example" !in appJson)
        assertEquals(setOf<String>(), links.claimed(creds, listOf(link.token)))

        val (code, key) = http("$base/cgi-bin/get?${link.token}&key")
        assertEquals(200, code)
        assertEquals(plain, key.trim())
        assertEquals(setOf(link.token), links.claimed(creds, listOf(link.token)))
        assertEquals(invite, http("$base/cgi-bin/get?${link.token}&app").second.trim())
        assertEquals(400, http("$base/cgi-bin/get?${link.token}&apk").first)
        assertTrue("[Interface]" in http("$base/cgi-bin/get?${link.token}&conf").second)

        // The server keeps a copy of the same public APK for people who cannot reach the mirrors.
        assertEquals(404, http("$base/paramnezia.apk").first)
        FleetPublisher(hostKeys).mirrorRelease(creds, sha, app.urls, null, forInvites = true, protocol = null)
        val served = java.net.URI("$base/paramnezia.apk").toURL().openStream().use { it.readBytes() }
        assertTrue(served.contentEquals(bytes))
        // A release the server cannot verify is refused, not served.
        val wrong = "0".repeat(64)
        assertTrue(runCatching { FleetPublisher(hostKeys).mirrorRelease(creds, wrong, listOf("https://127.0.0.1:1/x.apk"), null, true, null) }
            .exceptionOrNull() is SshException)

        // Unknown, expired and revoked links are gone.
        assertEquals(410, http("$base/cgi-bin/get?AAAAAAAAAAAAAAAAAAAAAA&key").first)
        val old = links.create(creds, invite, "old", app, ttlMs = -1000)
        assertEquals(410, http("$base/cgi-bin/get?${old.token}&key").first)
        val revoked = links.create(creds, invite, "r", app)
        links.revoke(creds, revoked.token)
        assertEquals(410, http("$base/cgi-bin/get?${revoked.token}&app").first)
        // The page keeps its port, and survives a stop.
        sh("docker stop paramnezia-invite >/dev/null")
        assertEquals(18080, java.net.URI(links.create(creds, invite, "again", null, preferredPort = 80).url).port)
        assertEquals(200, http("$base/").first)
    }

    /** Puts [bytes] where release-mirror.sh keeps verified APKs, as if downloaded earlier. */
    private fun putInCache(bytes: ByteArray): String {
        val sha = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val f = File.createTempFile("pn-release", ".apk").apply { writeBytes(bytes); deleteOnExit() }
        sh("S=\$( [ \"\$(id -u)\" -eq 0 ] || echo sudo -n ); \$S mkdir -p /opt/amnezia/paramnezia/cache && \$S cp ${f.absolutePath} /opt/amnezia/paramnezia/cache/$sha.apk")
        return sha
    }

    private fun http(url: String): Pair<Int, String> {
        val c = java.net.URI(url).toURL().openConnection() as java.net.HttpURLConnection
        val code = c.responseCode
        val body = (if (code < 400) c.inputStream else c.errorStream)?.use { it.readBytes().decodeToString() }.orEmpty()
        return code to body
    }

    private fun handshake(tunnel: TunnelConfig, port: Int) {
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
