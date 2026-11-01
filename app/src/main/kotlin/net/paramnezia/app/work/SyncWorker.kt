package net.paramnezia.app.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import net.paramnezia.app.App
import java.util.concurrent.TimeUnit

/**
 * The only scheduled work: every 12 hours (the system batches it with other apps' work, so it
 * rides on wake-ups that happen anyway) read the fleet's signed manifest over HTTPS, so a device
 * learns about new servers even if it was offline when the admin changed them. When the current
 * server stops answering, the tunnel checks at once (App.onServerUnreachable).
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as App
        if (app.repo.current.fleet != null) runCatching { app.fleet.sync() }
        return Result.success()
    }

    companion object {
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<SyncWorker>(12, TimeUnit.HOURS, 6, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("sync", ExistingPeriodicWorkPolicy.KEEP, request)
        }

        fun now(context: Context) {
            WorkManager.getInstance(context).enqueue(
                androidx.work.OneTimeWorkRequestBuilder<SyncWorker>()
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .build(),
            )
        }
    }
}
