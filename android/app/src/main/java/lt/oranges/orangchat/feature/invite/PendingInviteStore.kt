package lt.oranges.orangchat.feature.invite

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * An invite link the app was opened with, parked until something can act on it.
 *
 * Deep links arrive at the Activity, but the join UI only exists inside the
 * authenticated shell — and a link tapped by a logged-out user has to survive
 * the whole sign-in flow before it can be honoured. Holding the code here rather
 * than in the intent means the shell can pick it up whenever it appears.
 */
@Singleton
class PendingInviteStore @Inject constructor() {
    private val _code = MutableStateFlow<String?>(null)
    val code: StateFlow<String?> = _code.asStateFlow()

    fun offer(code: String) {
        _code.value = code
    }

    /** Clear once the join sheet has it, so a rotation can't re-raise it. */
    fun consume() {
        _code.value = null
    }
}
