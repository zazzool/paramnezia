package net.paramnezia.app.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.io.FileOutputStream
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * One encrypted file for all app state. The AES key lives in the Android Keystore (hardware-backed
 * where the phone has it) and never leaves it, so a copied file is useless elsewhere.
 *
 * Built not to lose data: every write goes to a temporary file, is flushed to disk and then renamed
 * over the old one (atomic on Android's file systems), and the previous good state is kept as
 * `state.bak`. If the main file cannot be read (a torn write, a Keystore hiccup), the backup is used;
 * if neither can be read, the unreadable file is moved aside instead of being overwritten, so nothing
 * is destroyed that a later version (or a developer) could still recover.
 */
class Vault(context: Context) {
    private val dir = context.noBackupFilesDir
    private val file = File(dir, "state.bin")
    private val backup = File(dir, "state.bak")

    /** The main file is known to decrypt (read or written by us in this process). */
    @Volatile private var fileGood = false

    /** Why the last [read] fell back, for logs; null when the main file was fine. */
    @Volatile var lastProblem: String? = null
        private set

    fun read(): String? {
        if (!file.exists() && !backup.exists()) return null
        decrypt(file)?.let { lastProblem = null; fileGood = true; return it }
        lastProblem = if (file.exists()) "state.bin unreadable" else "state.bin missing"
        decrypt(backup)?.let { return it }
        // Neither copy opens: keep them for later recovery and start empty.
        quarantine()
        return null
    }

    /** Moves the stored files aside (kept, never deleted) so the next write starts a fresh state. */
    fun quarantine() {
        val stamp = System.currentTimeMillis()
        if (file.exists()) file.renameTo(File(dir, "state.bin.unreadable-$stamp"))
        if (backup.exists()) backup.renameTo(File(dir, "state.bak.unreadable-$stamp"))
        fileGood = false
    }

    fun write(text: String) {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val out = cipher.iv + cipher.doFinal(text.toByteArray())
        val tmp = File(dir, "state.bin.tmp")
        FileOutputStream(tmp).use { s -> s.write(out); s.fd.sync() }
        // The current file becomes the backup only if it is good, so a bad write never replaces it.
        if (fileGood && file.exists()) file.copyTo(backup, overwrite = true)
        if (!tmp.renameTo(file)) { file.delete(); if (!tmp.renameTo(file)) throw java.io.IOException("cannot replace state.bin") }
        fileGood = true
    }

    private fun decrypt(f: File): String? {
        if (!f.exists()) return null
        return runCatching {
            val raw = f.readBytes()
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw, 0, 12))
            cipher.doFinal(raw, 12, raw.size - 12).decodeToString()
        }.getOrNull()
    }
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    private companion object {
        const val ALIAS = "paramnezia-state"
        const val TRANSFORM = "AES/GCM/NoPadding"
    }
}
