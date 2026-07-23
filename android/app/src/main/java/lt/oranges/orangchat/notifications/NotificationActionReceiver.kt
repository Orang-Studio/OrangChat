package lt.oranges.orangchat.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import lt.oranges.orangchat.data.remote.ApiService
import lt.oranges.orangchat.data.remote.SendMessageRequest
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
                    runCatching { apiService.sendMessage(channelId, SendMessageRequest(text)) }
                    pending.finish()
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

    companion object {
        const val ACTION_REPLY = "lt.oranges.orangchat.action.REPLY"
        const val ACTION_MARK_READ = "lt.oranges.orangchat.action.MARK_READ"
        const val ACTION_MUTE = "lt.oranges.orangchat.action.MUTE"
    }
}
