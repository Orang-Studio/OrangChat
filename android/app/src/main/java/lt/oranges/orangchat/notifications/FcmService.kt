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

/**
 * Where a push becomes a notification.
 *
 * The governing constraint is time. FCM hands this thread a message and gives
 * the process a few seconds of life around it; whatever has not been posted by
 * then is not late, it is gone. So every path here is bounded, and the
 * notification goes up with whatever is known rather than waiting for anything
 * that would make it better.
 */
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
        // The only thing worth suppressing is a notification for the very
        // conversation on screen. Dropping every push while the app happens to
        // be foreground - trusting the socket to have delivered it - is silent
        // loss whenever the socket is reconnecting, which is exactly when a
        // push is the only thing that arrives.
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
                ?.takeIf { it.isNotBlank() }
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

    /**
     * Open an encrypted push envelope (docs/E2EE.md §8), or fall back to a
     * placeholder.
     *
     * Bounded, because it has to be: the keys are local, but a repository that
     * decides it needs to reach the server for an epoch it has never seen would
     * otherwise sit here on a dead radio until the process is killed - taking
     * the notification with it. A timeout costs this one message its text; no
     * timeout costs it the notification entirely.
     *
     * Failure is a placeholder, never nothing.
     */
    private fun openEnvelope(
        channelId: String,
        ciphertext: String,
        data: Map<String, String>,
    ): String = runBlocking {
        withTimeoutOrNull(DECRYPT_TIMEOUT_MS) {
            runCatching {
                e2eeRepository.open(channelId, ciphertext, data["senderId"].orEmpty()).text
            }.onFailure { throwable ->
                // The placeholder is deliberate, but the reason must not vanish
                // with it - this is the only window we ever get into why a push
                // would not open.
                Log.w(TAG, "push envelope for $channelId did not open", throwable)
            }.getOrNull()
        } ?: run {
            Log.w(TAG, "push envelope for $channelId did not open in time")
            null
        } ?: "New message"
    }

    companion object {
        private const val TAG = "FcmService"

        /**
         * Long enough for a local unwrap on a cold-started process, short enough
         * to leave room to actually post inside FCM's window.
         */
        private const val DECRYPT_TIMEOUT_MS = 3_000L
    }
}
