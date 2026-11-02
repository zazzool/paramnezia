package net.paramnezia.app.ui

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/**
 * Sending settings to people through any messenger, always via Android's share sheet: the admin
 * picks the app and the person. Nothing is sent by itself.
 */
object Share {
    fun text(context: Context, text: String, title: String = "Отправить") {
        context.startActivity(notToSelf(context, Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), title)))
    }

    /** Paramnezia opens shared keys itself; it should not offer itself as a messenger. */
    private fun notToSelf(context: Context, chooser: Intent): Intent = chooser.putExtra(
        Intent.EXTRA_EXCLUDE_COMPONENTS, arrayOf(android.content.ComponentName(context, MainActivity::class.java)),
    )

    /**
     * A settings file `<name>.vpn` holding the key. The person taps it in the messenger or file
     * manager and Paramnezia opens with it (AmneziaVPN reads the same file).
     */
    fun file(context: Context, name: String, key: String) {
        val dir = File(context.cacheDir, "share").apply { mkdirs() }
        // Old files hold keys: keep only today's.
        dir.listFiles()?.forEach { if (System.currentTimeMillis() - it.lastModified() > 24 * 3600_000L) it.delete() }
        val file = File(dir, settingsFileName(name))
        file.writeText(key.trim() + "\n")
        val uri = FileProvider.getUriForFile(context, context.packageName + ".share", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType("application/octet-stream")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_TEXT, "Настройки VPN для «$name». Откройте файл на телефоне с Paramnezia (или AmneziaVPN).")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(notToSelf(context, Intent.createChooser(send, "Отправить файл настроек").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)))
    }

}
