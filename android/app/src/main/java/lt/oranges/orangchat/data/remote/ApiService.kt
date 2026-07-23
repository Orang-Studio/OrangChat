package lt.oranges.orangchat.data.remote

import lt.oranges.orangchat.data.model.AuditLogEntry
import lt.oranges.orangchat.data.model.AuthResult
import lt.oranges.orangchat.data.model.Channel
import lt.oranges.orangchat.data.model.ChannelOverwrite
import lt.oranges.orangchat.data.model.Conversation
import lt.oranges.orangchat.data.model.Emoji
import lt.oranges.orangchat.data.model.Friend
import lt.oranges.orangchat.data.model.FriendRequestsEnvelope
import lt.oranges.orangchat.data.model.Invite
import lt.oranges.orangchat.data.model.InvitePreview
import lt.oranges.orangchat.data.model.Message
import lt.oranges.orangchat.data.model.Page
import lt.oranges.orangchat.data.model.Role
import lt.oranges.orangchat.data.model.SelfUser
import lt.oranges.orangchat.data.model.Server
import lt.oranges.orangchat.data.model.ServerDetail
import lt.oranges.orangchat.data.model.ServerMember
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

/**
 * Retrofit surface. Paths mirror the modules under packages/server-rs/src/http
 * (base URL already includes /api/). The refresh token rides in an httpOnly
 * cookie handled by the OkHttp CookieJar; only the access token is a Bearer.
 */
interface ApiService {

    // ── auth.rs ─────────────────────────────────────────
    @POST("auth/signup")
    suspend fun signup(@Body body: SignupRequest): AuthResult

    @POST("auth/login")
    suspend fun login(@Body body: LoginRequest): AuthResult

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

    // ── security.rs (2FA) ───────────────────────────────
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

    /** Sessions live under auth/ so the path-scoped refresh cookie is in scope. */
    @GET("auth/sessions")
    suspend fun getSessions(): SessionsResult

    @DELETE("auth/sessions/{jti}")
    suspend fun revokeSession(@Path("jti") jti: String): RevokeResult

    @DELETE("auth/sessions")
    suspend fun revokeOtherSessions(): RevokeResult

    @GET("security/standing")
    suspend fun getAccountStanding(): AccountStanding

    // @HTTP rather than @DELETE: Retrofit's @DELETE can't carry a body, and the
    // confirmation fields have to travel with the request.
    @HTTP(method = "DELETE", path = "security/account", hasBody = true)
    suspend fun deleteAccount(@Body body: DeleteAccountRequest): DeleteAccountResult

    @HTTP(method = "DELETE", path = "security/messages", hasBody = true)
    suspend fun deleteAllMessages(@Body body: DeleteAllMessagesRequest): DeleteAllMessagesResult

    // ── servers.rs ──────────────────────────────────────
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

    /** Leave under your own steam — distinct from being kicked. */
    @POST("servers/{serverId}/leave")
    suspend fun leaveServer(@Path("serverId") serverId: String): Response<Unit>

    @POST("servers/leave-all")
    suspend fun leaveAllServers(): LeaveAllServersResult

    @POST("invites/{code}")
    suspend fun joinInvite(@Path("code") code: String): Server

    /** Resolve an invite link without joining, to show what it leads to. */
    @GET("invites/{code}")
    suspend fun invitePreview(@Path("code") code: String): InvitePreview

    @GET("servers/{serverId}/me/permissions")
    suspend fun myPermissions(@Path("serverId") serverId: String): MyPermissionsResponse

    // ── channels.rs ─────────────────────────────────────
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

    @DELETE("channels/{channelId}")
    suspend fun deleteChannel(@Path("channelId") channelId: String): Response<Unit>

    @GET("channels/{channelId}/permissions")
    suspend fun listChannelPermissions(@Path("channelId") channelId: String): List<ChannelOverwrite>

    /** Live voice/call participants of a channel. */
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

    /** Send a message over REST. Used by the notification quick-reply, which has
     *  no live socket to send over from a background broadcast. */
    @POST("channels/{channelId}/messages")
    suspend fun sendMessage(
        @Path("channelId") channelId: String,
        @Body body: SendMessageRequest,
    ): Message

    @GET("me/unreads")
    suspend fun getUnreads(): List<UnreadState>

    @GET("link-preview")
    suspend fun getLinkPreview(@Query("url") url: String): LinkPreviewData

    // ── servers.rs (search) ─────────────────────────────
    @GET("servers/{serverId}/search")
    suspend fun searchMessages(
        @Path("serverId") serverId: String,
        @Query("q") query: String,
        @Query("channelId") channelId: String? = null,
        @Query("authorId") authorId: String? = null,
        @Query("limit") limit: Int = 25,
        @Query("offset") offset: Int = 0,
    ): Page<Message>

    // ── dms.rs ──────────────────────────────────────────
    @GET("dms")
    suspend fun listDms(): List<Conversation>

    @POST("dms")
    suspend fun createDm(@Body body: CreateDmRequest): Conversation

    @POST("dms/{channelId}/participants")
    suspend fun addDmParticipants(@Path("channelId") channelId: String, @Body body: CreateDmRequest): Conversation

    // ── friends.rs ──────────────────────────────────────
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

    // ── roles.rs ────────────────────────────────────────
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

    /** `durationSeconds = null` lifts the timeout. Capped server-side at 28 days. */
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

    // ── channels.rs (pins) ──────────────────────────────
    @GET("channels/{channelId}/pins")
    suspend fun listPins(@Path("channelId") channelId: String): List<Message>

    @PUT("channels/{channelId}/pins/{messageId}")
    suspend fun pinMessage(@Path("channelId") channelId: String, @Path("messageId") messageId: String): Response<Unit>

    @DELETE("channels/{channelId}/pins/{messageId}")
    suspend fun unpinMessage(@Path("channelId") channelId: String, @Path("messageId") messageId: String): Response<Unit>

    // ── emojis.rs ───────────────────────────────────────
    /** Every emoji the caller can type, across all their servers. */
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

    // ── sounds.rs ───────────────────────────────────────
    @GET("servers/{serverId}/sounds")
    suspend fun listSounds(@Path("serverId") serverId: String): List<Sound>

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

    // ── uploads.rs ──────────────────────────────────────
    @Multipart
    @POST("uploads/image")
    suspend fun uploadImage(
        @Part file: MultipartBody.Part,
        @Query("kind") kind: String = "avatar",
    ): UploadResponse
}
