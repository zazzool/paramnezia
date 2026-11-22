package net.paramnezia.app.ota

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import net.paramnezia.app.BuildConfig
import net.paramnezia.app.data.Repo
import net.paramnezia.app.platform.ReleaseSource
import net.paramnezia.app.platform.UpdateInstaller
import net.paramnezia.app.ui.ShareProvider
import net.paramnezia.app.tunnel.Net
import net.paramnezia.core.fleet.Ed25519
import net.paramnezia.core.ota.Release
import net.paramnezia.core.ota.ReleaseCodec
import net.paramnezia.core.ota.UpdateChecker
import net.paramnezia.core.ota.UpdateCheckResult
import net.paramnezia.deploy.FleetPublisher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
 * Downloads start on a user action and verify the APK against the signed release. Android's
 * package installer handles installation and user confirmation.
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

    private val versionCode: Long by lazy { versionCodeOf(context.packageManager.getPackageInfo(context.packageName, 0)) }

    @Suppress("DEPRECATION")
    private fun versionCodeOf(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()

    /** Windows installer releases (any version counts), only to point people at them on the family page. */
    private val windowsChecker by lazy {
        UpdateChecker(WINDOWS_PACKAGE, 0, Int.MAX_VALUE, listOf(BuildConfig.RELEASE_KEY), ed)
    }

    override fun windows(): Release? = synchronized(windowsChecker) { windowsChecker.best?.first }

    private val checker by lazy {
        UpdateChecker(context.packageName, versionCode, Build.VERSION.SDK_INT, listOf(BuildConfig.RELEASE_KEY), ed, ownSigner())
    }

    val installer = UpdateInstaller(ShareProvider.updateDir(context), "apk", scope)

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

    /** Returns whether this channel confirmed the installed version or supplied a newer release. */
    private fun offer(text: String): Boolean {
        if (!enabled) return false
        return synchronized(checker) {
            val before = checker.installed
            val accepted = checker.offer(text)
            val confirmedInstalled = checker.installed !== before
            if (accepted) {
                val (release, signed) = checkNotNull(checker.best)
                repo.update { it.copy(release = ReleaseCodec.encode(signed)) }
                _status.value = Status.Available(release)
                installer.restore(release)
            } else if (confirmedInstalled && checker.best == null) {
                val (_, signed) = checkNotNull(checker.installed)
                repo.update { it.copy(release = ReleaseCodec.encode(signed)) }
            }
            accepted || confirmedInstalled || checker.best?.second?.let { it == ReleaseCodec.decode(text) } == true
        }
    }

    private fun releaseUrls() = listOfNotNull(
        BuildConfig.UPDATE_URL.takeIf { it.startsWith("https://") },
        FleetPublisher.TUNNEL_RELEASE_URL.takeIf { inFleet() },
    )

    /** A user-initiated check only needs this device's release. */
    suspend fun checkForUpdate(): UpdateCheckResult = withContext(Dispatchers.IO) {
        if (!enabled) return@withContext UpdateCheckResult.UNAVAILABLE
        val replies = coroutineScope {
            releaseUrls().map { url ->
                async {
                    if (url == FleetPublisher.TUNNEL_RELEASE_URL) net.getThroughTunnel(url)
                    else net.get(url, fresh = true, timeoutMs = 8_000)
                }
            }.awaitAll()
        }
        val confirmed = replies.filterNotNull().map(::offer).any { it }
        when {
            status.value is Status.Available -> UpdateCheckResult.AVAILABLE
            confirmed -> UpdateCheckResult.UP_TO_DATE
            else -> UpdateCheckResult.FAILED
        }
    }

    /** Reads release.json from the public address and, in a fleet, from the server. A few KB. */
    override suspend fun check(): Unit = withContext(Dispatchers.IO) {
        if (!enabled) return@withContext
        val windowsUrl = BuildConfig.UPDATE_URL.takeIf { it.startsWith("https://") && it.endsWith("/release.json") }
            ?.replace("/release.json", "/release-windows.json")
        val (texts, win) = coroutineScope {
            val w = windowsUrl?.let { u -> async { net.get(u) } }
            releaseUrls().map { u -> async { net.get(u) } }.awaitAll() to w?.await()
        }
        val confirmed = texts.filterNotNull().map(::offer).any { it }
        win?.let { synchronized(windowsChecker) { windowsChecker.offer(it) } }
        if (confirmed) repo.update { it.copy(lastUpdateCheck = System.currentTimeMillis()) }
    }

    /** At most every 12 hours, from the app's own start; costs one small request. */
    suspend fun checkIfDue() {
        if (System.currentTimeMillis() - repo.current.lastUpdateCheck >= 12 * 3600_000L) runCatching { check() }
    }


    fun downloadUpdate() {
        val release = (status.value as? Status.Available)?.release ?: return
        installer.download(release) { read ->
            release.publicApkUrls().distinct().any { url ->
                currentCoroutineContext().ensureActive()
                net.download(url, null, read) == true || net.direct()?.let {
                    currentCoroutineContext().ensureActive()
                    net.download(url, it, read)
                } == true
            } || (inFleet() && net.downloadThroughTunnel("${FleetPublisher.TUNNEL_APK_MIRROR}/${release.sha256}", read))
        }
    }

    suspend fun installUpdate(): String? {
        val release = (status.value as? Status.Available)?.release ?: return "Обновление недоступно"
        return installer.install(release) { file ->
            val archive = runCatching { context.packageManager.getPackageArchiveInfo(file.absolutePath, signingFlags()) }.getOrNull()
            val expectedSigner = ownSigner()
            if (archive == null || archive.packageName != context.packageName || versionCodeOf(archive) != release.versionCode ||
                expectedSigner.isEmpty() || signerOf(archive) != expectedSigner
            ) return@install "APK не совпадает с установленной Paramnezia"
            val allowed = context.packageManager.canRequestPackageInstalls()
            val intent = if (allowed) {
                val uri = ShareProvider.updateUri(context, file)
                Intent(Intent.ACTION_VIEW).setDataAndType(uri, ShareProvider.APK_TYPE)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    .apply { clipData = ClipData.newRawUri("Paramnezia", uri) }
            } else {
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.fromParts("package", context.packageName, null))
            }
            runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.fold(
                onSuccess = { if (allowed) null else "Разрешите установку для Paramnezia, вернитесь и нажмите «Установить»" },
                onFailure = { "Не удалось открыть установку Android" },
            )
        }
    }

    @Suppress("DEPRECATION")
    private fun signingFlags(): Int =
        if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES

    @Suppress("DEPRECATION")
    private fun signerOf(info: PackageInfo): String {
        val sig = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners?.singleOrNull()
        else info.signatures?.singleOrNull()
        return sig?.let {
            MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).joinToString("") { byte -> "%02x".format(byte) }
        }.orEmpty()
    }

    private fun ownSigner(): String = runCatching {
        signerOf(context.packageManager.getPackageInfo(context.packageName, signingFlags()))
    }.getOrDefault("")

    private companion object {
        const val WINDOWS_PACKAGE = "net.paramnezia.windows"
    }
}
