package lt.oranges.orangchat.feature.invite

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import lt.oranges.orangchat.data.model.InvitePreview
import lt.oranges.orangchat.data.model.Server
import lt.oranges.orangchat.data.repository.ServerRepository
import lt.oranges.orangchat.util.InviteLink
import javax.inject.Inject

data class InviteUiState(
    val resolving: Boolean = false,
    val preview: InvitePreview? = null,
    val invalid: Boolean = false,
    val joining: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class InviteViewModel @Inject constructor(
    private val serverRepository: ServerRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(InviteUiState())
    val state: StateFlow<InviteUiState> = _state.asStateFlow()

    private var resolveJob: Job? = null
    private var resolvedCode: String? = null

    fun resolve(code: String?) {
        if (code == null) {
            resolveJob?.cancel()
            resolvedCode = null
            _state.value = InviteUiState()
            return
        }
        if (code == resolvedCode) return
        resolvedCode = code

        resolveJob?.cancel()
        resolveJob = viewModelScope.launch {
            _state.value = InviteUiState(resolving = true)
            runCatching { serverRepository.invitePreview(code) }
                .onSuccess { _state.value = InviteUiState(preview = it) }
                .onFailure { _state.value = InviteUiState(invalid = true) }
        }
    }

    fun join(onJoined: (Server) -> Unit) {
        val code = resolvedCode ?: return
        if (_state.value.joining) return
        viewModelScope.launch {
            _state.update { it.copy(joining = true, error = null) }
            runCatching { serverRepository.joinInvite(code) }
                .onSuccess {
                    _state.update { s -> s.copy(joining = false) }
                    onJoined(it)
                }
                .onFailure { e ->
                    _state.update { s -> s.copy(joining = false, error = e.message) }
                }
        }
    }

    fun reset() = resolve(null)

    fun onInputChanged(input: String) = resolve(InviteLink.parseInput(input))
}
