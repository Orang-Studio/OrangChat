package lt.oranges.orangchat.feature.verify

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import lt.oranges.orangchat.crypto.E2eeQr

/**
 * A contact-verification code the app was opened with (docs/E2EE.md §6.7).
 *
 * Scanning happens in the phone's own camera app, which opens the
 * `orangchat://verify?...` deep link - the same route QR sign-in already takes.
 * That keeps a camera dependency out of the app and, more usefully, means the
 * scanner is one the user already trusts.
 *
 * The code is parked rather than acted on: pinning somebody's identity only
 * means anything once this phone is signed in, and the confirm tap is the
 * security step.
 */
@Singleton
class PendingVerifyStore @Inject constructor() {
    private val _code = MutableStateFlow<String?>(null)
    val code: StateFlow<String?> = _code.asStateFlow()

    /** Accepts a raw deep link, keeping only a well-formed verification code. */
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
