package net.paramnezia.app.lan

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.InputStream

/*
 * Handing the app and the family settings to devices in the same Wi-Fi (docs/LAN-SHARE.md).
 *
 * The device that shares runs a small HTTP server while its «Раздать по Wi-Fi» screen is open and
 * announces it over mDNS (DNS-SD, the way printers and Chromecasts are found). Anything with a
 * browser opens its short address (http://192.168.1.5:8080) and gets a page with the Android app and
 * the Windows installer. A device that already has Paramnezia finds the sharer by itself and asks
 * for settings with one tap; the sharer's owner allows it on their screen, and only then the server
 * hands out a new key for that device. Nothing is sent anywhere beyond the local network.
 */

object LanProtocol {
    /** DNS-SD service type (Android NSD form; JmDNS adds "local."). */
    const val SERVICE_TYPE = "_paramnezia._tcp"
    const val VERSION = 1
    /** Port 80 makes the address just an IP (works on Windows); Android cannot bind below 1024. */
    val PORTS = listOf(80, 8080, 8888, 8000, 8008, 8081)

    /** How long a request for settings waits for the owner's answer. */
    const val ASK_TTL_MS = 5 * 60_000L
    /** How long an approved key can be picked up. */
    const val KEY_TTL_MS = 10 * 60_000L
    /** Sharing stops by itself after this long, in case the screen was left open. */
    const val SHARE_TTL_MS = 60 * 60_000L

    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
}

/** GET /info: who shares what. */
@Serializable
data class LanInfo(
    val app: String = "paramnezia",
    val proto: Int = LanProtocol.VERSION,
    val name: String,
    val version: String,
    /** The owner can hand out settings (a family admin or a server owner). */
    val settings: Boolean,
    val apk: Boolean,
    val exe: Boolean,
)

/** POST /join: a device asks for settings. [kind]: android, tv, windows, browser. */
@Serializable
data class LanAsk(val device: String, val kind: String = "")

/** The answer to an ask, polled at GET /join/<id>. */
@Serializable
data class LanAnswer(
    val id: String,
    /** waiting, ok, denied, expired, failed */
    val state: String,
    val key: String? = null,
    val message: String? = null,
)

/** A file the sharer hands out: the Android app or the Windows installer. */
class LanFile(
    val fileName: String,
    val mime: String,
    val size: Long,
    val version: String,
    val open: () -> InputStream,
) {
    companion object {
        fun of(file: File, fileName: String, mime: String, version: String) =
            LanFile(fileName, mime, file.length(), version) { file.inputStream() }

        const val APK_MIME = "application/vnd.android.package-archive"
        const val EXE_MIME = "application/vnd.microsoft.portable-executable"
    }
}

/** A device in the same network that shares (found over mDNS or typed in). */
data class LanPeer(val name: String, val host: String, val port: Int, val info: LanInfo? = null) {
    /** What to type in a browser: no scheme, no port when it is 80. */
    val address: String get() = LanNet.shortAddress(host, port)
}

/** What the platform adds: service discovery (Android NSD, JmDNS on Windows) and its own files. */
interface LanPlatform {
    /** Announces the share; closing withdraws it. */
    fun advertise(name: String, port: Int): AutoCloseable
    /** Reports sharers as they appear ([onFound] with a resolved IPv4 host) and disappear. */
    fun browse(onFound: (name: String, host: String, port: Int) -> Unit, onLost: (name: String) -> Unit): AutoCloseable
    /** The app this device can hand out without the internet (Android: the installed APK). */
    fun ownApk(): LanFile? = null
    /** android, tv or windows: shown to the owner when this device asks for settings. */
    val kind: String
    /** Where downloaded installers are kept for the next time (see [LanHub.prepare]). */
    val cacheDir: File
}
