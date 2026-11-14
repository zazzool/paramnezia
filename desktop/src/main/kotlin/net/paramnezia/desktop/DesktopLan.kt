package net.paramnezia.desktop

import net.paramnezia.app.lan.LanPlatform
import net.paramnezia.app.lan.LanProtocol
import net.paramnezia.app.lan.Mdns
import java.io.File

/**
 * Sharing in the local network on Windows: our own mDNS ([Mdns], one socket for all Wi-Fi and
 * Ethernet interfaces). The computer has no app file of its own to hand out, so
 * [net.paramnezia.app.lan.LanHub] keeps verified copies of the released APK and installer downloaded
 * on the first share. The installer adds a Windows Firewall rule that lets neighbours reach
 * Paramnezia.exe (desktop/installer).
 */
class DesktopLan(override val cacheDir: File) : LanPlatform {
    override val kind = "windows"
    private val mdns = Mdns(LanProtocol.SERVICE_TYPE)

    /** No network at all while rendering screenshots (Screenshots.kt). */
    private val offline get() = System.getProperty("paramnezia.screenshots") == "true"

    override fun advertise(name: String, port: Int): AutoCloseable =
        if (offline) AutoCloseable {} else mdns.advertise(name, port, mapOf("v" to LanProtocol.VERSION.toString()))

    override fun browse(onFound: (String, String, Int) -> Unit, onLost: (String) -> Unit): AutoCloseable =
        if (offline) AutoCloseable {} else mdns.browse(onFound, onLost)
}
