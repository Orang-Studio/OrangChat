package lt.oranges.orangchat.feature.settings

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import lt.oranges.orangchat.data.local.TokenStore
import lt.oranges.orangchat.ui.theme.ThemePreference
import javax.inject.Inject

@HiltViewModel
class ThemeViewModel @Inject constructor(
    private val tokenStore: TokenStore,
) : ViewModel() {
    private val _preference = MutableStateFlow(tokenStore.themePreference)
    val preference: StateFlow<ThemePreference> = _preference.asStateFlow()

    fun setPreference(pref: ThemePreference) {
        tokenStore.themePreference = pref
        _preference.value = pref
    }
}
