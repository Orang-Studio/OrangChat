package lt.oranges.orangchat.feature.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import lt.oranges.orangchat.data.model.Message
import lt.oranges.orangchat.data.repository.ServerRepository
import javax.inject.Inject

data class SearchState(
    val query: String = "",
    val results: List<Message> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val hasMore: Boolean = false,
)

private const val PAGE_SIZE = 25
private const val DEBOUNCE_MS = 300L

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val serverRepository: ServerRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(SearchState())
    val state: StateFlow<SearchState> = _state.asStateFlow()

    private var searchJob: Job? = null
    private var offset = 0

    fun onQueryChange(serverId: String, query: String) {
        _state.value = _state.value.copy(query = query)
        // Restart the debounce: only the last keystroke should hit the server.
        searchJob?.cancel()
        if (query.isBlank()) {
            _state.value = _state.value.copy(results = emptyList(), hasMore = false, error = null)
            return
        }
        searchJob = viewModelScope.launch {
            delay(DEBOUNCE_MS)
            offset = 0
            runSearch(serverId, query, append = false)
        }
    }

    fun loadMore(serverId: String) {
        val current = _state.value
        if (current.loading || !current.hasMore || current.query.isBlank()) return
        searchJob = viewModelScope.launch { runSearch(serverId, current.query, append = true) }
    }

    private suspend fun runSearch(serverId: String, query: String, append: Boolean) {
        _state.value = _state.value.copy(loading = true, error = null)
        runCatching {
            serverRepository.searchMessages(
                serverId = serverId,
                query = query,
                limit = PAGE_SIZE,
                offset = offset,
            )
        }.onSuccess { page ->
            offset += page.items.size
            _state.value = _state.value.copy(
                results = if (append) _state.value.results + page.items else page.items,
                loading = false,
                // The server returns a next offset only while more remain.
                hasMore = page.nextCursor != null,
            )
        }.onFailure {
            _state.value = _state.value.copy(loading = false, error = it.message ?: "Search failed")
        }
    }
}
