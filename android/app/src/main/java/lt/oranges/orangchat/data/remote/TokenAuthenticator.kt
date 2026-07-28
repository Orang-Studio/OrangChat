package lt.oranges.orangchat.data.remote

import kotlinx.serialization.json.Json
import lt.oranges.orangchat.data.local.TokenStore
import lt.oranges.orangchat.data.model.AuthResult
import okhttp3.Authenticator
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.Route
import javax.inject.Provider

/**
 * On a 401 (outside the auth routes), refreshes the session once via POST
 * /auth/refresh
 * - which reads the refresh cookie (carried by the CookieJar) and returns a
 * fresh access token - then retries the original request with it. Mirrors the
 * web client's request() refresh-and-retry in lib/api.ts. Constructed in
 * NetworkModule (not @Inject) so it can bind the qualified refresh client.
 */
class TokenAuthenticator(
    private val tokenStore: TokenStore,
    private val baseUrlProvider: Provider<String>,
    private val clientProvider: Provider<OkHttpClient>,
    private val json: Json,
) : Authenticator {

    override fun authenticate(route: Route?, response: Response): Request? {
        val path = response.request.url.encodedPath
        if (path.contains("/auth/")) return null
        if (responseCount(response) >= 2) return null

        synchronized(this) {
            val current = tokenStore.accessToken
            // Another thread may have refreshed while we waited on the lock.
            val sent = response.request.header("Authorization")?.removePrefix("Bearer ")
            if (current != null && current != sent) {
                return response.request.newBuilder()
                    .header("Authorization", "Bearer $current")
                    .build()
            }

            val newAccess = runRefresh() ?: run {
                tokenStore.clear()
                return null
            }
            tokenStore.setAccessToken(newAccess)
            return response.request.newBuilder()
                .header("Authorization", "Bearer $newAccess")
                .build()
        }
    }

    /** Blocking refresh - Authenticator runs off the main thread already. */
    private fun runRefresh(): String? {
        return try {
            val url = baseUrlProvider.get().trimEnd('/') + "/auth/refresh"
            val req = Request.Builder()
                .url(url)
                .post(ByteArray(0).toRequestBody(null))
                .build()
            clientProvider.get().newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body?.string() ?: return null
                json.decodeFromString(AuthResult.serializer(), body).tokens.accessToken
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun responseCount(response: Response): Int {
        var count = 1
        var prior = response.priorResponse
        while (prior != null) {
            count++
            prior = prior.priorResponse
        }
        return count
    }
}
