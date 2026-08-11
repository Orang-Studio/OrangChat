package lt.oranges.orangchat.util

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner

object AppForegroundState {
    @Volatile
    var isForeground: Boolean = false
        private set

    @Volatile
    var visibleChannelId: String? = null
        private set

    fun register() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) { isForeground = true }
            override fun onStop(owner: LifecycleOwner) {
                isForeground = false
                visibleChannelId = null
            }
        })
    }

    fun setVisibleChannel(channelId: String?) {
        visibleChannelId = channelId
    }

    fun isOnScreen(channelId: String): Boolean =
        isForeground && visibleChannelId == channelId
}
