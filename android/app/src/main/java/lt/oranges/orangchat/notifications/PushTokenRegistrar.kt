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

    suspend fun registerNow(token: String? = null): Boolean {
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
