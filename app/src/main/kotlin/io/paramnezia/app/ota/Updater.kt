package io.paramnezia.app.ota

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import io.paramnezia.app.App
import io.paramnezia.app.BuildConfig
import io.paramnezia.app.data.Repo
import io.paramnezia.app.fleet.FleetManager
import io.paramnezia.app.tunnel.Net
import io.paramnezia.core.crypto.WgKey
import io.paramnezia.core.fleet.Ed25519
import io.paramnezia.core.fleet.GossipMessage
import io.paramnezia.core.nostr.NostrClient
import io.paramnezia.core.nostr.NostrFleet
import io.paramnezia.core.nostr.NostrRelease
import io.paramnezia.core.ota.Release
import io.paramnezia.core.ota.ReleaseCodec
import io.paramnezia.core.ota.UpdateChecker
import io.paramnezia.core.ota.verifyingCopy
import io.paramnezia.deploy.FleetPublisher
import io.paramnezia.tunnel.TunnelRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * Decentralized self-update. A release is trusted only if the release key pinned in this build
 * signed it; where it came from does not matter. Channels, all tried:
 *  - Nostr: the publisher's slot and every fleet device's release slot (devices re-publish);
 *  - gossip: devices pass verified releases to each other through the tunnel;
 *  - the fleet's servers: `release.json` and the APK inside the tunnel, reachable even when every
 *    public host is blocked;
 *  - Blossom servers and GitHub Releases for the APK bytes.
 * The APK must match the signed SHA-256 and size before it reaches the installer, and Android
 * itself refuses an APK not signed with our certificate.
 *
 * Downloading may happen in the background; installing never does. The user taps "Install" and
 * confirms in Android's own dialog. An app outside Google Play that silently installs APKs it found
 * on the network looks exactly like a malware dropper to Play Protect, so we do not do that, and the
 * app holds no UPDATE_PACKAGES_WITHOUT_USER_ACTION permission.
 */
class Updater(
    private val context: Context,
    private val repo: Repo,
    private val net: Net,
    private val fleet: FleetManager,
    private val ed: Ed25519,
    private val scope: CoroutineScope,
) {
    sealed interface Status {
        data object Idle : Status
        data class Available(val release: Release) : Status
        data class Downloading(val release: Release) : Status
        /** Downloaded and verified; waits for the user's tap. */
        data class Ready(val release: Release) : Status
        /** Android's confirmation dialog is (or was) on screen. */
        data class Installing(val release: Release) : Status
        data class Failed(val reason: String) : Status
    }

    private val _status = MutableStateFlow<Status>(Status.Idle)
    val status: StateFlow<Status> = _status

    val enabled: Boolean get() = BuildConfig.RELEASE_KEY.isNotEmpty()
    private val lock = Mutex()
    private val publisher = FleetPublisher(repo.hostKeys)

    private val versionCode: Long by lazy { context.packageManager.getPackageInfo(context.packageName, 0).let {
        if (Build.VERSION.SDK_INT >= 28) it.longVersionCode else @Suppress("DEPRECATION") it.versionCode.toLong()
    } }

    private val checker by lazy {
        UpdateChecker(context.packageName, versionCode, Build.VERSION.SDK_INT, listOf(BuildConfig.RELEASE_KEY), ed, ownSigner())
    }

    init {
        // A release learned earlier (from any channel) is offered again after a restart; after an
        // update it is the installed one, kept to hand the same APK to new devices.
        scope.launch(Dispatchers.IO) {
            repo.current.release?.let { offer(it, spread = false) }
            cleanCache()
        }
    }

    /**
     * The newest signed release this device knows that others may install: the one it runs, or a
     * newer one it has seen. Invite links and server mirrors point at its public URLs.
     */
    fun shareable(): Pair<Release, String>? = synchronized(checker) {
        (checker.best ?: checker.installed)?.let { (r, s) -> r to ReleaseCodec.encode(s) }
    }

    /** Offers a release from any channel; spreads it onward when it is new. */
    fun offer(text: String, spread: Boolean = true): Boolean {
        if (!enabled) return false
        val accepted = synchronized(checker) {
            val wasInstalled = checker.installed
            checker.offer(text).also { if (!it && checker.installed !== wasInstalled) rememberInstalled() }
        }
        if (!accepted) return false
        val (release, signed) = checker.best!!
        val wire = ReleaseCodec.encode(signed)
        repo.update { it.copy(release = wire) }
        _status.value = Status.Available(release)
        if (spread) {
            TunnelRuntime.gossip(GossipMessage.encode(GossipMessage.Release(wire)), fleet.peers())
            scope.launch(Dispatchers.IO) { republish(wire, release.versionCode) }
        }
        if (repo.current.settings.autoUpdate) scope.launch { prefetch() }
        return true
    }

    /** Keeps the installed version's signed release (repo.release) when nothing newer is known. */
    private fun rememberInstalled() {
        val installed = checker.installed ?: return
        if (checker.best == null) repo.update { it.copy(release = ReleaseCodec.encode(installed.second)) }
    }

    /** Looks for a newer release on every channel. Cheap: a few small requests, at most twice a day. */
    suspend fun check() = withContext(Dispatchers.IO) {
        if (!enabled) return@withContext
        val client = NostrClient(net.directSockets())
        val relays = repo.current.settings.relays.ifEmpty { NostrFleet.DEFAULT_RELAYS }
        val nf = fleet.stored?.let { NostrFleet(it.trust) }
        val fleetAuthors = fleet.manifest()?.let { nf?.authors(it) }.orEmpty()
        val texts = coroutineScope {
            val fromRelays = relays.map { relay ->
                async {
                    runCatching {
                        val official = BuildConfig.RELEASE_NOSTR.takeIf { it.isNotEmpty() }?.let { pub ->
                            NostrRelease.candidates(client.fetch(relay, listOf(pub), NostrRelease.TAG, 5), setOf(pub))
                        }.orEmpty()
                        val peers = if (nf != null && fleetAuthors.isNotEmpty()) {
                            NostrRelease.candidates(client.fetch(relay, fleetAuthors, nf.releaseTag, 20), fleetAuthors.toSet(), nf.releaseTag)
                        } else emptyList()
                        official + peers
                    }.getOrDefault(emptyList())
                }
            }
            val fromServer = async { listOfNotNull(if (fleet.stored != null) net.get(publisher.tunnelReleaseUrl) else null) }
            (fromRelays + fromServer).awaitAll().flatten()
        }
        texts.distinct().forEach { offer(it) }
    }

    /** Downloads and verifies the best known release in the background; installing waits for the user. */
    suspend fun prefetch() = lock.withLock {
        val release = checker.best?.first ?: return@withLock
        val apk = withContext(Dispatchers.IO) { download(release) }
        _status.value = if (apk != null) Status.Ready(release) else Status.Available(release)
    }

    /**
     * Called from the user's tap: downloads if needed, verifies, then hands the APK to Android's
     * installer, which asks the user to confirm.
     */
    suspend fun install() = lock.withLock {
        val best = checker.best ?: return@withLock
        val release = best.first
        val apk = withContext(Dispatchers.IO) { download(release) }
        if (apk == null) { _status.value = Status.Failed("Не удалось скачать обновление"); return@withLock }
        _status.value = Status.Installing(release)
        withContext(Dispatchers.IO) {
            runCatching { commit(apk) }.onFailure { _status.value = Status.Failed(it.message ?: "Установка не удалась") }
        }
    }

    private fun download(release: Release): File? {
        _status.value = Status.Downloading(release)
        val target = net.cacheFile("update-${release.sha256}.apk")
        if (target.exists() && target.length() == release.size && sha256(target) == release.sha256) return target
        val urls = release.downloadUrls(listOf(publisher.tunnelApkMirror))
        for (url in urls) for (network in listOf(null, net.direct())) {
            val tmp = File(target.path + ".part")
            val ok = net.download(url, network) { input ->
                tmp.outputStream().use { out -> verifyingCopy(input, { b, n -> out.write(b, 0, n) }, release) }
            } == true
            if (ok && tmp.renameTo(target)) return target
            tmp.delete()
        }
        return null
    }

    private fun commit(apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length())
            // The user always confirms in Android's dialog (see the class comment).
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("base.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val intent = Intent(context, InstallReceiver::class.java)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            session.commit(PendingIntent.getBroadcast(context, id, intent, flags).intentSender)
        }
    }

    /** Spreads a verified release: the fleet's release slot on Nostr and, on the admin, the servers. */
    private suspend fun republish(wire: String, versionCode: Long) {
        val f = fleet.stored ?: return
        val nf = NostrFleet(f.trust)
        val me = if (f.adminSeed != null) NostrFleet.ADMIN else WgKey.fromBase64(f.deviceKey).publicKey().toBase64()
        val event = NostrRelease.event(wire, nf.secretFor(me), versionCode, nf.releaseTag)
        val client = NostrClient(net.directSockets())
        for (r in repo.current.settings.relays.ifEmpty { NostrFleet.DEFAULT_RELAYS }) runCatching { client.publish(r, event) }
    }

    /** Drops downloads of releases that are installed or superseded. */
    private fun cleanCache() {
        val keep = checker.best?.first?.sha256
        context.cacheDir.listFiles { f -> f.name.startsWith("update-") }?.forEach { f ->
            if (keep == null || keep !in f.name) f.delete()
        }
    }

    fun onInstallResult(status: Int, message: String?) {
        when (status) {
            PackageInstaller.STATUS_SUCCESS, PackageInstaller.STATUS_PENDING_USER_ACTION -> {}
            // "Cancel" in Android's dialog: offer the same update again.
            PackageInstaller.STATUS_FAILURE_ABORTED -> checker.best?.first?.let { _status.value = Status.Ready(it) }
            else -> _status.value = Status.Failed(message ?: "Установка не удалась ($status)")
        }
    }

    @Suppress("DEPRECATION")
    private fun ownSigner(): String = runCatching {
        val pm = context.packageManager
        val sig = if (Build.VERSION.SDK_INT >= 28) {
            pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo?.apkContentsSigners?.firstOrNull()
        } else {
            pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES).signatures?.firstOrNull()
        } ?: return@runCatching ""
        MessageDigest.getInstance("SHA-256").digest(sig.toByteArray()).joinToString("") { "%02x".format(it) }
    }.getOrDefault("")

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}

/** Installer callback: brings up Android's confirmation dialog (the user has just tapped "Install"). */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            @Suppress("DEPRECATION")
            val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
            confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let { runCatching { context.startActivity(it) } }
        }
        (context.applicationContext as App).updater.onInstallResult(status, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
    }
}

/** Runs after we were updated: the tunnel comes back; the admin refreshes its servers. */
class UpdatedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as App
        val pending = goAsync()
        app.scope.launch {
            try { app.afterUpdate() } finally { pending.finish() }
        }
    }
}
