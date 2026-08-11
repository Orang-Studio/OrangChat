package lt.oranges.orangchat.feature.invite

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import lt.oranges.orangchat.data.model.InvitePreview
import lt.oranges.orangchat.data.repository.ServerRepository
import javax.inject.Inject

@HiltViewModel
class ChatInviteViewModel @Inject constructor(
    private val serverRepository: ServerRepository,
    private val pendingInviteStore: PendingInviteStore,
) : ViewModel() {
    private val previews = mutableMapOf<String, MutableStateFlow<Result<InvitePreview>?>>()

    fun previewFor(code: String): StateFlow<Result<InvitePreview>?> =
        previews.getOrPut(code) {
            MutableStateFlow<Result<InvitePreview>?>(null).also { flow ->
                viewModelScope.launch {
                    flow.value = runCatching { serverRepository.invitePreview(code) }
                }
            }
        }

    fun open(code: String) = pendingInviteStore.offer(code)
}
