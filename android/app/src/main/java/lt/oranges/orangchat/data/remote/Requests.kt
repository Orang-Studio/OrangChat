package lt.oranges.orangchat.data.remote

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import lt.oranges.orangchat.data.model.ProfileWidget

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
    val totpCode: String? = null,
    val skipPasskey: Boolean? = null,
    val lostAuthenticator: Boolean? = null,
)

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
    val profileWidgets: List<ProfileWidget>? = null,
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
    val sessionsRevoked: Int = 0,
)

@Serializable
data class ChangeEmailResult(val email: String = "")

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

@Serializable
data class DeleteAllMessagesResult(val deleted: Long = 0)

@Serializable
data class StandingEntry(
    val kind: String = "ban",
    val serverId: String = "",
    val serverName: String = "",
    val reason: String? = null,
    val expiresAt: String? = null,
    val createdAt: String? = null,
)

@Serializable
data class AccountStanding(
    val good: Boolean = true,
    val entries: List<StandingEntry> = emptyList(),
)

@Serializable
data class DeviceSession(
    val id: String = "",
    val current: Boolean = false,
    val userAgent: String? = null,
    val ip: String? = null,
    val createdAt: String? = null,
    val lastSeenAt: String? = null,
)

@Serializable
data class SessionsResult(val sessions: List<DeviceSession> = emptyList())

@Serializable
data class RevokeResult(val revoked: Int = 0)

@Serializable
data class LockdownRequest(val on: Boolean, val password: String? = null)

@Serializable
data class LockdownResult(val lockdown: Boolean = false, val sessionsRevoked: Int = 0)

@Serializable
data class QrTokenRequest(val token: String)

@Serializable
data class QrActionResult(val ok: Boolean = false)

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

@Serializable
data class ChannelBackgroundRequest(val url: String? = null)

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
    val ciphertext: String? = null,
    val encEpoch: Int? = null,
    val encVersion: Int? = null,
)

@Serializable
data class ReportMessageRequest(
    val reason: String? = null,
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
    val videoUrl: String? = null,
)

@Serializable
data class PushSubscriptionRequest(
    val kind: String,
    val endpoint: String,
    val label: String,
)

@Serializable
data class FieldTokenRequest(val label: String)
