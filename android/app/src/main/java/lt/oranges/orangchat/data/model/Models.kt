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
    /**
     * A bot account. Rendered as a label beside the name - it comes from the
     * account itself, so a nickname reading "BOT" cannot pass as one. Absent on
     * rows written before bots existed, hence the default.
     */
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
    val badges: List<String> = emptyList(),
    val createdAt: String = "",
    val email: String = "",
    val customCss: String? = null,
    /** Replaces the OrangChat mark on this user's own clients. Self-only. */
    val appIconUrl: String? = null,
    val dmPrivacy: DmPrivacy = DmPrivacy.EVERYONE,
    val friendRequestPrivacy: FriendRequestPrivacy = FriendRequestPrivacy.EVERYONE,
    val typingIndicators: Boolean = true,
    /** Which friend events raise a notification; online is opt-in. */
    val notifyFriendRequests: Boolean = true,
    val notifyFriendAccepted: Boolean = true,
    val notifyFriendOnline: Boolean = false,
    /** Refuse to mint a DM key until the peer was verified out of band. */
    val e2eeStrict: Boolean = false,
    val twoFactorEnabled: Boolean = false,
    /** False for OAuth-only accounts, which have no password to re-confirm. */
    val hasPassword: Boolean = true,
    /** True while the account is frozen: no new sign-ins, DMs or friend requests. */
    val lockdown: Boolean = false,
) {
    // `createdAt` is named because `bot` sits between it and `badges`, and the
    // signed-in account this is built from is never a bot.
    fun asUser() = User(id, username, displayName, avatarUrl, status, devices, activities, bio, bannerUrl, accentColor, pronouns, profileCss, badges, createdAt = createdAt)
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
    /** A past value is simply expired - compare against now, don't assume null. */
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
    /** DM-only: shared chat background, origin-relative or https. Plaintext
     *  like avatars, not E2EE like attachments: the server serves one image to
     *  every participant, including late joiners. */
    val backgroundUrl: String? = null,
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
     * Seconds, for audio and video. Measured by the sender's client at upload,
     * so a receiver knows the length without downloading the file.
     */
    val duration: Double? = null,
    /**
     * A still of the video's first frame, made at upload and stored next to
     * the bytes. Receivers show it as the preview instead of a dark box.
     */
    val thumbnailUrl: String? = null,
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
    /**
     * The declared type decides, but only when it says anything. Uploads whose
     * type has not been settled yet - and everything routed through OrangMove,
     * which records every file as application/octet-stream - arrive generic, and
     * keying off that alone is what left previewable files sitting behind a
     * download card until something re-sniffed them. The extension is the same
     * evidence the server falls back to, so use it here rather than waiting.
     */
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
    /** Display metadata for custom emoji referenced by this message. */
    val emojis: List<Emoji> = emptyList(),
    val createdAt: String,
    val editedAt: String? = null,
    val replyToId: String? = null,
    val attachments: List<Attachment> = emptyList(),
    val reactions: List<Reaction> = emptyList(),
    val pinned: Boolean = false,
    val pinnedAt: String? = null,
    /**
     * The end-to-end encrypted envelope (docs/E2EE.md §2). When it is set,
     * `content` is the empty string the server stores and the real text only
     * exists once E2eeRepository opens this.
     */
    val ciphertext: String? = null,
    val encEpoch: Int? = null,
    val encVersion: Int? = null,
    /**
     * Set by the server when it wrote this message about the conversation rather
     * than a person typing it - see [lt.oranges.orangchat.feature.chat.SystemNotice].
     * Never accepted from a client, which is what makes a notice trustworthy:
     * `author` is the person whose action it describes, not the author of the
     * claim.
     */
    val systemNotice: String? = null,
    /** A notice's payload, for the kinds that are a card rather than a sentence. */
    val systemData: JsonElement? = null,
    /**
     * The local id this row was sent under, kept after the server confirms it.
     * Lists key on this so a confirmed message stays the *same* item as its
     * optimistic row - keying on `id` alone makes the id change at confirmation
     * look like a removal and an insert, and the row re-animates from scratch.
     * Never sent to the server; absent on anything that did not start here.
     */
    val clientId: String? = null,
)

/**
 * `actor` is null when the account that made the change was deleted - the entry
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
    val latestMessage: Message? = null,
    /** DM-only: shared chat background, plaintext like avatars. */
    val backgroundUrl: String? = null,
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

/**
 * POST /auth/login answers with whichever second factor the account can reach -
 * a passkey, a mailed code, and [loginToken] is what ties the second call to it.
 * The one exception is an authenticator code sent with the password: that has
 * already cleared a second factor, so the session comes back in [user]/[tokens]
 * and there is no second call.
 */
@Serializable
data class LoginChallenge(
    val email2faRequired: Boolean = false,
    val loginToken: String = "",
    /**
     * Set instead of [email2faRequired] on an account that has a passkey: it is
     * phishing-resistant where a mailed code is not, so it is asked for first.
     */
    val passkeyRequired: Boolean = false,
    /** The WebAuthn request, passed straight to Credential Manager. */
    val challenge: JsonElement? = null,
    val ceremonyToken: String = "",
    /** Both set only when an authenticator code finished the sign-in outright. */
    val user: SelfUser? = null,
    val tokens: AuthTokens? = null,
)

/** POST /auth/signup: the account exists but cannot sign in until verified. */
@Serializable
data class SignupResult(
    val emailVerificationRequired: Boolean = false,
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
