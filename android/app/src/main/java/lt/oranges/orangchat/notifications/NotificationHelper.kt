package lt.oranges.orangchat.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.content.ContextCompat
import androidx.core.content.LocusIdCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.core.graphics.drawable.toBitmap
import dagger.hilt.android.qualifiers.ApplicationContext
import java.net.URL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import lt.oranges.orangchat.MainActivity
import lt.oranges.orangchat.R
import lt.oranges.orangchat.data.model.DmCall
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Local (non-push) notifications for incoming messages. Driven by the live
 * Socket.IO 'message:new' event (see AppViewModel). FCM push can layer on top
 * later via the same [notifyMessage] entry point.
 */
@Singleton
class NotificationHelper @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val history = context.getSharedPreferences("notification_messages", Context.MODE_PRIVATE)
    private val historyLock = Any()

    init {
        if (history.getInt("schema", 0) < HISTORY_SCHEMA) {
            history.edit().clear().putInt("schema", HISTORY_SCHEMA).apply()
        }
        createChannels()
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        val messages = NotificationChannel(
            CHANNEL_MESSAGES,
            "Messages",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply { description = "New messages, mentions and direct messages" }
        manager.createNotificationChannel(messages)

        // Calls ring through RingtonePlayer rather than the channel, so the tone
        // can be cut the instant the call is answered — a channel sound would
        // play on regardless. Channel sound/vibration are therefore off here.
        val calls = NotificationChannel(
            CHANNEL_CALLS,
            "Calls",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Incoming and ongoing voice and video calls"
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        }
        manager.createNotificationChannel(calls)
    }

    fun hasPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Raise a message notification. [title] is the author/DM/channel label,
     * [body] the rendered message text. Notifications collapse per-channel.
     */
    fun notifyMessage(
        channelId: String,
        title: String,
        body: String,
        senderId: String = title,
        senderName: String = title,
        senderAvatarUrl: String? = null,
        isGroup: Boolean = false,
        messageId: String? = null,
    ) {
        if (!hasPermission()) return

        // Persist before avatar I/O so two fast messages retain arrival order
        // even if the second sender's image downloads first.
        val messages = appendMessage(channelId, body, senderId, senderName, messageId)
        scope.launch {
            postConversationMessage(
                channelId, title, body, senderId, senderName,
                loadAvatar(senderAvatarUrl), isGroup, messages,
            )
        }
    }

    private fun postConversationMessage(
        channelId: String,
        title: String,
        body: String,
        senderId: String,
        senderName: String,
        senderAvatar: Bitmap?,
        isGroup: Boolean,
        messages: List<StoredMessage>,
    ) {

        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_CHANNEL_ID, channelId)
        }
        val pending = PendingIntent.getActivity(
            context,
            channelId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val sender = Person.Builder()
            .setName(senderName)
            .setKey(senderId)
            .apply { senderAvatar?.let { setIcon(IconCompat.createWithBitmap(it)) } }
            .build()
        val self = Person.Builder().setName("You").setKey("orangchat:self").build()
        val shortcutId = "conversation:$channelId"
        val appLogo = context.applicationInfo.loadIcon(context.packageManager).toBitmap()
        val shortcut = ShortcutInfoCompat.Builder(context, shortcutId)
            .setShortLabel(title)
            .setLongLived(true)
            .setLocusId(LocusIdCompat(shortcutId))
            .setPerson(sender)
            .setIntent(intent)
            .setIcon(IconCompat.createWithBitmap(senderAvatar ?: appLogo))
            .setCategories(setOf(CONVERSATION_CATEGORY))
            .build()
        ShortcutManagerCompat.pushDynamicShortcut(context, shortcut)

        val style = NotificationCompat.MessagingStyle(self).setGroupConversation(isGroup)
        messages.forEach { message ->
            val person = Person.Builder()
                .setName(message.senderName)
                .setKey(message.senderId)
                .apply {
                    if (message.senderId == senderId) {
                        senderAvatar?.let { setIcon(IconCompat.createWithBitmap(it)) }
                    }
                }
                .build()
            style.addMessage(message.body, message.timestamp, person)
        }
        if (isGroup) style.conversationTitle = title

        val notification = NotificationCompat.Builder(context, CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setLargeIcon(appLogo)
            .setStyle(style)
            .setShortcutId(shortcutId)
            .setLocusId(LocusIdCompat(shortcutId))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setContentIntent(pending)
            .build()

        runCatching {
            NotificationManagerCompat.from(context)
                .notify(channelId.hashCode(), notification)
        }
    }

    private fun loadAvatar(rawUrl: String?): Bitmap? {
        if (rawUrl.isNullOrBlank()) return null
        return runCatching {
            val url = URL(if (rawUrl.startsWith("http")) rawUrl else "https://chat.oranges.lt$rawUrl")
            val connection = url.openConnection().apply {
                connectTimeout = 3_000
                readTimeout = 3_000
            }
            connection.getInputStream().use(BitmapFactory::decodeStream)
        }.getOrNull()
    }

    private data class StoredMessage(
        val body: String,
        val timestamp: Long,
        val senderId: String,
        val senderName: String,
        val messageId: String?,
    )

    private fun appendMessage(
        channelId: String,
        body: String,
        senderId: String,
        senderName: String,
        messageId: String?,
    ): List<StoredMessage> = synchronized(historyLock) {
        val key = "conversation:$channelId"
        val array = runCatching { JSONArray(history.getString(key, "[]")) }.getOrElse { JSONArray() }
        val messages = buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val storedId = item.optString("messageId").ifBlank { null }
                if (messageId != null && storedId == messageId) continue
                add(StoredMessage(
                    body = item.optString("body"),
                    timestamp = item.optLong("timestamp"),
                    senderId = item.optString("senderId"),
                    senderName = item.optString("senderName"),
                    messageId = storedId,
                ))
            }
            add(StoredMessage(body, System.currentTimeMillis(), senderId, senderName, messageId))
        }.takeLast(MAX_CONVERSATION_MESSAGES)
        val trimmed = JSONArray()
        messages.forEach { item ->
            trimmed.put(JSONObject()
                .put("body", item.body)
                .put("timestamp", item.timestamp)
                .put("senderId", item.senderId)
                .put("senderName", item.senderName)
                .put("messageId", item.messageId ?: ""))
        }
        history.edit().putString(key, trimmed.toString()).apply()
        messages
    }

    fun clearConversationHistory(channelId: String) {
        synchronized(historyLock) {
            history.edit().remove("conversation:$channelId").apply()
        }
    }

    /**
     * Ring for an inbound call. Uses a full-screen intent so it takes over the
     * screen (and shows over the lockscreen) the way a phone call does, falling
     * back to a heads-up notification when the system declines to launch it.
     */
    fun notifyIncomingCall(call: DmCall) {
        if (!hasPermission()) return

        val full = PendingIntent.getActivity(
            context,
            call.channelId.hashCode(),
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(EXTRA_CHANNEL_ID, call.channelId)
                putExtra(EXTRA_INCOMING_CALL, true)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val kind = if (call.video) "video call" else "voice call"
        val notification = NotificationCompat.Builder(context, CHANNEL_CALLS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Incoming $kind")
            .setContentText(
                if (call.isGroup) "${call.caller.displayName} started a $kind"
                else "${call.caller.displayName} is calling",
            )
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)
            .setAutoCancel(false)
            .setContentIntent(full)
            .setFullScreenIntent(full, true)
            .build()

        runCatching {
            NotificationManagerCompat.from(context).notify(callNotificationId(call.channelId), notification)
        }
    }

    fun notifyPushCall(channelId: String, title: String, body: String) {
        if (!hasPermission()) return
        val full = PendingIntent.getActivity(
            context,
            channelId.hashCode(),
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(EXTRA_CHANNEL_ID, channelId)
                putExtra(EXTRA_INCOMING_CALL, true)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_CALLS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)
            .setContentIntent(full)
            .setFullScreenIntent(full, true)
            .build()
        runCatching {
            NotificationManagerCompat.from(context).notify(callNotificationId(channelId), notification)
        }
    }

    fun cancelCall(channelId: String) {
        runCatching {
            NotificationManagerCompat.from(context).cancel(callNotificationId(channelId))
        }
    }

    /** The persistent notification CallService runs its foreground state on. */
    fun buildOngoingCallNotification(): android.app.Notification {
        val pending = PendingIntent.getActivity(
            context,
            ONGOING_CALL_ID,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(context, CHANNEL_CALLS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Call in progress")
            .setContentText("Tap to return to the call")
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)
            .setContentIntent(pending)
            .build()
    }

    private fun callNotificationId(channelId: String): Int = channelId.hashCode() xor CALL_ID_SALT

    companion object {
        const val CHANNEL_MESSAGES = "messages"
        const val CHANNEL_CALLS = "calls"
        const val EXTRA_CHANNEL_ID = "channelId"
        const val EXTRA_INCOMING_CALL = "incomingCall"
        const val ONGOING_CALL_ID = 0x0CA11
        /** Keeps a call's id off the message notification for the same channel. */
        private const val CALL_ID_SALT = 0x7C_A11
        private const val CONVERSATION_CATEGORY = "lt.oranges.orangchat.CONVERSATION"
        private const val MAX_CONVERSATION_MESSAGES = 10
        private const val HISTORY_SCHEMA = 2
    }
}
