package lt.oranges.orangchat.feature.share

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import lt.oranges.orangchat.data.model.ChannelType
import lt.oranges.orangchat.data.model.Friend
import lt.oranges.orangchat.data.model.Server
import lt.oranges.orangchat.data.remote.AttachmentUploader
import lt.oranges.orangchat.data.repository.ServerRepository
import lt.oranges.orangchat.data.repository.SocialRepository
import lt.oranges.orangchat.realtime.SocketManager
import javax.inject.Inject

sealed interface ShareDestination {
    val id: String
    val title: String

    data class FriendDestination(val friend: Friend) : ShareDestination {
        override val id = "friend:${friend.user.id}"
        override val title = friend.user.displayName
    }

    data class ChannelDestination(
        val channelId: String,
        override val title: String,
        val subtitle: String,
    ) : ShareDestination {
        override val id = "channel:$channelId"
    }
}

data class ShareUiState(
    val loading: Boolean = true,
    val friends: List<Friend> = emptyList(),
    val channels: List<ShareDestination.ChannelDestination> = emptyList(),
    val sending: Boolean = false,
    val progress: Float = 0f,
    val error: String? = null,
    val sent: Boolean = false,
)

@HiltViewModel
class ShareViewModel @Inject constructor(
    private val socialRepository: SocialRepository,
    private val serverRepository: ServerRepository,
    private val uploader: AttachmentUploader,
    private val socketManager: SocketManager,
) : ViewModel() {
    private val _state = MutableStateFlow(ShareUiState())
    val state: StateFlow<ShareUiState> = _state.asStateFlow()
    private var dms = emptyList<lt.oranges.orangchat.data.model.Conversation>()

    fun load() {
        if (!_state.value.loading || _state.value.friends.isNotEmpty()) return
        viewModelScope.launch {
            runCatching {
                coroutineScope {
                    val friends = async { socialRepository.listFriends() }
                    val conversations = async { socialRepository.listDms() }
                    val servers = async { serverRepository.listServers() }
                    Triple(friends.await(), conversations.await(), servers.await())
                }
            }.onSuccess { (friends, conversations, servers) ->
                dms = conversations
                val dmDestinations = conversations
                    .sortedByDescending { it.lastMessageAt.orEmpty() }
                    .map { dm ->
                        val name = dm.name ?: dm.participants.joinToString { it.displayName }
                        ShareDestination.ChannelDestination(dm.id, name.ifBlank { "Direct message" }, "Direct message")
                    }
                val serverDestinations = loadServerChannels(servers)
                _state.value = ShareUiState(
                    loading = false,
                    friends = friends.sortedBy { it.user.displayName.lowercase() },
                    channels = (dmDestinations + serverDestinations).distinctBy { it.channelId },
                )
            }.onFailure {
                _state.value = ShareUiState(loading = false, error = it.message ?: "Could not load recipients")
            }
        }
    }

    private suspend fun loadServerChannels(servers: List<Server>) = coroutineScope {
        servers.map { server ->
            async {
                runCatching { serverRepository.getServer(server.id) }.getOrNull()
                    ?.channels.orEmpty()
                    .filter { it.type == ChannelType.TEXT }
                    .sortedBy { it.position }
                    .map { channel ->
                        ShareDestination.ChannelDestination(
                            channelId = channel.id,
                            title = "# ${channel.name ?: "channel"}",
                            subtitle = server.name,
                        )
                    }
            }
        }.awaitAll().flatten()
    }

    fun send(destination: ShareDestination, text: String, uris: List<Uri>) {
        if (_state.value.sending || (text.isBlank() && uris.isEmpty())) return
        viewModelScope.launch {
            _state.update { it.copy(sending = true, progress = 0f, error = null) }
            runCatching {
                require(uris.size <= AttachmentUploader.MAX_PER_MESSAGE) {
                    "You can share at most ${AttachmentUploader.MAX_PER_MESSAGE} attachments at once"
                }
                val attachments = uris.mapIndexed { index, uri ->
                    uploader.upload(uri) { fileProgress ->
                        _state.update {
                            it.copy(progress = (index + fileProgress) / uris.size.coerceAtLeast(1))
                        }
                    }
                }
                val channelId = when (destination) {
                    is ShareDestination.ChannelDestination -> destination.channelId
                    is ShareDestination.FriendDestination -> {
                        dms.firstOrNull { dm -> dm.participants.any { it.id == destination.friend.user.id } }?.id
                            ?: socialRepository.createDm(listOf(destination.friend.user.id)).id
                    }
                }
                socketManager.sendMessage(channelId, text.trim(), attachmentIds = attachments.map { it.id })
            }.onSuccess {
                _state.update { it.copy(sending = false, progress = 1f, sent = true) }
            }.onFailure { error ->
                _state.update { it.copy(sending = false, error = error.message ?: "Could not share") }
            }
        }
    }
}
