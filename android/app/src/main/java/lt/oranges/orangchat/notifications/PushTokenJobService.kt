package lt.oranges.orangchat.notifications

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Gets this device's FCM token to the server after the app itself could not.
 *
 * Registration fails for reasons that have nothing to do with the device being
 * reachable - no signal at sign-in, a radio still associating on first launch -
 * and the cost of losing that race is total: the server has no way to push to a
 * token it was never told about, so notifications simply never arrive, with
 * nothing on screen to suggest why. Scheduled with a network requirement and an
 * exponential backoff, so the system runs it the moment there is a connection
 * and keeps running it until the token lands.
 */
@AndroidEntryPoint
class PushTokenJobService : JobService() {
    @Inject lateinit var registrar: PushTokenRegistrar

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var work: Job? = null

    override fun onStartJob(params: JobParameters?): Boolean {
        work = scope.launch {
            val landed = registrar.registerNow()
            // Rescheduling on failure is the whole point: the alternative is a
            // device that stays unreachable until somebody reopens the app.
            jobFinished(params, !landed)
        }
        return true
    }

    override fun onStopJob(params: JobParameters?): Boolean {
        work?.cancel()
        return true
    }

    companion object {
        private const val JOB_ID = 0x0F04

        /** Queue (or re-queue) the registration. Replaces any pending copy. */
        fun schedule(context: Context) {
            val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
            // Expedited so a sign-in on a bad connection is not left waiting for
            // the system's next batch - but the quota for it can be spent, and a
            // refused schedule would leave the device unreachable, so an
            // ordinary job is the fallback rather than nothing.
            val accepted =
                runCatching { scheduler.schedule(buildJob(context, expedited = true)) }
                    .getOrDefault(JobScheduler.RESULT_FAILURE) == JobScheduler.RESULT_SUCCESS
            if (!accepted) {
                runCatching { scheduler.schedule(buildJob(context, expedited = false)) }
            }
        }

        private fun buildJob(context: Context, expedited: Boolean): JobInfo =
            JobInfo.Builder(JOB_ID, ComponentName(context, PushTokenJobService::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setBackoffCriteria(BACKOFF_MS, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                .apply {
                    if (expedited) setExpedited(true) else setMinimumLatency(0)
                }
                .build()

        private const val BACKOFF_MS = 30_000L
    }
}
