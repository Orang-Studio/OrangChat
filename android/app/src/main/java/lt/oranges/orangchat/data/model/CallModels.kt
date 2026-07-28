package lt.oranges.orangchat.data.model

import kotlinx.serialization.Serializable

/**
 * Mirrors of the call half of the shared Socket.IO contract - see
 * VoiceStatePayload / DmCallPayload / DmCallEndedPayload in
 * packages/shared/src/events.ts. The Rust server serialises camelCase, so the
 * field names carry over as-is.
 */

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

/** A call and its live roster. Covers 1:1 and group DMs alike. */
@Serializable
data class DmCall(
    val channelId: String,
    val callerId: String,
    val caller: User,
    /** Users still being rung. Disjoint from [participants]. */
    val ringing: List<String> = emptyList(),
    /** Users connected to the call right now. */
    val participants: List<String> = emptyList(),
    val video: Boolean = false,
    val startedAt: String = "",
) {
    /** More than two people involved means this is a group call. */
    val isGroup: Boolean get() = ringing.size + participants.size > 2
}

/** One user dropped out; [callOver] marks the last one leaving. */
@Serializable
data class DmCallEnded(
    val channelId: String,
    val userId: String,
    val reason: String = "ended",
    val callOver: Boolean = false,
)

/** LiveKit credentials handed back by the `voice:join` ack. */
data class VoiceCredentials(val token: String, val url: String)

/** Mirror of UnreadState (packages/shared/src/types.ts). */
@Serializable
data class UnreadState(
    val channelId: String,
    val serverId: String? = null,
    val unread: Boolean = false,
    /** Unread messages from other people, saturating at [UNREAD_COUNT_CAP]. */
    val unreadCount: Int = 0,
    val mentionCount: Int = 0,
)

/** Server-side counting stops here; the UI renders the cap as "99+". */
const val UNREAD_COUNT_CAP = 100

/** Mirror of UnreadActivityPayload - drives unread dots and mention badges. */
@Serializable
data class UnreadActivity(
    val channelId: String,
    val serverId: String? = null,
    val authorId: String,
    val mentions: List<String> = emptyList(),
    val preview: String = "",
    val author: User,
)
