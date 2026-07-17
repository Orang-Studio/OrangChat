package lt.oranges.orangchat.data.repository

import lt.oranges.orangchat.data.model.AuditLogEntry
import lt.oranges.orangchat.data.model.Channel
import lt.oranges.orangchat.data.model.Emoji
import lt.oranges.orangchat.data.model.InvitePreview
import lt.oranges.orangchat.data.model.Message
import lt.oranges.orangchat.data.model.Page
import lt.oranges.orangchat.data.model.Role
import lt.oranges.orangchat.data.model.Server
import lt.oranges.orangchat.data.model.ServerDetail
import lt.oranges.orangchat.data.model.ServerMember
import lt.oranges.orangchat.data.model.Sound
import lt.oranges.orangchat.data.model.UnreadState
import lt.oranges.orangchat.data.model.VoiceState
import lt.oranges.orangchat.data.remote.ApiService
import lt.oranges.orangchat.data.remote.BanRequest
import lt.oranges.orangchat.data.remote.CreateChannelRequest
import lt.oranges.orangchat.data.remote.CreateInviteRequest
import lt.oranges.orangchat.data.remote.CreateRoleRequest
import lt.oranges.orangchat.data.remote.CreateServerRequest
import lt.oranges.orangchat.data.remote.PatchChannelRequest
import lt.oranges.orangchat.data.remote.PositionEntry
import lt.oranges.orangchat.data.remote.RenameRequest
import lt.oranges.orangchat.data.remote.SetNicknameRequest
import lt.oranges.orangchat.data.remote.SetTimeoutRequest
import lt.oranges.orangchat.data.remote.UpdateRoleRequest
import lt.oranges.orangchat.data.remote.UpdateServerRequest
import lt.oranges.orangchat.data.remote.UpdateSoundRequest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject
import javax.inject.Singleton

/** Thin repository over the server / channel / message / role / member REST. */
@Singleton
class ServerRepository @Inject constructor(
    private val api: ApiService,
) {
    suspend fun listServers(): List<Server> = api.listServers()

    suspend fun createServer(name: String, iconUrl: String? = null): Server =
        api.createServer(CreateServerRequest(name, iconUrl))

    suspend fun getServer(serverId: String): ServerDetail = api.getServer(serverId)

    suspend fun updateServer(serverId: String, name: String? = null, iconUrl: String? = null): Server =
        api.updateServer(serverId, UpdateServerRequest(name, iconUrl))

    suspend fun deleteServer(serverId: String) { api.deleteServer(serverId) }

    suspend fun createChannel(serverId: String, name: String, type: String = "text", parentCategoryId: String? = null): Channel =
        api.createChannel(serverId, CreateChannelRequest(name = name, type = type, parentCategoryId = parentCategoryId))

    suspend fun createInvite(serverId: String, expiresInSeconds: Long? = null, maxUses: Int? = null) =
        api.createInvite(serverId, CreateInviteRequest(expiresInSeconds, maxUses))

    suspend fun joinInvite(code: String): Server = api.joinInvite(code)

    suspend fun invitePreview(code: String): InvitePreview = api.invitePreview(code)

    suspend fun myPermissions(serverId: String): String = api.myPermissions(serverId).permissions

    suspend fun getHistory(channelId: String, before: String? = null, limit: Int = 50): Page<Message> =
        api.getHistory(channelId, before, limit)

    suspend fun leaveServer(serverId: String) {
        api.leaveServer(serverId)
    }

    // Unread / read state
    suspend fun getUnreads(): List<UnreadState> = api.getUnreads()

    suspend fun markChannelRead(channelId: String) {
        api.markChannelRead(channelId)
    }

    /** Search a server's messages the viewer can see. Offset-paginated. */
    suspend fun searchMessages(
        serverId: String,
        query: String,
        channelId: String? = null,
        authorId: String? = null,
        limit: Int = 25,
        offset: Int = 0,
    ): Page<Message> = api.searchMessages(serverId, query, channelId, authorId, limit, offset)

    suspend fun getVoiceParticipants(channelId: String): List<VoiceState> =
        api.getVoiceParticipants(channelId)

    // Roles
    suspend fun createRole(serverId: String, name: String, color: Int?, permissions: String?): Role =
        api.createRole(serverId, CreateRoleRequest(name, color, permissions))

    suspend fun updateRole(serverId: String, roleId: String, patch: UpdateRoleRequest): Role =
        api.updateRole(serverId, roleId, patch)

    suspend fun deleteRole(serverId: String, roleId: String) { api.deleteRole(serverId, roleId) }

    suspend fun assignRole(serverId: String, userId: String, roleId: String): ServerMember =
        api.assignRole(serverId, userId, roleId)

    suspend fun unassignRole(serverId: String, userId: String, roleId: String): ServerMember =
        api.unassignRole(serverId, userId, roleId)

    // Members / moderation
    suspend fun setNickname(serverId: String, userId: String, nickname: String?): ServerMember =
        api.setNickname(serverId, userId, SetNicknameRequest(nickname))

    suspend fun kickMember(serverId: String, userId: String) { api.kickMember(serverId, userId) }

    suspend fun banMember(serverId: String, userId: String, reason: String? = null) {
        api.banMember(serverId, userId, BanRequest(reason))
    }

    suspend fun unbanMember(serverId: String, userId: String) { api.unbanMember(serverId, userId) }

    suspend fun timeoutMember(serverId: String, userId: String, durationSeconds: Long): ServerMember =
        api.setTimeout(serverId, userId, SetTimeoutRequest.of(durationSeconds))

    suspend fun liftTimeout(serverId: String, userId: String): ServerMember =
        api.setTimeout(serverId, userId, SetTimeoutRequest.lift())

    suspend fun reorderRoles(serverId: String, positions: List<PositionEntry>): List<Role> =
        api.reorderRoles(serverId, positions)

    suspend fun reorderChannels(serverId: String, positions: List<PositionEntry>): List<Channel> =
        api.reorderChannels(serverId, positions)

    suspend fun getAuditLog(serverId: String, limit: Int = 50, offset: Int = 0, action: String? = null): Page<AuditLogEntry> =
        api.getAuditLog(serverId, limit, offset, action)

    // Channel settings
    suspend fun patchChannel(channelId: String, patch: PatchChannelRequest): Channel =
        api.patchChannel(channelId, patch)

    suspend fun updateServerSettings(serverId: String, patch: UpdateServerRequest): Server =
        api.updateServer(serverId, patch)

    // Pins
    suspend fun listPins(channelId: String): List<Message> = api.listPins(channelId)

    suspend fun pinMessage(channelId: String, messageId: String) { api.pinMessage(channelId, messageId) }

    suspend fun unpinMessage(channelId: String, messageId: String) { api.unpinMessage(channelId, messageId) }

    // Expressions
    suspend fun listUsableEmojis(): List<Emoji> = api.listUsableEmojis()

    suspend fun listEmojis(serverId: String): List<Emoji> = api.listEmojis(serverId)

    suspend fun createEmoji(serverId: String, file: MultipartBody.Part, name: String): Emoji =
        api.createEmoji(serverId, file, name.toPlainBody())

    suspend fun renameEmoji(serverId: String, emojiId: String, name: String): Emoji =
        api.renameEmoji(serverId, emojiId, RenameRequest(name))

    suspend fun deleteEmoji(serverId: String, emojiId: String) { api.deleteEmoji(serverId, emojiId) }

    suspend fun listSounds(serverId: String): List<Sound> = api.listSounds(serverId)

    suspend fun createSound(serverId: String, file: MultipartBody.Part, name: String, emoji: String?): Sound =
        api.createSound(serverId, file, name.toPlainBody(), emoji?.toPlainBody())

    suspend fun updateSound(serverId: String, soundId: String, patch: UpdateSoundRequest): Sound =
        api.updateSound(serverId, soundId, patch)

    suspend fun deleteSound(serverId: String, soundId: String) { api.deleteSound(serverId, soundId) }
}

private fun String.toPlainBody(): RequestBody = toRequestBody("text/plain".toMediaType())
