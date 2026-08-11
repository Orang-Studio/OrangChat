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

@AndroidEntryPoint
class PushTokenJobService : JobService() {
    @Inject lateinit var registrar: PushTokenRegistrar

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var work: Job? = null

    override fun onStartJob(params: JobParameters?): Boolean {
        work = scope.launch {
            val landed = registrar.registerNow()
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

        fun schedule(context: Context) {
            val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
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
