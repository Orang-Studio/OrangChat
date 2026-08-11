package lt.oranges.orangchat.data.model

import kotlinx.serialization.Serializable


@Serializable
data class VoiceState(
    val channelId: String,
    val userId: String,
    val joined: Boolean = false,
    val muted: Boolean = false,
    val deafened: Boolean = false,
    val video: Boolean = false,
    val screenSharing: Boolean = false,
)

@Serializable
data class DmCall(
    val channelId: String,
    val callerId: String,
    val caller: User,
    val ringing: List<String> = emptyList(),
    val participants: List<String> = emptyList(),
    val video: Boolean = false,
    val startedAt: String = "",
) {
    val isGroup: Boolean get() = ringing.size + participants.size > 2
}

@Serializable
data class DmCallEnded(
    val channelId: String,
    val userId: String,
    val reason: String = "ended",
    val callOver: Boolean = false,
)

data class VoiceCredentials(val token: String, val url: String)

@Serializable
data class UnreadState(
    val channelId: String,
    val serverId: String? = null,
    val unread: Boolean = false,
    val unreadCount: Int = 0,
    val mentionCount: Int = 0,
)

const val UNREAD_COUNT_CAP = 100

@Serializable
data class UnreadActivity(
    val channelId: String,
    val serverId: String? = null,
    val authorId: String,
    val mentions: List<String> = emptyList(),
    val preview: String = "",
    val author: User,
)
