package lt.oranges.orangchat.notifications

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import android.util.LruCache
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import androidx.core.content.LocusIdCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import java.net.HttpURLConnection
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
import lt.oranges.orangchat.util.absoluteUrl
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
    private val notificationGenerations = mutableMapOf<String, Long>()
    /** Downloaded portraits, by absolute URL - a chatty conversation would
     *  otherwise refetch the same face for every message. */
    private val avatarCache = LruCache<String, Bitmap>(32)
    private val initialsCache = LruCache<String, Bitmap>(32)

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
        // can be cut the instant the call is answered - a channel sound would
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

        // Deliberately not user-silenceable in the way messages are: these are
        // about the account itself, and one of them is a warning that somebody
        // is erasing the keys. IMPORTANCE_HIGH so it arrives as a heads-up.
        val security = NotificationChannel(
            CHANNEL_SECURITY,
            "Account security",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Encryption and account changes that need your attention"
            lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
        }
        manager.createNotificationChannel(security)
    }

    /**
     * An account-level warning. No conversation to collapse against, so [tag]
     * decides what replaces what: a second warning about a different event must
     * not overwrite the first.
     */
    fun notifySecurity(tag: String, title: String, body: String) {
        if (!hasPermission()) return
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            context,
            tag.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_SECURITY)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setOngoing(false)
            .setContentIntent(pending)
            .build()
        NotificationManagerCompat.from(context).notify(tag.hashCode(), notification)
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
     * Avatar I/O happens off this thread.
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
        val post = stageMessage(
            channelId, title, body, senderId, senderName, senderAvatarUrl, isGroup, messageId,
        ) ?: return
        scope.launch { post() }
    }

    /**
     * Same, but the avatar is fetched and the notification posted before this
     * returns. FCM calls it from its own background thread: a push can arrive
     * with the app not running at all, and a fire-and-forget download would be
     * racing the process being torn down the moment we hand control back.
     */
    fun notifyMessageNow(
        channelId: String,
        title: String,
        body: String,
        senderId: String = title,
        senderName: String = title,
        senderAvatarUrl: String? = null,
        isGroup: Boolean = false,
        messageId: String? = null,
    ) {
        stageMessage(
            channelId, title, body, senderId, senderName, senderAvatarUrl, isGroup, messageId,
        )?.invoke()
    }

    /**
     * Record the message and hand back the work that posts it, or null if this
     * notification is not to be shown. Recording happens up front so two fast
     * messages keep their arrival order even if the second sender's avatar
     * downloads first.
     */
    private fun stageMessage(
        channelId: String,
        title: String,
        body: String,
        senderId: String,
        senderName: String,
        senderAvatarUrl: String?,
        isGroup: Boolean,
        messageId: String?,
    ): (() -> Unit)? {
        if (!hasPermission()) return null
        // Muted-for-an-hour channels stay silent until the window lapses.
        if (isMuted(channelId)) return null

        // Remember what a background reply needs to rebuild this conversation's
        // notification without the message that prompted it in hand - including
        // the face on it, which a reply of our own has no way to know.
        history.edit()
            .putString("meta:$channelId", JSONObject()
                .put("title", title)
                .put("isGroup", isGroup)
                .put("avatarUrl", senderAvatarUrl.orEmpty())
                .toString())
            .commit()

        val (messages, generation) = synchronized(historyLock) {
            val messages =
                appendMessage(channelId, body, senderId, senderName, senderAvatarUrl, messageId)
            val generation = (notificationGenerations[channelId] ?: 0L) + 1L
            notificationGenerations[channelId] = generation
            messages to generation
        }
        return {
            val avatar = avatarFor(senderAvatarUrl, senderName)
            postConversationMessage(
                channelId, title, body, senderId, avatar, avatar, isGroup, messages, generation,
            )
        }
    }

    @SuppressLint("MissingPermission") // hasPermission() gates every caller; notify is also caught.
    private fun postConversationMessage(
        channelId: String,
        title: String,
        body: String,
        senderId: String,
        senderAvatar: Bitmap?,
        conversationIcon: Bitmap,
        isGroup: Boolean,
        messages: List<StoredMessage>,
        generation: Long,
        alertOnce: Boolean = false,
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
            .setName(title)
            .setKey(senderId)
            .apply { senderAvatar?.let { setIcon(IconCompat.createWithBitmap(it)) } }
            .build()
        val self = Person.Builder().setName("You").setKey(SELF_KEY).build()
        val shortcutId = "conversation:$channelId"
        val shortcut = ShortcutInfoCompat.Builder(context, shortcutId)
            .setShortLabel(title)
            .setLongLived(true)
            .setLocusId(LocusIdCompat(shortcutId))
            .setPerson(sender)
            .setIntent(intent)
            .setIcon(IconCompat.createWithBitmap(conversationIcon))
            .setCategories(setOf(CONVERSATION_CATEGORY))
            .build()
        ShortcutManagerCompat.pushDynamicShortcut(context, shortcut)

        val style = NotificationCompat.MessagingStyle(self).setGroupConversation(isGroup)
        messages.forEach { message ->
            // Every face in the thread is its own author's, so a group reads
            // the way the chat itself does rather than as one repeated portrait.
            val person = if (message.senderId == SELF_KEY) {
                self
            } else {
                Person.Builder()
                    .setName(message.senderName)
                    .setKey(message.senderId)
                    .setIcon(
                        IconCompat.createWithBitmap(
                            // Cache-only for the older messages: the one that
                            // prompted this notification is the only face worth
                            // holding the post back for.
                            avatarFor(message.avatarUrl, message.senderName, fetch = false),
                        ),
                    )
                    .build()
            }
            style.addMessage(message.body, message.timestamp, person)
        }
        if (isGroup) style.conversationTitle = title

        val builder = NotificationCompat.Builder(context, CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            // The small icon must remain the monochrome app glyph on Android,
            // but the large icon is the sender's portrait - never the app icon
            // a second time.
            .setLargeIcon(conversationIcon)
            .setStyle(style)
            .setShortcutId(shortcutId)
            .setLocusId(LocusIdCompat(shortcutId))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            // Repost for something the user themselves did - their own reply,
            // or its delivery catching up - must not buzz the phone again.
            .setOnlyAlertOnce(alertOnce)
            .setContentIntent(pending)
        // Reply / mark-read / mute live only on DM (non-group-channel)
        // notifications - the places a one-tap reply actually makes sense.
        if (!isGroup) {
            builder.addAction(replyAction(channelId))
            builder.addAction(markReadAction(channelId))
            builder.addAction(muteAction(channelId))
        }
        val notification = builder.build()

        synchronized(historyLock) {
            if (notificationGenerations[channelId] != generation) return@synchronized
            runCatching {
                NotificationManagerCompat.from(context)
                    .notify(channelId.hashCode(), notification)
            }
        }
    }

    /**
     * The face to put on a notification: the user's own picture when there is
     * one, otherwise their initial drawn the way the in-app avatar draws it.
     * Never the app icon - the shade already carries that as the small icon,
     * and a second copy of it says nothing about who is writing.
     */
    private fun avatarFor(rawUrl: String?, name: String, fetch: Boolean = true): Bitmap {
        val url = absoluteUrl(rawUrl)
        if (url != null) {
            avatarCache.get(url)?.let { return it }
            if (fetch) loadAvatar(url)?.let { return it }
        }
        return initialsAvatar(name)
    }

    private fun loadAvatar(url: String): Bitmap? {
        val bitmap = runCatching {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = AVATAR_TIMEOUT_MS
                readTimeout = AVATAR_TIMEOUT_MS
                instanceFollowRedirects = true
                setRequestProperty("Accept", "image/*")
            }
            try {
                if (connection.responseCode !in 200..299) return@runCatching null
                connection.inputStream.use(BitmapFactory::decodeStream)
            } finally {
                connection.disconnect()
            }
        }.getOrNull() ?: return null
        return circleCrop(bitmap).also { avatarCache.put(url, it) }
    }

    /** Square-crop, downscale and round off, so the shade shows a portrait and
     *  not a letterboxed banner. */
    private fun circleCrop(source: Bitmap): Bitmap {
        val edge = minOf(source.width, source.height).coerceAtLeast(1)
        val output = Bitmap.createBitmap(AVATAR_PX, AVATAR_PX, Bitmap.Config.ARGB_8888)
        val scale = AVATAR_PX.toFloat() / edge
        val shader = BitmapShader(source, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
            setLocalMatrix(
                Matrix().apply {
                    setScale(scale, scale)
                    postTranslate(
                        (AVATAR_PX - source.width * scale) / 2f,
                        (AVATAR_PX - source.height * scale) / 2f,
                    )
                },
            )
        }
        val radius = AVATAR_PX / 2f
        Canvas(output).drawCircle(
            radius,
            radius,
            radius,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { this.shader = shader },
        )
        return output
    }

    /** Mirrors the app's own initial avatar: brand circle, first letter on top. */
    private fun initialsAvatar(name: String): Bitmap {
        val letter = name.trim().take(1).uppercase().ifBlank { "?" }
        initialsCache.get(letter)?.let { return it }
        val bitmap = Bitmap.createBitmap(AVATAR_PX, AVATAR_PX, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val radius = AVATAR_PX / 2f
        canvas.drawCircle(
            radius,
            radius,
            radius,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = AVATAR_FALLBACK_COLOR },
        )
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = AVATAR_PX * 0.44f
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        // Centre on the glyph's own box, not the baseline, or it sits low.
        canvas.drawText(letter, radius, radius - (text.descent() + text.ascent()) / 2f, text)
        initialsCache.put(letter, bitmap)
        return bitmap
    }

    private data class StoredMessage(
        val body: String,
        val timestamp: Long,
        val senderId: String,
        val senderName: String,
        val avatarUrl: String?,
        val messageId: String?,
    )

    private fun appendMessage(
        channelId: String,
        body: String,
        senderId: String,
        senderName: String,
        avatarUrl: String?,
        messageId: String?,
    ): List<StoredMessage> = synchronized(historyLock) {
        val messages = (
            // A message we already hold is being re-notified, not repeated.
            storedMessages(channelId).filterNot { messageId != null && it.messageId == messageId } +
                StoredMessage(
                    body, System.currentTimeMillis(), senderId, senderName, avatarUrl, messageId,
                )
            ).takeLast(MAX_CONVERSATION_MESSAGES)
        history.edit().putString("conversation:$channelId", serialize(messages)).commit()
        messages
    }

    private fun storedMessages(channelId: String): List<StoredMessage> {
        val raw = history.getString("conversation:$channelId", "[]")
        val array = runCatching { JSONArray(raw) }.getOrElse { JSONArray() }
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                add(StoredMessage(
                    body = item.optString("body"),
                    timestamp = item.optLong("timestamp"),
                    senderId = item.optString("senderId"),
                    senderName = item.optString("senderName"),
                    avatarUrl = item.optString("avatarUrl").ifBlank { null },
                    messageId = item.optString("messageId").ifBlank { null },
                ))
            }
        }
    }

    private fun serialize(messages: List<StoredMessage>): String {
        val array = JSONArray()
        messages.forEach { item ->
            array.put(JSONObject()
                .put("body", item.body)
                .put("timestamp", item.timestamp)
                .put("senderId", item.senderId)
                .put("senderName", item.senderName)
                .put("avatarUrl", item.avatarUrl.orEmpty())
                .put("messageId", item.messageId ?: ""))
        }
        return array.toString()
    }

    /** Remove both the visible notification and its grouped message history. */
    fun clearConversationNotifications(channelId: String) {
        synchronized(historyLock) {
            notificationGenerations[channelId] =
                (notificationGenerations[channelId] ?: 0L) + 1L
            history.edit().remove("conversation:$channelId").commit()
        }
        runCatching {
            NotificationManagerCompat.from(context).cancel(channelId.hashCode())
        }
    }

    /**
     * Append the user's own reply - sent straight from the notification, without
     * opening the app - to the conversation's notification so the shade reflects
     * it as a message from "You", the same as it would inside the app. Rebuilt
     * from the persisted meta since the reply path never had the full context.
     */
    fun appendOwnReply(channelId: String, text: String) {
        if (!hasPermission()) return
        val (messages, generation, isGroup, title, avatarUrl) = synchronized(historyLock) {
            val messages = appendMessage(channelId, text, SELF_KEY, "You", null, null)
            val generation = (notificationGenerations[channelId] ?: 0L) + 1L
            notificationGenerations[channelId] = generation
            val meta = runCatching { JSONObject(history.getString("meta:$channelId", "{}").orEmpty()) }
                .getOrElse { JSONObject() }
            ReplyContext(messages, generation, meta.optBoolean("isGroup", false),
                meta.optString("title").ifBlank { "OrangChat" },
                meta.optString("avatarUrl").ifBlank { null })
        }
        scope.launch {
            // Our own reply must not repaint the conversation as us: the icon
            // stays whoever we are talking to, remembered from their message.
            postConversationMessage(
                channelId, title, text, SELF_KEY, null, avatarFor(avatarUrl, title),
                isGroup, messages, generation, alertOnce = true,
            )
        }
    }

    /**
     * Say in the shade that a quick reply has not gone out yet. Silence would
     * read as "sent" - the reply is already sitting there in the thread - and
     * the user would only find out it never arrived by asking the other side.
     */
    fun markReplyUnsent(channelId: String) {
        appendOwnReply(channelId, UNSENT_MARKER)
    }

    /** Drop that warning once the retry gets the reply out after all. */
    fun clearUnsentMarkers(channelId: String) {
        if (!hasPermission()) return
        val key = "conversation:$channelId"
        val (messages, generation, isGroup, title, avatarUrl) = synchronized(historyLock) {
            val stored = storedMessages(channelId)
            val kept = stored.filterNot { it.body == UNSENT_MARKER }
            if (kept.size == stored.size) return
            history.edit().putString(key, serialize(kept)).commit()
            val generation = (notificationGenerations[channelId] ?: 0L) + 1L
            notificationGenerations[channelId] = generation
            val meta = runCatching { JSONObject(history.getString("meta:$channelId", "{}").orEmpty()) }
                .getOrElse { JSONObject() }
            ReplyContext(kept, generation, meta.optBoolean("isGroup", false),
                meta.optString("title").ifBlank { "OrangChat" },
                meta.optString("avatarUrl").ifBlank { null })
        }
        if (messages.isEmpty()) return
        scope.launch {
            postConversationMessage(
                channelId, title, messages.last().body, SELF_KEY, null,
                avatarFor(avatarUrl, title), isGroup, messages, generation, alertOnce = true,
            )
        }
    }

    private data class ReplyContext(
        val messages: List<StoredMessage>,
        val generation: Long,
        val isGroup: Boolean,
        val title: String,
        val avatarUrl: String?,
    )

    /** Silence a channel's notifications for [millis] from now. */
    fun muteFor(channelId: String, millis: Long) {
        history.edit().putLong("mute:$channelId", System.currentTimeMillis() + millis).commit()
    }

    private fun isMuted(channelId: String): Boolean =
        history.getLong("mute:$channelId", 0L) > System.currentTimeMillis()

    private fun replyAction(channelId: String): NotificationCompat.Action {
        val remoteInput = RemoteInput.Builder(KEY_REPLY).setLabel("Reply").build()
        // FLAG_MUTABLE: the system fills the reply text into this intent.
        val pending = PendingIntent.getBroadcast(
            context,
            channelId.hashCode() xor REPLY_SALT,
            actionIntent(NotificationActionReceiver.ACTION_REPLY, channelId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        return NotificationCompat.Action.Builder(R.drawable.ic_notification, "Reply", pending)
            .addRemoteInput(remoteInput)
            .setAllowGeneratedReplies(true)
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
            .setShowsUserInterface(false)
            .build()
    }

    private fun markReadAction(channelId: String): NotificationCompat.Action {
        val pending = PendingIntent.getBroadcast(
            context,
            channelId.hashCode() xor MARK_READ_SALT,
            actionIntent(NotificationActionReceiver.ACTION_MARK_READ, channelId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Action.Builder(R.drawable.ic_notification, "Mark as read", pending)
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ)
            .setShowsUserInterface(false)
            .build()
    }

    private fun muteAction(channelId: String): NotificationCompat.Action {
        val pending = PendingIntent.getBroadcast(
            context,
            channelId.hashCode() xor MUTE_SALT,
            actionIntent(NotificationActionReceiver.ACTION_MUTE, channelId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Action.Builder(R.drawable.ic_notification, "Mute 1h", pending)
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MUTE)
            .setShowsUserInterface(false)
            .build()
    }

    private fun actionIntent(action: String, channelId: String): Intent =
        Intent(context, NotificationActionReceiver::class.java).apply {
            this.action = action
            // Explicit component + package so the broadcast is deliverable while
            // the app is in the background.
            setPackage(context.packageName)
            putExtra(EXTRA_CHANNEL_ID, channelId)
        }

    /**
     * Ring for an inbound call. Uses a full-screen intent so it takes over the
     * screen (and shows over the lockscreen) the way a phone call does, falling
     * back to a heads-up notification when the system declines to launch it.
     */
    @SuppressLint("MissingPermission") // guarded below and resilient to permission revocation.
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
            // Cache-only: a ringing call must go up now, not after a download.
            .setLargeIcon(
                avatarFor(call.caller.avatarUrl, call.caller.displayName, fetch = false),
            )
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

    @SuppressLint("MissingPermission") // guarded below and resilient to permission revocation.
    fun notifyPushCall(
        channelId: String,
        title: String,
        body: String,
        callerName: String,
        callerAvatarUrl: String?,
    ) {
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
            // Push arrives on FCM's own background thread, so the caller's
            // picture can be fetched before the call is put on screen.
            .setLargeIcon(avatarFor(callerAvatarUrl, callerName))
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
        const val CHANNEL_SECURITY = "security"
        const val EXTRA_CHANNEL_ID = "channelId"
        const val EXTRA_INCOMING_CALL = "incomingCall"
        const val KEY_REPLY = "key_reply"
        private const val SELF_KEY = "orangchat:self"
        private const val UNSENT_MARKER = "⚠ Not sent yet - retrying"
        private const val REPLY_SALT = 0x5E01
        private const val MARK_READ_SALT = 0x4EAD
        private const val MUTE_SALT = 0x3B7E
        const val ONGOING_CALL_ID = 0x0CA11
        /** Keeps a call's id off the message notification for the same channel. */
        private const val CALL_ID_SALT = 0x7C_A11
        private const val CONVERSATION_CATEGORY = "lt.oranges.orangchat.CONVERSATION"
        private const val MAX_CONVERSATION_MESSAGES = 10
        private const val HISTORY_SCHEMA = 3
        /** Portrait edge in px: what the shade asks for at the largest density. */
        private const val AVATAR_PX = 128
        private const val AVATAR_TIMEOUT_MS = 8_000
        /** The brand orange the in-app initial avatar uses. */
        private const val AVATAR_FALLBACK_COLOR = 0xFFFF6A1A.toInt()
    }
}
