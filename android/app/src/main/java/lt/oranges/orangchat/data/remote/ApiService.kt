package lt.oranges.orangchat.data.remote

import lt.oranges.orangchat.data.model.AuditLogEntry
import lt.oranges.orangchat.data.model.AuthResult
import lt.oranges.orangchat.data.model.Channel
import lt.oranges.orangchat.data.model.ChannelOverwrite
import lt.oranges.orangchat.data.model.Conversation
import lt.oranges.orangchat.data.model.E2eeAddDeviceRequest
import lt.oranges.orangchat.data.model.E2eeBlob
import lt.oranges.orangchat.data.model.E2eeBlobRequest
import lt.oranges.orangchat.data.model.E2eeChannelState
import lt.oranges.orangchat.data.model.E2eeDevice
import lt.oranges.orangchat.data.model.E2eeDeviceList
import lt.oranges.orangchat.data.model.E2eeEpoch
import lt.oranges.orangchat.data.model.E2eeEpochKeys
import lt.oranges.orangchat.data.model.E2eeEraseKeysRequest
import lt.oranges.orangchat.data.model.E2eeGenesisRequest
import lt.oranges.orangchat.data.model.E2eeMintEpochRequest
import lt.oranges.orangchat.data.model.E2eeRevokeRequest
import lt.oranges.orangchat.data.model.E2eeStrictOverrides
import lt.oranges.orangchat.data.model.E2eeStrictRequest
import lt.oranges.orangchat.data.model.E2eeStrictResponse
import lt.oranges.orangchat.data.model.E2eeTransferEmailCode
import lt.oranges.orangchat.data.model.E2eeTransferGrant
import lt.oranges.orangchat.data.model.E2eeTransferGrantRequest
import lt.oranges.orangchat.data.model.E2eeTransferId
import lt.oranges.orangchat.data.model.Emoji
import lt.oranges.orangchat.data.model.Friend
import lt.oranges.orangchat.data.model.FriendRequestsEnvelope
import lt.oranges.orangchat.data.model.Invite
import lt.oranges.orangchat.data.model.InvitePreview
import lt.oranges.orangchat.data.model.LoginChallenge
import lt.oranges.orangchat.data.model.Message
import lt.oranges.orangchat.data.model.Page
import lt.oranges.orangchat.data.model.Role
import lt.oranges.orangchat.data.model.SelfUser
import lt.oranges.orangchat.data.model.Server
import lt.oranges.orangchat.data.model.ServerDetail
import lt.oranges.orangchat.data.model.ServerMember
import lt.oranges.orangchat.data.model.SignupResult
import lt.oranges.orangchat.data.model.Sound
import lt.oranges.orangchat.data.model.UnreadState
import lt.oranges.orangchat.data.model.VoiceState
import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.HTTP
import retrofit2.http.Multipart
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query

interface ApiService {

    @GET("/health")
    suspend fun health(): HealthDto

    @POST("auth/signup")
    suspend fun signup(@Body body: SignupRequest): SignupResult

    @POST("auth/login")
    suspend fun login(@Body body: LoginRequest): LoginChallenge

    @POST("auth/login/email-2fa")
    suspend fun verifyEmailTwoFactor(@Body body: EmailTwoFactorRequest): AuthResult

    @POST("auth/login/email-2fa/resend")
    suspend fun resendEmailTwoFactor(@Body body: ResendEmailTwoFactorRequest): OkResult

    @POST("auth/passkey/start")
    suspend fun startPasskeySignIn(): PasskeyChallenge

    @POST("auth/passkey/finish")
    suspend fun finishPasskeySignIn(@Body body: PasskeyFinishRequest): AuthResult

    @GET("security/passkeys")
    suspend fun getPasskeys(): PasskeyListResult

    @POST("security/passkeys/register/start")
    suspend fun startPasskeyRegistration(@Body body: TwoFactorDisableRequest): PasskeyChallenge

    @POST("security/passkeys/register/finish")
    suspend fun finishPasskeyRegistration(@Body body: PasskeyRegisterFinishRequest): PasskeyResult

    @PATCH("security/passkeys/{id}")
    suspend fun renamePasskey(@Path("id") id: String, @Body body: PasskeyNameRequest): PasskeyResult

    @HTTP(method = "DELETE", path = "security/passkeys/{id}", hasBody = true)
    suspend fun deletePasskey(
        @Path("id") id: String,
        @Body body: TwoFactorDisableRequest,
    ): Response<Unit>

    @POST("auth/refresh")
    suspend fun refresh(): AuthResult

    @POST("auth/logout")
    suspend fun logout(): Response<Unit>

    @GET("auth/me")
    suspend fun getMe(): SelfUser

    @PUT("push/subscriptions")
    suspend fun savePushSubscription(@Body body: PushSubscriptionRequest): Response<Unit>

    @PATCH("auth/me")
    suspend fun patchMe(@Body body: UpdateMeRequest): SelfUser

    @GET("security/2fa")
    suspend fun getTwoFactorStatus(): TwoFactorStatus

    @POST("security/2fa/setup")
    suspend fun setupTwoFactor(@Body body: TwoFactorPasswordRequest): TwoFactorSetup

    @POST("security/2fa/enable")
    suspend fun enableTwoFactor(@Body body: TwoFactorCodeRequest): TwoFactorEnableResult

    @POST("security/2fa/disable")
    suspend fun disableTwoFactor(@Body body: TwoFactorDisableRequest): TwoFactorStatus

    @POST("security/2fa/backup-codes")
    suspend fun regenerateBackupCodes(@Body body: TwoFactorDisableRequest): BackupCodesResult

    @POST("security/password")
    suspend fun changePassword(@Body body: ChangePasswordRequest): ChangePasswordResult

    @POST("security/email")
    suspend fun changeEmail(@Body body: ChangeEmailRequest): ChangeEmailResult

    @GET("auth/sessions")
    suspend fun getSessions(): SessionsResult

    @DELETE("auth/sessions/{jti}")
    suspend fun revokeSession(@Path("jti") jti: String): RevokeResult

    @DELETE("auth/sessions")
    suspend fun revokeOtherSessions(): RevokeResult

    @POST("auth/lockdown")
    suspend fun setLockdown(@Body body: LockdownRequest): LockdownResult

    @POST("auth/qr/scan")
    suspend fun qrScan(@Body body: QrTokenRequest): QrActionResult

    @POST("auth/qr/approve")
    suspend fun qrApprove(@Body body: QrTokenRequest): QrActionResult

    @GET("security/standing")
    suspend fun getAccountStanding(): AccountStanding

    @HTTP(method = "DELETE", path = "security/account", hasBody = true)
    suspend fun deleteAccount(@Body body: DeleteAccountRequest): DeleteAccountResult

    @HTTP(method = "DELETE", path = "security/messages", hasBody = true)
    suspend fun deleteAllMessages(@Body body: DeleteAllMessagesRequest): DeleteAllMessagesResult

    @GET("servers")
    suspend fun listServers(): List<Server>

    @POST("servers")
    suspend fun createServer(@Body body: CreateServerRequest): Server

    @GET("servers/{serverId}")
    suspend fun getServer(@Path("serverId") serverId: String): ServerDetail

    @PATCH("servers/{serverId}")
    suspend fun updateServer(@Path("serverId") serverId: String, @Body body: UpdateServerRequest): Server

    @DELETE("servers/{serverId}")
    suspend fun deleteServer(@Path("serverId") serverId: String): Response<Unit>

    @POST("servers/{serverId}/channels")
    suspend fun createChannel(@Path("serverId") serverId: String, @Body body: CreateChannelRequest): Channel

    @POST("servers/{serverId}/invites")
    suspend fun createInvite(@Path("serverId") serverId: String, @Body body: CreateInviteRequest): Invite

    @POST("servers/{serverId}/leave")
    suspend fun leaveServer(@Path("serverId") serverId: String): Response<Unit>

    @POST("servers/leave-all")
    suspend fun leaveAllServers(): LeaveAllServersResult

    @POST("invites/{code}")
    suspend fun joinInvite(@Path("code") code: String): Server

    @GET("invites/{code}")
    suspend fun invitePreview(@Path("code") code: String): InvitePreview

    @GET("servers/{serverId}/me/permissions")
    suspend fun myPermissions(@Path("serverId") serverId: String): MyPermissionsResponse

    @GET("channels/{channelId}/messages")
    suspend fun getHistory(
        @Path("channelId") channelId: String,
        @Query("before") before: String? = null,
        @Query("limit") limit: Int = 50,
    ): Page<Message>

    @GET("channels/{channelId}")
    suspend fun getChannel(@Path("channelId") channelId: String): Channel

    @PATCH("channels/{channelId}")
    suspend fun patchChannel(@Path("channelId") channelId: String, @Body body: PatchChannelRequest): Channel

    @PUT("channels/{channelId}/background")
    suspend fun putChannelBackground(
        @Path("channelId") channelId: String,
        @Body body: ChannelBackgroundRequest,
    ): Channel

    @PUT("channels/{channelId}/icon")
    suspend fun putChannelIcon(
        @Path("channelId") channelId: String,
        @Body body: ChannelIconRequest,
    ): Channel

    @DELETE("channels/{channelId}")
    suspend fun deleteChannel(@Path("channelId") channelId: String): Response<Unit>

    @GET("channels/{channelId}/permissions")
    suspend fun listChannelPermissions(@Path("channelId") channelId: String): List<ChannelOverwrite>

    @GET("channels/{channelId}/voice")
    suspend fun getVoiceParticipants(@Path("channelId") channelId: String): List<VoiceState>

    @POST("channels/{channelId}/read")
    suspend fun markChannelRead(@Path("channelId") channelId: String): Response<Unit>

    @GET("channels/{channelId}/draft")
    suspend fun getDraft(@Path("channelId") channelId: String): DraftResponse

    @PUT("channels/{channelId}/draft")
    suspend fun putDraft(
        @Path("channelId") channelId: String,
        @Body body: DraftBody,
    ): Response<Unit>

    @DELETE("channels/{channelId}/draft")
    suspend fun deleteDraft(@Path("channelId") channelId: String): Response<Unit>

    @POST("channels/{channelId}/messages")
    suspend fun sendMessage(
        @Path("channelId") channelId: String,
        @Body body: SendMessageRequest,
    ): Message

    @POST("messages/{messageId}/report")
    suspend fun reportMessage(
        @Path("messageId") messageId: String,
        @Body body: ReportMessageRequest,
    ): MessageReportReceipt

    @GET("me/unreads")
    suspend fun getUnreads(): List<UnreadState>

    @GET("link-preview")
    suspend fun getLinkPreview(@Query("url") url: String): LinkPreviewData

    @GET("servers/{serverId}/search")
    suspend fun searchMessages(
        @Path("serverId") serverId: String,
        @Query("q") query: String,
        @Query("channelId") channelId: String? = null,
        @Query("authorId") authorId: String? = null,
        @Query("limit") limit: Int = 25,
        @Query("offset") offset: Int = 0,
    ): Page<Message>

    @GET("dms")
    suspend fun listDms(): List<Conversation>

    @POST("dms")
    suspend fun createDm(@Body body: CreateDmRequest): Conversation

    @POST("dms/{channelId}/participants")
    suspend fun addDmParticipants(@Path("channelId") channelId: String, @Body body: CreateDmRequest): Conversation

    @DELETE("dms/{channelId}")
    suspend fun leaveDm(@Path("channelId") channelId: String)

    @GET("friends")
    suspend fun listFriends(): List<Friend>

    @GET("friends/requests")
    suspend fun listFriendRequests(): FriendRequestsEnvelope

    @POST("friends/requests")
    suspend fun sendFriendRequest(@Body body: SendFriendRequest): SendFriendResult

    @POST("friends/requests/{id}/accept")
    suspend fun acceptFriendRequest(@Path("id") id: String): Friend

    @DELETE("friends/requests/{id}")
    suspend fun deleteFriendRequest(@Path("id") id: String): Response<Unit>

    @DELETE("friends/{userId}")
    suspend fun removeFriend(@Path("userId") userId: String): Response<Unit>

    @POST("servers/{serverId}/roles")
    suspend fun createRole(@Path("serverId") serverId: String, @Body body: CreateRoleRequest): Role

    @PATCH("servers/{serverId}/roles/{roleId}")
    suspend fun updateRole(@Path("serverId") serverId: String, @Path("roleId") roleId: String, @Body body: UpdateRoleRequest): Role

    @DELETE("servers/{serverId}/roles/{roleId}")
    suspend fun deleteRole(@Path("serverId") serverId: String, @Path("roleId") roleId: String): Response<Unit>

    @PUT("servers/{serverId}/members/{userId}/roles/{roleId}")
    suspend fun assignRole(@Path("serverId") serverId: String, @Path("userId") userId: String, @Path("roleId") roleId: String): ServerMember

    @DELETE("servers/{serverId}/members/{userId}/roles/{roleId}")
    suspend fun unassignRole(@Path("serverId") serverId: String, @Path("userId") userId: String, @Path("roleId") roleId: String): ServerMember

    @PATCH("servers/{serverId}/members/{userId}/nickname")
    suspend fun setNickname(@Path("serverId") serverId: String, @Path("userId") userId: String, @Body body: SetNicknameRequest): ServerMember

    @PATCH("servers/{serverId}/members/{userId}/timeout")
    suspend fun setTimeout(
        @Path("serverId") serverId: String,
        @Path("userId") userId: String,
        @Body body: SetTimeoutRequest,
    ): ServerMember

    @PATCH("servers/{serverId}/roles")
    suspend fun reorderRoles(@Path("serverId") serverId: String, @Body body: List<PositionEntry>): List<Role>

    @PATCH("servers/{serverId}/channels")
    suspend fun reorderChannels(@Path("serverId") serverId: String, @Body body: List<PositionEntry>): List<Channel>

    @GET("servers/{serverId}/audit-log")
    suspend fun getAuditLog(
        @Path("serverId") serverId: String,
        @Query("limit") limit: Int = 50,
        @Query("offset") offset: Int = 0,
        @Query("action") action: String? = null,
    ): Page<AuditLogEntry>

    @DELETE("servers/{serverId}/members/{userId}")
    suspend fun kickMember(@Path("serverId") serverId: String, @Path("userId") userId: String): Response<Unit>

    @POST("servers/{serverId}/bans/{userId}")
    suspend fun banMember(@Path("serverId") serverId: String, @Path("userId") userId: String, @Body body: BanRequest): Response<Unit>

    @DELETE("servers/{serverId}/bans/{userId}")
    suspend fun unbanMember(@Path("serverId") serverId: String, @Path("userId") userId: String): Response<Unit>

    @GET("channels/{channelId}/pins")
    suspend fun listPins(@Path("channelId") channelId: String): List<Message>

    @PUT("channels/{channelId}/pins/{messageId}")
    suspend fun pinMessage(@Path("channelId") channelId: String, @Path("messageId") messageId: String): Response<Unit>

    @DELETE("channels/{channelId}/pins/{messageId}")
    suspend fun unpinMessage(@Path("channelId") channelId: String, @Path("messageId") messageId: String): Response<Unit>

    @GET("emojis")
    suspend fun listUsableEmojis(): List<Emoji>

    @GET("servers/{serverId}/emojis")
    suspend fun listEmojis(@Path("serverId") serverId: String): List<Emoji>

    @Multipart
    @POST("servers/{serverId}/emojis")
    suspend fun createEmoji(
        @Path("serverId") serverId: String,
        @Part file: MultipartBody.Part,
        @Part("name") name: RequestBody,
    ): Emoji

    @PATCH("servers/{serverId}/emojis/{emojiId}")
    suspend fun renameEmoji(
        @Path("serverId") serverId: String,
        @Path("emojiId") emojiId: String,
        @Body body: RenameRequest,
    ): Emoji

    @DELETE("servers/{serverId}/emojis/{emojiId}")
    suspend fun deleteEmoji(@Path("serverId") serverId: String, @Path("emojiId") emojiId: String): Response<Unit>

    @GET("servers/{serverId}/sounds")
    suspend fun listSounds(@Path("serverId") serverId: String): List<Sound>

    @GET("sounds")
    suspend fun listUsableSounds(): List<Sound>

    @Multipart
    @POST("servers/{serverId}/sounds")
    suspend fun createSound(
        @Path("serverId") serverId: String,
        @Part file: MultipartBody.Part,
        @Part("name") name: RequestBody,
        @Part("emoji") emoji: RequestBody? = null,
    ): Sound

    @PATCH("servers/{serverId}/sounds/{soundId}")
    suspend fun updateSound(
        @Path("serverId") serverId: String,
        @Path("soundId") soundId: String,
        @Body body: UpdateSoundRequest,
    ): Sound

    @DELETE("servers/{serverId}/sounds/{soundId}")
    suspend fun deleteSound(@Path("serverId") serverId: String, @Path("soundId") soundId: String): Response<Unit>

    @Multipart
    @POST("uploads/image")
    suspend fun uploadImage(
        @Part file: MultipartBody.Part,
        @Query("kind") kind: String = "avatar",
    ): UploadResponse

    @GET("e2ee/devices")
    suspend fun getMyE2eeDevices(): E2eeDeviceList

    @GET("e2ee/users/{userId}/devices")
    suspend fun getPeerE2eeDevices(@Path("userId") userId: String): E2eeDeviceList

    @POST("e2ee/devices/genesis")
    suspend fun enrolGenesisDevice(@Body body: E2eeGenesisRequest): E2eeDevice

    @POST("e2ee/devices")
    suspend fun enrolAuthorizedDevice(@Body body: E2eeAddDeviceRequest): E2eeDevice

    @POST("e2ee/devices/revoke")
    suspend fun revokeE2eeDevice(@Body body: E2eeRevokeRequest): E2eeDevice

    @POST("e2ee/keys/deletion/now")
    suspend fun eraseE2eeKeysNow(@Body body: E2eeEraseKeysRequest): Response<Unit>

    @POST("e2ee/devices/{deviceId}/seen")
    suspend fun markE2eeDeviceSeen(@Path("deviceId") deviceId: String): Response<Unit>

    @POST("e2ee/transfers")
    suspend fun startE2eeTransfer(): E2eeTransferId

    @POST("e2ee/transfer-grant")
    suspend fun requestE2eeTransferGrant(@Body body: E2eeTransferGrantRequest): E2eeTransferGrant

    @POST("e2ee/transfer-grant/email-code")
    suspend fun requestE2eeTransferEmailCode(): E2eeTransferEmailCode

    @POST("e2ee/transfers/{transferId}/blob")
    suspend fun putE2eeTransferBlob(
        @Path("transferId") transferId: String,
        @Body body: E2eeBlobRequest,
    ): Response<Unit>

    @GET("e2ee/transfers/{transferId}/blob")
    suspend fun takeE2eeTransferBlob(
        @Path("transferId") transferId: String,
        @Query("slot") slot: String,
    ): E2eeBlob

    @GET("e2ee/channels/{channelId}/state")
    suspend fun getE2eeChannelState(@Path("channelId") channelId: String): E2eeChannelState

    @POST("e2ee/channels/{channelId}/epochs")
    suspend fun mintE2eeEpoch(
        @Path("channelId") channelId: String,
        @Body body: E2eeMintEpochRequest,
    ): E2eeEpoch

    @GET("e2ee/channels/{channelId}/keys")
    suspend fun getE2eeEpochKeys(
        @Path("channelId") channelId: String,
        @Query("deviceId") deviceId: String,
    ): E2eeEpochKeys


    @GET("me/e2ee-strict")
    suspend fun getMyE2eeStrict(): E2eeStrictOverrides

    @PUT("channels/{channelId}/e2ee-strict")
    suspend fun setChannelE2eeStrict(
        @Path("channelId") channelId: String,
        @Body body: E2eeStrictRequest,
    ): E2eeStrictResponse
}
