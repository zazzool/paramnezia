package net.paramnezia.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.paramnezia.app.platform.readUpTo
import net.paramnezia.app.ui.UiPlatform
import net.paramnezia.app.ui.settingsFileName
import java.awt.Desktop
import java.awt.FileDialog
import java.awt.Frame
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.image.BufferedImage
import java.io.File
import java.net.URI

/**
 * The shared screens on Windows. There is no share sheet: "send" copies the text and says so, and a
 * settings file is saved where the admin chooses, to attach in any messenger. QR codes are read
 * from an image (a screenshot in the clipboard, or a file).
 */
class DesktopUiPlatform(override val deviceModel: String) : UiPlatform {
    override val deviceWhere = "компьютере"
    override val deviceOf = "компьютера"

    /** Messages for the snackbar of the open window. */
    val messages = MutableSharedFlow<String>(extraBufferCapacity = 8)

    override fun toast(message: String) { messages.tryEmit(message) }

    override fun openUrl(url: String) = openInBrowser(url)

    override fun copy(text: String, sensitive: Boolean) {
        runCatching { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null) }
    }

    override fun shareText(text: String, title: String) {
        copy(text)
        toast("Скопировано: вставьте в мессенджер (Ctrl+V)")
    }

    override fun shareFile(name: String, key: String) {
        val dialog = FileDialog(null as Frame?, "Сохранить файл настроек", FileDialog.SAVE).apply {
            file = settingsFileName(name)
            isVisible = true
        }
        val f = dialog.file ?: return
        val target = File(dialog.directory, if (f.endsWith(".vpn", true)) f else "$f.vpn")
        runCatching { target.writeText(key.trim() + "\n") }
            .onSuccess { toast("Сохранено: ${target.name}. Отправьте этот файл в мессенджере.") }
            .onFailure { toast("Не удалось сохранить: ${it.message}") }
    }

    @Composable
    override fun rememberQrScanner(onText: (String) -> Unit): () -> Unit {
        val scope = rememberCoroutineScope()
        return {
            scope.launch {
                val image = clipboardImage() ?: pickFile("Картинка с QR-кодом", "*.png;*.jpg;*.jpeg;*.bmp;*.gif")?.let { f ->
                    withContext(Dispatchers.IO) { runCatching { javax.imageio.ImageIO.read(f) }.getOrNull() }
                }
                if (image == null) { toast("Скопируйте картинку с QR-кодом или выберите файл"); return@launch }
                val codes = withContext(Dispatchers.Default) { QrImage.decode(image) }
                if (codes.isEmpty()) toast("На картинке не найден QR-код") else codes.forEach(onText)
            }
        }
    }

    @Composable
    override fun rememberFilePicker(onText: (String) -> Unit): () -> Unit {
        val scope = rememberCoroutineScope()
        return {
            scope.launch {
                val f = pickFile("Файл настроек", "*.vpn;*.conf;*.txt") ?: return@launch
                val text = withContext(Dispatchers.IO) { runCatching { f.inputStream().use { it.readUpTo(256 * 1024).decodeToString() } }.getOrNull() }
                if (text != null) onText(text) else toast("Не удалось прочитать файл")
            }
        }
    }

    private fun pickFile(title: String, filter: String): File? {
        val dialog = FileDialog(null as Frame?, title, FileDialog.LOAD).apply {
            file = filter // Windows reads this as the file type filter
            isVisible = true
        }
        return dialog.file?.let { File(dialog.directory, it) }
    }

    private fun clipboardImage(): BufferedImage? = runCatching {
        val c = Toolkit.getDefaultToolkit().systemClipboard
        if (!c.isDataFlavorAvailable(DataFlavor.imageFlavor)) return null
        val img = c.getData(DataFlavor.imageFlavor) as? java.awt.Image ?: return null
        if (img is BufferedImage) img else BufferedImage(img.getWidth(null), img.getHeight(null), BufferedImage.TYPE_INT_RGB).apply {
            createGraphics().apply { drawImage(img, 0, 0, null); dispose() }
        }
    }.getOrNull()

    companion object {
        fun openInBrowser(url: String) {
            if (!url.startsWith("https://") && !url.startsWith("http://")) return
            val ok = runCatching { Desktop.getDesktop().browse(URI(url)); true }.getOrDefault(false)
            if (!ok && StateFile.WINDOWS) runCatching { ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", url).start() }
        }
    }
}
