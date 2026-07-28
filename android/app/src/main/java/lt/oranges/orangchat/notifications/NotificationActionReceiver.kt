package lt.oranges.orangchat.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.RemoteInput
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import lt.oranges.orangchat.data.remote.ApiService
import lt.oranges.orangchat.data.remote.SendMessageRequest
import lt.oranges.orangchat.data.repository.E2eeRepository
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * Handles the DM notification quick actions without ever opening the app. Reply
 * sends over REST (there is no live socket from a background broadcast) and
 * echoes the text back into the notification; mark-read and mute-1h resolve
 * entirely on-device plus, for read, a server round-trip.
 */
@AndroidEntryPoint
class NotificationActionReceiver : BroadcastReceiver() {
    @Inject lateinit var apiService: ApiService
    @Inject lateinit var notificationHelper: NotificationHelper
    @Inject lateinit var replyOutbox: ReplyOutbox
    @Inject lateinit var e2eeRepository: E2eeRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        val channelId = intent.getStringExtra(NotificationHelper.EXTRA_CHANNEL_ID) ?: return
        when (intent.action) {
            ACTION_REPLY -> {
                val text = RemoteInput.getResultsFromIntent(intent)
                    ?.getCharSequence(NotificationHelper.KEY_REPLY)
                    ?.toString()
                    ?.trim()
                    .orEmpty()
                if (text.isEmpty()) return
                // Reflect it in the shade at once; the send just confirms it.
                notificationHelper.appendOwnReply(channelId, text)
                val pending = goAsync()
                scope.launch {
                    try {
                        if (!sendWithRetry(channelId, text)) {
                            // A background send fails for reasons that have
                            // nothing to do with the message - no signal, a
                            // sleeping radio. Hand it to the retry job, which
                            // the system runs the moment there is a network,
                            // rather than swallowing it or waiting on the app.
                            replyOutbox.add(channelId, text)
                            notificationHelper.markReplyUnsent(channelId)
                            ReplyRetryJobService.schedule(context)
                        }
                    } finally {
                        pending.finish()
                    }
                }
            }
            ACTION_MARK_READ -> {
                notificationHelper.clearConversationNotifications(channelId)
                val pending = goAsync()
                scope.launch {
                    runCatching { apiService.markChannelRead(channelId) }
                    pending.finish()
                }
            }
            ACTION_MUTE -> {
                notificationHelper.muteFor(channelId, TimeUnit.HOURS.toMillis(1))
                notificationHelper.clearConversationNotifications(channelId)
            }
        }
    }

    /**
     * A broadcast gets ten seconds of process life, so one immediate retry fits
     * comfortably and covers the common case: a radio that was asleep when the
     * notification was tapped.
     */
    private suspend fun sendWithRetry(channelId: String, text: String): Boolean {
        // A quick reply into an encrypted conversation is sealed here, from the
        // broadcast, exactly as the app would seal it. Sending it in the clear
        // instead would hand the server the one thing the conversation exists to
        // withhold, and the server refuses plaintext into a latched channel
        // anyway - so this is what makes the shade reply work at all.
        val body = runCatching {
            if (e2eeRepository.isEncrypted(channelId)) {
                val sealed = e2eeRepository.seal(channelId, text)
                SendMessageRequest(
                    content = "",
                    ciphertext = sealed.ciphertext,
                    encEpoch = sealed.encEpoch,
                    encVersion = sealed.encVersion,
                )
            } else {
                SendMessageRequest(text)
            }
        }.getOrElse {
            Log.w(TAG, "could not seal a quick reply", it)
            return false
        }

        repeat(SEND_ATTEMPTS) { attempt ->
            val result = runCatching { apiService.sendMessage(channelId, body) }
            if (result.isSuccess) return true
            // The one place this failure is visible: a reply sent from the shade
            // has no UI of its own to report into.
            Log.w(TAG, "quick reply attempt ${attempt + 1} failed", result.exceptionOrNull())
            if (attempt < SEND_ATTEMPTS - 1) delay(RETRY_DELAY_MS)
        }
        return false
    }

    companion object {
        private const val TAG = "OrangChatReply"
        private const val SEND_ATTEMPTS = 2
        private const val RETRY_DELAY_MS = 1_500L
        const val ACTION_REPLY = "lt.oranges.orangchat.action.REPLY"
        const val ACTION_MARK_READ = "lt.oranges.orangchat.action.MARK_READ"
        const val ACTION_MUTE = "lt.oranges.orangchat.action.MUTE"
    }
}
