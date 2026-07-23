package lt.oranges.orangchat.realtime

import lt.oranges.orangchat.data.model.Channel
import lt.oranges.orangchat.data.model.DmCall
import lt.oranges.orangchat.data.model.DmCallEnded
import lt.oranges.orangchat.data.model.Friend
import lt.oranges.orangchat.data.model.FriendRequest
import lt.oranges.orangchat.data.model.Message
import lt.oranges.orangchat.data.model.Role
import lt.oranges.orangchat.data.model.Server
import lt.oranges.orangchat.data.model.ServerMember
import lt.oranges.orangchat.data.model.UnreadActivity
import lt.oranges.orangchat.data.model.User
import lt.oranges.orangchat.data.model.UserActivity
import lt.oranges.orangchat.data.model.VoiceState

/**
 * Typed mirror of ServerToClientEvents (packages/shared/src/events.ts). The raw
 * Socket.IO JSON payloads are decoded into these and pushed on a SharedFlow.
 */
sealed interface SocketEvent {
    data class MessageNew(val message: Message) : SocketEvent
    data class MessageUpdated(val message: Message) : SocketEvent
    data class MessageDeleted(val channelId: String, val messageId: String) : SocketEvent
    data class Typing(val channelId: String, val userId: String) : SocketEvent
    data class Presence(
        val userId: String,
        val status: String,
        val devices: List<String>,
        val activities: List<UserActivity>,
    ) : SocketEvent
    data class ReactionEvent(
        val channelId: String,
        val messageId: String,
        val emoji: String,
        val userId: String,
        val added: Boolean,
    ) : SocketEvent
    data class MemberJoined(val serverId: String, val member: ServerMember) : SocketEvent
    data class MemberUpdated(val serverId: String, val member: ServerMember) : SocketEvent
    data class MemberLeft(val serverId: String, val userId: String) : SocketEvent
    data class RoleCreated(val role: Role) : SocketEvent
    data class RoleUpdated(val role: Role) : SocketEvent
    data class RoleDeleted(val serverId: String, val roleId: String) : SocketEvent
    data class ChannelCreated(val channel: Channel) : SocketEvent
    data class ChannelUpdated(val channel: Channel) : SocketEvent
    data class ChannelDeleted(val serverId: String?, val channelId: String) : SocketEvent
    data class ServerUpdated(val server: Server) : SocketEvent
    data class ServerDeleted(val serverId: String) : SocketEvent
    data class UserUpdated(val user: User) : SocketEvent
    data class FriendRequestReceived(val request: FriendRequest) : SocketEvent
    data class FriendAccepted(val friend: Friend) : SocketEvent
    data class FriendRequestRemoved(val id: String) : SocketEvent
    data class FriendRemoved(val userId: String) : SocketEvent
    data class VoiceStateChanged(val state: VoiceState) : SocketEvent
    data class DmCallRinging(val call: DmCall) : SocketEvent
    data class DmCallAccepted(val call: DmCall) : SocketEvent
    data class DmCallFinished(val ended: DmCallEnded) : SocketEvent
    data class UnreadActivityEvent(val activity: UnreadActivity) : SocketEvent
    data class ChannelRead(val channelId: String) : SocketEvent
    /**
     * Carries the url and volume rather than only an id: the clip has to start
     * the moment it lands, and fetching first would make every punchline late.
     */
    data class SoundboardPlayed(
        val channelId: String,
        val soundId: String,
        val userId: String,
        val url: String,
        val volume: Double,
    ) : SocketEvent
    data class ConnectionState(val connected: Boolean, val reason: String? = null) : SocketEvent
}
