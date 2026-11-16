package net.paramnezia.deploy

import net.paramnezia.core.amnezia.AmneziaFormat
import net.paramnezia.core.config.AwgParam
import net.paramnezia.core.config.Camouflage
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

            // Spare ports: the same listener behind other host ports, from the install on.
            val ports = manager.ports(creds)
            assertEquals(port, ports.main)
            assertTrue("spare ports: ${ports.alt}", ports.alt.isNotEmpty())
            // A client with its own preshared key, dialled on a spare port the way Camouflage does.
            val own = WgKey.generatePreshared()
            val ownKey = WgKey.generatePrivate()
            val ownTunnel = manager.addClient(creds, ownKey, "own-psk", psk = own)
            assertEquals(own, ownTunnel.peers.single().presharedKey)
            assertTrue(own.toBase64() in sh("docker exec amnezia-awg2 awg show awg0 preshared-keys"))
            val alt = ports.alt.first()
            val dressed = Camouflage.apply(ownTunnel, Camouflage.Variant("127.0.0.1", alt, Camouflage.Mimic.WEBRTC))
            handshake(dressed, alt)
            // An existing peer asked for with another key gets that key (devices converge on their own).
            val other = WgKey.generatePreshared()
            assertEquals(emptyMap<String, String>(), manager.ensurePeers(creds, listOf(ServerManager.PeerSpec(ownKey.publicKey(), "own-psk", null, other))))
            val keys = sh("docker exec amnezia-awg2 awg show awg0 preshared-keys")
            assertTrue(other.toBase64() in keys && own.toBase64() !in keys)
            assertEquals(1, sh("docker exec amnezia-awg2 grep -c '${other.toBase64()}' /opt/amnezia/awg/awg0.conf").trim().toInt())

            // A server that has to get another port is re-created around its config: clients stay, the new port works.
            val before = manager.usedAddresses(creds)
            val extra = 42000 + profile.ordinal
            val grown = manager.ports(creds, listOf(extra))
            assertTrue(extra in grown.alt && ports.alt.all { it in grown.alt })
            assertEquals(before, manager.usedAddresses(creds))
            assertEquals(grown, manager.ports(creds, listOf(extra)))
            handshake(tunnel, extra)
            handshake(tunnel, port)

            // Tuned at install: MSS clamping in the container, kept by its start script (the snapshot
            // above carried it over), and the host settings persisted.
            val mangle = sh("docker exec amnezia-awg2 iptables -t mangle -S FORWARD")
            assertTrue(mangle, "--clamp-mss-to-pmtu" in mangle && "--set-mss 1380" in mangle)
            assertTrue("tune.sh" in sh("docker exec amnezia-awg2 cat /opt/amnezia/start.sh"))
            assertTrue(manager.tune(creds)) // idempotent
            assertEquals(2, sh("docker exec amnezia-awg2 iptables -t mangle -S FORWARD").lines().count { "TCPMSS" in it })
            sh("docker restart amnezia-awg2 >/dev/null; sleep 4")
            assertEquals(2, sh("docker exec amnezia-awg2 iptables -t mangle -S FORWARD").lines().count { "TCPMSS" in it })
            assertTrue("16777216" in sh("cat /etc/sysctl.d/90-paramnezia.conf"))
            // A client that measured a wide path uses the largest candidate (1420 for AmneziaWG 3, less with
            // a large S4): packets of exactly that size pass both ways.
            val wide = net.paramnezia.core.config.PathMtu.candidates(tunnel).first()
            handshake(tunnel.copy(iface = tunnel.iface.copy(mtu = wide)), port, bigPing = wide - 28)
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
        val exeBytes = ByteArray(200_000) { (it * 7 + 1).toByte() }
        val exeSha = putInCache(exeBytes, "exe")
        val win = InviteLinks.AppDownload("v9.9.9", exeSha, listOf("https://github.com/zazzool/paramnezia/releases/download/v9/Paramnezia-Setup-9.9.9.exe"))
        val app = InviteLinks.AppDownload("v9.9.9", sha, listOf("https://github.com/zazzool/paramnezia/releases/download/v9/paramnezia-v9.9.9.apk", "http://plain.example/x.apk"), win)

        // A clean server, then a page from before 2.16 (at the server's root), which is moved away from there.
        sh("S=\$( [ \"\$(id -u)\" -eq 0 ] || echo sudo -n ); \$S rm -rf /opt/amnezia/paramnezia/invite")
        sh("S=\$( [ \"\$(id -u)\" -eq 0 ] || echo sudo -n ); \$S mkdir -p /opt/amnezia/paramnezia/invite/www && echo old | \$S tee /opt/amnezia/paramnezia/invite/www/index.html >/dev/null")
        val link = links.create(creds, invite, "Мама", app, preferredPort = 18080)
        val secret = Regex("^http://127\\.0\\.0\\.1:18080/([A-Za-z0-9_-]{16,})/#").find(link.url)?.groupValues?.get(1)
        assertTrue(link.url, secret != null)
        val root = "http://127.0.0.1:18080"
        val base = "$root/$secret"
        val cgi = "$root/cgi-bin/$secret"
        // The address itself shows nothing: no page, no app, no download info for scanners.
        for (path in listOf("/", "/index.html", "/app.json", "/paramnezia.apk", "/cgi-bin/get?x&key"))
            assertEquals(path, 404, http("$root$path").first)
        assertTrue(http("$root/httpd.conf").first >= 400)
        val page = http("$base/").second
        assertTrue("Вас пригласили" in page)
        // Nothing downloads by itself, and the APK is never personalised.
        assertTrue("no automatic download", "setTimeout" !in page)
        val appJson = http("$base/app.json").second
        assertTrue(appJson, sha in appJson && "https://github.com/zazzool/paramnezia/releases/download/v9/" in appJson && "plain.example" !in appJson)
        assertTrue(appJson, "\"windows\"" in appJson && exeSha in appJson)
        // The newest releases for both platforms, and how to get past SmartScreen.
        assertTrue("github.com/zazzool/paramnezia/releases/latest/download/" in page &&
            "GH + 'paramnezia.apk'" in page && "GH + 'Paramnezia-Setup.exe'" in page)
        assertTrue("Скачать для Windows" in page && "Выполнить в любом случае" in page)
        assertEquals(setOf<String>(), links.claimed(creds, listOf(link.token)))

        val (code, key) = http("$cgi?${link.token}&key")
        assertEquals(200, code)
        assertEquals(plain, key.trim())
        assertEquals(setOf(link.token), links.claimed(creds, listOf(link.token)))
        assertEquals(invite, http("$cgi?${link.token}&app").second.trim())
        assertEquals(400, http("$cgi?${link.token}&apk").first)
        assertTrue("[Interface]" in http("$cgi?${link.token}&conf").second)

        // The server keeps a copy of the same public APK for people who cannot reach the mirrors.
        assertEquals(404, http("$base/paramnezia.apk").first)
        assertEquals(404, http("$base/Paramnezia-Setup.exe").first)
        FleetPublisher(hostKeys).mirrorRelease(creds, sha, app.urls, null, forInvites = true, protocol = null, windows = win)
        val served = java.net.URI("$base/paramnezia.apk").toURL().openStream().use { it.readBytes() }
        assertTrue(served.contentEquals(bytes))
        val servedExe = java.net.URI("$base/Paramnezia-Setup.exe").toURL().openStream().use { it.readBytes() }
        assertTrue(servedExe.contentEquals(exeBytes))
        // A Windows installer the server cannot get does not break the APK copy, and the old one stays.
        FleetPublisher(hostKeys).mirrorRelease(creds, sha, app.urls, null, forInvites = true, protocol = null,
            windows = InviteLinks.AppDownload("v10", "1".repeat(64), listOf("https://127.0.0.1:1/x.exe")))
        assertEquals(200, http("$base/Paramnezia-Setup.exe").first)
        assertEquals(200, http("$base/paramnezia.apk").first)
        // A release the server cannot verify is refused, not served.
        val wrong = "0".repeat(64)
        assertTrue(runCatching { FleetPublisher(hostKeys).mirrorRelease(creds, wrong, listOf("https://127.0.0.1:1/x.apk"), null, true, null) }
            .exceptionOrNull() is SshException)

        // Unknown, expired and revoked links are gone.
        assertEquals(410, http("$cgi?AAAAAAAAAAAAAAAAAAAAAA&key").first)
        val old = links.create(creds, invite, "old", app, ttlMs = -1000)
        assertEquals(410, http("$cgi?${old.token}&key").first)
        val revoked = links.create(creds, invite, "r", app)
        links.revoke(creds, revoked.token)
        assertEquals(410, http("$cgi?${revoked.token}&app").first)
        // The page keeps its port, and survives a stop.
        sh("docker stop paramnezia-invite >/dev/null")
        val again = links.create(creds, invite, "again", null, preferredPort = 80).url
        assertEquals(18080, java.net.URI(again).port)
        assertTrue("same secret path", again.startsWith("$base/#"))
        assertEquals(200, http("$base/").first)
        // The quick path (page prepared earlier): only the token is written; a stopped page is started
        // again, and the link works exactly like one made the long way.
        sh("docker stop paramnezia-invite >/dev/null")
        val quick = links.create(creds, invite, "quick", app, fast = true)
        assertEquals(18080, java.net.URI(quick.url).port)
        assertTrue(quick.url.startsWith("$base/#"))
        assertEquals(plain, http("$cgi?${quick.token}&key").second.trim())
    }

    /** Puts [bytes] where release-mirror.sh keeps verified releases, as if downloaded earlier. */
    private fun putInCache(bytes: ByteArray, ext: String = "apk"): String {
        val sha = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val f = File.createTempFile("pn-release", ".apk").apply { writeBytes(bytes); deleteOnExit() }
        sh("S=\$( [ \"\$(id -u)\" -eq 0 ] || echo sudo -n ); \$S mkdir -p /opt/amnezia/paramnezia/cache && \$S cp ${f.absolutePath} /opt/amnezia/paramnezia/cache/$sha.$ext")
        return sha
    }

    private fun http(url: String): Pair<Int, String> {
        val c = java.net.URI(url).toURL().openConnection() as java.net.HttpURLConnection
        val code = c.responseCode
        val body = (if (code < 400) c.inputStream else c.errorStream)?.use { it.readBytes().decodeToString() }.orEmpty()
        return code to body
    }

    private fun handshake(tunnel: TunnelConfig, port: Int, bigPing: Int? = null) {
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
        val size = bigPing?.let { "-s $it " } ?: ""
        val ping = sh("sleep 2; docker exec pn-e2e-client ping -c 2 -W 2 10.8.1.1 >/dev/null; docker exec pn-e2e-client ping $size-c 3 -W 2 10.8.1.1; docker exec pn-e2e-client awg show awg0", check = false)
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
