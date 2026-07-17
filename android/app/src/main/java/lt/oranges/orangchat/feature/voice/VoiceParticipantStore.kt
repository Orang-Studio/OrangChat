package lt.oranges.orangchat.feature.voice

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import lt.oranges.orangchat.data.model.VoiceState
import lt.oranges.orangchat.data.repository.ServerRepository
import lt.oranges.orangchat.realtime.SocketEvent
import lt.oranges.orangchat.realtime.SocketManager
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Who is sitting in which voice channel. Port of the web client's voice store
 * `participants` map, fed by `voice:state` and seeded per channel from
 * GET /channels/:id/voice.
 */
@Singleton
class VoiceParticipantStore @Inject constructor(
    private val socketManager: SocketManager,
    private val serverRepository: ServerRepository,
) {
    private val scope = CoroutineScope(SupervisorJob())

    private val _participants = MutableStateFlow<Map<String, Map<String, VoiceState>>>(emptyMap())
    /** channelId -> userId -> live voice state. */
    val participants: StateFlow<Map<String, Map<String, VoiceState>>> = _participants.asStateFlow()

    init {
        scope.launch {
            socketManager.events.collect { event ->
                if (event is SocketEvent.VoiceStateChanged) apply(event.state)
            }
        }
    }

    private fun apply(state: VoiceState) {
        val channel = _participants.value[state.channelId].orEmpty().toMutableMap()
        if (state.joined) channel[state.userId] = state else channel.remove(state.userId)
        _participants.value = _participants.value + (state.channelId to channel)
    }

    /** Seed a channel's roster; realtime events keep it current afterwards. */
    fun seed(channelId: String) {
        scope.launch {
            runCatching { serverRepository.getVoiceParticipants(channelId) }
                .onSuccess { list ->
                    _participants.value = _participants.value +
                        (channelId to list.associateBy { it.userId })
                }
        }
    }

    /** Seed every voice channel of a server at once, for the channel list. */
    fun seedAll(channelIds: List<String>) {
        channelIds.forEach(::seed)
    }
}
