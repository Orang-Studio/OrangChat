package lt.oranges.orangchat.feature.home

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PendingConversationStore @Inject constructor() {
    private val _channelId = MutableStateFlow<String?>(null)
    val channelId: StateFlow<String?> = _channelId.asStateFlow()

    fun offer(channelId: String) {
        _channelId.value = channelId
    }

    fun consume() {
        _channelId.value = null
    }
}
