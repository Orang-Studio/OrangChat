package lt.oranges.orangchat.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement


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
data class ProfileWidget(
    val id: String? = null,
    val type: String,
    val hidden: Boolean = false,
    val config: Map<String, JsonElement> = emptyMap(),
)

/**
 * One node of a widget's render tree. Which fields matter depends on
 * [block]: `section` uses heading/body, `native` uses component, `text` uses
 * value/markdown, `rows` and `links` use from, `image` uses src/alt,
 * `spacer` uses size, `divider` uses nothing.
 */
@Serializable
data class ProfileWidgetBlock(
    val block: String,
    val component: String? = null,
    val heading: String? = null,
    val body: ProfileWidgetBlock? = null,
    val value: String? = null,
    val markdown: Boolean = false,
    val from: String? = null,
    val src: String? = null,
    val alt: String? = null,
    val size: String? = null,
)

@Serializable
data class ProfileWidgetConfigOption(val value: String, val label: String)

@Serializable
data class ProfileWidgetConfigField(
    val key: String,
    val kind: String,
    val label: String,
    val placeholder: String? = null,
    val multiline: Boolean = false,
    val max: Int? = null,
    val options: List<ProfileWidgetConfigOption> = emptyList(),
    val of: List<ProfileWidgetConfigField> = emptyList(),
)

/**
 * A widget type the server knows about. `label`, `description` and every
 * `placeholder` are catalogue keys, not display text - run them through
 * [lt.oranges.orangchat.util.widgetString].
 */
@Serializable
data class ProfileWidgetDefinition(
    val type: String,
    val label: String,
    val description: String? = null,
    val icon: String? = null,
    val singleton: Boolean = false,
    val default: Boolean = false,
    val config: List<ProfileWidgetConfigField> = emptyList(),
    val render: ProfileWidgetBlock? = null,
)

@Serializable
data class ProfileWidgetCatalog(
    val rev: String = "",
    val widgets: List<ProfileWidgetDefinition> = emptyList(),
    val defaultLayout: List<ProfileWidget> = emptyList(),
)

@Serializable
data class ProfileFieldTokenInfo(
    val id: String,
    val label: String,
    val hint: String,
    val createdAt: String = "",
    val lastUsedAt: String? = null,
)

@Serializable
data class MintedFieldToken(val id: String, val token: String, val hint: String)

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
    val profileWidgets: List<ProfileWidget> = emptyList(),
    val profileFields: Map<String, String> = emptyMap(),
    val badges: List<String> = emptyList(),
    val bot: Boolean = false,
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
    val profileWidgets: List<ProfileWidget> = emptyList(),
    val profileFields: Map<String, String> = emptyMap(),
    val badges: List<String> = emptyList(),
    val createdAt: String = "",
    val email: String = "",
    val customCss: String? = null,
    val appIconUrl: String? = null,
    val dmPrivacy: DmPrivacy = DmPrivacy.EVERYONE,
    val friendRequestPrivacy: FriendRequestPrivacy = FriendRequestPrivacy.EVERYONE,
    val typingIndicators: Boolean = true,
    val notifyFriendRequests: Boolean = true,
    val notifyFriendAccepted: Boolean = true,
    val notifyFriendOnline: Boolean = false,
    val e2eeStrict: Boolean = false,
    val twoFactorEnabled: Boolean = false,
    val hasPassword: Boolean = true,
    val lockdown: Boolean = false,
) {
    fun asUser() = User(
        id = id,
        username = username,
        displayName = displayName,
        avatarUrl = avatarUrl,
        status = status,
        devices = devices,
        activities = activities,
        bio = bio,
        bannerUrl = bannerUrl,
        accentColor = accentColor,
        pronouns = pronouns,
        profileCss = profileCss,
        profileWidgets = profileWidgets,
        profileFields = profileFields,
        badges = badges,
        createdAt = createdAt,
    )
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
    val rateLimitPerUser: Int = 0,
    val userLimit: Int = 0,
    val bitrate: Int = 64000,
    val backgroundUrl: String? = null,
    val iconUrl: String? = null,
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
    val url: String,
    val filename: String,
    val contentType: String,
    val size: Long,
    val width: Int? = null,
    val height: Int? = null,
    val duration: Double? = null,
    val thumbnailUrl: String? = null,
    val storage: String? = null,
    val flagged: Boolean = false,
    val expiresAt: String? = null,
) {
    val isImage: Boolean get() = matches("image/", IMAGE_EXTENSIONS)
    val isAudio: Boolean get() = matches("audio/", AUDIO_EXTENSIONS)
    val isVideo: Boolean get() = matches("video/", VIDEO_EXTENSIONS)

    private fun matches(prefix: String, extensions: Set<String>): Boolean =
        if (contentType.isNotBlank() && contentType != GENERIC_TYPE) {
            contentType.startsWith(prefix)
        } else {
            filename.substringAfterLast('.', "").lowercase() in extensions
        }

    companion object {
        private const val GENERIC_TYPE = "application/octet-stream"
        private val IMAGE_EXTENSIONS = setOf(
            "png", "jpg", "jpeg", "gif", "webp", "bmp", "heic", "heif", "avif",
        )
        private val AUDIO_EXTENSIONS = setOf(
            "mp3", "m4a", "aac", "ogg", "oga", "opus", "wav", "flac",
        )
        private val VIDEO_EXTENSIONS = setOf(
            "mp4", "m4v", "webm", "mkv", "mov", "3gp",
        )
    }
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
    val emojis: List<Emoji> = emptyList(),
    val createdAt: String,
    val editedAt: String? = null,
    val replyToId: String? = null,
    val attachments: List<Attachment> = emptyList(),
    val reactions: List<Reaction> = emptyList(),
    val pinned: Boolean = false,
    val pinnedAt: String? = null,
    val ciphertext: String? = null,
    val encEpoch: Int? = null,
    val encVersion: Int? = null,
    val systemNotice: String? = null,
    val systemData: JsonElement? = null,
    val clientId: String? = null,
)

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
    val latestMessage: Message? = null,
    val backgroundUrl: String? = null,
    val iconUrl: String? = null,
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

object InviteStatus {
    const val OK = "ok"
    const val EXPIRED = "expired"
    const val EXHAUSTED = "exhausted"
    const val BANNED = "banned"
    const val ALREADY_MEMBER = "alreadyMember"
}

@Serializable
data class InvitePreview(
    val code: String,
    val server: Server,
    val memberCount: Int = 0,
    val inviterName: String? = null,
    val expiresAt: String? = null,
    val status: String = InviteStatus.OK,
) {
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
    val direction: String,
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
data class LoginChallenge(
    val email2faRequired: Boolean = false,
    val loginToken: String = "",
    val passkeyRequired: Boolean = false,
    val challenge: JsonElement? = null,
    val ceremonyToken: String = "",
    val user: SelfUser? = null,
    val tokens: AuthTokens? = null,
)

@Serializable
data class SignupResult(
    val emailVerificationRequired: Boolean = false,
)

@Serializable
data class Page<T>(
    val items: List<T> = emptyList(),
    val nextCursor: String? = null,
)

@Serializable
data class ServerDetail(
    val server: Server,
    val channels: List<Channel> = emptyList(),
    val roles: List<Role> = emptyList(),
    val members: List<ServerMember> = emptyList(),
    val overwrites: List<ChannelOverwrite> = emptyList(),
)

@Serializable
data class FriendRequestsEnvelope(
    val incoming: List<FriendRequest> = emptyList(),
    val outgoing: List<FriendRequest> = emptyList(),
)
