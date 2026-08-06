package lt.oranges.orangchat.data.repository

import lt.oranges.orangchat.data.model.Channel
import lt.oranges.orangchat.data.model.Conversation
import lt.oranges.orangchat.data.model.Friend
import lt.oranges.orangchat.data.model.FriendRequestsEnvelope
import lt.oranges.orangchat.data.remote.ApiService
import lt.oranges.orangchat.data.remote.ChannelBackgroundRequest
import lt.oranges.orangchat.data.remote.CreateDmRequest
import lt.oranges.orangchat.data.remote.SendFriendRequest
import lt.oranges.orangchat.data.remote.SendFriendResult
import javax.inject.Inject
import javax.inject.Singleton

/** DMs + friends REST. */
@Singleton
class SocialRepository @Inject constructor(
    private val api: ApiService,
) {
    // DMs
    suspend fun listDms(): List<Conversation> = api.listDms()

    suspend fun leaveDm(channelId: String) { api.leaveDm(channelId) }

    suspend fun createDm(userIds: List<String>): Conversation =
        api.createDm(CreateDmRequest(userIds))

    suspend fun addDmParticipants(channelId: String, userIds: List<String>): Conversation =
        api.addDmParticipants(channelId, CreateDmRequest(userIds))

    /** Set a DM's shared background; [url] null clears it. Plaintext like avatars. */
    suspend fun setDmBackground(channelId: String, url: String?): Channel =
        api.putChannelBackground(channelId, ChannelBackgroundRequest(url))

    // Friends
    suspend fun listFriends(): List<Friend> = api.listFriends()

    suspend fun listRequests(): FriendRequestsEnvelope = api.listFriendRequests()

    suspend fun sendRequest(username: String): SendFriendResult =
        api.sendFriendRequest(SendFriendRequest(username))

    suspend fun acceptRequest(id: String): Friend = api.acceptFriendRequest(id)

    suspend fun declineRequest(id: String) { api.deleteFriendRequest(id) }

    suspend fun removeFriend(userId: String) { api.removeFriend(userId) }
}
