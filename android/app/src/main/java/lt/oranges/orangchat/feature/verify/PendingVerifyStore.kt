package lt.oranges.orangchat.feature.verify

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import lt.oranges.orangchat.crypto.E2eeQr

@Singleton
class PendingVerifyStore @Inject constructor() {
    private val _code = MutableStateFlow<String?>(null)
    val code: StateFlow<String?> = _code.asStateFlow()

    fun offer(raw: String) {
        if (E2eeQr.kindOf(raw) != E2ee_VERIFY) return
        runCatching { E2eeQr.decodeContactVerify(raw) }.onSuccess { _code.value = raw }
    }

    fun consume() {
        _code.value = null
    }

    private companion object {
        const val E2ee_VERIFY = "verify"
    }
}
