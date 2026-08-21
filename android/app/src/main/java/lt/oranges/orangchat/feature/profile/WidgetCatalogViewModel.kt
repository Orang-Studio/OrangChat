package lt.oranges.orangchat.feature.profile

import androidx.compose.runtime.compositionLocalOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import lt.oranges.orangchat.data.model.ProfileWidget
import lt.oranges.orangchat.data.model.ProfileWidgetDefinition
import lt.oranges.orangchat.data.repository.WidgetCatalogRepository
import javax.inject.Inject

/**
 * Definitions by type, for every profile card in the tree. Empty until the
 * catalogue loads; the six built-in widgets render from their fallbacks in the
 * meantime, so a cold start never shows a blank card.
 */
val LocalWidgetCatalog = compositionLocalOf<Map<String, ProfileWidgetDefinition>> { emptyMap() }

data class WidgetCatalogState(
    val definitions: Map<String, ProfileWidgetDefinition> = emptyMap(),
    val ordered: List<ProfileWidgetDefinition> = emptyList(),
    val defaultLayout: List<ProfileWidget> = emptyList(),
)

@HiltViewModel
class WidgetCatalogViewModel @Inject constructor(
    private val repository: WidgetCatalogRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(WidgetCatalogState())
    val state: StateFlow<WidgetCatalogState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            repository.ensureLoaded()
            repository.catalog.collect { catalog ->
                _state.value = WidgetCatalogState(
                    definitions = catalog.widgets.associateBy { it.type },
                    ordered = catalog.widgets,
                    defaultLayout = catalog.defaultLayout,
                )
            }
        }
    }
}
