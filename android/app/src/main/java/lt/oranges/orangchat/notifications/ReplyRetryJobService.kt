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

/**
 * Gets a notification quick reply out without the app ever being opened.
 *
 * The broadcast tries to send the reply itself; this picks up whatever that
 * could not deliver, usually because the phone had no usable network at that
 * instant. It is scheduled with a network requirement and expedited where the
 * platform supports it, so the system runs it as soon as there is a connection
 * - app closed, screen off - and backs off and retries until the reply lands.
 */
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
                // Sealed here rather than when the reply was queued, so it goes
                // under the epoch that is current when it actually leaves.
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
        // The send runs on the coroutine above; jobFinished ends the job.
        return true
    }

    override fun onStopJob(params: JobParameters?): Boolean {
        work?.cancel()
        // Whatever was not delivered is still in the outbox, so ask to be rerun.
        return true
    }

    companion object {
        private const val JOB_ID = 0x0AE9

        /** Queue (or re-queue) the retry. Replaces any pending copy of itself. */
        fun schedule(context: Context) {
            val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
            // Expedited work starts as soon as there is a network instead of
            // whenever the system next batches jobs - but the quota for it can
            // be spent, and a refused schedule would strand the reply, so fall
            // back to an ordinary job.
            val expedited = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
            val accepted = expedited &&
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
                    if (expedited && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        setExpedited(true)
                    } else {
                        setMinimumLatency(0)
                    }
                }
                .build()

        private const val BACKOFF_MS = 10_000L
    }
}
