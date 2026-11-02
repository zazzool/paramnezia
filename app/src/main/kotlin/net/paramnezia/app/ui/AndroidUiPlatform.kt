package net.paramnezia.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PersistableBundle
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

/** The shared screens on Android: toasts, the share sheet, the camera QR scanner, the file picker. */
class AndroidUiPlatform(private val context: Context) : UiPlatform {
    override val deviceWhere = "телефоне"
    override val deviceOf = "телефона"
    override val deviceModel: String = Build.MODEL

    override fun toast(message: String) = Toast.makeText(context, message, Toast.LENGTH_SHORT).show()

    override fun openUrl(url: String) {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    override fun copy(text: String, sensitive: Boolean) {
        val clip = ClipData.newPlainText("Paramnezia", text)
        if (sensitive) clip.description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
    }

    override fun shareText(text: String, title: String) = Share.text(context, text, title)

    override fun shareFile(name: String, key: String) = Share.file(context, name, key)

    @Composable
    override fun rememberQrScanner(onText: (String) -> Unit): () -> Unit {
        val launcher = rememberLauncherForActivityResult(ScanContract()) { result -> result.contents?.let(onText) }
        return { launcher.launch(scanOptions()) }
    }

    @Composable
    override fun rememberFilePicker(onText: (String) -> Unit): () -> Unit {
        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            val content = uri?.let { runCatching { context.contentResolver.openInputStream(it)?.use { s -> s.reader().readText() } }.getOrNull() }
            if (content != null) onText(content)
        }
        return { launcher.launch(arrayOf("*/*")) }
    }

    private fun scanOptions() = ScanOptions().apply {
        setDesiredBarcodeFormats(ScanOptions.QR_CODE)
        setBeepEnabled(false)
        setOrientationLocked(false)
        setPrompt("Наведите камеру на QR-код")
    }
}
