package net.paramnezia.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * What the shared screens need from the system they run on. The screens in this package are
 * compiled twice: into the Android app and into the Windows app (`desktop/`, see
 * docs/DEVELOPMENT.md), so they never touch Android or Windows APIs directly, only this.
 */
interface UiPlatform {
    /** Prepositional form for hints: "на этом телефоне", "на этом компьютере". */
    val deviceWhere: String
    /** Genitive form: "сеть телефона", "сеть компьютера". */
    val deviceOf: String
    /** Device name for a new fleet member ("Pixel 8", "DESKTOP-1234"). */
    val deviceModel: String

    fun toast(message: String)
    fun openUrl(url: String)
    /** Copies [text]; [sensitive] keeps it out of clipboard previews and history where the system allows. */
    fun copy(text: String, sensitive: Boolean = false)
    /** Hands [text] to the user's messengers (Android share sheet; on Windows: copy and say so). */
    fun shareText(text: String, title: String)
    /** A settings file `<name>.vpn` with [key] for the person to send on. */
    fun shareFile(name: String, key: String)

    /** Returns a launcher that reads QR codes (camera on Android, an image on Windows) into [onText]. */
    @Composable
    fun rememberQrScanner(onText: (String) -> Unit): () -> Unit

    /** Returns a launcher that lets the user pick a settings file; its text goes to [onText]. */
    @Composable
    fun rememberFilePicker(onText: (String) -> Unit): () -> Unit

    /** Label of the QR tile ("QR-код" / "QR с картинки"). */
    val qrLabel: String get() = "QR-код"
}

val LocalUiPlatform = staticCompositionLocalOf<UiPlatform> { error("UiPlatform not provided") }

/** Safe in every messenger and file system; Cyrillic stays. */
fun settingsFileName(name: String): String {
    val clean = name.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "").trim().replace(Regex("\\s+"), "-").take(40)
    return (clean.ifEmpty { "paramnezia" }) + ".vpn"
}
