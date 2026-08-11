package lt.oranges.orangchat.feature.invite

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PendingInviteStore @Inject constructor() {
    private val _code = MutableStateFlow<String?>(null)
    val code: StateFlow<String?> = _code.asStateFlow()

    fun offer(code: String) {
        _code.value = code
    }

    fun consume() {
        _code.value = null
    }
}
