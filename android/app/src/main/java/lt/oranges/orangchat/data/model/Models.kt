package lt.oranges.orangchat.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Wire DTOs mirroring packages/shared/src/types.ts. Dates are ISO strings,
 * permission bitfields are decimal strings (JSON has no Date or BigInt).
 */

@Serializable
enum class ChannelType {
    @SerialName("text") TEXT,
    @SerialName("voice") VOICE,
    @SerialName("category") CATEGORY,
    @SerialName("dm") DM,
    @SerialName("group_dm") GROUP_DM,
}

@Serializable
enum class PresenceStatus {
    @SerialName("online") ONLINE,
    @SerialName("idle") IDLE,
    @SerialName("dnd") DND,
    @SerialName("offline") OFFLINE,
}

@Serializable
enum class PresenceDevice {
    @SerialName("mobile") MOBILE,
    @SerialName("browser") BROWSER,
    @SerialName("desktop") DESKTOP,
}

@Serializable
data class UserActivity(
    val kind: String,
    val name: String,
    val details: String? = null,
    val url: String? = null,
    val imageUrl: String? = null,
    val startedAt: String? = null,
    val endsAt: String? = null,
)

@Serializable
enum class DmPrivacy {
    @SerialName("everyone") EVERYONE,
    @SerialName("friends") FRIENDS,
    @SerialName("none") NONE,
}

@Serializable
enum class FriendRequestPrivacy {
    @SerialName("everyone") EVERYONE,
    @SerialName("mutual") MUTUAL,
    @SerialName("none") NONE,
}

@Serializable
data class User(
    val id: String,
    val username: String,
    val displayName: String,
    val avatarUrl: String? = null,
    val status: PresenceStatus = PresenceStatus.OFFLINE,
    val devices: List<PresenceDevice> = emptyList(),
    val activities: List<UserActivity> = emptyList(),
    val bio: String? = null,
    val bannerUrl: String? = null,
    val accentColor: Int? = null,
    val pronouns: String? = null,
    val profileCss: String? = null,
    /** Awarded badge slugs; unknown ones are dropped at render. */
    val badges: List<String> = emptyList(),
    val createdAt: String = "",
)

@Serializable
data class SelfUser(
    val id: String,
    val username: String,
    val displayName: String,
    val avatarUrl: String? = null,
    val status: PresenceStatus = PresenceStatus.OFFLINE,
    val devices: List<PresenceDevice> = emptyList(),
    val activities: List<UserActivity> = emptyList(),
    val bio: String? = null,
    val bannerUrl: String? = null,
    val accentColor: Int? = null,
    val pronouns: String? = null,
    val profileCss: String? = null,
    val badges: List<String> = emptyList(),
    val createdAt: String = "",
    val email: String = "",
    val customCss: String? = null,
    val dmPrivacy: DmPrivacy = DmPrivacy.EVERYONE,
    val friendRequestPrivacy: FriendRequestPrivacy = FriendRequestPrivacy.EVERYONE,
    val typingIndicators: Boolean = true,
    val twoFactorEnabled: Boolean = false,
    /** False for OAuth-only accounts, which have no password to re-confirm. */
    val hasPassword: Boolean = true,
    /** True while the account is frozen: no new sign-ins, DMs or friend requests. */
    val lockdown: Boolean = false,
) {
    fun asUser() = User(id, username, displayName, avatarUrl, status, devices, activities, bio, bannerUrl, accentColor, pronouns, profileCss, badges, createdAt)
}

@Serializable
enum class MessageNotificationLevel {
    @SerialName("all") ALL,
    @SerialName("mentions") MENTIONS,
}

@Serializable
data class Server(
    val id: String,
    val name: String,
    val iconUrl: String? = null,
    val description: String? = null,
    val bannerUrl: String? = null,
    val systemChannelId: String? = null,
    val afkChannelId: String? = null,
    val afkTimeout: Int = 300,
    val defaultMessageNotifications: MessageNotificationLevel = MessageNotificationLevel.ALL,
    val ownerId: String,
    val createdAt: String = "",
)

/** The fixed ladder the server accepts for afkTimeout. */
val AFK_TIMEOUT_CHOICES = listOf(60, 300, 900, 1800, 3600)

@Serializable
data class Emoji(
    val id: String,
    val serverId: String,
    val name: String,
    val url: String,
    val animated: Boolean = false,
    val creatorId: String? = null,
    val createdAt: String = "",
)

@Serializable
data class Sound(
    val id: String,
    val serverId: String,
    val name: String,
    val url: String,
    val duration: Double = 0.0,
    val emoji: String? = null,
    val volume: Double = 1.0,
    val creatorId: String? = null,
    val createdAt: String = "",
)

@Serializable
data class Role(
    val id: String,
    val serverId: String,
    val name: String,
    val color: Int = 0,
    /** Decimal-string-encoded permission bitfield. */
    val permissions: String = "0",
    val position: Int = 0,
    val hoist: Boolean = false,
    val mentionable: Boolean = false,
)

@Serializable
data class ServerMember(
    val id: String,
    val serverId: String,
    val userId: String,
    val nickname: String? = null,
    /** A past value is simply expired — compare against now, don't assume null. */
    val timedOutUntil: String? = null,
    val joinedAt: String = "",
    val roleIds: List<String> = emptyList(),
    val user: User,
)

@Serializable
data class Channel(
    val id: String,
    val serverId: String? = null,
    val name: String? = null,
    val type: ChannelType,
    val topic: String? = null,
    val position: Int = 0,
    val parentCategoryId: String? = null,
    val nsfw: Boolean = false,
    /** Slowmode seconds between messages. 0 = off. Text channels only. */
    val rateLimitPerUser: Int = 0,
    /** Voice only. 0 = unlimited. */
    val userLimit: Int = 0,
    /** Voice only, bits per second. */
    val bitrate: Int = 64000,
)

@Serializable
enum class OverwriteType {
    @SerialName("role") ROLE,
    @SerialName("member") MEMBER,
}

@Serializable
data class ChannelOverwrite(
    val id: String,
    val channelId: String,
    val type: OverwriteType,
    val targetId: String,
    val allow: String = "0",
    val deny: String = "0",
)

@Serializable
data class Attachment(
    val id: String,
    /** Origin-relative, e.g. `/attachments/<id>.png` or `/orangmove/file/<token>`. */
    val url: String,
    val filename: String,
    val contentType: String,
    val size: Long,
    val width: Int? = null,
    val height: Int? = null,
    /**
     * "local" | "cloudinary" | "orangmove". Files over 10MB go to OrangMove,
     * which expires them; the other two are permanent.
     */
    val storage: String? = null,
    /** Images only: automatic moderation recommends hiding this by default. */
    val flagged: Boolean = false,
    /**
     * When OrangMove deletes the file (its reaper caps files at an hour); null
     * for local files, which are kept as long as the message. Past this the url
     * 404s, so show it as expired rather than a broken download.
     */
    val expiresAt: String? = null,
) {
    val isImage: Boolean get() = contentType.startsWith("image/")
    val isAudio: Boolean get() = contentType.startsWith("audio/")
    val isVideo: Boolean get() = contentType.startsWith("video/")
}

@Serializable
data class Reaction(
    val emoji: String,
    val count: Int,
    val me: Boolean = false,
)

@Serializable
data class Message(
    val id: String,
    val channelId: String,
    val author: User,
    val content: String,
    val createdAt: String,
    val editedAt: String? = null,
    val replyToId: String? = null,
    val attachments: List<Attachment> = emptyList(),
    val reactions: List<Reaction> = emptyList(),
    val pinned: Boolean = false,
    val pinnedAt: String? = null,
)

/**
 * `actor` is null when the account that made the change was deleted — the entry
 * itself is never removed.
 */
@Serializable
data class AuditLogEntry(
    val id: String,
    val action: String,
    val targetId: String? = null,
    val targetType: String? = null,
    val changes: Map<String, AuditLogChange> = emptyMap(),
    val reason: String? = null,
    val createdAt: String = "",
    val actor: User? = null,
)

@Serializable
data class AuditLogChange(
    val old: JsonElement? = null,
    val new: JsonElement? = null,
)

@Serializable
data class Conversation(
    val id: String,
    val type: ChannelType,
    val name: String? = null,
    val participants: List<User> = emptyList(),
    val lastMessageAt: String? = null,
)

@Serializable
data class Invite(
    val code: String,
    val serverId: String,
    val inviterId: String,
    val expiresAt: String? = null,
    val maxUses: Int? = null,
    val uses: Int = 0,
)

/** Why an invite can't be used, or that it can. Mirrors the server's strings. */
object InviteStatus {
    const val OK = "ok"
    const val EXPIRED = "expired"
    const val EXHAUSTED = "exhausted"
    const val BANNED = "banned"
    const val ALREADY_MEMBER = "alreadyMember"
}

/** What an invite link resolves to, before anyone commits to joining. */
@Serializable
data class InvitePreview(
    val code: String,
    val server: Server,
    val memberCount: Int = 0,
    val inviterName: String? = null,
    val expiresAt: String? = null,
    val status: String = InviteStatus.OK,
) {
    /** The reason the Join button is unavailable, or null when it isn't. */
    val blockedReason: String?
        get() = when (status) {
            InviteStatus.EXPIRED -> "This invite has expired."
            InviteStatus.EXHAUSTED -> "This invite has reached its use limit."
            InviteStatus.BANNED -> "You are banned from this server."
            else -> null
        }

    val isMember: Boolean get() = status == InviteStatus.ALREADY_MEMBER
}

@Serializable
data class Friend(
    val id: String,
    val user: User,
    val createdAt: String = "",
)

@Serializable
data class FriendRequest(
    val id: String,
    val user: User,
    val direction: String, // "incoming" | "outgoing"
    val createdAt: String = "",
)

@Serializable
data class AuthTokens(
    val accessToken: String,
    val expiresIn: Long = 0,
)

@Serializable
data class AuthResult(
    val user: SelfUser,
    val tokens: AuthTokens,
)

@Serializable
data class Page<T>(
    val items: List<T> = emptyList(),
    val nextCursor: String? = null,
)

/** GET /servers/:id detail envelope (see servers.rs get_server). */
@Serializable
data class ServerDetail(
    val server: Server,
    val channels: List<Channel> = emptyList(),
    val roles: List<Role> = emptyList(),
    val members: List<ServerMember> = emptyList(),
    val overwrites: List<ChannelOverwrite> = emptyList(),
)

/** GET /friends/requests envelope. */
@Serializable
data class FriendRequestsEnvelope(
    val incoming: List<FriendRequest> = emptyList(),
    val outgoing: List<FriendRequest> = emptyList(),
)
