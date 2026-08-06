package lt.oranges.orangchat.feature.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import lt.oranges.orangchat.data.local.OfflineCache
import lt.oranges.orangchat.data.model.Message
import lt.oranges.orangchat.data.model.User
import lt.oranges.orangchat.data.repository.AuthRepository
import lt.oranges.orangchat.data.repository.E2eeRepository
import lt.oranges.orangchat.data.repository.ServerRepository

data class SearchResult(
    val id: String,
    val channelId: String,
    val authorId: String,
    val author: User? = null,
    val content: String,
    val createdAt: String,
)

data class SearchState(
    val query: String = "",
    val results: List<SearchResult> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val hasMore: Boolean = false,
    /** True for a DM search or when an unreachable server fell back to disk. */
    val searchedLocally: Boolean = false,
)

private const val PAGE_SIZE = 25
private const val LOCAL_LIMIT = 100
private const val DEBOUNCE_MS = 300L

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val serverRepository: ServerRepository,
    private val e2eeRepository: E2eeRepository,
    private val offlineCache: OfflineCache,
    private val authRepository: AuthRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(SearchState())
    val state: StateFlow<SearchState> = _state.asStateFlow()

    private var searchJob: Job? = null
    private var offset = 0

    fun reset() {
        searchJob?.cancel()
        offset = 0
        _state.value = SearchState()
    }

    fun onQueryChange(serverId: String?, channelIds: Set<String>, query: String) {
        _state.value = _state.value.copy(query = query)
        searchJob?.cancel()
        if (query.isBlank()) {
            _state.value = SearchState(query = query)
            return
        }
        searchJob = viewModelScope.launch {
            delay(DEBOUNCE_MS)
            offset = 0
            runSearch(serverId, channelIds, query, append = false)
        }
    }

    fun loadMore(serverId: String?, channelIds: Set<String>) {
        val current = _state.value
        if (
            serverId == null || current.searchedLocally || current.loading ||
            !current.hasMore || current.query.isBlank()
        ) return
        searchJob = viewModelScope.launch {
            runSearch(serverId, channelIds, current.query, append = true)
        }
    }

    private suspend fun runSearch(
        serverId: String?,
        channelIds: Set<String>,
        query: String,
        append: Boolean,
    ) {
        _state.value = _state.value.copy(loading = true, error = null)
        if (serverId == null) {
            val results = localResults(query, channelIds)
            _state.value = _state.value.copy(
                results = results,
                loading = false,
                hasMore = false,
                searchedLocally = true,
            )
            return
        }

        try {
            val page = serverRepository.searchMessages(
                serverId = serverId,
                query = query,
                channelId = channelIds.singleOrNull(),
                limit = PAGE_SIZE,
                offset = offset,
            )
            offset += page.items.size
            val pageResults = page.items.map(Message::toSearchResult)
            _state.value = _state.value.copy(
                results = mergeSearchResults(_state.value.results, pageResults, append),
                loading = false,
                hasMore = page.nextCursor != null,
                searchedLocally = false,
            )
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            // The cached history is useful both as an offline fallback and for
            // encrypted rows the server cannot inspect. It is intentionally a
            // substring search, matching the browser's local index.
            val fallback = localResults(query, channelIds)
            _state.value = _state.value.copy(
                results = fallback,
                loading = false,
                error = null,
                hasMore = false,
                searchedLocally = true,
            )
        }
    }

    private suspend fun localResults(query: String, channelIds: Set<String>): List<SearchResult> {
        val decrypted = runCatching {
            e2eeRepository.searchLocal(query, channelIds, LOCAL_LIMIT)
        }.getOrDefault(emptyList()).map {
            SearchResult(
                id = it.id,
                channelId = it.channelId,
                authorId = it.authorId,
                content = it.text,
                createdAt = it.createdAt,
            )
        }
        val userId = authRepository.currentUser?.id
        val offline = if (userId == null) emptyList() else {
            runCatching {
                offlineCache.searchMessages(userId, query, channelIds, LOCAL_LIMIT)
            }.getOrDefault(emptyList()).map(Message::toSearchResult)
        }
        return mergeLocalResults(decrypted, offline, LOCAL_LIMIT)
    }
}

/** Offset pages can overlap when the index changes between requests. */
internal fun mergeSearchResults(
    existing: List<SearchResult>,
    page: List<SearchResult>,
    append: Boolean,
): List<SearchResult> = (if (append) existing + page else page).distinctBy(SearchResult::id)

private fun Message.toSearchResult() = SearchResult(
    id = id,
    channelId = channelId,
    authorId = author.id,
    author = author,
    content = content,
    createdAt = createdAt,
)

/** Full offline rows replace legacy text-only E2EE hits with the same id. */
internal fun mergeLocalResults(
    decrypted: List<SearchResult>,
    offline: List<SearchResult>,
    limit: Int = LOCAL_LIMIT,
): List<SearchResult> = (decrypted + offline)
    .associateBy(SearchResult::id)
    .values
    .sortedByDescending(SearchResult::createdAt)
    .take(limit)
