package lt.oranges.orangchat.notifications

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.os.Build
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import lt.oranges.orangchat.data.remote.ApiService
import lt.oranges.orangchat.data.remote.SendMessageRequest
import lt.oranges.orangchat.data.repository.E2eeRepository
import javax.inject.Inject

@AndroidEntryPoint
class ReplyRetryJobService : JobService() {
    @Inject lateinit var apiService: ApiService
    @Inject lateinit var notificationHelper: NotificationHelper
    @Inject lateinit var replyOutbox: ReplyOutbox
    @Inject lateinit var e2eeRepository: E2eeRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var work: Job? = null

    override fun onStartJob(params: JobParameters?): Boolean {
        work = scope.launch {
            var unfinished = false
            replyOutbox.all().forEach { entry ->
                val sent = runCatching {
                    val body = if (e2eeRepository.isEncrypted(entry.channelId)) {
                        val sealed = e2eeRepository.seal(entry.channelId, entry.text)
                        SendMessageRequest(
                            content = "",
                            ciphertext = sealed.ciphertext,
                            encEpoch = sealed.encEpoch,
                            encVersion = sealed.encVersion,
                        )
                    } else {
                        SendMessageRequest(entry.text)
                    }
                    apiService.sendMessage(entry.channelId, body)
                }.isSuccess
                if (sent) {
                    replyOutbox.remove(entry)
                    notificationHelper.clearUnsentMarkers(entry.channelId)
                } else {
                    unfinished = true
                }
            }
            jobFinished(params, unfinished)
        }
        return true
    }

    override fun onStopJob(params: JobParameters?): Boolean {
        work?.cancel()
        return true
    }

    companion object {
        private const val JOB_ID = 0x0AE9

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
            JobInfo.Builder(JOB_ID, ComponentName(context, ReplyRetryJobService::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setBackoffCriteria(BACKOFF_MS, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                .apply {
                    if (expedited) {
                        setExpedited(true)
                    } else {
                        setMinimumLatency(0)
                    }
                }
                .build()

        private const val BACKOFF_MS = 10_000L
    }
}
