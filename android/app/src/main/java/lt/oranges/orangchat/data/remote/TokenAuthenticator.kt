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
                RefreshResult.Rejected -> {
                    tokenStore.clear()
                    null
                }
                RefreshResult.Unreachable -> null
            }
        }
    }

    private sealed interface RefreshResult {
        data class Refreshed(val accessToken: String) : RefreshResult
        data object Rejected : RefreshResult
        data object Unreachable : RefreshResult
    }

    private fun runRefresh(): RefreshResult {
        return try {
            val url = baseUrlProvider.get().trimEnd('/') + "/auth/refresh"
            val req = Request.Builder()
                .url(url)
                .post(ByteArray(0).toRequestBody(null))
                .build()
            clientProvider.get().newCall(req).execute().use { resp ->
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
