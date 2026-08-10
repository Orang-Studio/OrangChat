package lt.oranges.orangchat.data.remote

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

@Serializable
data class DraftBody(val content: String)

@Serializable
data class DraftResponse(val content: String? = null)

@Serializable
data class SignupRequest(
    val email: String,
    val username: String,
    val password: String,
    val displayName: String? = null,
)

@Serializable
data class LoginRequest(
    val email: String,
    val password: String,
    /** Required only when the account has 2FA on; a recovery code also works. */
    val totpCode: String? = null,
    /**
     * Asks for the emailed code even on an account that has a passkey - the way
     * out for someone whose authenticator isn't to hand. Not a bypass: the
     * password is checked either way.
     */
    val skipPasskey: Boolean? = null,
    /**
     * The same escape hatch one rung lower: skips the authenticator and mails a
     * code instead. It trades the strength of the authenticator for the
     * reachability of the mailbox, so it is only ever set on request.
     */
    val lostAuthenticator: Boolean? = null,
)

/** Second half of a login: the token from /auth/login plus the mailed code. */
@Serializable
data class EmailTwoFactorRequest(
    val loginToken: String,
    val code: String,
)

@Serializable
data class ResendEmailTwoFactorRequest(val loginToken: String)

@Serializable
data class OkResult(val ok: Boolean = false)

@Serializable
data class UpdateMeRequest(
    val username: String? = null,
    val displayName: String? = null,
    val avatarUrl: String? = null,
    val status: String? = null,
    val bio: String? = null,
    val bannerUrl: String? = null,
    val accentColor: Int? = null,
    val pronouns: String? = null,
    val customCss: String? = null,
    val profileCss: String? = null,
    /** "" clears it; null is dropped by `explicitNulls = false` and means "leave". */
    val appIconUrl: String? = null,
    val dmPrivacy: String? = null,
    val friendRequestPrivacy: String? = null,
    val typingIndicators: Boolean? = null,
    val e2eeStrict: Boolean? = null,
)

@Serializable
data class TwoFactorPasswordRequest(val password: String? = null)

@Serializable
data class TwoFactorCodeRequest(val code: String)

@Serializable
data class TwoFactorDisableRequest(val password: String? = null, val code: String)

@Serializable
data class TwoFactorStatus(
    val enabled: Boolean = false,
    val backupCodesRemaining: Int = 0,
)

// ── Passkeys ────────────────────────────────────────────
//
// A ceremony is two calls: the server states a challenge, the authenticator
// signs it, the server checks its own challenge back. [ceremonyToken] is the
// thread between them, and the challenge and the response are passed through as
// raw JSON - Credential Manager speaks the WebAuthn JSON shapes directly, so
// re-modelling them here would only be a chance to get them wrong.

@Serializable
data class PasskeyChallenge(
    val challenge: JsonElement? = null,
    val ceremonyToken: String = "",
)

@Serializable
data class PasskeyFinishRequest(
    val ceremonyToken: String,
    val response: JsonElement,
)

@Serializable
data class PasskeyRegisterFinishRequest(
    val ceremonyToken: String,
    val name: String,
    val response: JsonElement,
)

@Serializable
data class Passkey(
    val id: String,
    val name: String = "Passkey",
    /** Whether the authenticator syncs it - i.e. whether losing the device loses it. */
    val backedUp: Boolean = false,
    val createdAt: String = "",
    val lastUsedAt: String? = null,
)

@Serializable
data class PasskeyListResult(
    val passkeys: List<Passkey> = emptyList(),
    val max: Int = 0,
)

@Serializable
data class PasskeyResult(val passkey: Passkey)

@Serializable
data class PasskeyNameRequest(val name: String)

/** `/health` payload - reports the running backend's build and dependencies. */
@Serializable
data class HealthDto(
    val status: String = "ok",
    val version: String? = null,
    val db: String? = null,
    val redis: String? = null,
    val uptime: Double = 0.0,
)

@Serializable
data class TwoFactorSetup(
    val secret: String,
    val otpauthUrl: String,
)

@Serializable
data class TwoFactorEnableResult(
    val enabled: Boolean = false,
    val backupCodes: List<String> = emptyList(),
)

@Serializable
data class BackupCodesResult(
    val backupCodes: List<String> = emptyList(),
)

/** `code` is ignored server-side unless 2FA is on; `password` is null for OAuth-only accounts. */
@Serializable
data class ChangePasswordRequest(
    val password: String? = null,
    val newPassword: String,
    val code: String = "",
)

@Serializable
data class ChangeEmailRequest(
    val password: String? = null,
    val email: String,
    val code: String = "",
)

@Serializable
data class ChangePasswordResult(
    val ok: Boolean = false,
    /** Other sessions signed out by the change. */
    val sessionsRevoked: Int = 0,
)

@Serializable
data class ChangeEmailResult(val email: String = "")

/** `username` must match the account's exactly; the server refuses otherwise. */
@Serializable
data class DeleteAccountRequest(
    val password: String? = null,
    val username: String,
    val code: String = "",
)

@Serializable
data class DeleteAccountResult(val deleted: Boolean = false)

@Serializable
data class DeleteAllMessagesRequest(
    val password: String? = null,
    val code: String = "",
)

/** How many messages were removed. */
@Serializable
data class DeleteAllMessagesResult(val deleted: Long = 0)

/** One restriction in force against the account; moderation is per-server. */
@Serializable
data class StandingEntry(
    val kind: String = "ban",
    val serverId: String = "",
    val serverName: String = "",
    val reason: String? = null,
    /** When a timeout lifts; null for bans, which don't expire. */
    val expiresAt: String? = null,
    val createdAt: String? = null,
)

@Serializable
data class AccountStanding(
    /** True when nothing currently restricts the account. */
    val good: Boolean = true,
    val entries: List<StandingEntry> = emptyList(),
)

/** One live session, as shown on the devices screen. */
@Serializable
data class DeviceSession(
    val id: String = "",
    /** True for the session making the request. */
    val current: Boolean = false,
    /** Raw User-Agent; turned into a device name client-side. */
    val userAgent: String? = null,
    val ip: String? = null,
    val createdAt: String? = null,
    val lastSeenAt: String? = null,
)

@Serializable
data class SessionsResult(val sessions: List<DeviceSession> = emptyList())

@Serializable
data class RevokeResult(val revoked: Int = 0)

/** `password` is only checked when lifting a lockdown. */
@Serializable
data class LockdownRequest(val on: Boolean, val password: String? = null)

@Serializable
data class LockdownResult(val lockdown: Boolean = false, val sessionsRevoked: Int = 0)

@Serializable
data class QrTokenRequest(val token: String)

@Serializable
data class QrActionResult(val ok: Boolean = false)

/** `keptOwned` names the servers left untouched because the user owns them. */
@Serializable
data class LeaveAllServersResult(
    val left: Int = 0,
    val keptOwned: List<String> = emptyList(),
)

@Serializable
data class CreateServerRequest(val name: String, val iconUrl: String? = null)

@Serializable
data class UpdateServerRequest(
    val name: String? = null,
    val iconUrl: String? = null,
    val description: String? = null,
    val bannerUrl: String? = null,
    val systemChannelId: String? = null,
    val afkChannelId: String? = null,
    val afkTimeout: Int? = null,
    val defaultMessageNotifications: String? = null,
)

@Serializable
data class CreateChannelRequest(
    val name: String,
    val type: String = "text",
    val topic: String? = null,
    val parentCategoryId: String? = null,
)

@Serializable
data class PatchChannelRequest(
    val name: String? = null,
    val topic: String? = null,
    val position: Int? = null,
    val parentCategoryId: String? = null,
    val nsfw: Boolean? = null,
    val rateLimitPerUser: Int? = null,
    val userLimit: Int? = null,
    val bitrate: Int? = null,
)

/** `PUT /channels/{channelId}/background`; null clears the shared DM background. */
@Serializable
data class ChannelBackgroundRequest(val url: String? = null)

/** `PUT /channels/{channelId}/icon`; null clears the group DM's icon. */
@Serializable
data class ChannelIconRequest(val url: String? = null)

@Serializable
data class CreateInviteRequest(val expiresInSeconds: Long? = null, val maxUses: Int? = null)

@Serializable
data class CreateDmRequest(val userIds: List<String>)

@Serializable
data class SendFriendRequest(val username: String)

@Serializable
data class SendMessageRequest(
    val content: String,
    /**
     * Set for an end-to-end encrypted conversation (docs/E2EE.md §2). `content`
     * then goes as the empty string the server stores, and the real text only
     * exists inside this envelope.
     */
    val ciphertext: String? = null,
    val encEpoch: Int? = null,
    val encVersion: Int? = null,
)

@Serializable
data class ReportMessageRequest(
    val reason: String? = null,
    /** One HKDF-derived message key, never the conversation key. */
    val messageKey: String? = null,
)

@Serializable
data class MessageReportReceipt(
    val id: String,
    val status: String,
    val encrypted: Boolean,
)

@Serializable
data class CreateRoleRequest(
    val name: String,
    val color: Int? = null,
    val permissions: String? = null,
    val hoist: Boolean? = null,
    val mentionable: Boolean? = null,
)

@Serializable
data class UpdateRoleRequest(
    val name: String? = null,
    val color: Int? = null,
    val permissions: String? = null,
    val position: Int? = null,
    val hoist: Boolean? = null,
    val mentionable: Boolean? = null,
)

@Serializable
data class SetNicknameRequest(val nickname: String?)

/**
 * durationSeconds is a JsonElement, not a nullable Long, because the Json config
 * sets explicitNulls = false: a Kotlin null would be dropped from the body
 * entirely and the server rejects a payload without the key. JsonNull encodes as
 * a literal null, which is what lifts a timeout.
 */
@Serializable
data class SetTimeoutRequest(val durationSeconds: JsonElement) {
    companion object {
        fun of(seconds: Long) = SetTimeoutRequest(JsonPrimitive(seconds))
        fun lift() = SetTimeoutRequest(JsonNull)
    }
}

@Serializable
data class PositionEntry(val id: String, val position: Int)

@Serializable
data class RenameRequest(val name: String)

@Serializable
data class UpdateSoundRequest(
    val name: String? = null,
    val emoji: String? = null,
    val volume: Double? = null,
)

@Serializable
data class BanRequest(val reason: String? = null)

@Serializable
data class MyPermissionsResponse(val permissions: String)

@Serializable
data class UploadResponse(val url: String)

@Serializable
data class SendFriendResult(
    val accepted: Boolean = false,
    val friend: lt.oranges.orangchat.data.model.Friend? = null,
    val request: lt.oranges.orangchat.data.model.FriendRequest? = null,
)

@Serializable
data class LinkPreviewData(
    val url: String,
    val siteName: String,
    val title: String? = null,
    val description: String? = null,
    val imageUrl: String? = null,
    /**
     * Set when the server resolved the link to a playable video (an Instagram
     * post). Already an origin-relative proxy url, and [imageUrl] is then its
     * poster rather than a card thumbnail.
     */
    val videoUrl: String? = null,
)

@Serializable
data class PushSubscriptionRequest(
    val kind: String,
    val endpoint: String,
    val label: String,
)
