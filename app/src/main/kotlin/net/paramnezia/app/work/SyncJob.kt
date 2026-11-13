package net.paramnezia.app.work

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import net.paramnezia.app.App
import java.util.concurrent.TimeUnit

/**
 * The only scheduled work: every 12 hours (the system batches it with other apps' work, so it
 * rides on wake-ups that happen anyway) read the fleet's signed manifest over HTTPS, so a device
 * learns about new servers even if it was offline when the admin changed them. When the current
 * server stops answering, the tunnel checks at once (App.onServerUnreachable).
 *
 * Plain [JobScheduler] instead of WorkManager: one periodic job needs no database, no startup
 * initializer and none of the permissions WorkManager adds to the manifest (boot receiver, wake
 * lock, foreground service), which docs/PLAY-PROTECT.md counts against an app. The job is not
 * persisted across reboots (that would need RECEIVE_BOOT_COMPLETED): every app start schedules it
 * again, and the VPN itself starts the app after a reboot.
 */
class SyncJob : JobService() {
    private var work: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        val app = application as App
        work = app.scope.launch {
            if (app.repo.current.fleet != null) runCatching { app.fleet.sync() }
            jobFinished(params, false)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        work?.cancel()
        return true // try again later
    }

    companion object {
        private const val ID = 1

        /** Schedules the periodic sync unless it is already scheduled; cheap, called on every start. */
        fun schedule(context: Context) {
            val js = context.getSystemService(JobScheduler::class.java) ?: return
            if (js.getPendingJob(ID) != null) return
            val job = JobInfo.Builder(ID, ComponentName(context, SyncJob::class.java))
                .setPeriodic(TimeUnit.HOURS.toMillis(12), TimeUnit.HOURS.toMillis(6))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .build()
            runCatching { js.schedule(job) }
        }
    }
}
