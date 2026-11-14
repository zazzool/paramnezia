package net.paramnezia.deploy.ssh

import net.paramnezia.core.profile.SshCredentials
import net.paramnezia.deploy.HostKeyStore
import net.paramnezia.deploy.MemoryHostKeyStore
import net.paramnezia.deploy.SshConnection
import net.paramnezia.deploy.SshException
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.keyboard.DefaultKeyboardInteractiveAuthenticator
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.apache.sshd.server.shell.ProcessShellCommandFactory
import org.junit.AfterClass
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.math.BigInteger
import java.net.ServerSocket
import java.nio.file.Files
import java.util.concurrent.TimeUnit

class SshClientTest {
    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    // ---- primitives against their RFC vectors ----

    @Test fun x25519MatchesRfc7748() {
        val out = Curve25519.x25519(
            hex("a546e36bf0527c9d3b16154b82465edd62144c0ac1fc5a18506a2244ba449ac4"),
            hex("e6db6867583030db3594c1a424b15f7c726624ec26b3353b10a903a6d0ab1c4c"),
        )
        assertArrayEquals(hex("c3da55379de9c6908e94ea4df28d084f32eccf03491c71f754b4075577a28552"), out)
        // Alice and Bob (RFC 7748 §6.1).
        val a = hex("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a")
        val b = hex("5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb")
        assertArrayEquals(hex("8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a"), Curve25519.x25519Base(a))
        assertArrayEquals(Curve25519.x25519(a, Curve25519.x25519Base(b)), Curve25519.x25519(b, Curve25519.x25519Base(a)))
    }

    @Test fun ed25519MatchesRfc8032() {
        val seed = hex("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60")
        val pub = Curve25519.edPublic(seed)
        assertArrayEquals(hex("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a"), pub)
        val sig = Curve25519.edSign(seed, pub, ByteArray(0))
        assertArrayEquals(hex("e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b"), sig)
        assertTrue(Curve25519.edVerify(pub, ByteArray(0), sig))
        // Test 3: a two-byte message.
        val seed3 = hex("c5aa8df43f9f837bedb7442f31dcb7b166d38535076f094b85ce3a2e0b4458f7")
        val pub3 = Curve25519.edPublic(seed3)
        assertArrayEquals(hex("fc51cd8e6218a1a38da47ed00230f0580816ed13ba3303ac5deb911548908025"), pub3)
        val sig3 = Curve25519.edSign(seed3, pub3, hex("af82"))
        assertArrayEquals(hex("6291d657deec24024827e69c3abe01a30ce548a284743a445e3680d7db5ac3ac18ff9b538d16f290ae67f760984dc6594a7c15e9716ed28dc027beceea1ec40a"), sig3)
        // Anything changed fails.
        assertFalse(Curve25519.edVerify(pub3, hex("af83"), sig3))
        assertFalse(Curve25519.edVerify(pub, hex("af82"), sig3))
        val bad = sig3.copyOf().also { it[40] = (it[40] + 1).toByte() }
        assertFalse(Curve25519.edVerify(pub3, hex("af82"), bad))
        repeat(20) {
            val s = Curve25519.edGenerateSeed(); val p = Curve25519.edPublic(s); val m = ByteArray(it * 7) { i -> i.toByte() }
            assertTrue(Curve25519.edVerify(p, m, Curve25519.edSign(s, p, m)))
        }
    }

    @Test fun piAndDhGroupsComeOutRight() {
        val w = Bcrypt.piWords(18 + 1024)
        assertEquals(0x243F6A88, w[0]); assertEquals(0x8979FB1B.toInt(), w[17])
        assertEquals(0xD1310BA6.toInt(), w[18]); assertEquals(0x3AC372E6, w[18 + 1023])
        val g14 = SshClient.MODP2048.toString(16).uppercase()
        assertTrue(g14.startsWith("FFFFFFFFFFFFFFFFC90FDAA22168C234C4C6628B80DC1CD1"))
        assertTrue(g14.endsWith("15728E5A8AACAA68FFFFFFFFFFFFFFFF"))
        assertEquals(2048, SshClient.MODP2048.bitLength())
        assertTrue(SshClient.MODP2048.isProbablePrime(40))
        assertTrue(SshClient.MODP2048.shiftRight(1).isProbablePrime(20))
        assertTrue(SshClient.MODP4096.toString(16).uppercase().endsWith("4DF435C934063199FFFFFFFFFFFFFFFF"))
        assertTrue(SshClient.MODP4096.isProbablePrime(20))
    }

    @Test fun bcryptPbkdfMatchesOpenBsd() {
        // OpenBSD's bcrypt_pbkdf regression values (also what pyca/bcrypt's kdf gives).
        assertArrayEquals(hex("5bbf0cc293587f1c3635555c27796598d47e579071bf427e9d8fbe842aba34d9"), Bcrypt.pbkdf("password".toByteArray(), "salt".toByteArray(), 32, 4))
        assertArrayEquals(hex("7578cf09f03644188b58dd336812168396e59d5dc35b5af7c6c3c90f520caf33dadc94033068763458bbc95f8ecdbe2d"), Bcrypt.pbkdf("password".toByteArray(), "salt".toByteArray(), 48, 2))
    }

    @Test fun generatedKeyRoundTrips() {
        val (priv, pub) = SshKeys.generateEd25519("me@test")
        val k = SshKeys.parse(priv, "")
        assertEquals(pub.split(' ')[1], java.util.Base64.getEncoder().encodeToString(k.publicBlob))
        assertTrue(pub.endsWith(" me@test"))
        try { SshKeys.parse("hello", ""); fail() } catch (e: SshKeyException) { assertEquals("bad_key", e.code) }
        // A text field that turned the newlines into spaces.
        assertArrayEquals(k.publicBlob, SshKeys.parse(priv.replace('\n', ' '), "").publicBlob)
    }

    // ---- against real OpenSSH ----

    companion object {
        private val dir: File by lazy { Files.createTempDirectory("pn-sshd").toFile() }
        private val sshd: Boolean by lazy {
            File("/usr/sbin/sshd").canExecute() && File("/usr/bin/ssh-keygen").canExecute() &&
                System.getProperty("user.name") == "root"
        }
        private var server: Process? = null

        @AfterClass @JvmStatic fun tearDown() { server?.destroyForcibly(); runCatching { dir.deleteRecursively() } }

        private fun run(vararg cmd: String): String {
            val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            check(p.waitFor(60, TimeUnit.SECONDS) && p.exitValue() == 0) { "${cmd.joinToString(" ")}: $out" }
            return out
        }

        /** Host keys, made once. */
        private val hostKeys: Map<String, File> by lazy {
            mapOf("ed25519" to "ed25519", "ecdsa" to "ecdsa", "rsa" to "rsa").mapValues { (_, t) ->
                File(dir, "host_$t").also { if (!it.exists()) run("ssh-keygen", "-q", "-t", t, "-N", "", "-f", it.path) }
            }
        }

        private fun freePort() = ServerSocket(0).use { it.localPort }

        /** Starts sshd with [extra] config lines; returns its port. */
        fun startSshd(vararg extra: String): Int {
            server?.let { it.destroyForcibly(); it.waitFor() }
            File("/run/sshd").mkdirs()
            val port = freePort()
            val cfg = File(dir, "sshd_config")
            cfg.writeText(
                (listOf(
                    "Port $port", "ListenAddress 127.0.0.1", "PidFile ${dir.path}/pid",
                    "AuthorizedKeysFile ${dir.path}/authorized_keys", "StrictModes no", "PermitRootLogin yes",
                    "PasswordAuthentication no", "KbdInteractiveAuthentication no", "UsePAM no", "LogLevel ERROR",
                ) + hostKeys.values.map { "HostKey ${it.path}" } + extra).joinToString("\n") + "\n",
            )
            server = ProcessBuilder("/usr/sbin/sshd", "-D", "-e", "-f", cfg.path).redirectErrorStream(true)
                .redirectOutput(File(dir, "sshd.log")).start()
            val deadline = System.currentTimeMillis() + 5_000
            while (System.currentTimeMillis() < deadline) {
                if (runCatching { java.net.Socket("127.0.0.1", port).close() }.isSuccess) return port
                Thread.sleep(20)
            }
            error("sshd did not start: " + File(dir, "sshd.log").readText())
        }

        /** A user key made by ssh-keygen, authorized for root. */
        fun userKey(name: String, vararg args: String): String {
            val f = File(dir, "user_$name")
            if (!f.exists()) {
                run("ssh-keygen", "-q", *args, "-C", name, "-f", f.path)
                File(dir, "authorized_keys").appendText(File(f.path + ".pub").readText())
            }
            return f.readText()
        }
    }

    private fun connect(port: Int, key: String, passphrase: String = "", store: HostKeyStore = MemoryHostKeyStore()) =
        SshConnection.open(SshCredentials("127.0.0.1", port, "root", privateKey = key, passphrase = passphrase), store)

    @Test fun everyKeyExchangeCipherAndMac() {
        assumeTrue("needs root and OpenSSH", sshd)
        val key = userKey("ed", "-t", "ed25519", "-N", "")
        for (kex in SshClient.KEX) {
            val port = startSshd("KexAlgorithms $kex")
            connect(port, key).use { assertEquals("ok:$kex\n", it.run("echo ok:$kex").stdout) }
        }
        for (cipher in SshClient.CIPHERS) {
            val port = startSshd("Ciphers $cipher")
            connect(port, key).use { assertEquals("ok\n", it.run("echo ok").stdout) }
        }
        for (mac in SshClient.MACS) {
            val port = startSshd("Ciphers aes128-ctr", "MACs $mac")
            connect(port, key).use { assertEquals("ok\n", it.run("echo ok").stdout) }
        }
    }

    @Test fun everyHostKeyTypeAndTrustOnFirstUse() {
        assumeTrue("needs root and OpenSSH", sshd)
        val key = userKey("ed", "-t", "ed25519", "-N", "")
        for (alg in SshKeys.HOST_KEY_FAMILIES.flatten().filter { !it.contains("384") && !it.contains("521") }) {
            val port = startSshd("HostKeyAlgorithms $alg")
            val store = MemoryHostKeyStore()
            connect(port, key, store = store).use { it.run("true") }
            val fp = store.get("127.0.0.1", port)!!
            val file = when { alg.startsWith("ssh-ed") -> "ed25519"; alg.startsWith("ecdsa") -> "ecdsa"; else -> "rsa" }
            val expected = run("ssh-keygen", "-lf", hostKeys.getValue(file).path + ".pub").split(' ')[1]
            assertEquals(alg, expected, fp)
            connect(port, key, store = store).use { it.run("true") }
        }
        // The server prefers Ed25519, but RSA was remembered (another app version chose it): still the same server.
        val port = startSshd()
        val rsaFp = run("ssh-keygen", "-lf", hostKeys.getValue("rsa").path + ".pub").split(' ')[1]
        val store = MemoryHostKeyStore().apply { put("127.0.0.1", port, rsaFp) }
        connect(port, key, store = store).use { assertEquals("ok\n", it.run("echo ok").stdout) }
        // A key the server does not have: refused.
        val wrong = MemoryHostKeyStore().apply { put("127.0.0.1", port, "SHA256:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA") }
        try { connect(port, key, store = wrong).close(); fail() } catch (e: SshException) { assertEquals("host_key_changed", e.code) }
    }

    @Test fun everyUserKeyFormat() {
        assumeTrue("needs root and OpenSSH", sshd)
        val port = startSshd()
        val keys = listOf(
            Triple("ed25519", arrayOf("-t", "ed25519", "-N", ""), ""),
            Triple("ed25519-pass", arrayOf("-t", "ed25519", "-N", "secret pass", "-a", "4"), "secret pass"),
            Triple("ecdsa256", arrayOf("-t", "ecdsa", "-b", "256", "-N", ""), ""),
            Triple("ecdsa384-pass", arrayOf("-t", "ecdsa", "-b", "384", "-N", "pass5", "-a", "2"), "pass5"),
            Triple("ecdsa521", arrayOf("-t", "ecdsa", "-b", "521", "-N", ""), ""),
            Triple("rsa", arrayOf("-t", "rsa", "-b", "2048", "-N", ""), ""),
            Triple("rsa-pass", arrayOf("-t", "rsa", "-b", "2048", "-N", "pass5", "-a", "2"), "pass5"),
            Triple("rsa-pem", arrayOf("-t", "rsa", "-b", "2048", "-N", "", "-m", "PEM"), ""),
            Triple("rsa-pem-pass", arrayOf("-t", "rsa", "-b", "2048", "-N", "pass5", "-m", "PEM"), "pass5"),
            Triple("ecdsa-pem", arrayOf("-t", "ecdsa", "-N", "", "-m", "PEM"), ""),
            Triple("ecdsa-pem-pass", arrayOf("-t", "ecdsa", "-N", "pass5", "-m", "PEM"), "pass5"),
            Triple("rsa-pkcs8", arrayOf("-t", "rsa", "-b", "2048", "-N", "", "-m", "PKCS8"), ""),
            Triple("rsa-pkcs8-pass", arrayOf("-t", "rsa", "-b", "2048", "-N", "pass5", "-m", "PKCS8"), "pass5"),
            Triple("ecdsa-pkcs8", arrayOf("-t", "ecdsa", "-N", "", "-m", "PKCS8"), ""),
            Triple("ecdsa-pkcs8-pass", arrayOf("-t", "ecdsa", "-N", "pass5", "-m", "PKCS8"), "pass5"),
        )
        for ((name, args, pass) in keys) {
            val text = userKey(name, *args)
            try {
                connect(port, text, pass).use { assertEquals(name, "ok\n", it.run("echo ok").stdout) }
            } catch (e: Exception) { throw AssertionError("$name: $e", e) }
            if (pass.isNotEmpty()) {
                for (wrong in listOf("", "wrong")) try { connect(port, text, wrong).close(); fail(name) } catch (e: SshException) {
                    assertEquals("$name/$wrong", "bad_passphrase", e.code)
                }
            }
        }
        // PuTTY: v2 with and without a passphrase, v3 without.
        if (File("/usr/bin/puttygen").canExecute()) {
            for ((name, version, pass) in listOf(Triple("ppk2", "2", ""), Triple("ppk2-pass", "2", "pass5"), Triple("ppk3", "3", ""), Triple("ppk3-rsa", "3", ""))) {
                val src = File(dir, "user_" + if (name == "ppk3-rsa") "rsa" else "ed25519")
                val out = File(dir, "$name.ppk")
                val passFile = File(dir, "$name.pass").apply { writeText(pass) }
                val argv = mutableListOf("puttygen", src.path, "-o", out.path, "--ppk-param", "version=$version")
                argv += if (pass.isEmpty()) listOf("--new-passphrase", "/dev/null") else listOf("--new-passphrase", passFile.path)
                run(*argv.toTypedArray())
                connect(port, out.readText(), pass).use { assertEquals(name, "ok\n", it.run("echo ok").stdout) }
            }
        }
    }

    @Test fun execStreamsWindowsRekeysAndReportsStatus() {
        assumeTrue("needs root and OpenSSH", sshd)
        val key = userKey("ed", "-t", "ed25519", "-N", "")
        // Re-key every 64 KB: several key exchanges happen in the middle of one command.
        val port = startSshd("RekeyLimit 64K")
        connect(port, key).use { ssh ->
            // A 3 MB script (beyond every window) and 2 MB of output.
            val big = "# " + "x".repeat(3_000_000) + "\n"
            val r = ssh.run(big + "head -c 2000000 /dev/zero | tr '\\0' 'a'; echo; echo err >&2; exit 7")
            assertEquals(7, r.exitCode)
            assertEquals(2_000_001, r.stdout.length)
            assertEquals("err\n", r.stderr)
            // Lines are whole UTF-8 even when a letter straddles two packets.
            val lines = ArrayList<String>()
            val text = "ж".repeat(40_000)
            ssh.run("printf '%s\\n' \"\$T\"; printf 'второй\\n'", env = mapOf("T" to text)) { lines += it }
            assertEquals(listOf(text, "второй"), lines)
            // Results and errors from our scripts.
            val res = ssh.run("echo ::result::a=1; echo ::result::b=x=y; echo ::error::boom")
            assertEquals(mapOf("a" to "1", "b" to "x=y"), res.results)
            assertEquals("boom", res.error)
            // Killed by a signal.
            assertEquals(128 + 15, ssh.run("kill -TERM \$\$").exitCode)
        }
    }

    @Test fun timeoutsAndRefusals() {
        assumeTrue("needs root and OpenSSH", sshd)
        val key = userKey("ed", "-t", "ed25519", "-N", "")
        val port = startSshd()
        connect(port, key).use { ssh ->
            try { ssh.run("sleep 5", timeoutMs = 500); fail() } catch (e: SshException) { assertEquals("timeout", e.code) }
        }
        val stranger = SshKeys.generateEd25519("x").first
        try { connect(port, stranger).close(); fail() } catch (e: SshException) { assertEquals("auth_failed", e.code) }
        try { connect(freePort(), key).close(); fail() } catch (e: SshException) { assertEquals("connect_failed", e.code) }
        // A port that accepts but never speaks SSH.
        ServerSocket(0).use { silent ->
            val t0 = System.currentTimeMillis()
            try {
                SshConnection.open(SshCredentials("127.0.0.1", silent.localPort, "root", "pass5"), MemoryHostKeyStore()).close(); fail()
            } catch (e: SshException) { assertEquals("unreachable", e.code) }
            assertTrue(System.currentTimeMillis() - t0 < 90_000)
        }
    }

    // ---- password and keyboard-interactive, against Apache MINA sshd ----

    @Test fun passwordAndKeyboardInteractive() {
        for (kbdOnly in listOf(false, true)) {
            val server = SshServer.setUpDefaultServer().apply {
                port = 0
                keyPairProvider = SimpleGeneratorHostKeyProvider(Files.createTempFile("hostkey", ".ser"))
                if (kbdOnly) {
                    passwordAuthenticator = null
                    keyboardInteractiveAuthenticator = object : DefaultKeyboardInteractiveAuthenticator() {}
                    setPasswordAuthenticator { u, p, _ -> u == "root" && p == "пароль" }
                    // Only keyboard-interactive is offered; it checks through the password authenticator.
                    userAuthFactories = listOf(org.apache.sshd.server.auth.keyboard.UserAuthKeyboardInteractiveFactory.INSTANCE)
                } else setPasswordAuthenticator { u, p, _ -> u == "root" && p == "пароль" }
                commandFactory = ProcessShellCommandFactory.INSTANCE
                start()
            }
            try {
                SshConnection.open(SshCredentials("127.0.0.1", server.port, "root", "пароль"), MemoryHostKeyStore()).use {
                    assertEquals("ok\n", it.run("echo ok").stdout)
                }
                try {
                    SshConnection.open(SshCredentials("127.0.0.1", server.port, "root", "wrong"), MemoryHostKeyStore()).close(); fail()
                } catch (e: SshException) { assertEquals("auth_failed", e.code) }
            } finally { server.stop(true) }
        }
    }

    @Test fun mpintAndReaderBounds() {
        val w = SshWriter().mpint(BigInteger.ZERO).mpint(BigInteger("80", 16)).mpint(BigInteger.valueOf(-1)).bytes()
        assertArrayEquals(hex("00000000" + "000000020080" + "00000001ff"), w)
        try { SshReader(hex("0000ffff00")).string(); fail() } catch (e: SshProtocolException) {}
    }

    /** mlkem768x25519-sha256 against MINA sshd (BouncyCastle's ML-KEM against the JDK's). Needs Java 24+. */
    @Test fun postQuantumKeyExchange() {
        assumeTrue("ML-KEM needs Java 24+ (running ${System.getProperty("java.version")})", MlKem768.available)
        assertEquals("mlkem768x25519-sha256", SshClient.kexAlgorithms.first())
        val server = SshServer.setUpDefaultServer().apply {
            port = 0
            keyPairProvider = SimpleGeneratorHostKeyProvider(Files.createTempFile("hostkey", ".ser"))
            keyExchangeFactories = org.apache.sshd.server.ServerBuilder.setUpDefaultKeyExchanges(false)
                .filter { it.name == "mlkem768x25519-sha256" }
            setPasswordAuthenticator { u, p, _ -> u == "root" && p == "pw" }
            commandFactory = ProcessShellCommandFactory.INSTANCE
            start()
        }
        try {
            assertEquals(listOf("mlkem768x25519-sha256"), server.keyExchangeFactories.map { it.name })
            SshConnection.open(SshCredentials("127.0.0.1", server.port, "root", "pw"), MemoryHostKeyStore()).use {
                assertEquals("pq\n", it.run("echo pq").stdout)
            }
        } finally { server.stop(true) }
    }
}
