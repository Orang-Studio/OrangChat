package lt.oranges.orangchat.feature.home

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A conversation the app was asked to open, parked until the shell can show it.
 *
 * Notifications, conversation shortcuts and bubbles all arrive as an intent on
 * the Activity, but the chat only exists inside the authenticated shell - which
 * may still be signing in, or loading a server the channel belongs to. Holding
 * the id here rather than reading it back off the intent also means a rotation
 * cannot re-open a conversation the user has already navigated away from.
 *
 * Mirrors [lt.oranges.orangchat.feature.invite.PendingInviteStore].
 */
@Singleton
class PendingConversationStore @Inject constructor() {
    private val _channelId = MutableStateFlow<String?>(null)
    val channelId: StateFlow<String?> = _channelId.asStateFlow()

    fun offer(channelId: String) {
        _channelId.value = channelId
    }

    /** Clear once the shell has opened it. */
    fun consume() {
        _channelId.value = null
    }
}
