package lt.oranges.orangchat.data.remote

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UpdateGate @Inject constructor() {
    private val _upgradeRequired = MutableStateFlow(false)
    val upgradeRequired: StateFlow<Boolean> = _upgradeRequired.asStateFlow()

    @Volatile
    var latestVersion: String? = null
        private set

    fun onUpgradeRequired(latest: String?) {
        if (latest != null) latestVersion = latest
        _upgradeRequired.value = true
    }
}
