package lt.oranges.orangchat.data.local

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import lt.oranges.orangchat.ui.theme.ThemePreference
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Persists the access token, the refresh cookie (via [cookiePrefs]), and app
 * preferences in EncryptedSharedPreferences. The access token is also mirrored
 * in memory so interceptors read it without disk hits on every request.
 * Constructed once by AppModule (@Provides @Singleton).
 */
class TokenStore(context: Context) {
    private val tokenListeners = CopyOnWriteArraySet<(String?) -> Unit>()

    private val prefs: SharedPreferences = run {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "orangchat_secure",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    /** Separate encrypted store the CookieJar owns for persisted cookies. */
    val cookiePrefs: SharedPreferences = prefs

    @Volatile
    var accessToken: String? = prefs.getString(KEY_ACCESS, null)
        private set

    fun setAccessToken(token: String?) {
        if (accessToken == token) return
        accessToken = token
        prefs.edit().apply {
            if (token == null) remove(KEY_ACCESS) else putString(KEY_ACCESS, token)
        }.apply()
        tokenListeners.forEach { it(token) }
    }

    fun addTokenListener(listener: (String?) -> Unit) {
        tokenListeners += listener
    }

    fun clear() {
        setAccessToken(null)
        prefs.edit().remove(KEY_ACCESS).remove(KEY_COOKIES).apply()
    }

    var themePreference: ThemePreference
        get() = runCatching { ThemePreference.valueOf(prefs.getString(KEY_THEME, ThemePreference.DARK.name)!!) }
            .getOrDefault(ThemePreference.DARK)
        set(value) = prefs.edit().putString(KEY_THEME, value.name).apply()

    /**
     * A content:// URI for a user-picked call ringtone, or null for the device
     * default. Device-local by design - the file is never uploaded, and only
     * this URI is stored, so nothing about it reaches the server.
     */
    var ringtoneUri: String?
        get() = prefs.getString(KEY_RINGTONE, null)
        set(value) = prefs.edit().apply {
            if (value == null) remove(KEY_RINGTONE) else putString(KEY_RINGTONE, value)
        }.apply()

    /** Display name for the picked ringtone, cached so settings need no query. */
    var ringtoneName: String?
        get() = prefs.getString(KEY_RINGTONE_NAME, null)
        set(value) = prefs.edit().apply {
            if (value == null) remove(KEY_RINGTONE_NAME) else putString(KEY_RINGTONE_NAME, value)
        }.apply()

    // ── Device-local preferences (never sent to the server) ──

    /** UI scale factor for accessibility, clamped 0.85–1.5 by the settings UI. */
    var fontScale: Float
        get() = prefs.getFloat(KEY_FONT_SCALE, 1f)
        set(value) = prefs.edit().putFloat(KEY_FONT_SCALE, value).apply()

    var reducedMotion: Boolean
        get() = prefs.getBoolean(KEY_REDUCED_MOTION, false)
        set(value) = prefs.edit().putBoolean(KEY_REDUCED_MOTION, value).apply()

    var compactMessages: Boolean
        get() = prefs.getBoolean(KEY_COMPACT, false)
        set(value) = prefs.edit().putBoolean(KEY_COMPACT, value).apply()

    /** Start calls with the mic muted. */
    var joinMuted: Boolean
        get() = prefs.getBoolean(KEY_JOIN_MUTED, false)
        set(value) = prefs.edit().putBoolean(KEY_JOIN_MUTED, value).apply()

    /** Start calls with the camera already publishing. */
    var joinWithVideo: Boolean
        get() = prefs.getBoolean(KEY_JOIN_VIDEO, false)
        set(value) = prefs.edit().putBoolean(KEY_JOIN_VIDEO, value).apply()

    companion object {
        const val KEY_ACCESS = "access_token"
        const val KEY_COOKIES = "cookies"
        const val KEY_THEME = "theme_pref"
        const val KEY_RINGTONE = "ringtone_uri"
        const val KEY_RINGTONE_NAME = "ringtone_name"
        const val KEY_FONT_SCALE = "pref_font_scale"
        const val KEY_REDUCED_MOTION = "pref_reduced_motion"
        const val KEY_COMPACT = "pref_compact_messages"
        const val KEY_JOIN_MUTED = "pref_join_muted"
        const val KEY_JOIN_VIDEO = "pref_join_video"
    }
}
