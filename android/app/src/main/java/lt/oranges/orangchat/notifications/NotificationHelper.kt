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
import android.graphics.ImageDecoder
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
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import lt.oranges.orangchat.MainActivity
import lt.oranges.orangchat.feature.chat.BubbleActivity
import lt.oranges.orangchat.R
import lt.oranges.orangchat.data.model.DmCall
import lt.oranges.orangchat.data.local.TokenStore
import lt.oranges.orangchat.util.absoluteUrl
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NotificationHelper @Inject constructor(
    @ApplicationContext private val context: Context,
    private val tokenStore: TokenStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val history = context.getSharedPreferences("notification_messages", Context.MODE_PRIVATE)
    private val historyLock = Any()
    private val notificationGenerations = mutableMapOf<String, Long>()
    private val avatarCache = LruCache<String, Bitmap>(32)
    private val initialsCache = LruCache<String, Bitmap>(32)

    init {
        if (history.getInt("schema", 0) < HISTORY_SCHEMA) {
            history.edit().clear().putInt("schema", HISTORY_SCHEMA).apply()
        }
        createChannels()
    }

    private fun createChannels() {
        val manager = context.getSystemService(NotificationManager::class.java)
        val messages = NotificationChannel(
            CHANNEL_MESSAGES,
            "Messages",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply { description = "New messages, mentions and direct messages" }
        manager.createNotificationChannel(messages)

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

    @SuppressLint("MissingPermission")
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
        if (hasPermission()) {
            NotificationManagerCompat.from(context).notify(tag.hashCode(), notification)
        }
    }

    fun hasPermission(): Boolean = hasNotificationPermission(context)

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
        val staged = stageMessage(
            channelId, title, preview(body, senderName, isGroup), senderId, senderName,
            senderAvatarUrl, isGroup, messageId,
        ) ?: return
        staged.post()
        staged.refine?.let { refine -> scope.launch { refine() } }
    }

    private fun preview(body: String, senderName: String, isGroup: Boolean): String = when {
        previewsEnabled -> body
        isGroup -> "New message from $senderName"
        else -> "New message"
    }

    val previewsEnabled: Boolean get() = tokenStore.notificationPreviews

    private class Staged(val post: () -> Unit, val refine: (() -> Unit)?)

    private fun stageMessage(
        channelId: String,
        title: String,
        body: String,
        senderId: String,
        senderName: String,
        senderAvatarUrl: String?,
        isGroup: Boolean,
        messageId: String?,
    ): Staged? {
        if (!hasPermission()) return null
        if (isMuted(channelId)) return null
        if (messageId != null && synchronized(historyLock) {
                storedMessages(channelId).any { it.messageId == messageId }
            }
        ) {
            return null
        }

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
        val post = {
            val avatar = avatarFor(senderAvatarUrl, senderName, fetch = false)
            postConversationMessage(
                channelId, title, body, senderId, senderName, avatar, avatar, isGroup, messages, generation,
            )
        }
        val refine = if (messages.any { isAvatarPending(it.avatarUrl) }) {
            {
                val avatar = avatarFor(senderAvatarUrl, senderName)
                postConversationMessage(
                    channelId, title, body, senderId, senderName, avatar, avatar, isGroup,
                    messages, generation, alertOnce = true, fetchAvatars = true,
                )
            }
        } else {
            null
        }
        return Staged(post, refine)
    }

    private fun isAvatarPending(rawUrl: String?): Boolean {
        val url = absoluteUrl(rawUrl) ?: return false
        if (isStale(url)) return true
        return avatarCache.get(url) == null && !avatarFile(url).exists()
    }

    @SuppressLint("MissingPermission")
    private fun postConversationMessage(
        channelId: String,
        title: String,
        body: String,
        senderId: String,
        senderName: String,
        senderAvatar: Bitmap?,
        conversationIcon: Bitmap,
        isGroup: Boolean,
        messages: List<StoredMessage>,
        generation: Long,
        alertOnce: Boolean = false,
        fetchAvatars: Boolean = false,
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
        val self = Person.Builder().setName("You").setKey(SELF_KEY).build()
        val shortcutId = CONVERSATION_SHORTCUT_PREFIX + channelId
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
            val person = if (message.senderId == SELF_KEY) {
                self
            } else {
                Person.Builder()
                    .setName(message.senderName)
                    .setKey(message.senderId)
                    .setIcon(
                        IconCompat.createWithBitmap(
                            avatarFor(message.avatarUrl, message.senderName, fetch = fetchAvatars),
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
            .setLargeIcon(conversationIcon)
            .setStyle(style)
            .setShortcutInfo(shortcut)
            .setShortcutId(shortcutId)
            .setLocusId(LocusIdCompat(shortcutId))
            .setBubbleMetadata(bubbleMetadata(channelId, conversationIcon))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setOnlyAlertOnce(alertOnce)
            .setContentIntent(pending)
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

    private fun bubbleMetadata(channelId: String, icon: Bitmap): NotificationCompat.BubbleMetadata {
        val intent = Intent(context, BubbleActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            putExtra(EXTRA_CHANNEL_ID, channelId)
        }
        val pending = PendingIntent.getActivity(
            context,
            BUBBLE_REQUEST_OFFSET + channelId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        return NotificationCompat.BubbleMetadata.Builder(pending, IconCompat.createWithBitmap(icon))
            .setDesiredHeight(BUBBLE_HEIGHT_DP)
            .setAutoExpandBubble(false)
            .setSuppressNotification(false)
            .build()
    }

    private fun avatarFor(rawUrl: String?, name: String, fetch: Boolean = true): Bitmap {
        val url = absoluteUrl(rawUrl) ?: return initialsAvatar(name)
        val refresh = fetch && isStale(url)
        if (!refresh) {
            avatarCache.get(url)?.let { return it }
            loadAvatarFromDisk(url)?.let { return it }
        }
        if (fetch) loadAvatar(url)?.let { return it }
        return avatarCache.get(url) ?: loadAvatarFromDisk(url) ?: initialsAvatar(name)
    }

    private fun isStale(url: String): Boolean {
        if (!url.contains(ASSET_ROUTE)) return false
        val modified = avatarFile(url).lastModified()
        return modified > 0L && System.currentTimeMillis() - modified > ASSET_MAX_AGE_MS
    }

    private fun loadAvatarFromDisk(url: String): Bitmap? {
        val file = avatarFile(url)
        if (!file.exists()) return null
        return runCatching { file.inputStream().use(::decodeAvatar) }
            .getOrNull()
            ?.let { circleCrop(it).also { bitmap -> avatarCache.put(url, bitmap) } }
    }

    private fun avatarFile(url: String): File {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(url.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return File(context.cacheDir, "avatars/$digest.img")
    }

    private fun loadAvatar(url: String): Bitmap? {
        val bytes = runCatching {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = AVATAR_TIMEOUT_MS
                readTimeout = AVATAR_TIMEOUT_MS
                instanceFollowRedirects = true
                setRequestProperty("Accept", "image/*")
            }
            try {
                if (connection.responseCode !in 200..299) return@runCatching null
                connection.inputStream.use { it.readBytes() }
            } finally {
                connection.disconnect()
            }
        }.getOrNull() ?: return null
        val file = avatarFile(url)
        runCatching {
            file.parentFile?.mkdirs()
            file.writeBytes(bytes)
        }
        val bitmap = runCatching { decodeAvatar(ByteArrayInputStream(bytes)) }.getOrNull() ?: return null
        return circleCrop(bitmap).also { avatarCache.put(url, it) }
    }

    private fun decodeAvatar(stream: InputStream): Bitmap? {
        val bytes = stream.readBytes()
        val source = ImageDecoder.createSource(bytes)
        return runCatching {
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val size = minOf(info.size.width, info.size.height)
                if (size > AVATAR_PX * 4) decoder.setTargetSize(AVATAR_PX * 4, AVATAR_PX * 4)
            }
        }.getOrNull() ?: BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

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
            postConversationMessage(
                channelId, title, text, SELF_KEY, "You", null, avatarFor(avatarUrl, title),
                isGroup, messages, generation, alertOnce = true,
            )
        }
    }

    fun markReplyUnsent(channelId: String) {
        appendOwnReply(channelId, UNSENT_MARKER)
    }

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
                channelId, title, messages.last().body, SELF_KEY, "You", null,
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

    fun muteFor(channelId: String, millis: Long) {
        history.edit().putLong("mute:$channelId", System.currentTimeMillis() + millis).commit()
    }

    private fun isMuted(channelId: String): Boolean =
        history.getLong("mute:$channelId", 0L) > System.currentTimeMillis()

    private fun replyAction(channelId: String): NotificationCompat.Action {
        val remoteInput = RemoteInput.Builder(KEY_REPLY).setLabel("Reply").build()
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
            setPackage(context.packageName)
            putExtra(EXTRA_CHANNEL_ID, channelId)
        }

    @SuppressLint("MissingPermission")
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

    @SuppressLint("MissingPermission")
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
        val ring = { avatar: Bitmap ->
            val notification = NotificationCompat.Builder(context, CHANNEL_CALLS)
                .setSmallIcon(R.drawable.ic_notification)
                .setLargeIcon(avatar)
                .setContentTitle(title)
                .setContentText(body)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(full)
                .setFullScreenIntent(full, true)
                .build()
            runCatching {
                NotificationManagerCompat.from(context)
                    .notify(callNotificationId(channelId), notification)
            }
            Unit
        }
        ring(avatarFor(callerAvatarUrl, callerName, fetch = false))
        if (isAvatarPending(callerAvatarUrl)) {
            scope.launch { ring(avatarFor(callerAvatarUrl, callerName)) }
        }
    }

    fun cancelCall(channelId: String) {
        runCatching {
            NotificationManagerCompat.from(context).cancel(callNotificationId(channelId))
        }
    }

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
        private const val CALL_ID_SALT = 0x7C_A11
        private const val CONVERSATION_CATEGORY = "lt.oranges.orangchat.CONVERSATION"
        const val CONVERSATION_SHORTCUT_PREFIX = "conversation:"
        private const val BUBBLE_REQUEST_OFFSET = 0x0B0B
        private const val BUBBLE_HEIGHT_DP = 600
        private const val MAX_CONVERSATION_MESSAGES = 10
        private const val HISTORY_SCHEMA = 3
        private const val AVATAR_PX = 128
        private const val AVATAR_TIMEOUT_MS = 8_000
        private const val ASSET_ROUTE = "/api/media/asset/"
        private const val ASSET_MAX_AGE_MS = 12L * 60 * 60 * 1000
        private const val AVATAR_FALLBACK_COLOR = 0xFFFF6A1A.toInt()
    }
}
