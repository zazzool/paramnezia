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

    val enabled: Boolean get() = BuildConfig.RELEASE_KEY.isNotEmpty()

    private val versionCode: Long by lazy { context.packageManager.getPackageInfo(context.packageName, 0).let {
        if (Build.VERSION.SDK_INT >= 28) it.longVersionCode else @Suppress("DEPRECATION") it.versionCode.toLong()
    } }

    private val checker by lazy {
        UpdateChecker(context.packageName, versionCode, Build.VERSION.SDK_INT, listOf(BuildConfig.RELEASE_KEY), ed, ownSigner())
    }

    init {
        scope.launch(Dispatchers.IO) { repo.current.release?.let { offer(it) } }
    }

    /**
     * The newest signed release this device knows that others may install: the one it runs, or a
     * newer one. Invite links and server copies point at its public URLs.
     */
    override fun shareable(): Pair<Release, String>? = synchronized(checker) {
        (checker.best ?: checker.installed)?.let { (r, s) -> r to ReleaseCodec.encode(s) }
    }

    private fun offer(text: String): Boolean {
        if (!enabled) return false
        val accepted = synchronized(checker) {
            val before = checker.installed
            checker.offer(text).also { if (!it && checker.installed !== before && checker.best == null) remember() }
        }
        if (!accepted) return false
        val (release, signed) = checker.best!!
        repo.update { it.copy(release = ReleaseCodec.encode(signed)) }
        _status.value = Status.Available(release)
        return true
    }

    private fun remember() {
        checker.installed?.let { (_, s) -> repo.update { it.copy(release = ReleaseCodec.encode(s)) } }
    }

    /** Reads release.json from the public address and, in a fleet, from the server. A few KB. */
    override suspend fun check(): Unit = withContext(Dispatchers.IO) {
        if (!enabled) return@withContext
        val urls = listOfNotNull(BuildConfig.UPDATE_URL.takeIf { it.startsWith("https://") },
            FleetPublisher.TUNNEL_RELEASE_URL.takeIf { inFleet() })
        val texts = coroutineScope { urls.map { u -> async { net.get(u) } }.awaitAll() }
        texts.filterNotNull().forEach { offer(it) }
        repo.update { it.copy(lastUpdateCheck = System.currentTimeMillis()) }
    }

    /** At most every 12 hours, from the app's own start; costs one small request. */
    suspend fun checkIfDue() {
        if (System.currentTimeMillis() - repo.current.lastUpdateCheck >= 12 * 3600_000L) runCatching { check() }
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
}
