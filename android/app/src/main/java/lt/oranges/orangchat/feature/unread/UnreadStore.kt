package lt.oranges.orangchat.feature.unread

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import lt.oranges.orangchat.data.model.UNREAD_COUNT_CAP
import lt.oranges.orangchat.data.model.UnreadState
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Unread dots and mention badges. Port of the web client's stores/unread.ts.
 *
 * Server-side truth arrives from GET /me/unreads on start and `unread:activity`
 * thereafter; the channel the user is looking at is never marked unread.
 */
@Singleton
class UnreadStore @Inject constructor() {

    private val _states = MutableStateFlow<Map<String, UnreadState>>(emptyMap())
    /** channelId -> unread state. */
    val states: StateFlow<Map<String, UnreadState>> = _states.asStateFlow()

    /** The channel on screen: activity here is read immediately, never badged. */
    @Volatile
    var activeChannelId: String? = null
        private set

    fun setActiveChannel(channelId: String?) {
        activeChannelId = channelId
        channelId?.let { markRead(it) }
    }

    fun hydrate(list: List<UnreadState>) {
        _states.value = list
            .filterNot { it.channelId == activeChannelId }
            .associateBy { it.channelId }
    }

    /** Fold in a new message's activity. [mentionsMe] bumps the badge count. */
    fun onActivity(channelId: String, serverId: String?, mentionsMe: Boolean) {
        if (channelId == activeChannelId) return
        _states.update(channelId) { current ->
            (current ?: UnreadState(channelId = channelId, serverId = serverId)).copy(
                unread = true,
                unreadCount = minOf((current?.unreadCount ?: 0) + 1, UNREAD_COUNT_CAP),
                mentionCount = (current?.mentionCount ?: 0) + if (mentionsMe) 1 else 0,
            )
        }
    }

    fun markRead(channelId: String) {
        _states.update(channelId) { current ->
            current?.copy(unread = false, unreadCount = 0, mentionCount = 0)
        }
    }

    fun clear() {
        _states.value = emptyMap()
    }

    fun isUnread(channelId: String): Boolean = _states.value[channelId]?.unread == true

    fun mentionCount(channelId: String): Int = _states.value[channelId]?.mentionCount ?: 0

    fun unreadCount(channelId: String): Int = _states.value[channelId]?.unreadCount ?: 0

    private inline fun MutableStateFlow<Map<String, UnreadState>>.update(
        channelId: String,
        transform: (UnreadState?) -> UnreadState?,
    ) {
        val next = transform(value[channelId]) ?: return
        this.value = value + (channelId to next)
    }
}

/** Total mentions across a server, for the rail badge. */
fun Map<String, UnreadState>.mentionsForServer(serverId: String): Int =
    values.filter { it.serverId == serverId }.sumOf { it.mentionCount }

/** Any unread channel in a server, for the rail dot. */
fun Map<String, UnreadState>.hasUnreadInServer(serverId: String): Boolean =
    values.any { it.serverId == serverId && it.unread }

/** Total unread DM *messages* (not conversations) for the home button. */
fun Map<String, UnreadState>.unreadDmCount(): Int =
    minOf(values.filter { it.serverId == null }.sumOf { it.unreadCount }, UNREAD_COUNT_CAP)
