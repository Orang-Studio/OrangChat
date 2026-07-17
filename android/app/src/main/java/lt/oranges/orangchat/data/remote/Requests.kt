package lt.oranges.orangchat.data.remote

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

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
)

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
    val dmPrivacy: String? = null,
    val friendRequestPrivacy: String? = null,
    val typingIndicators: Boolean? = null,
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
data class CreateInviteRequest(val expiresInSeconds: Long? = null, val maxUses: Int? = null)

@Serializable
data class CreateDmRequest(val userIds: List<String>)

@Serializable
data class SendFriendRequest(val username: String)

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
)

@Serializable
data class PushSubscriptionRequest(
    val kind: String,
    val endpoint: String,
    val label: String,
)
