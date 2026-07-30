package lt.oranges.orangchat.data.remote

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Set once the server has refused this build outright (HTTP 426).
 *
 * Held here rather than surfaced as an error on each call because a retired
 * build fails *every* request: without a single place to notice it, the user
 * would get a screenful of unrelated "couldn't load" messages and no
 * explanation. The flag is deliberately one-way - nothing clears it short of
 * installing a new build, so a later request that happens to succeed cannot
 * take the wall back down.
 */
@Singleton
class UpdateGate @Inject constructor() {
    private val _upgradeRequired = MutableStateFlow(false)
    val upgradeRequired: StateFlow<Boolean> = _upgradeRequired.asStateFlow()

    /** Newest published versionName, when the server told us one. */
    @Volatile
    var latestVersion: String? = null
        private set

    fun onUpgradeRequired(latest: String?) {
        if (latest != null) latestVersion = latest
        _upgradeRequired.value = true
    }
}
