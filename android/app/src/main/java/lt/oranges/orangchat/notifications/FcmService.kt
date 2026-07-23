package lt.oranges.orangchat.notifications

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dagger.hilt.android.AndroidEntryPoint
import lt.oranges.orangchat.util.AppForegroundState
import javax.inject.Inject

@AndroidEntryPoint
class FcmService : FirebaseMessagingService() {
    @Inject lateinit var notificationHelper: NotificationHelper
    @Inject lateinit var tokenRegistrar: PushTokenRegistrar

    override fun onNewToken(token: String) = tokenRegistrar.register(token).let { Unit }

    override fun onMessageReceived(message: RemoteMessage) {
        val channelId = message.data["channelId"] ?: return
        // A read elsewhere: dismiss this channel's notification even if the app
        // is foreground or was asleep — the point is to clear a stale banner.
        if (message.data["kind"] == "read") {
            notificationHelper.clearConversationNotifications(channelId)
            return
        }
        if (AppForegroundState.isForeground) return
        val title = message.data["title"] ?: "OrangChat"
        val body = message.data["body"].orEmpty()
        if (message.data["kind"] == "call") {
            notificationHelper.notifyPushCall(channelId, title, body)
        } else {
            notificationHelper.notifyMessage(
                channelId = channelId,
                title = title,
                body = body,
                senderId = message.data["senderId"] ?: title,
                senderName = message.data["senderName"] ?: title,
                senderAvatarUrl = message.data["icon"],
                isGroup = message.data["isGroup"] == "true",
                messageId = message.data["messageId"]?.ifBlank { null },
            )
        }
    }
}
