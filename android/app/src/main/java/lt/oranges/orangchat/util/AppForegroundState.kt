package lt.oranges.orangchat.util

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner

/**
 * Process-wide foreground flag. Registered once from OrangChatApp against
 * ProcessLifecycleOwner. Notifications only fire when the app is NOT foreground
 * (or the relevant chat isn't focused).
 */
object AppForegroundState {
    @Volatile
    var isForeground: Boolean = false
        private set

    fun register() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) { isForeground = true }
            override fun onStop(owner: LifecycleOwner) { isForeground = false }
        })
    }
}
