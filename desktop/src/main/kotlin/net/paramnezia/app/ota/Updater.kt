package net.paramnezia.app.ota

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
import net.paramnezia.app.BuildConfig
import net.paramnezia.app.data.Repo
import net.paramnezia.app.platform.UpdateInstaller
import net.paramnezia.desktop.DesktopHttp
import java.awt.Desktop
import java.io.File
import net.paramnezia.app.platform.ReleaseSource
import net.paramnezia.core.fleet.Ed25519
import net.paramnezia.core.ota.Release
import net.paramnezia.core.ota.ReleaseCodec
import net.paramnezia.core.ota.UpdateChecker
import net.paramnezia.core.ota.UpdateCheckResult
import net.paramnezia.deploy.FleetPublisher

/**
 * Update check for Windows, the same way as on Android: a small `release-windows.json`, signed by
 * the release key pinned in this build, is read over HTTPS from GitHub Releases. The installer is downloaded on request, verified and opened with the Windows shell.
 *
 * It also keeps the newest signed Android release ([android]): the admin's invite pages and the
 * servers' copies point people at the Android app, wherever the admin runs Paramnezia.
 */
class Updater(
    private val repo: Repo,
    private val net: DesktopHttp,
    private val ed: Ed25519,
    private val scope: CoroutineScope,
    cacheDir: File,
) {
    sealed interface Status {
        data object Idle : Status
        data class Available(val release: Release) : Status
    }

    private val _status = MutableStateFlow<Status>(Status.Idle)
    val status: StateFlow<Status> = _status

    val enabled: Boolean get() = BuildConfig.RELEASE_KEY.isNotEmpty()

    private val checker = UpdateChecker(PACKAGE, BuildConfig.VERSION_CODE, Int.MAX_VALUE, listOf(BuildConfig.RELEASE_KEY), ed)
    /** Android releases: versionCode 0 so the newest one always counts as "best". */
    private val androidChecker = UpdateChecker(ANDROID_PACKAGE, 0, Int.MAX_VALUE, listOf(BuildConfig.RELEASE_KEY), ed)
    /** Windows releases for the family page: any version counts, not only ones newer than this build. */
    private val windowsShare = UpdateChecker(PACKAGE, 0, Int.MAX_VALUE, listOf(BuildConfig.RELEASE_KEY), ed)

    /** Release info for invite pages and server copies: the Android app, plus the Windows installer. */
    val android: ReleaseSource = object : ReleaseSource {
        override fun shareable(): Pair<Release, String>? = synchronized(androidChecker) {
            androidChecker.best?.let { (r, s) -> r to ReleaseCodec.encode(s) }
        }
        override fun windows(): Release? = synchronized(windowsShare) { windowsShare.best?.first }
        override suspend fun check() = this@Updater.check()
    }

    val installer = UpdateInstaller(File(cacheDir, "updates"), "exe", scope)

    init {
        scope.launch(Dispatchers.IO) {
            val state = repo.current
            state.release?.let(::offerAndroid)
            state.windowsRelease?.let(::offer)
        }
    }

    private fun offer(text: String): Boolean {
        if (!enabled) return false
        synchronized(windowsShare) { windowsShare.offer(text) }
        return synchronized(checker) {
            val before = checker.installed
            val accepted = checker.offer(text)
            if (accepted) {
                val (release, signed) = checkNotNull(checker.best)
                repo.update { it.copy(windowsRelease = ReleaseCodec.encode(signed)) }
                _status.value = Status.Available(release)
                installer.restore(release)
            }
            accepted || checker.installed !== before || checker.best?.second?.let { it == ReleaseCodec.decode(text) } == true
        }
    }

    /** A user-initiated check only needs the Windows release. */
    suspend fun checkForUpdate(): UpdateCheckResult = withContext(Dispatchers.IO) {
        if (!enabled) return@withContext UpdateCheckResult.UNAVAILABLE
        val url = BuildConfig.UPDATE_URL.takeIf { it.startsWith("https://") }
            ?: return@withContext UpdateCheckResult.UNAVAILABLE
        val confirmed = net.get(url, fresh = true, timeoutMs = 8_000)?.let(::offer) == true
        when {
            status.value is Status.Available -> UpdateCheckResult.AVAILABLE
            confirmed -> UpdateCheckResult.UP_TO_DATE
            else -> UpdateCheckResult.FAILED
        }
    }

    private fun offerAndroid(text: String) {
        if (!enabled) return
        val accepted = synchronized(androidChecker) { androidChecker.offer(text) }
        if (accepted) repo.update { it.copy(release = ReleaseCodec.encode(androidChecker.best!!.second)) }
    }

    /** Reads both release files from GitHub Releases (and Android's from the fleet server). A few KB. */
    suspend fun check() { schedule.check(force = true, fetch = ::fetchUpdates) }

    private val schedule = UpdateCheckSchedule(repo)

    private suspend fun fetchUpdates(): Boolean = withContext(Dispatchers.IO) {
        if (!enabled) return@withContext false
        val inFleet = repo.current.fleet != null
        val (win, androids) = coroutineScope {
            val w = async { net.get(BuildConfig.UPDATE_URL) }
            val a = listOfNotNull(BuildConfig.ANDROID_UPDATE_URL, FleetPublisher.TUNNEL_RELEASE_URL.takeIf { inFleet })
                .map { u -> async { net.get(u) } }
            w.await() to a.awaitAll()
        }
        val confirmed = win?.let(::offer) == true
        androids.filterNotNull().forEach(::offerAndroid)
        confirmed
    }

    suspend fun checkIfDue() { schedule.check(fetch = ::fetchUpdates) }

    suspend fun monitorUpdates() { if (enabled) schedule.run(::fetchUpdates) }


    fun downloadUpdate() {
        val release = (status.value as? Status.Available)?.release ?: return
        installer.download(release) { read ->
            release.publicApkUrls().distinct().any {
                currentCoroutineContext().ensureActive()
                net.download(it, read)
            }
        }
    }

    suspend fun installUpdate(): String? {
        val release = (status.value as? Status.Available)?.release ?: return "Обновление недоступно"
        return installer.install(release) { file ->
            runCatching { Desktop.getDesktop().open(file) }.fold(
                onSuccess = { null },
                onFailure = { "Не удалось запустить установщик Windows" },
            )
        }
    }

    companion object {
        const val PACKAGE = "net.paramnezia.windows"
        const val ANDROID_PACKAGE = "net.paramnezia"
    }
}
