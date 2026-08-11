package lt.oranges.orangchat.data.remote

import lt.oranges.orangchat.BuildConfig
import okhttp3.Interceptor
import okhttp3.Response
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ClientVersionInterceptor @Inject constructor(
    private val updateGate: UpdateGate,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(
            chain.request().newBuilder()
                .header("X-Client-Platform", "android")
                .header("X-Client-Version", BuildConfig.VERSION_CODE.toString())
                .build(),
        )
        if (response.code == HTTP_UPGRADE_REQUIRED) {
            val latest = runCatching {
                JSONObject(response.peekBody(PEEK_LIMIT).string()).optString("latest")
            }.getOrNull()?.takeIf { it.isNotBlank() }
            updateGate.onUpgradeRequired(latest)
        }
        return response
    }

    private companion object {
        const val HTTP_UPGRADE_REQUIRED = 426
        const val PEEK_LIMIT = 4096L
    }
}
