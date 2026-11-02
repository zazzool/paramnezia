package net.paramnezia.app.ota

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.paramnezia.app.BuildConfig
import net.paramnezia.app.data.Repo
import net.paramnezia.app.platform.Http
import net.paramnezia.app.platform.ReleaseSource
import net.paramnezia.core.fleet.Ed25519
import net.paramnezia.core.ota.Release
import net.paramnezia.core.ota.ReleaseCodec
import net.paramnezia.core.ota.UpdateChecker
import net.paramnezia.deploy.FleetPublisher

/**
 * Update check for Windows, the same way as on Android: a small `release-windows.json`, signed by
 * the release key pinned in this build, is read over HTTPS from GitHub Releases. The app never
 * downloads or runs installers itself: «Скачать» opens the installer link in the browser, and the
 * user runs it, exactly as with the first install (docs/WINDOWS.md).
 *
 * It also keeps the newest signed Android release ([android]): the admin's invite pages and the
 * servers' copies point people at the Android app, wherever the admin runs Paramnezia.
 */
class Updater(
    private val repo: Repo,
    private val net: Http,
    private val ed: Ed25519,
    private val scope: CoroutineScope,
    private val openUrl: (String) -> Unit,
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

    /** Release info for invite pages and server copies: always about the Android app. */
    val android: ReleaseSource = object : ReleaseSource {
        override fun shareable(): Pair<Release, String>? = synchronized(androidChecker) {
            androidChecker.best?.let { (r, s) -> r to ReleaseCodec.encode(s) }
        }
        override suspend fun check() = this@Updater.check()
    }

    init {
        scope.launch(Dispatchers.IO) { repo.current.release?.let { offerAndroid(it) } }
    }

    private fun offer(text: String) {
        if (!enabled) return
        val accepted = synchronized(checker) { checker.offer(text) }
        if (accepted) _status.value = Status.Available(checker.best!!.first)
    }

    private fun offerAndroid(text: String) {
        if (!enabled) return
        val accepted = synchronized(androidChecker) { androidChecker.offer(text) }
        if (accepted) repo.update { it.copy(release = ReleaseCodec.encode(androidChecker.best!!.second)) }
    }

    /** Reads both release files from GitHub Releases (and Android's from the fleet server). A few KB. */
    suspend fun check() = withContext(Dispatchers.IO) {
        if (!enabled) return@withContext
        val inFleet = repo.current.fleet != null
        val (win, androids) = coroutineScope {
            val w = async { net.get(BuildConfig.UPDATE_URL) }
            val a = listOfNotNull(BuildConfig.ANDROID_UPDATE_URL, FleetPublisher.TUNNEL_RELEASE_URL.takeIf { inFleet })
                .map { u -> async { net.get(u) } }
            w.await() to a.awaitAll()
        }
        win?.let(::offer)
        androids.filterNotNull().forEach(::offerAndroid)
        repo.update { it.copy(lastUpdateCheck = System.currentTimeMillis()) }
    }

    suspend fun checkIfDue() {
        if (System.currentTimeMillis() - repo.current.lastUpdateCheck >= 12 * 3600_000L) runCatching { check() }
    }

    /** The user's click: opens the installer link in the browser. False if there is nothing to open. */
    fun openDownload(): Boolean {
        val release = (status.value as? Status.Available)?.release ?: return false
        val url = release.publicApkUrls().firstOrNull() ?: return false
        openUrl(url)
        return true
    }

    companion object {
        const val PACKAGE = "net.paramnezia.windows"
        const val ANDROID_PACKAGE = "net.paramnezia"
    }
}
