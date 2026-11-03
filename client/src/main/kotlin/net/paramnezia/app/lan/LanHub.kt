package net.paramnezia.app.lan

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.paramnezia.app.data.Repo
import net.paramnezia.app.fleet.FleetManager
import net.paramnezia.app.fleet.Links
import net.paramnezia.app.platform.Log
import net.paramnezia.app.platform.ReleaseSource
import net.paramnezia.core.ota.Release
import net.paramnezia.core.ota.verifyingCopy
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Sharing in the local network, both ways, for the screens:
 * - [start]/[stop]: this device shares the app (and, for an admin, settings) while the screen is open;
 * - [watch]/[nearby]/[receive]: this device finds sharers next to it and asks one for settings.
 */
class LanHub(
    private val platform: LanPlatform,
    private val repo: Repo,
    private val fleet: FleetManager,
    private val links: () -> Links,
    /** Android release (and the Windows one) to fetch when this device has no copy of its own. */
    private val releases: () -> ReleaseSource,
    private val deviceModel: String,
    private val scope: CoroutineScope,
) {
    /** What the share screen shows. */
    data class Sharing(
        /** Addresses to type, the best first ("192.168.1.5:8080"). Empty: no Wi-Fi or Ethernet. */
        val addresses: List<String>,
        val port: Int,
        val name: String,
        val apk: LanFile?,
        val exe: LanFile?,
        val settings: Boolean,
        val asks: List<LanServer.Ask> = emptyList(),
        val served: Map<String, Int> = emptyMap(),
        /** Devices that got settings during this share. */
        val given: List<String> = emptyList(),
        val error: String? = null,
        /** Files the person can download now to share (missing, or a newer release is known). */
        val downloads: List<Download> = emptyList(),
    )

    /** A file to fetch on the person's tap: "apk" or "exe", and its version when known. */
    data class Download(val what: String, val version: String?)

    private val _sharing = MutableStateFlow<Sharing?>(null)
    val sharing: StateFlow<Sharing?> = _sharing
    private val _preparing = MutableStateFlow<String?>(null)
    /** What is being downloaded on the person's tap ("Установщик для Windows 2.11.0: 40 %"), or null. */
    val preparing: StateFlow<String?> = _preparing

    private var server: LanServer? = null
    private var advert: AutoCloseable? = null
    private var shareJobs: List<Job> = emptyList()
    @Volatile private var apk: LanFile? = null
    @Volatile private var exe: LanFile? = null

    /** The name neighbours see: the family device name, else the model. */
    val shareName: String get() = repo.current.fleet?.deviceName?.takeIf { it.isNotBlank() } ?: deviceModel

    /** This device can hand out settings: it manages the family's servers, or a server of its own. */
    fun canGiveSettings(): Boolean {
        val s = repo.current
        val f = s.fleet
        if (f != null) return f.adminSeed != null && fleet.managedServer() != null
        return s.profiles.any { it.ssh != null }
    }

    /**
     * Opens the share with what this device already has: its own APK (Android) and copies downloaded
     * earlier. Nothing is downloaded here; newer or missing files are offered in [Sharing.downloads]
     * and fetched only on the person's tap ([fetch]).
     */
    suspend fun start() = withContext(Dispatchers.IO) {
        if (server != null) return@withContext
        apk = cached(releases().shareable()?.first, "apk") ?: platform.ownApk() ?: newestCached("apk")
        exe = cached(releases().windows(), "exe") ?: newestCached("exe")
        val give = canGiveSettings()
        val name = shareName
        val s = LanServer(
            info = { LanInfo(name = name, version = (apk?.version ?: exe?.version).orEmpty(), settings = give, apk = apk != null, exe = exe != null) },
            apk = { apk }, exe = { exe },
        )
        try { s.start() } catch (e: Exception) {
            _sharing.value = Sharing(emptyList(), 0, name, apk, exe, give, error = e.message)
            return@withContext
        }
        server = s
        advert = runCatching { platform.advertise(name, s.port) }.onFailure { Log.w(TAG, "advertise", it) }.getOrNull()
        _sharing.value = Sharing(addresses(s.port), s.port, name, apk, exe, give, downloads = downloads())
        shareJobs = listOf(
            scope.launch { s.asks.collect { a -> _sharing.update { it?.copy(asks = a) } } },
            scope.launch { s.served.collect { m -> _sharing.update { it?.copy(served = m) } } },
            // Wi-Fi changes (another network, hotspot on): keep the addresses true.
            scope.launch(Dispatchers.IO) {
                val started = System.currentTimeMillis()
                while (true) {
                    delay(5_000)
                    if (System.currentTimeMillis() - started > LanProtocol.SHARE_TTL_MS) { stop(); break }
                    val now = addresses(s.port)
                    _sharing.update { if (it != null && it.addresses != now) it.copy(addresses = now) else it }
                }
            },
        )
    }

    fun stop() {
        shareJobs.forEach { it.cancel() }
        shareJobs = emptyList()
        runCatching { advert?.close() }
        advert = null
        server?.stop()
        server = null
        _sharing.value = null
    }

    /** The owner allows [ask]: a new key for that device (a family member, or an account on the server). */
    suspend fun approve(ask: LanServer.Ask) = withContext(Dispatchers.IO) {
        val s = server ?: return@withContext
        try {
            val key = newKey(ask.device)
            s.approve(ask.id, key)
            _sharing.update { it?.copy(given = it.given + ask.device) }
        } catch (e: Exception) {
            Log.w(TAG, "settings for ${ask.device}", e)
            s.deny(ask.id, "Не удалось выдать настройки: ${e.message}")
            throw e
        }
    }

    fun deny(ask: LanServer.Ask) { server?.deny(ask.id) }

    /** What can be downloaded to share: a missing file, or a newer release than the one shared. */
    private fun downloads(): List<Download> {
        val ra = releases().shareable()?.first
        val rw = releases().windows()
        return listOfNotNull(
            when {
                apk == null -> Download("apk", ra?.versionName)
                ra != null && ra.versionName != apk?.version -> Download("apk", ra.versionName)
                else -> null
            },
            when {
                exe == null -> Download("exe", rw?.versionName)
                rw != null && rw.versionName != exe?.version -> Download("exe", rw.versionName)
                else -> null
            },
        )
    }

    /**
     * The person's tap on «Скачать»: reads the newest signed release, downloads its file ("apk" or
     * "exe") from GitHub Releases, checks it and shares it from now on (and keeps it for next time).
     * For the case when the TV or PC cannot reach GitHub but this device can.
     */
    suspend fun fetch(what: String) = withContext(Dispatchers.IO) {
        try {
            _preparing.value = "Проверяю последнюю версию…"
            runCatching { releases().check() }
            val r = (if (what == "apk") releases().shareable()?.first else releases().windows())
                ?: error("Не удалось узнать последнюю версию: нужен интернет на этом устройстве")
            val current = if (what == "apk") apk else exe
            if (current?.version != r.versionName) {
                val label = (if (what == "apk") "Paramnezia для Android " else "Установщик для Windows ") + r.versionName
                val file = cached(r, what) ?: download(r, what, label) ?: error("Не удалось скачать: проверьте интернет и попробуйте ещё раз")
                if (what == "apk") apk = file else exe = file
            }
            _sharing.update { it?.copy(apk = apk, exe = exe, downloads = downloads()) }
        } finally {
            _preparing.value = null
        }
    }

    private suspend fun newKey(device: String): String {
        val l = links()
        val s = repo.current
        if (s.fleet != null) return l.forFleetDevice(device, Links.Delivery.FILE).key
        val profile = s.profiles.firstOrNull { it.id == s.activeProfile && it.ssh != null } ?: s.profiles.firstOrNull { it.ssh != null }
            ?: error("Нет сервера с доступом по SSH")
        return l.forProfile(profile, device, Links.Delivery.FILE).key
    }

    private fun addresses(port: Int) = LanNet.interfaces().map { LanNet.shortAddress(it.host, port) }

    // --- files ------------------------------------------------------------------------------------

    private fun fileName(r: Release, what: String) =
        if (what == "apk") "paramnezia-${r.versionName}.apk" else "Paramnezia-Setup-${r.versionName}.exe"

    private fun cacheFile(r: Release, what: String) = File(File(platform.cacheDir, "lan-share"), "${r.sha256}.$what")

    private fun asLanFile(r: Release, what: String, f: File) =
        LanFile.of(f, fileName(r, what), if (what == "apk") LanFile.APK_MIME else LanFile.EXE_MIME, r.versionName)

    /** The copy downloaded last, whatever its release (it was checked when it was downloaded). */
    private fun newestCached(what: String): LanFile? {
        val dir = File(platform.cacheDir, "lan-share")
        val version = File(dir, "$what.version").takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val f = dir.listFiles()?.firstOrNull { it.isFile && it.name.endsWith(".$what") } ?: return null
        val name = if (what == "apk") "paramnezia-$version.apk" else "Paramnezia-Setup-$version.exe"
        return LanFile.of(f, name, if (what == "apk") LanFile.APK_MIME else LanFile.EXE_MIME, version)
    }

    /** A copy downloaded earlier, if it is still the newest release. */
    private fun cached(r: Release?, what: String): LanFile? {
        r ?: return null
        val f = cacheFile(r, what)
        return if (f.isFile && f.length() == r.size) asLanFile(r, what, f) else null
    }

    /**
     * Downloads the signed release's file from its public HTTPS address, checks size and SHA-256
     * against the signed release, and keeps it for later shares (older copies are removed). Only on
     * the person's tap; the file is never opened or run here, only handed to neighbours.
     */
    private fun download(r: Release?, what: String, label: String): LanFile? {
        r ?: return null
        val urls = r.publicApkUrls().ifEmpty { return null }
        _preparing.value = label
        val dir = File(platform.cacheDir, "lan-share").apply { mkdirs() }
        val target = cacheFile(r, what)
        val part = File(dir, "${r.sha256}.$what.part")
        var lastShown = -1
        val progress: (Long) -> Unit = { done ->
            val pct = (done * 100 / r.size.coerceAtLeast(1)).toInt()
            if (pct != lastShown) { lastShown = pct; _preparing.value = "$label: $pct %" }
        }
        for (u in urls) {
            var done = 0L
            val ok = runCatching {
                val c = URL(u).openConnection() as HttpURLConnection
                c.connectTimeout = 15_000; c.readTimeout = 30_000; c.instanceFollowRedirects = true
                c.setRequestProperty("User-Agent", "paramnezia")
                try {
                    part.outputStream().use { out -> c.inputStream.use { input -> verifyingCopy(input, { b, n -> out.write(b, 0, n); done += n; progress(done) }, r) } }
                } finally { c.disconnect() }
            }.onFailure { Log.w(TAG, "download $u", it) }.getOrDefault(false)
            if (ok && part.renameTo(target)) {
                dir.listFiles()?.filter { it.name.endsWith(".$what") && it != target }?.forEach { it.delete() }
                // The version, for sharing it later while no release info is at hand (offline, after a restart).
                File(dir, "$what.version").writeText(r.versionName)
                return asLanFile(r, what, target)
            }
            part.delete()
        }
        return null
    }

    // --- receiving --------------------------------------------------------------------------------

    private val _nearby = MutableStateFlow<List<LanPeer>>(emptyList())
    /** Sharers found next to this device (with what they share), while someone [watch]es. */
    val nearby: StateFlow<List<LanPeer>> = _nearby
    private var watchers = 0
    private var browsing: Job? = null
    private val lock = Any()

    /** Starts looking for sharers; close the result when the screen goes. Several screens may watch at once. */
    fun watch(): AutoCloseable {
        synchronized(lock) {
            if (watchers++ == 0) {
                // JmDNS needs a moment to open its sockets: never on the UI thread.
                browsing = scope.launch(Dispatchers.IO) {
                    val handle = runCatching {
                        platform.browse(
                            onFound = { name, host, port -> found(name, host, port) },
                            onLost = { name -> _nearby.update { list -> list.filterNot { it.name == name } } },
                        )
                    }.onFailure { Log.w(TAG, "browse", it) }.getOrNull() ?: return@launch
                    try { kotlinx.coroutines.awaitCancellation() } finally { runCatching { handle.close() } }
                }
            }
        }
        var closed = false
        return AutoCloseable {
            synchronized(lock) {
                if (closed) return@AutoCloseable
                closed = true
                if (--watchers == 0) {
                    browsing?.cancel()
                    browsing = null
                    _nearby.value = emptyList()
                }
            }
        }
    }

    private fun found(name: String, host: String, port: Int) {
        // Our own share is not "nearby".
        if (server != null && port == server?.port && LanNet.interfaces().any { it.host == host }) return
        scope.launch {
            val info = LanClient.info(host, port) ?: return@launch
            _nearby.update { list -> (list.filterNot { it.name == name || (it.host == host && it.port == port) } + LanPeer(name, host, port, info)) }
        }
    }

    /** A sharer typed in by hand ("192.168.1.5:8080"); null if nothing answers there. */
    suspend fun peerAt(address: String): LanPeer? {
        val (host, port) = LanNet.parseAddress(address) ?: return null
        val info = LanClient.info(host, port) ?: if (port == 80) LanClient.info(host, 8080)?.let { return LanPeer(it.name, host, 8080, it) } else null
        return info?.let { LanPeer(it.name, host, port, it) }
    }

    /** Asks [peer] for settings for this device; returns the key once its owner allows it. */
    suspend fun receive(peer: LanPeer): String = LanClient.askSettings(peer, deviceModel, platform.kind)

    /** Sample state for the screenshots (desktop `--screenshots`, Android DemoActivity); never used by the app itself. */
    fun preview(sharing: Sharing?, nearby: List<LanPeer> = emptyList()) {
        _sharing.value = sharing
        _nearby.value = nearby
    }

    private companion object {
        const val TAG = "LanShare"
    }
}
