package lt.oranges.orangchat.notifications

import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import android.util.Log
import kotlinx.coroutines.tasks.await
import lt.oranges.orangchat.data.local.TokenStore
import lt.oranges.orangchat.data.remote.ApiService
import lt.oranges.orangchat.data.remote.PushSubscriptionRequest
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PushTokenRegistrar @Inject constructor(
    private val api: ApiService,
    private val tokenStore: TokenStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun registerCurrentToken() = scope.launch {
        if (tokenStore.accessToken == null) return@launch
        runCatching {
            val token = FirebaseMessaging.getInstance().token.await()
            registerWithRetry(token)
        }.onFailure { Log.e(TAG, "Could not obtain/register FCM token", it) }
    }

    fun register(token: String) = scope.launch {
        if (tokenStore.accessToken != null) {
            runCatching { registerWithRetry(token) }
                .onFailure { Log.e(TAG, "Could not register rotated FCM token", it) }
        }
    }

    private suspend fun registerWithRetry(token: String) {
        var last: Throwable? = null
        repeat(3) { attempt ->
            try {
                val response = api.savePushSubscription(
                    PushSubscriptionRequest(kind = "fcm", endpoint = token, label = "Android"),
                )
                check(response.isSuccessful) {
                    "FCM registration failed with HTTP ${response.code()}"
                }
                Log.i(TAG, "FCM token registered")
                return
            } catch (error: Throwable) {
                last = error
                if (attempt < 2) delay(1_000L shl attempt)
            }
        }
        throw last ?: IllegalStateException("FCM registration failed")
    }

    private companion object { const val TAG = "PushTokenRegistrar" }
}
