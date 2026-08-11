package lt.oranges.orangchat.notifications

import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
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
        if (message.data["kind"] == "security") {
            notificationHelper.notifySecurity(
                tag = message.data["tag"] ?: "security",
                title = message.data["title"] ?: "OrangChat",
                body = message.data["body"].orEmpty(),
            )
            return
        }
        val channelId = message.data["channelId"] ?: return
        if (message.data["kind"] == "read") {
            notificationHelper.clearConversationNotifications(channelId)
            return
        }
        if (AppForegroundState.isOnScreen(channelId)) return

        val title = message.data["title"] ?: "OrangChat"
        val body = message.data["body"].orEmpty()
        val avatarUrl = message.data["avatarUrl"]?.takeIf { it.isNotBlank() }
            ?: message.data["icon"]?.takeIf { it.isNotBlank() }
        if (message.data["kind"] == "call") {
            notificationHelper.notifyPushCall(
                channelId = channelId,
                title = title,
                body = body,
                callerName = message.data["senderName"] ?: title,
                callerAvatarUrl = avatarUrl,
            )
        } else {
            val senderName = message.data["senderName"] ?: title
            val text = message.data["ciphertext"]
                ?.takeIf { it.isNotBlank() && notificationHelper.previewsEnabled }
                ?.let { ciphertext -> openEnvelope(channelId, ciphertext, message.data) }

            notificationHelper.notifyMessage(
                channelId = channelId,
                title = title.ifBlank { senderName },
                body = text ?: body,
                senderId = message.data["senderId"] ?: title,
                senderName = senderName,
                senderAvatarUrl = avatarUrl,
                isGroup = message.data["isGroup"] == "true",
                messageId = message.data["messageId"]?.ifBlank { null },
            )
        }
    }

    private fun openEnvelope(
        channelId: String,
        ciphertext: String,
        data: Map<String, String>,
    ): String = runBlocking {
        withTimeoutOrNull(DECRYPT_TIMEOUT_MS) {
            runCatching {
                e2eeRepository.open(channelId, ciphertext, data["senderId"].orEmpty()).text
            }.onFailure { throwable ->
                Log.w(TAG, "push envelope for $channelId did not open", throwable)
            }.getOrNull()
        } ?: run {
            Log.w(TAG, "push envelope for $channelId did not open in time")
            null
        } ?: "New message"
    }

    companion object {
        private const val TAG = "FcmService"

        private const val DECRYPT_TIMEOUT_MS = 3_000L
    }
}
