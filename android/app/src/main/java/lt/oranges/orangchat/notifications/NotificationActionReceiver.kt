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
                notificationHelper.appendOwnReply(channelId, text)
                val pending = goAsync()
                scope.launch {
                    try {
                        if (!sendWithRetry(channelId, text)) {
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

    private suspend fun sendWithRetry(channelId: String, text: String): Boolean {
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
