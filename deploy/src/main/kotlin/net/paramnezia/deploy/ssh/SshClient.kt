package net.paramnezia.deploy.ssh

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.EOFException
import java.math.BigInteger
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.spec.ECPublicKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** The handshake took longer than allowed, or the server stopped answering. */
internal class SshTimeoutException(message: String) : SshProtocolException(message)
/** Authentication was refused; [code] is "auth_failed" or "password_change_required". */
internal class SshAuthException(val code: String, message: String) : SshProtocolException(message)
/** The server's host key is not the one remembered; [algorithm] is the key type it presented. */
internal class SshHostKeyException(val algorithm: String, val fingerprint: String) : SshProtocolException("host key mismatch")
/** No algorithm in common for [what]: the server does not offer what was asked for. */
internal class SshNegotiationException(val what: String, message: String) : SshProtocolException(message)

/**
 * A minimal SSH 2 client (RFC 4251–4254): exactly what server management needs, nothing more.
 *
 * Key exchange: curve25519-sha256, ECDH on the NIST curves, DH groups 14 and 16, with OpenSSH's
 * strict key exchange (the Terrapin fix). Host keys: Ed25519, ECDSA, RSA with SHA-2. Ciphers:
 * AES-GCM, or AES-CTR with HMAC-SHA2 (encrypt-then-MAC preferred). Authentication: public key,
 * password and keyboard-interactive. Channels: one `exec` at a time. The server may re-key at any
 * moment; a connection that stops answering keepalives is reported instead of hanging.
 *
 * Blocking and single-threaded: one caller drives it, reads and writes interleave in [exec].
 */
internal class SshClient(
    private val socket: Socket,
    /** Host key algorithms to offer, best first. */
    private val hostKeyAlgorithms: List<String> = SshKeys.HOST_KEY_FAMILIES.flatten(),
    /** Called with the host key algorithm and blob; throws to refuse the key. */
    private val acceptHostKey: (algorithm: String, blob: ByteArray) -> Unit,
) : Closeable {
    private val random = SecureRandom()
    private val input = BufferedInputStream(socket.getInputStream(), 1 shl 15)
    private val output = BufferedOutputStream(socket.getOutputStream(), 1 shl 15)

    private var serverVersion = ""
    private var sessionId: ByteArray? = null
    private var hostKey: ByteArray? = null
    private var strict = false
    private var authenticated = false
    private var inKex = false
    private var sendSeq = 0
    private var recvSeq = 0
    private var sendCipher: PacketCipher? = null
    private var recvCipher: PacketCipher? = null
    /** RSA signature algorithms the server accepts for user keys (EXT_INFO), null if it did not say. */
    private var serverSigAlgs: Set<String>? = null

    /** What to do when a read waits [idleMs]: send a keepalive, or give up. */
    private var onIdle: () -> Unit = { throw SshTimeoutException("server did not answer") }
    private var missedKeepalives = 0

    // ---- packets ----

    private abstract class PacketCipher {
        abstract fun write(seq: Int, packet: (block: Int, aead: Boolean) -> ByteArray, out: BufferedOutputStream)
        abstract fun read(seq: Int, input: (ByteArray, Int, Int) -> Unit): ByteArray
    }

    /** aes{128,256}-gcm@openssh.com (RFC 5647): the length is clear but authenticated. */
    private class Gcm(key: ByteArray, iv: ByteArray, private val encrypt: Boolean) : PacketCipher() {
        private val key = SecretKeySpec(key, "AES")
        private val fixed = iv.copyOf(4)
        private var counter = BigInteger(1, iv.copyOfRange(4, 12)).toLong()
        private val cipher = Cipher.getInstance("AES/GCM/NoPadding")

        private fun nextIv(): GCMParameterSpec {
            val iv = fixed.copyOf(12)
            for (i in 0 until 8) iv[4 + i] = (counter ushr (56 - 8 * i)).toByte()
            counter++
            return GCMParameterSpec(128, iv)
        }

        override fun write(seq: Int, packet: (Int, Boolean) -> ByteArray, out: BufferedOutputStream) {
            val p = packet(16, true) // without the length field
            val len = int4(p.size)
            cipher.init(Cipher.ENCRYPT_MODE, key, nextIv())
            cipher.updateAAD(len)
            out.write(len); out.write(cipher.doFinal(p))
        }

        override fun read(seq: Int, input: (ByteArray, Int, Int) -> Unit): ByteArray {
            val len = ByteArray(4).also { input(it, 0, 4) }
            val n = readInt(len, 0)
            if (n < 16 || n > MAX_PACKET || n % 16 != 0) throw SshProtocolException("bad packet length $n")
            val body = ByteArray(n + 16).also { input(it, 0, it.size) }
            cipher.init(Cipher.DECRYPT_MODE, key, nextIv())
            cipher.updateAAD(len)
            return try { cipher.doFinal(body) } catch (e: GeneralSecurityException) { throw SshProtocolException("packet authentication failed") }
        }
    }

    /** aes*-ctr with hmac-sha2-*, either encrypt-then-MAC (-etm@openssh.com) or the original order. */
    private class CtrHmac(key: ByteArray, iv: ByteArray, macAlg: String, macKey: ByteArray, private val etm: Boolean, encrypt: Boolean) : PacketCipher() {
        private val cipher = Cipher.getInstance("AES/CTR/NoPadding").apply {
            init(if (encrypt) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        }
        private val mac = Mac.getInstance(macAlg).apply { init(SecretKeySpec(macKey, macAlg)) }
        private fun crypt(b: ByteArray): ByteArray = if (b.isEmpty()) b else cipher.update(b) ?: ByteArray(0)

        override fun write(seq: Int, packet: (Int, Boolean) -> ByteArray, out: BufferedOutputStream) {
            if (etm) {
                val p = packet(16, true)
                val len = int4(p.size)
                val ct = crypt(p)
                mac.update(int4(seq)); mac.update(len); mac.update(ct)
                out.write(len); out.write(ct); out.write(mac.doFinal())
            } else {
                val p = packet(16, false) // with the length field
                mac.update(int4(seq)); mac.update(p)
                out.write(crypt(p)); out.write(mac.doFinal())
            }
        }

        override fun read(seq: Int, input: (ByteArray, Int, Int) -> Unit): ByteArray {
            val macLen = mac.macLength
            if (etm) {
                val len = ByteArray(4).also { input(it, 0, 4) }
                val n = readInt(len, 0)
                if (n < 16 || n > MAX_PACKET || n % 16 != 0) throw SshProtocolException("bad packet length $n")
                val ct = ByteArray(n).also { input(it, 0, n) }
                val tag = ByteArray(macLen).also { input(it, 0, macLen) }
                mac.update(int4(seq)); mac.update(len); mac.update(ct)
                if (!MessageDigest.isEqual(mac.doFinal(), tag)) throw SshProtocolException("packet authentication failed")
                return crypt(ct)
            }
            val first = ByteArray(16).also { input(it, 0, 16) }
            val head = crypt(first)
            val n = readInt(head, 0)
            if (n < 12 || n > MAX_PACKET || (n + 4) % 16 != 0) throw SshProtocolException("bad packet length $n")
            val rest = ByteArray(n + 4 - 16).also { input(it, 0, it.size) }
            val plain = head + crypt(rest)
            val tag = ByteArray(macLen).also { input(it, 0, macLen) }
            mac.update(int4(seq)); mac.update(plain)
            if (!MessageDigest.isEqual(mac.doFinal(), tag)) throw SshProtocolException("packet authentication failed")
            return plain.copyOfRange(4, plain.size)
        }
    }

    /** Reads exactly [len] bytes; a read that times out calls [onIdle] and keeps waiting. */
    private fun readFully(b: ByteArray, off: Int, len: Int) {
        var pos = off
        while (pos < off + len) {
            val n = try { input.read(b, pos, off + len - pos) } catch (e: SocketTimeoutException) { onIdle(); continue }
            if (n < 0) throw EOFException("connection closed by the server")
            pos += n
        }
    }

    private fun send(payload: ByteArray) {
        val build = { block: Int, aead: Boolean ->
            val headLen = if (aead) 1 else 5
            var pad = block - (headLen + payload.size) % block
            if (pad < 4) pad += block
            val w = SshWriter(headLen + payload.size + pad)
            if (!aead) w.uint32(1 + payload.size + pad)
            w.byte(pad).raw(payload).raw(ByteArray(pad).also(random::nextBytes))
            w.bytes()
        }
        val c = sendCipher
        if (c == null) output.write(build(8, false)) else c.write(sendSeq, build, output)
        output.flush()
        sendSeq++
    }

    /** The next packet's payload, whatever it is. */
    private fun readPacket(): ByteArray {
        val c = recvCipher
        val body = if (c == null) {
            val len = ByteArray(4).also { readFully(it, 0, 4) }
            val n = readInt(len, 0)
            if (n < 12 || n > MAX_PACKET) throw SshProtocolException("bad packet length $n")
            ByteArray(n).also { readFully(it, 0, n) }
        } else c.read(recvSeq) { b, o, l -> readFully(b, o, l) }
        recvSeq++
        missedKeepalives = 0
        val pad = body[0].toInt() and 0xFF
        if (pad < 4 || pad >= body.size) throw SshProtocolException("bad padding")
        return body.copyOfRange(1, body.size - pad)
    }

    /** The next message for the caller; transport-level messages (re-keying, keepalives, …) are handled here. */
    private fun readMessage(): SshReader {
        while (true) {
            val p = readPacket()
            when (p[0].toInt() and 0xFF) {
                MSG_DISCONNECT -> { val r = SshReader(p, 1); r.uint32(); throw SshProtocolException("server disconnected: ${r.text()}") }
                MSG_IGNORE, MSG_DEBUG, MSG_UNIMPLEMENTED, MSG_REQUEST_SUCCESS, MSG_REQUEST_FAILURE -> {}
                MSG_EXT_INFO -> {
                    val r = SshReader(p, 1)
                    repeat(r.uint32()) { val name = r.text(); val value = r.text(); if (name == "server-sig-algs") serverSigAlgs = value.split(',').toSet() }
                }
                MSG_KEXINIT -> kex(p)
                MSG_GLOBAL_REQUEST -> { val r = SshReader(p, 1); r.text(); if (r.bool()) send(byteArrayOf(MSG_REQUEST_FAILURE.toByte())) }
                else -> return SshReader(p)
            }
        }
    }

    private fun expect(type: Int, what: String): SshReader {
        val r = readMessage()
        val t = r.byte()
        if (t != type) throw SshProtocolException("expected $what, got message $t")
        return r
    }

    // ---- handshake ----

    /** Version exchange and the first key exchange; the host key is checked by [acceptHostKey]. */
    fun handshake(timeoutMs: Int) {
        socket.soTimeout = timeoutMs
        onIdle = { throw SshTimeoutException("server did not answer") }
        output.write("$CLIENT_VERSION\r\n".toByteArray(Charsets.US_ASCII)); output.flush()
        // The server may print lines before its version (RFC 4253 §4.2).
        repeat(64) {
            val line = readLine()
            if (line.startsWith("SSH-")) {
                if (!line.startsWith("SSH-2.0-") && !line.startsWith("SSH-1.99-")) throw SshProtocolException("unsupported SSH version: $line")
                serverVersion = line
                kex(null)
                return
            }
        }
        throw SshProtocolException("no SSH version line")
    }

    private fun readLine(): String {
        val b = ByteArrayOutputStream()
        while (true) {
            val one = ByteArray(1); readFully(one, 0, 1)
            val c = one[0].toInt()
            if (c == '\n'.code) break
            if (c != '\r'.code) b.write(c)
            if (b.size() > 255) throw SshProtocolException("version line too long")
        }
        return b.toString(Charsets.UTF_8.name())
    }

    private fun kexInit(): ByteArray = SshWriter().byte(MSG_KEXINIT).raw(ByteArray(16).also(random::nextBytes))
        .nameList(kexAlgorithms + listOf("ext-info-c", "kex-strict-c-v00@openssh.com"))
        .nameList(hostKeyAlgorithms)
        .nameList(CIPHERS).nameList(CIPHERS).nameList(MACS).nameList(MACS)
        .nameList(listOf("none")).nameList(listOf("none")).nameList(emptyList()).nameList(emptyList())
        .bool(false).uint32(0).bytes()

    /** Reads a key exchange message; in the first exchange with strict-kex nothing else is allowed. */
    private fun kexMessage(): ByteArray {
        while (true) {
            val p = readPacket()
            val t = p[0].toInt() and 0xFF
            when {
                t == MSG_DISCONNECT -> { val r = SshReader(p, 1); r.uint32(); throw SshProtocolException("server disconnected: ${r.text()}") }
                t == MSG_KEXINIT || t == MSG_NEWKEYS || t in 30..49 -> return p
                (t == MSG_IGNORE || t == MSG_DEBUG || t == MSG_UNIMPLEMENTED) && !(strict && sessionId == null) -> {}
                else -> throw SshProtocolException("unexpected message $t during key exchange")
            }
        }
    }

    private fun pick(what: String, ours: List<String>, theirs: List<String>): String =
        ours.firstOrNull { it in theirs } ?: throw SshNegotiationException(what, "no common $what (server offers ${theirs.joinToString(",")})")

    /** One key exchange; [serverInit] is the server's KEXINIT when the server started it (re-key). */
    private fun kex(serverInit: ByteArray?) {
        inKex = true
        val ours = kexInit()
        send(ours)
        val theirs = serverInit ?: kexMessage().also { if (it[0].toInt() != MSG_KEXINIT) throw SshProtocolException("expected KEXINIT") }
        val r = SshReader(theirs, 17)
        val lists = List(10) { r.nameList() }
        val guessFollows = r.bool()
        if (sessionId == null) strict = "kex-strict-s-v00@openssh.com" in lists[0]
        val kexAlg = pick("key exchange", kexAlgorithms, lists[0])
        val hostAlg = pick("host key type", hostKeyAlgorithms, lists[1])
        val cOut = pick("cipher", CIPHERS, lists[2]); val cIn = pick("cipher", CIPHERS, lists[3])
        val mOut = if (cOut.contains("gcm")) "" else pick("MAC", MACS, lists[4])
        val mIn = if (cIn.contains("gcm")) "" else pick("MAC", MACS, lists[5])
        if ("none" !in lists[6] || "none" !in lists[7]) throw SshNegotiationException("compression", "server requires compression")
        // A wrong guess means its first key exchange packet was for another method: skip it.
        if (guessFollows && (lists[0].firstOrNull() != kexAlg || lists[1].firstOrNull() != hostAlg)) kexMessage()

        val hashAlg = when { kexAlg.endsWith("sha512") || kexAlg == "ecdh-sha2-nistp521" -> "SHA-512"; kexAlg == "ecdh-sha2-nistp384" -> "SHA-384"; else -> "SHA-256" }
        val prefix = SshWriter().string(CLIENT_VERSION).string(serverVersion).string(ours).string(theirs)
        val (kEnc, h, keyBlob, sig) = when {
            kexAlg == "mlkem768x25519-sha256" -> {
                // Hybrid post-quantum exchange (after JSch's DHXECKEM): ML-KEM-768 and X25519 in one
                // round trip; K is the string SHA-256(kem secret ‖ x25519 secret).
                val kem = MlKem768.Session()
                val priv = ByteArray(32).also(random::nextBytes)
                val xpub = Curve25519.x25519Base(priv)
                val qc = kem.publicKey + xpub
                send(SshWriter().byte(MSG_KEX_ECDH_INIT).string(qc).bytes())
                val reply = kexReply()
                val ks = reply.string(); val qs = reply.string(); val sg = reply.string()
                if (qs.size != MlKem768.CIPHERTEXT + 32) throw SshProtocolException("bad mlkem768x25519 reply")
                val kemSecret = kem.decapsulate(qs.copyOfRange(0, MlKem768.CIPHERTEXT))
                val shared = Curve25519.x25519(priv, qs.copyOfRange(MlKem768.CIPHERTEXT, qs.size))
                if (shared.all { it.toInt() == 0 }) throw SshProtocolException("degenerate curve25519 key")
                val kk = SshWriter().string(hash("SHA-256", kemSecret + shared)).bytes()
                Kex(kk, hash(hashAlg, prefix.string(ks).string(qc).string(qs).raw(kk).bytes()), ks, sg)
            }
            kexAlg.startsWith("curve25519") -> {
                val priv = ByteArray(32).also(random::nextBytes)
                val pub = Curve25519.x25519Base(priv)
                send(SshWriter().byte(MSG_KEX_ECDH_INIT).string(pub).bytes())
                val reply = kexReply()
                val ks = reply.string(); val qs = reply.string(); val sg = reply.string()
                if (qs.size != 32) throw SshProtocolException("bad curve25519 key")
                val shared = Curve25519.x25519(priv, qs)
                if (shared.all { it.toInt() == 0 }) throw SshProtocolException("degenerate curve25519 key")
                val kk = SshWriter().mpint(BigInteger(1, shared)).bytes()
                Kex(kk, hash(hashAlg, prefix.string(ks).string(pub).string(qs).raw(kk).bytes()), ks, sg)
            }
            kexAlg.startsWith("ecdh-sha2-") -> {
                val curve = EcCurve.byId(kexAlg.removePrefix("ecdh-sha2-"))!!
                val kp = KeyPairGenerator.getInstance("EC").apply { initialize(curve.params, random) }.generateKeyPair()
                val pub = curve.encode((kp.public as java.security.interfaces.ECPublicKey).w)
                send(SshWriter().byte(MSG_KEX_ECDH_INIT).string(pub).bytes())
                val reply = kexReply()
                val ks = reply.string(); val qs = reply.string(); val sg = reply.string()
                val peer = KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(curve.decode(qs), curve.params))
                val shared = KeyAgreement.getInstance("ECDH").apply { init(kp.private); doPhase(peer, true) }.generateSecret()
                val kk = SshWriter().mpint(BigInteger(1, shared)).bytes()
                Kex(kk, hash(hashAlg, prefix.string(ks).string(pub).string(qs).raw(kk).bytes()), ks, sg)
            }
            else -> {
                val p = if (kexAlg.contains("group16")) MODP4096 else MODP2048
                val x = BigInteger(if (p === MODP4096) 1024 else 512, random).setBit(0)
                val e = BigInteger.valueOf(2).modPow(x, p)
                send(SshWriter().byte(MSG_KEX_ECDH_INIT).mpint(e).bytes())
                val reply = kexReply()
                val ks = reply.string(); val f = reply.mpint(); val sg = reply.string()
                if (f <= BigInteger.ONE || f >= p - BigInteger.ONE) throw SshProtocolException("bad DH value")
                val kk = SshWriter().mpint(f.modPow(x, p)).bytes()
                Kex(kk, hash(hashAlg, prefix.string(ks).mpint(e).mpint(f).raw(kk).bytes()), ks, sg)
            }
        }
        if (!SshKeys.verify(hostAlg, keyBlob, sig, h)) throw SshProtocolException("host key signature is invalid")
        val known = hostKey
        if (known == null) { acceptHostKey(hostAlg, keyBlob); hostKey = keyBlob }
        else if (!known.contentEquals(keyBlob)) throw SshProtocolException("host key changed during re-key")
        val sid = sessionId ?: h.also { sessionId = it }

        fun derive(letter: Char, len: Int): ByteArray {
            var out = hash(hashAlg, kEnc + h + byteArrayOf(letter.code.toByte()) + sid)
            while (out.size < len) out += hash(hashAlg, kEnc + h + out)
            return out.copyOf(len)
        }
        fun cipher(name: String, mac: String, ivL: Char, keyL: Char, macL: Char, encrypt: Boolean): PacketCipher {
            val keyLen = when { name.startsWith("aes128") -> 16; name.startsWith("aes192") -> 24; else -> 32 }
            return if (name.contains("gcm")) Gcm(derive(keyL, keyLen), derive(ivL, 12), encrypt)
            else {
                val (jca, macLen) = when (mac.removeSuffix("-etm@openssh.com")) { "hmac-sha2-512" -> "HmacSHA512" to 64; else -> "HmacSHA256" to 32 }
                CtrHmac(derive(keyL, keyLen), derive(ivL, 16), jca, derive(macL, macLen), mac.endsWith("-etm@openssh.com"), encrypt)
            }
        }

        send(byteArrayOf(MSG_NEWKEYS.toByte()))
        sendCipher = cipher(cOut, mOut, 'A', 'C', 'E', true)
        if (strict) sendSeq = 0
        if (kexMessage()[0].toInt() != MSG_NEWKEYS) throw SshProtocolException("expected NEWKEYS")
        recvCipher = cipher(cIn, mIn, 'B', 'D', 'F', false)
        if (strict) recvSeq = 0
        inKex = false
    }

    @Suppress("ArrayInDataClass") private data class Kex(val kEnc: ByteArray, val h: ByteArray, val keyBlob: ByteArray, val sig: ByteArray)

    private fun kexReply(): SshReader {
        val p = kexMessage()
        if (p[0].toInt() and 0xFF != MSG_KEX_ECDH_REPLY) throw SshProtocolException("expected key exchange reply")
        return SshReader(p, 1)
    }

    private fun hash(alg: String, data: ByteArray): ByteArray = MessageDigest.getInstance(alg).digest(data)

    // ---- authentication ----

    /**
     * Logs in as [user]: with [key] if given, then with [password] (as a password and through
     * keyboard-interactive, which is how many servers ask for it). Throws [SshAuthException].
     */
    fun authenticate(user: String, key: SshPrivateKey?, password: String) {
        send(SshWriter().byte(MSG_SERVICE_REQUEST).string("ssh-userauth").bytes())
        expect(MSG_SERVICE_ACCEPT, "service accept")
        fun request(method: String) = SshWriter().byte(MSG_USERAUTH_REQUEST).string(user).string("ssh-connection").string(method)

        var allowed: List<String>? = null
        /** Sends one attempt; true when logged in, false when refused (and [allowed] updated). */
        fun attempt(payload: ByteArray, kbd: Boolean = false): Boolean {
            send(payload)
            var rounds = 0
            while (true) {
                val r = readMessage()
                when (r.byte()) {
                    MSG_USERAUTH_SUCCESS -> return true
                    MSG_USERAUTH_FAILURE -> { allowed = r.nameList(); return false }
                    MSG_USERAUTH_BANNER -> {}
                    MSG_USERAUTH_INFO_REQUEST -> {
                        if (!kbd) throw SshAuthException("password_change_required", "The server requires a new password before it lets anyone in")
                        r.text(); r.text(); r.text()
                        val n = r.uint32()
                        if (n !in 0..16 || ++rounds > 4) return false
                        val w = SshWriter().byte(MSG_USERAUTH_INFO_RESPONSE).uint32(n)
                        repeat(n) { r.text(); r.bool(); w.string(password) }
                        send(w.bytes())
                    }
                    else -> throw SshProtocolException("unexpected message during authentication")
                }
            }
        }

        if (attempt(request("none").bytes())) return done()
        if (key != null && allowed!!.contains("publickey")) {
            for (alg in key.algorithms(serverSigAlgs)) {
                val signed = SshWriter().string(sessionId!!).raw(request("publickey").bool(true).string(alg).string(key.publicBlob).bytes())
                val sig = key.sign(alg, signed.bytes())
                if (attempt(request("publickey").bool(true).string(alg).string(key.publicBlob).string(sig).bytes())) return done()
                if (allowed?.contains("publickey") != true) break
            }
        }
        if (password.isNotEmpty()) {
            if (allowed!!.contains("password") && attempt(request("password").bool(false).string(password).bytes())) return done()
            if (allowed!!.contains("keyboard-interactive") && attempt(request("keyboard-interactive").string("").string("").bytes(), kbd = true)) return done()
        }
        throw SshAuthException("auth_failed", "authentication failed (server allows: ${allowed?.joinToString(",")})")
    }

    private fun done() { authenticated = true }

    // ---- exec ----

    class ExecOutput(val exitCode: Int, val stdout: ByteArray, val stderr: ByteArray)

    private var nextChannel = 0

    /**
     * Runs [command] with [stdin] as its input (then end of file) and waits for it to finish.
     * [onStdout] gets output bytes as they arrive. Keepalives go out every [keepaliveMs] of silence;
     * [maxMissedKeepalives] unanswered in a row mean the connection is gone.
     */
    fun exec(command: String, stdin: ByteArray, deadline: Long, keepaliveMs: Int = 15_000, maxMissedKeepalives: Int = 4, onStdout: (ByteArray, Int, Int) -> Unit): ExecOutput {
        check(authenticated)
        val local = nextChannel++
        socket.soTimeout = keepaliveMs
        onIdle = {
            if (System.currentTimeMillis() > deadline) throw SshTimeoutException("remote command timed out")
            if (++missedKeepalives > maxMissedKeepalives) throw SshConnectionLostException()
            if (!inKex) send(SshWriter().byte(MSG_GLOBAL_REQUEST).string("keepalive@openssh.com").bool(true).bytes())
        }
        var localWindow = WINDOW
        send(SshWriter().byte(MSG_CHANNEL_OPEN).string("session").uint32(local).uint32(localWindow).uint32(MAX_DATA).bytes())
        var remote = 0; var remoteWindow = 0L; var remoteMax = 0
        while (true) {
            val r = readMessage()
            when (r.byte()) {
                MSG_CHANNEL_OPEN_CONFIRMATION -> { if (r.uint32() != local) continue; remote = r.uint32(); remoteWindow = r.uint32Long(); remoteMax = r.uint32(); break }
                MSG_CHANNEL_OPEN_FAILURE -> { r.uint32(); r.uint32(); throw SshProtocolException("server refused a session: ${r.text()}") }
                else -> {}
            }
        }
        send(SshWriter().byte(MSG_CHANNEL_REQUEST).uint32(remote).string("exec").bool(true).string(command).bytes())

        val stdout = ByteArrayOutputStream(); val stderr = ByteArrayOutputStream()
        var exit = -1
        var sent = 0
        var eofSent = false
        var started = false
        fun pump() {
            if (!started || eofSent) return
            while (sent < stdin.size && remoteWindow > 0) {
                val n = minOf(stdin.size - sent, remoteMax.coerceIn(1, MAX_DATA), remoteWindow.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
                send(SshWriter(n + 16).byte(MSG_CHANNEL_DATA).uint32(remote).uint32(n).raw(stdin, sent, n).bytes())
                sent += n; remoteWindow -= n
            }
            if (sent == stdin.size) { send(SshWriter().byte(MSG_CHANNEL_EOF).uint32(remote).bytes()); eofSent = true }
        }
        fun consumed(n: Int) {
            localWindow -= n
            if (localWindow < WINDOW / 2) { send(SshWriter().byte(MSG_CHANNEL_WINDOW_ADJUST).uint32(remote).uint32(WINDOW - localWindow).bytes()); localWindow = WINDOW }
        }
        while (true) {
            if (System.currentTimeMillis() > deadline) throw SshTimeoutException("remote command timed out")
            val r = readMessage()
            val type = r.byte()
            if (type !in MSG_CHANNEL_WINDOW_ADJUST..MSG_CHANNEL_FAILURE) continue
            if (r.uint32() != local) continue
            when (type) {
                MSG_CHANNEL_SUCCESS -> { started = true; pump() }
                MSG_CHANNEL_FAILURE -> if (!started) throw SshProtocolException("server refused to run the command")
                MSG_CHANNEL_WINDOW_ADJUST -> { remoteWindow += r.uint32Long(); pump() }
                MSG_CHANNEL_DATA -> { val d = r.string(); stdout.write(d); onStdout(d, 0, d.size); consumed(d.size) }
                MSG_CHANNEL_EXTENDED_DATA -> { r.uint32(); val d = r.string(); stderr.write(d); consumed(d.size) }
                MSG_CHANNEL_EOF -> {}
                MSG_CHANNEL_REQUEST -> {
                    val name = r.text(); val wantReply = r.bool()
                    when (name) {
                        "exit-status" -> exit = r.uint32()
                        "exit-signal" -> exit = 128 + (SIGNALS.indexOf(r.text()).takeIf { it >= 0 }?.plus(1) ?: 0)
                    }
                    if (wantReply) send(SshWriter().byte(MSG_CHANNEL_FAILURE).uint32(remote).bytes())
                }
                MSG_CHANNEL_CLOSE -> {
                    send(SshWriter().byte(MSG_CHANNEL_CLOSE).uint32(remote).bytes())
                    return ExecOutput(exit, stdout.toByteArray(), stderr.toByteArray())
                }
            }
        }
    }

    override fun close() {
        runCatching {
            if (sessionId != null) send(SshWriter().byte(MSG_DISCONNECT).uint32(11).string("bye").string("").bytes())
        }
        runCatching { socket.close() }
    }

    companion object {
        const val CLIENT_VERSION = "SSH-2.0-Paramnezia_1.0"
        private const val MAX_PACKET = 256 * 1024
        private const val MAX_DATA = 32 * 1024
        private const val WINDOW = 2 * 1024 * 1024

        /** What we offer: the post-quantum hybrid first (as OpenSSH 9.9+ does), then [KEX]. */
        val kexAlgorithms: List<String> by lazy { listOf("mlkem768x25519-sha256") + KEX }

        val KEX = listOf(
            "curve25519-sha256", "curve25519-sha256@libssh.org",
            "ecdh-sha2-nistp256", "ecdh-sha2-nistp384", "ecdh-sha2-nistp521",
            "diffie-hellman-group16-sha512", "diffie-hellman-group14-sha256",
        )
        val CIPHERS = listOf("aes256-gcm@openssh.com", "aes128-gcm@openssh.com", "aes256-ctr", "aes192-ctr", "aes128-ctr")
        val MACS = listOf("hmac-sha2-256-etm@openssh.com", "hmac-sha2-512-etm@openssh.com", "hmac-sha2-256", "hmac-sha2-512")

        /** RFC 3526 groups, from their definition: 2^n − 2^(n−64) − 1 + 2^64·(⌊2^(n−130)·π⌋ + c). */
        private fun modp(n: Int, c: Long): BigInteger = BigInteger.ONE.shiftLeft(n).subtract(BigInteger.ONE.shiftLeft(n - 64)).subtract(BigInteger.ONE)
            .add(Bcrypt.piScaled(n - 130).add(BigInteger.valueOf(c)).shiftLeft(64))
        internal val MODP2048: BigInteger by lazy { modp(2048, 124476) }
        internal val MODP4096: BigInteger by lazy { modp(4096, 240904) }

        private val SIGNALS = listOf("HUP", "INT", "QUIT", "ILL", "TRAP", "ABRT", "BUS", "FPE", "KILL", "USR1", "SEGV", "USR2", "PIPE", "ALRM", "TERM")

        private const val MSG_DISCONNECT = 1
        private const val MSG_IGNORE = 2
        private const val MSG_UNIMPLEMENTED = 3
        private const val MSG_DEBUG = 4
        private const val MSG_SERVICE_REQUEST = 5
        private const val MSG_SERVICE_ACCEPT = 6
        private const val MSG_EXT_INFO = 7
        private const val MSG_KEXINIT = 20
        private const val MSG_NEWKEYS = 21
        private const val MSG_KEX_ECDH_INIT = 30
        private const val MSG_KEX_ECDH_REPLY = 31
        private const val MSG_USERAUTH_REQUEST = 50
        private const val MSG_USERAUTH_FAILURE = 51
        private const val MSG_USERAUTH_SUCCESS = 52
        private const val MSG_USERAUTH_BANNER = 53
        private const val MSG_USERAUTH_INFO_REQUEST = 60
        private const val MSG_USERAUTH_INFO_RESPONSE = 61
        private const val MSG_GLOBAL_REQUEST = 80
        private const val MSG_REQUEST_SUCCESS = 81
        private const val MSG_REQUEST_FAILURE = 82
        private const val MSG_CHANNEL_OPEN = 90
        private const val MSG_CHANNEL_OPEN_CONFIRMATION = 91
        private const val MSG_CHANNEL_OPEN_FAILURE = 92
        private const val MSG_CHANNEL_WINDOW_ADJUST = 93
        private const val MSG_CHANNEL_DATA = 94
        private const val MSG_CHANNEL_EXTENDED_DATA = 95
        private const val MSG_CHANNEL_EOF = 96
        private const val MSG_CHANNEL_CLOSE = 97
        private const val MSG_CHANNEL_REQUEST = 98
        private const val MSG_CHANNEL_SUCCESS = 99
        private const val MSG_CHANNEL_FAILURE = 100

        private fun int4(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
        private fun readInt(b: ByteArray, o: Int) = ((b[o].toInt() and 0xFF) shl 24) or ((b[o + 1].toInt() and 0xFF) shl 16) or ((b[o + 2].toInt() and 0xFF) shl 8) or (b[o + 3].toInt() and 0xFF)
    }
}

/** Keepalives went unanswered: the network or the server is gone. */
internal class SshConnectionLostException : SshProtocolException("connection lost")

/**
 * ML-KEM-768 (FIPS 203) for the hybrid key exchange: the JDK's own implementation where it has one
 * (Java 24+, the Windows app), otherwise ours (MlKem.kt, ported from Bouncy Castle: Android).
 * The JDK classes are touched only after a check, so nothing breaks where javax.crypto.KEM is missing.
 */
internal object MlKem768 {
    const val PUBLIC_KEY = 1184
    const val CIPHERTEXT = 1088

    val jdk: Boolean by lazy { runCatching { JdkSession().publicKey.size == PUBLIC_KEY }.getOrDefault(false) }
    /** Tests switch this off to run our own implementation where the JDK has one too. */
    @Volatile internal var preferJdk = true

    interface Session {
        val publicKey: ByteArray
        fun decapsulate(ciphertext: ByteArray): ByteArray
    }

    fun Session(): Session = if (preferJdk && jdk) JdkSession() else KotlinSession()

    private class KotlinSession : Session {
        private val pair = MlKemKotlin.keyPair()
        override val publicKey: ByteArray get() = pair.first
        override fun decapsulate(ciphertext: ByteArray) = MlKemKotlin.decapsulate(pair.second, ciphertext)
    }

    private class JdkSession : Session {
        private val decapsulator: javax.crypto.KEM.Decapsulator
        /** The raw encapsulation key: the end of its X.509 SubjectPublicKeyInfo. */
        override val publicKey: ByteArray

        init {
            val kp = KeyPairGenerator.getInstance("ML-KEM").apply { initialize(java.security.spec.NamedParameterSpec("ML-KEM-768")) }.generateKeyPair()
            decapsulator = javax.crypto.KEM.getInstance("ML-KEM").newDecapsulator(kp.private)
            val spki = kp.public.encoded
            publicKey = spki.copyOfRange(spki.size - PUBLIC_KEY, spki.size)
        }

        override fun decapsulate(ciphertext: ByteArray): ByteArray = decapsulator.decapsulate(ciphertext).encoded
    }
}
