package lt.oranges.orangchat.data.remote

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import lt.oranges.orangchat.data.local.TokenStore
import lt.oranges.orangchat.data.model.AuthResult
import okhttp3.Authenticator
import okhttp3.MediaType
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

            return when (val result = runRefresh()) {
                is RefreshResult.Refreshed -> {
                    tokenStore.setAccessToken(result.accessToken)
                    response.request.newBuilder()
                        .header("Authorization", "Bearer ${result.accessToken}")
                        .build()
                }
                // The server rejected the refresh token itself: really signed out.
                RefreshResult.Rejected -> {
                    tokenStore.clear()
                    null
                }
                // Unreachable server, not a dead session. Give up on this request
                // and leave the credentials alone - clearing them here is what
                // turned a dropped connection into a sign-out.
                RefreshResult.Unreachable -> null
            }
        }
    }

    private sealed interface RefreshResult {
        data class Refreshed(val accessToken: String) : RefreshResult
        data object Rejected : RefreshResult
        data object Unreachable : RefreshResult
    }

    /** Blocking refresh - Authenticator runs off the main thread already. */
    private fun runRefresh(): RefreshResult {
        return try {
            val url = baseUrlProvider.get().trimEnd('/') + "/auth/refresh"
            val req = Request.Builder()
                .url(url)
                .post(ByteArray(0).toRequestBody(null))
                .build()
            clientProvider.get().newCall(req).execute().use { resp ->
                // Read once: for an error status this is the only chance, and
                // whether it is really ours is the whole question below.
                val contentType = resp.body?.contentType()
                val body = resp.body?.string()
                if (!resp.isSuccessful) return classifyFailure(resp.code, contentType, body)
                if (body == null) return RefreshResult.Unreachable
                val token = runCatching {
                    json.decodeFromString(AuthResult.serializer(), body).tokens.accessToken
                }.getOrNull() ?: return RefreshResult.Unreachable
                RefreshResult.Refreshed(token)
            }
        } catch (_: Exception) {
            RefreshResult.Unreachable
        }
    }

    /**
     * Whether a failed refresh really means the session is over.
     *
     * A status code alone is not enough, because we are not the only thing that
     * can answer this request. A captive portal, a corporate proxy, Cloudflare
     * mid-incident and an nginx `deny` all reply 401/403 with an HTML page, and
     * taking one of those at face value clears the refresh cookie - which is
     * what turned "opened the app after losing signal" into a permanent
     * sign-out that nobody on a stable connection could ever reproduce.
     *
     * So a rejection has to look like it came from us: our API answers this
     * route with 401 and a JSON body carrying an `error` field (see AppError's
     * IntoResponse). 403 never comes from `/auth/refresh` at all - the route
     * only ever raises Unauthorized - so one arriving here came from something
     * in the middle and is treated as a bad link, not a dead account.
     */
    private fun classifyFailure(
        code: Int,
        contentType: MediaType?,
        body: String?,
    ): RefreshResult {
        if (code != 401) return RefreshResult.Unreachable
        if (contentType?.subtype?.contains("json", ignoreCase = true) != true) {
            return RefreshResult.Unreachable
        }
        val ours = runCatching {
            json.parseToJsonElement(body ?: return@runCatching false)
                .jsonObject
                .containsKey("error")
        }.getOrDefault(false)
        return if (ours) RefreshResult.Rejected else RefreshResult.Unreachable
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
