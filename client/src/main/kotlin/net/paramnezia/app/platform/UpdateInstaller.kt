package net.paramnezia.app.platform

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.paramnezia.core.ota.Release
import net.paramnezia.core.ota.UpdateCheckResult
import net.paramnezia.core.ota.verifyingCopy
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.coroutines.coroutineContext

/** Downloads a release already accepted by the platform's UpdateChecker. */
class UpdateInstaller(private val directory: File, private val extension: String, private val scope: CoroutineScope) {
    sealed interface State {
        data object Idle : State
        data class Downloading(val percent: Int) : State
        data class Ready(val release: Release, val file: File) : State
        data object Failed : State
    }

    private val mutableState = MutableStateFlow<State>(State.Idle)
    val state = mutableState.asStateFlow()
    private var download: Job? = null

    private val mutableChecking = MutableStateFlow(false)
    val checking = mutableChecking.asStateFlow()
    private val mutableInstalling = MutableStateFlow(false)
    val installing = mutableInstalling.asStateFlow()
    private val mutableNotice = MutableStateFlow<String?>(null)
    val notice = mutableNotice.asStateFlow()

    fun dismissNotice(message: String): Boolean = mutableNotice.compareAndSet(message, null)

    fun check(fetch: suspend () -> UpdateCheckResult) {
        if (!mutableChecking.compareAndSet(false, true)) return
        scope.launch(Dispatchers.IO) {
            try {
                mutableNotice.value = when (fetch()) {
                    UpdateCheckResult.AVAILABLE -> null
                    UpdateCheckResult.UP_TO_DATE -> "Установлена последняя версия"
                    UpdateCheckResult.UNAVAILABLE -> "В этой сборке обновления недоступны"
                    UpdateCheckResult.FAILED -> "Не удалось проверить обновления. Попробуйте ещё раз."
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                mutableNotice.value = "Не удалось проверить обновления. Попробуйте ещё раз."
            } finally {
                mutableChecking.value = false
            }
        }
    }

    fun startInstall(open: suspend () -> String?) {
        if (!mutableInstalling.compareAndSet(false, true)) return
        scope.launch(Dispatchers.IO) {
            try {
                mutableNotice.value = open()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                mutableNotice.value = "Не удалось открыть установщик"
            } finally {
                mutableInstalling.value = false
            }
        }
    }

    /** Reuse a fully verified artifact after the process was restarted. */
    fun restore(release: Release) {
        scope.launch(Dispatchers.IO) {
            val file = artifact(release) ?: return@launch
            if (!matches(file, release)) return@launch
            synchronized(this@UpdateInstaller) {
                val current = state.value
                if (current == State.Idle || (current is State.Ready && current.release.versionCode < release.versionCode)) {
                    mutableState.compareAndSet(current, State.Ready(release, file))
                }
            }
        }
    }

    private fun artifact(release: Release): File? =
        if (release.size > 0 && release.sha256.matches(Regex("[a-f0-9]{64}"))) {
            File(directory, "paramnezia-${release.sha256}.$extension")
        } else null

    @Synchronized
    fun download(release: Release, read: suspend ((InputStream) -> Boolean) -> Boolean) {
        if (download?.isCompleted == false || installing.value) return
        mutableState.value = State.Downloading(0)
        download = scope.launch(Dispatchers.IO) {
            try {
                val file = verifiedFile(release, read)
                mutableState.value = if (file != null) State.Ready(release, file) else State.Failed
            } catch (e: CancellationException) {
                mutableState.value = State.Idle
                throw e
            } catch (_: Exception) {
                mutableState.value = State.Failed
            }
        }
    }

    @Synchronized
    fun cancel() {
        download?.cancel()
    }

    /** Opens only a complete artifact which still matches the accepted manifest. */
    suspend fun install(release: Release, open: (File) -> String?): String? = withContext(Dispatchers.IO) {
        val ready = (state.value as? State.Ready)?.takeIf { it.release == release }
            ?: return@withContext "Сначала скачайте обновление"
        if (!matches(ready.file, release)) {
            mutableState.compareAndSet(ready, State.Failed)
            return@withContext "Файл обновления повреждён. Скачайте его ещё раз"
        }
        open(ready.file)
    }

    private suspend fun verifiedFile(release: Release, read: suspend ((InputStream) -> Boolean) -> Boolean): File? {
        val file = artifact(release) ?: return null
        directory.mkdirs()
        if (matches(file, release)) return file
        val tmp = File.createTempFile("paramnezia-", ".part", directory)
        val context = coroutineContext
        try {
            val valid = read { input ->
                context.ensureActive()
                var bytes = 0L
                mutableState.value = State.Downloading(0)
                tmp.outputStream().buffered().use { output ->
                    verifyingCopy(input, { buffer, count ->
                        context.ensureActive()
                        output.write(buffer, 0, count)
                        bytes += count
                        mutableState.value = State.Downloading((bytes.toDouble() * 100 / release.size).toInt())
                    }, release)
                }
            }
            context.ensureActive()
            if (!valid) return null
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            directory.listFiles { candidate ->
                candidate.name.startsWith("paramnezia-") && candidate.extension == extension && candidate != file
            }?.forEach { it.delete() }
            return file
        } finally {
            tmp.delete()
        }
    }

    private fun matches(file: File, release: Release): Boolean =
        file.isFile && file.length() == release.size &&
            runCatching { file.inputStream().buffered().use { verifyingCopy(it, { _, _ -> }, release) } }.getOrDefault(false)
}
