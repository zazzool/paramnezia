package net.paramnezia.app.lan

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import net.paramnezia.app.BuildConfig
import net.paramnezia.app.platform.Log
import java.io.File
import java.net.Inet4Address
import java.util.concurrent.Executors

/**
 * Sharing in the local network on Android: the system's own mDNS (NsdManager, no permission needed
 * up to targetSdk 36; see docs/LAN-SHARE.md for Android 17) and the installed APK as the file to
 * hand out, so a TV next to the phone gets the app without the internet.
 */
class AndroidLan(private val context: Context) : LanPlatform {
    private val nsd = context.getSystemService(NsdManager::class.java)
    private val main = Handler(Looper.getMainLooper())

    override val kind: String = run {
        val ui = context.getSystemService(UiModeManager::class.java)
        if (ui?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION) "tv" else "android"
    }

    override val cacheDir: File get() = context.cacheDir

    /**
     * The APK this phone runs: the same signed file for everyone (Play Protect rule 1), read only
     * while the person keeps the share screen open, and only sent to neighbours that ask for it.
     */
    override fun ownApk(): LanFile? {
        val f = File(context.applicationInfo.sourceDir)
        if (!f.isFile) return null
        return LanFile.of(f, "paramnezia-${BuildConfig.VERSION_NAME}.apk", LanFile.APK_MIME, BuildConfig.VERSION_NAME)
    }

    override fun advertise(name: String, port: Int): AutoCloseable {
        val info = NsdServiceInfo().apply {
            serviceName = name.take(60)
            serviceType = LanProtocol.SERVICE_TYPE
            setPort(port)
            setAttribute("v", LanProtocol.VERSION.toString())
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) { Log.i(TAG, "announced as ${info.serviceName}") }
            override fun onRegistrationFailed(info: NsdServiceInfo, error: Int) { Log.w(TAG, "announce failed: $error") }
            override fun onServiceUnregistered(info: NsdServiceInfo) {}
            override fun onUnregistrationFailed(info: NsdServiceInfo, error: Int) {}
        }
        nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
        return AutoCloseable { runCatching { nsd.unregisterService(listener) } }
    }

    override fun browse(onFound: (String, String, Int) -> Unit, onLost: (String) -> Unit): AutoCloseable {
        // Older Androids resolve one service at a time: a queue keeps them in line.
        val resolver = Executors.newSingleThreadExecutor()
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String) {}
            override fun onDiscoveryStopped(type: String) {}
            override fun onStartDiscoveryFailed(type: String, error: Int) { Log.w(TAG, "discovery failed: $error") }
            override fun onStopDiscoveryFailed(type: String, error: Int) {}
            override fun onServiceLost(info: NsdServiceInfo) { main.post { onLost(info.serviceName) } }
            override fun onServiceFound(info: NsdServiceInfo) {
                resolver.execute { resolve(info)?.let { (host, port) -> onFound(info.serviceName, host, port) } }
            }
        }
        nsd.discoverServices(LanProtocol.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        return AutoCloseable {
            runCatching { nsd.stopServiceDiscovery(listener) }
            resolver.shutdownNow()
        }
    }

    /** Resolves one service to an IPv4 address and port, waiting up to 8 s. */
    private fun resolve(info: NsdServiceInfo): Pair<String, Int>? {
        val done = java.util.concurrent.CountDownLatch(1)
        var result: Pair<String, Int>? = null
        resolveCompat(info, object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, error: Int) { done.countDown() }
            override fun onServiceResolved(info: NsdServiceInfo) {
                hostOf(info)?.hostAddress?.let { result = it to info.port }
                done.countDown()
            }
        })
        done.await(8, java.util.concurrent.TimeUnit.SECONDS)
        return result
    }

    @Suppress("DEPRECATION")
    private fun resolveCompat(info: NsdServiceInfo, listener: NsdManager.ResolveListener) = nsd.resolveService(info, listener)

    @Suppress("DEPRECATION")
    private fun hostOf(info: NsdServiceInfo): java.net.InetAddress? =
        if (Build.VERSION.SDK_INT >= 34) info.hostAddresses.firstOrNull { it is Inet4Address } ?: info.hostAddresses.firstOrNull()
        else info.host

    private companion object {
        const val TAG = "LanShare"
    }
}
