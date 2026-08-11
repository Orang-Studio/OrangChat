package lt.oranges.orangchat.feature.chat

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class MessageDraftViewModel @Inject constructor(
    private val store: MessageDraftStore,
) : ViewModel() {
    suspend fun load(channelId: String): String = store.load(channelId)
    fun save(channelId: String, content: String) = store.save(channelId, content)
    fun saveNow(channelId: String, content: String) = store.saveNow(channelId, content)
    fun clear(channelId: String) = store.clear(channelId)
}
