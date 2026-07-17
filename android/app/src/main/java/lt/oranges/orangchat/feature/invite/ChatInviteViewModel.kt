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

/**
 * Resolves the invite links appearing in chat. One instance backs every embed on
 * screen, keyed by code, so the same invite posted three times costs one request
 * and a scroll back to it costs none.
 *
 * Null means still resolving; a failed Result means the link is dead.
 */
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

    /** Hand the code to the shell, which raises the confirm sheet over it. */
    fun open(code: String) = pendingInviteStore.offer(code)
}
