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

/** What the join sheet knows about the code currently typed or deep-linked. */
data class InviteUiState(
    val resolving: Boolean = false,
    val preview: InvitePreview? = null,
    /** The invite doesn't resolve at all — expired, revoked, or never existed. */
    val invalid: Boolean = false,
    val joining: Boolean = false,
    val error: String? = null,
)

/**
 * Resolving and accepting one invite code. Backs both the Join tab of the
 * add-server sheet and the sheet a deep link raises, so a tapped link and a
 * pasted link behave identically.
 */
@HiltViewModel
class InviteViewModel @Inject constructor(
    private val serverRepository: ServerRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(InviteUiState())
    val state: StateFlow<InviteUiState> = _state.asStateFlow()

    private var resolveJob: Job? = null
    private var resolvedCode: String? = null

    /**
     * Resolve a code, or clear back to empty when the input isn't one yet.
     * Re-resolving the same code is a no-op so that typing in the field doesn't
     * re-hit the API on every keystroke once it already matches.
     */
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

    /** Accept the resolved invite. [onJoined] receives the server to open. */
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

    /** Convenience for the text field: parse then resolve in one step. */
    fun onInputChanged(input: String) = resolve(InviteLink.parseInput(input))
}
