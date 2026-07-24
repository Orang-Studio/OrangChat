package lt.oranges.orangchat.feature.qrlogin

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A QR sign-in token the app was opened with, parked until the authenticated
 * shell can raise the confirm prompt.
 *
 * Mirrors [lt.oranges.orangchat.feature.invite.PendingInviteStore]: the deep
 * link lands at the Activity, but approving a web sign-in only makes sense once
 * this phone is itself signed in, so the token waits here until then.
 */
@Singleton
class PendingQrLoginStore @Inject constructor() {
    private val _token = MutableStateFlow<String?>(null)
    val token: StateFlow<String?> = _token.asStateFlow()

    fun offer(token: String) {
        _token.value = token
    }

    /** Clear once the prompt has it, so a rotation can't re-raise it. */
    fun consume() {
        _token.value = null
    }
}
