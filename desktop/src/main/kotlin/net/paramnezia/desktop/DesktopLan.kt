package net.paramnezia.desktop

import net.paramnezia.app.lan.LanNet
import net.paramnezia.app.lan.LanPlatform
import net.paramnezia.app.lan.LanProtocol
import net.paramnezia.app.platform.Log
import java.io.File
import java.net.Inet4Address
import javax.jmdns.JmDNS
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceInfo
import javax.jmdns.ServiceListener

/**
 * Sharing in the local network on Windows: mDNS through JmDNS on each Wi-Fi/Ethernet address. The
 * computer has no app file of its own to hand out, so [net.paramnezia.app.lan.LanHub] keeps
 * verified copies of the released APK and installer downloaded on the first share. The installer
 * adds a Windows Firewall rule that lets neighbours reach Paramnezia.exe (desktop/installer).
 */
class DesktopLan(override val cacheDir: File) : LanPlatform {
    override val kind = "windows"
    private val type = LanProtocol.SERVICE_TYPE + ".local."

    /** No network at all while rendering screenshots (Screenshots.kt). */
    private val offline get() = System.getProperty("paramnezia.screenshots") == "true"

    private fun open(): List<JmDNS> = if (offline) emptyList() else LanNet.interfaces().mapNotNull { lan ->
        runCatching { JmDNS.create(lan.address, "paramnezia-" + lan.host.replace('.', '-')) }
            .onFailure { Log.w(TAG, "mDNS on ${lan.host}", it) }.getOrNull()
    }

    override fun advertise(name: String, port: Int): AutoCloseable {
        val all = open()
        for (j in all) runCatching { j.registerService(ServiceInfo.create(type, name.take(60), port, "v=${LanProtocol.VERSION}")) }
            .onFailure { Log.w(TAG, "announce", it) }
        return AutoCloseable { closeAll(all) }
    }

    override fun browse(onFound: (String, String, Int) -> Unit, onLost: (String) -> Unit): AutoCloseable {
        val all = open()
        val listener = object : ServiceListener {
            override fun serviceAdded(e: ServiceEvent) { e.dns.requestServiceInfo(e.type, e.name, 3_000) }
            override fun serviceRemoved(e: ServiceEvent) = onLost(e.name)
            override fun serviceResolved(e: ServiceEvent) {
                val host = e.info.inet4Addresses.firstOrNull { it is Inet4Address } ?: return
                onFound(e.name, host.hostAddress, e.info.port)
            }
        }
        for (j in all) j.addServiceListener(type, listener)
        return AutoCloseable {
            for (j in all) runCatching { j.removeServiceListener(type, listener) }
            closeAll(all)
        }
    }

    /** JmDNS takes a moment to say goodbye on the network: off the caller's thread. */
    private fun closeAll(all: List<JmDNS>) {
        Thread({ for (j in all) runCatching { j.unregisterAllServices(); j.close() } }, "mdns-close").apply { isDaemon = true }.start()
    }

    private companion object {
        const val TAG = "LanShare"
    }
}
