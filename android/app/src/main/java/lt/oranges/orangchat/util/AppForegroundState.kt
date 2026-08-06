package lt.oranges.orangchat.util

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner

/**
 * Process-wide record of what the user can actually see: whether the app is
 * foreground at all, and which conversation is on screen if it is.
 *
 * Both the socket path (AppViewModel.maybeNotify) and the push path (FcmService)
 * consult this, and they have to agree. A push dropped merely because the app is
 * foreground - while the socket that was supposed to cover for it is mid-
 * reconnect - is a message the user is never told about at all, and that is the
 * single largest reason notifications used to go missing. So the only thing
 * either path suppresses is a notification for the conversation already open in
 * front of the user.
 *
 * In memory on purpose. FcmService runs in this same process, so a push arriving
 * after the process was torn down reads exactly what is true then: nothing
 * foreground, no conversation open.
 */
object AppForegroundState {
    @Volatile
    var isForeground: Boolean = false
        private set

    /** The conversation on screen, or null when none is. */
    @Volatile
    var visibleChannelId: String? = null
        private set

    fun register() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) { isForeground = true }
            override fun onStop(owner: LifecycleOwner) {
                isForeground = false
                // Backgrounded with a chat still open means the user is not
                // reading it, so anything for that channel is worth showing.
                visibleChannelId = null
            }
        })
    }

    fun setVisibleChannel(channelId: String?) {
        visibleChannelId = channelId
    }

    /**
     * Whether a notification for [channelId] would be telling the user something
     * they are already looking at.
     */
    fun isOnScreen(channelId: String): Boolean =
        isForeground && visibleChannelId == channelId
}
