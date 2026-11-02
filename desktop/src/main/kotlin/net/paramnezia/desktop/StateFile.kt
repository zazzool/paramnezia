package net.paramnezia.desktop

import com.sun.jna.platform.win32.Crypt32Util
import net.paramnezia.app.platform.StateStore
import java.io.File
import java.io.FileOutputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * All app state in one encrypted file, like the Android vault: written to a temporary file,
 * flushed, then renamed over the old one; the previous good state is kept as `state.bak`;
 * unreadable files are moved aside, never overwritten.
 *
 * The AES key is protected by Windows DPAPI for the current user: a copied file (or key) is useless
 * on another computer or to another Windows account. Off Windows (development, CI screenshots) a
 * plain key file stands in.
 */
class StateFile(private val dir: File) : StateStore {
    private val file = File(dir, "state.bin")
    private val backup = File(dir, "state.bak")
    private val keyFile = File(dir, "state.key")
    @Volatile private var fileGood = false
    @Volatile override var lastProblem: String? = null
        private set

    init { dir.mkdirs() }

    override fun read(): String? {
        if (!file.exists() && !backup.exists()) return null
        decrypt(file)?.let { lastProblem = null; fileGood = true; return it }
        lastProblem = if (file.exists()) "state.bin unreadable" else "state.bin missing"
        decrypt(backup)?.let { return it }
        quarantine()
        return null
    }

    override fun quarantine() {
        val stamp = System.currentTimeMillis()
        if (file.exists()) file.renameTo(File(dir, "state.bin.unreadable-$stamp"))
        if (backup.exists()) backup.renameTo(File(dir, "state.bak.unreadable-$stamp"))
        fileGood = false
    }

    override fun write(text: String) {
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(), GCMParameterSpec(128, iv))
        val out = iv + cipher.doFinal(text.toByteArray())
        val tmp = File(dir, "state.bin.tmp")
        FileOutputStream(tmp).use { s -> s.write(out); s.fd.sync() }
        if (fileGood && file.exists()) file.copyTo(backup, overwrite = true)
        if (!tmp.renameTo(file)) { file.delete(); if (!tmp.renameTo(file)) throw java.io.IOException("cannot replace state.bin") }
        fileGood = true
    }

    private fun decrypt(f: File): String? {
        if (!f.exists()) return null
        return runCatching {
            val raw = f.readBytes()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw, 0, 12))
            cipher.doFinal(raw, 12, raw.size - 12).decodeToString()
        }.getOrNull()
    }

    @Volatile private var cached: SecretKeySpec? = null

    @Synchronized
    private fun key(): SecretKeySpec {
        cached?.let { return it }
        val raw = if (keyFile.exists()) unprotect(keyFile.readBytes()) else {
            val k = ByteArray(32).also { SecureRandom().nextBytes(it) }
            val tmp = File(dir, "state.key.tmp")
            FileOutputStream(tmp).use { s -> s.write(protect(k)); s.fd.sync() }
            if (!tmp.renameTo(keyFile)) throw java.io.IOException("cannot write state.key")
            k
        }
        return SecretKeySpec(raw, "AES").also { cached = it }
    }

    private fun protect(b: ByteArray): ByteArray = if (WINDOWS) Crypt32Util.cryptProtectData(b) else b
    private fun unprotect(b: ByteArray): ByteArray = if (WINDOWS) Crypt32Util.cryptUnprotectData(b) else b

    companion object {
        val WINDOWS = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
    }
}

/** Where the app keeps its files: %LOCALAPPDATA%\Paramnezia (never roams to other computers). */
object Paths {
    val home: File by lazy {
        val base = System.getProperty("paramnezia.home")?.let(::File)
            ?: System.getenv("LOCALAPPDATA")?.let { File(it, "Paramnezia") }
            ?: File(System.getProperty("user.home"), ".paramnezia")
        base.apply { mkdirs() }
    }
    val cache: File get() = File(home, "cache").apply { mkdirs() }
    val log: File get() = File(home, "paramnezia.log")
}
