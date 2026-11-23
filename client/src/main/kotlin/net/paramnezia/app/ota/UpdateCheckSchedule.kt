package net.paramnezia.app.ota

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.paramnezia.app.data.Repo

/** One check at a time. Only a verified response starts the regular interval. */
class UpdateCheckSchedule(private val repo: Repo, private val now: () -> Long = System::currentTimeMillis) {
    private val lock = Mutex()
    private var lastFailure: Long? = null

    suspend fun check(force: Boolean = false, fetch: suspend () -> Boolean): Boolean = lock.withLock {
        val time = now()
        val failure = lastFailure
        val lastSuccess = repo.current.lastUpdateCheck
        val waiting = if (failure != null) time - failure in 0 until RETRY_INTERVAL
            else lastSuccess > 0 && time - lastSuccess in 0 until REGULAR_INTERVAL
        if (!force && waiting) return@withLock false
        val success = try {
            fetch()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
        lastFailure = if (success) null else now()
        if (success) repo.update { it.copy(lastUpdateCheck = now()) }
        success
    }

    suspend fun run(fetch: suspend () -> Boolean) {
        while (true) {
            check(fetch = fetch)
            delay(RETRY_INTERVAL)
        }
    }

    private companion object {
        const val REGULAR_INTERVAL = 6 * 60 * 60_000L
        const val RETRY_INTERVAL = 5 * 60_000L
    }
}
