package lt.oranges.orangchat.data.remote

import lt.oranges.orangchat.BuildConfig
import okhttp3.Interceptor
import okhttp3.Response
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tells the server which build this is, and notices when it answers that the
 * build is no longer accepted (see services::update_policy on the server).
 *
 * The versionCode is sent rather than the versionName because it is the only
 * value guaranteed to increase with every release - two builds can share the
 * name "0.6.4", but never the code. A build that sends nothing is left alone by
 * the server, so this interceptor going missing degrades to no enforcement
 * rather than to a lockout.
 */
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
            // peekBody so the body is still there for whoever called this.
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
