package lt.oranges.orangchat.notifications

import android.content.Context
import android.util.Log
import com.google.firebase.messaging.FirebaseMessaging
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.tasks.await
import lt.oranges.orangchat.data.local.TokenStore
import lt.oranges.orangchat.data.remote.ApiService
import lt.oranges.orangchat.data.remote.PushSubscriptionRequest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tells the server which device to push to.
 *
 * This is the single point of failure for the whole push path: a token the
 * server never received is a device it can never reach, and nothing about the
 * app looks broken until somebody notices they stopped getting notifications.
 * The inline attempts here cover a momentary blip; anything longer - signing in
 * on a train, a first launch before wifi associates - is handed to a job the
 * system runs when there is actually a network, because giving up after three
 * seconds and waiting for the next cold start is how a device goes quiet for
 * days.
 */
@Singleton
class PushTokenRegistrar @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: ApiService,
    private val tokenStore: TokenStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun registerCurrentToken() = scope.launch {
        if (!registerNow()) PushTokenJobService.schedule(context)
    }

    fun register(token: String) = scope.launch {
        if (!registerNow(token)) PushTokenJobService.schedule(context)
    }

    /**
     * Register the current (or given) token, reporting whether it landed. Never
     * throws: every caller's next move is to hand the work to the retry job, and
     * the reason it failed does not change that.
     */
    suspend fun registerNow(token: String? = null): Boolean {
        // Not signed in yet - there is no account to attach a device to. Sign-in
        // calls back here, so this is a no-op rather than a failure to retry.
        if (tokenStore.accessToken == null) return true
        val resolved = token ?: runCatching { FirebaseMessaging.getInstance().token.await() }
            .onFailure { Log.w(TAG, "could not obtain an FCM token", it) }
            .getOrNull()
            ?: return false

        repeat(INLINE_ATTEMPTS) { attempt ->
            val landed = runCatching {
                api.savePushSubscription(
                    PushSubscriptionRequest(kind = "fcm", endpoint = resolved, label = "Android"),
                ).isSuccessful
            }.getOrElse { error ->
                Log.w(TAG, "FCM registration attempt ${attempt + 1} failed", error)
                false
            }
            if (landed) {
                Log.i(TAG, "FCM token registered")
                return true
            }
            if (attempt < INLINE_ATTEMPTS - 1) delay(BACKOFF_MS shl attempt)
        }
        return false
    }

    private companion object {
        const val TAG = "PushTokenRegistrar"
        const val INLINE_ATTEMPTS = 3
        const val BACKOFF_MS = 1_000L
    }
}
