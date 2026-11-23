package net.paramnezia.app.ota

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import net.paramnezia.app.BuildConfig
import net.paramnezia.app.data.Repo
import net.paramnezia.app.platform.ReleaseSource
import net.paramnezia.app.tunnel.Net
import net.paramnezia.core.fleet.Ed25519
import net.paramnezia.core.ota.Release
import net.paramnezia.core.ota.ReleaseCodec
import net.paramnezia.core.ota.UpdateChecker
import net.paramnezia.deploy.FleetPublisher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.MessageDigest

/**
 * Update check, the way most apps outside Google Play do it: a small signed `release.json` is read
 * over HTTPS from one public address ([BuildConfig.UPDATE_URL]) and, for fleet devices, from their
 * server inside the tunnel. A release counts only if the release key pinned in this build signed it.
 *
 * The app never downloads or installs APKs itself. "Download" opens the APK link in the browser;
 * the user installs it with Android's own installer, exactly as with the first install. There is no
 * REQUEST_INSTALL_PACKAGES permission and no background download (docs/PLAY-PROTECT.md).
 */
class Updater(
    private val context: Context,
    private val repo: Repo,
    private val net: Net,
    private val ed: Ed25519,
    private val scope: CoroutineScope,
    private val inFleet: () -> Boolean,
) : ReleaseSource {
    sealed interface Status {
        data object Idle : Status
        data class Available(val release: Release) : Status
    }

    private val _status = MutableStateFlow<Status>(Status.Idle)
    val status: StateFlow<Status> = _status

    private val schedule = UpdateCheckSchedule(repo)

    val enabled: Boolean get() = BuildConfig.RELEASE_KEY.isNotEmpty()

    private val versionCode: Long by lazy { context.packageManager.getPackageInfo(context.packageName, 0).let {
        if (Build.VERSION.SDK_INT >= 28) it.longVersionCode else @Suppress("DEPRECATION") it.versionCode.toLong()
    } }

    /** Windows installer releases (any version counts), only to point people at them on the family page. */
    private val windowsChecker by lazy {
        UpdateChecker(WINDOWS_PACKAGE, 0, Int.MAX_VALUE, listOf(BuildConfig.RELEASE_KEY), ed)
    }

    override fun windows(): Release? = synchronized(windowsChecker) { windowsChecker.best?.first }

    private val checker by lazy {
        UpdateChecker(context.packageName, versionCode, Build.VERSION.SDK_INT, listOf(BuildConfig.RELEASE_KEY), ed, ownSigner())
    }

    private val restoreCache by lazy { repo.current.release?.let(::offer) }

    init {
        scope.launch(Dispatchers.IO) { restoreCache }
    }

    /**
     * The newest signed release this device knows that others may install: the one it runs, or a
     * newer one. Invite links and server copies point at its public URLs.
     */
    override fun shareable(): Pair<Release, String>? = synchronized(checker) {
        (checker.best ?: checker.installed)?.let { (r, s) -> r to ReleaseCodec.encode(s) }
    }

    private fun offer(text: String): Boolean = synchronized(checker) {
        val result = checker.inspect(text)
        val record = checker.best ?: checker.installed
        if (result != UpdateChecker.Result.REJECTED && record != null) {
            val (release, signed) = record
            repo.update { it.copy(release = ReleaseCodec.encode(signed)) }
            if (checker.best != null) _status.value = Status.Available(release)
        }
        result != UpdateChecker.Result.REJECTED
    }

    /** Reads release.json from the public address and, in a fleet, from the server. A few KB. */
    override suspend fun check() { checkNow() }

    suspend fun checkNow(): Boolean = enabled && schedule.check(force = true, fetch = ::fetch)

    private suspend fun fetch(): Boolean = withContext(Dispatchers.IO) {
        restoreCache
        val urls = listOfNotNull(BuildConfig.UPDATE_URL.takeIf { it.startsWith("https://") },
            FleetPublisher.TUNNEL_RELEASE_URL.takeIf { inFleet() })
        val windowsUrl = BuildConfig.UPDATE_URL.takeIf { it.startsWith("https://") && it.endsWith("/release.json") }
            ?.replace("/release.json", "/release-windows.json")
        val (texts, win) = coroutineScope {
            val w = windowsUrl?.let { u -> async { net.get(u, maxBytes = 64 * 1024) } }
            urls.map { u -> async { net.get(u, maxBytes = 64 * 1024) } }.awaitAll() to w?.await()
        }
        val verified = texts.filterNotNull().map(::offer).any { it }
        win?.let { if (enabled) synchronized(windowsChecker) { windowsChecker.offer(it) } }
        verified
    }

    suspend fun checkPeriodically() {
        if (enabled) schedule.run(::fetch)
    }

    /** The user's tap: opens the APK link in the browser. Returns false if there is nothing to open. */
    fun openDownload(): Boolean {
        val release = (status.value as? Status.Available)?.release ?: return false
        val url = release.publicApkUrls().firstOrNull()
            ?: (FleetPublisher.TUNNEL_APK_MIRROR + "/" + release.sha256).takeIf { inFleet() }
            ?: return false
        return runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.isSuccess
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

    private companion object {
        const val WINDOWS_PACKAGE = "net.paramnezia.windows"
    }
}
