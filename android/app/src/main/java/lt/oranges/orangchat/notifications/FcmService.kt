package lt.oranges.orangchat.notifications

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.runBlocking
import lt.oranges.orangchat.data.repository.E2eeRepository
import lt.oranges.orangchat.util.AppForegroundState
import javax.inject.Inject

@AndroidEntryPoint
class FcmService : FirebaseMessagingService() {
    @Inject lateinit var notificationHelper: NotificationHelper
    @Inject lateinit var tokenRegistrar: PushTokenRegistrar
    @Inject lateinit var e2eeRepository: E2eeRepository

    override fun onNewToken(token: String) = tokenRegistrar.register(token).let { Unit }

    override fun onMessageReceived(message: RemoteMessage) {
        // Account-level, so it has no conversation to hang off and no reason to
        // wait for the app to be backgrounded - the whole point is reaching
        // somebody who is looking at something else.
        if (message.data["kind"] == "security") {
            notificationHelper.notifySecurity(
                tag = message.data["tag"] ?: "security",
                title = message.data["title"] ?: "OrangChat",
                body = message.data["body"].orEmpty(),
            )
            return
        }
        val channelId = message.data["channelId"] ?: return
        // A read elsewhere: dismiss this channel's notification even if the app
        // is foreground or was asleep - the point is to clear a stale banner.
        if (message.data["kind"] == "read") {
            notificationHelper.clearConversationNotifications(channelId)
            return
        }
        if (AppForegroundState.isForeground) return
        val title = message.data["title"] ?: "OrangChat"
        val body = message.data["body"].orEmpty()
        if (message.data["kind"] == "call") {
            notificationHelper.notifyPushCall(
                channelId = channelId,
                title = title,
                body = body,
                callerName = message.data["senderName"] ?: title,
                callerAvatarUrl = message.data["icon"],
            )
        } else {
            val senderName = message.data["senderName"] ?: title
            // docs/E2EE.md §8: an encrypted conversation has no body for the
            // server to compose, so the envelope arrives instead and is opened
            // here. Failure is a placeholder, never nothing - a swallowed
            // exception that shows no notification is the bug to avoid.
            val text = message.data["ciphertext"]?.takeIf { it.isNotBlank() }?.let { ciphertext ->
                runBlocking {
                    runCatching {
                        e2eeRepository.open(
                            channelId,
                            ciphertext,
                            message.data["senderId"].orEmpty(),
                        ).text
                    }.getOrNull()
                } ?: "New message"
            }

            // The blocking variant: this thread is all that keeps the process
            // alive, so the avatar has to land before we return.
            notificationHelper.notifyMessageNow(
                channelId = channelId,
                title = title.ifBlank { senderName },
                body = text ?: body,
                senderId = message.data["senderId"] ?: title,
                senderName = senderName,
                senderAvatarUrl = message.data["icon"],
                isGroup = message.data["isGroup"] == "true",
                messageId = message.data["messageId"]?.ifBlank { null },
            )
        }
    }
}
