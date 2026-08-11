package lt.oranges.orangchat.data.local

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import lt.oranges.orangchat.ui.theme.ThemePreference
import java.util.concurrent.CopyOnWriteArraySet

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

    var cachedUser: String?
        get() = prefs.getString(KEY_USER, null)
        set(value) = prefs.edit().apply {
            if (value == null) remove(KEY_USER) else putString(KEY_USER, value)
        }.apply()

    fun clear() {
        setAccessToken(null)
        prefs.edit().remove(KEY_ACCESS).remove(KEY_COOKIES).remove(KEY_USER).apply()
    }

    var themePreference: ThemePreference
        get() = runCatching { ThemePreference.valueOf(prefs.getString(KEY_THEME, ThemePreference.DARK.name)!!) }
            .getOrDefault(ThemePreference.DARK)
        set(value) = prefs.edit().putString(KEY_THEME, value.name).apply()

    var ringtoneUri: String?
        get() = prefs.getString(KEY_RINGTONE, null)
        set(value) = prefs.edit().apply {
            if (value == null) remove(KEY_RINGTONE) else putString(KEY_RINGTONE, value)
        }.apply()

    var ringtoneName: String?
        get() = prefs.getString(KEY_RINGTONE_NAME, null)
        set(value) = prefs.edit().apply {
            if (value == null) remove(KEY_RINGTONE_NAME) else putString(KEY_RINGTONE_NAME, value)
        }.apply()


    var fontScale: Float
        get() = prefs.getFloat(KEY_FONT_SCALE, 1f)
        set(value) = prefs.edit().putFloat(KEY_FONT_SCALE, value).apply()

    var reducedMotion: Boolean
        get() = prefs.getBoolean(KEY_REDUCED_MOTION, false)
        set(value) = prefs.edit().putBoolean(KEY_REDUCED_MOTION, value).apply()

    var compactMessages: Boolean
        get() = prefs.getBoolean(KEY_COMPACT, false)
        set(value) = prefs.edit().putBoolean(KEY_COMPACT, value).apply()

    var joinMuted: Boolean
        get() = prefs.getBoolean(KEY_JOIN_MUTED, false)
        set(value) = prefs.edit().putBoolean(KEY_JOIN_MUTED, value).apply()

    var joinWithVideo: Boolean
        get() = prefs.getBoolean(KEY_JOIN_VIDEO, false)
        set(value) = prefs.edit().putBoolean(KEY_JOIN_VIDEO, value).apply()

    var notificationPreviews: Boolean
        get() = prefs.getBoolean(KEY_NOTIFICATION_PREVIEWS, true)
        set(value) = prefs.edit().putBoolean(KEY_NOTIFICATION_PREVIEWS, value).apply()

    var notificationPermissionAsked: Boolean
        get() = prefs.getBoolean(KEY_NOTIFICATION_ASKED, false)
        set(value) = prefs.edit().putBoolean(KEY_NOTIFICATION_ASKED, value).apply()

    companion object {
        const val KEY_ACCESS = "access_token"
        const val KEY_COOKIES = "cookies"
        const val KEY_USER = "cached_user"
        const val KEY_THEME = "theme_pref"
        const val KEY_RINGTONE = "ringtone_uri"
        const val KEY_RINGTONE_NAME = "ringtone_name"
        const val KEY_FONT_SCALE = "pref_font_scale"
        const val KEY_REDUCED_MOTION = "pref_reduced_motion"
        const val KEY_COMPACT = "pref_compact_messages"
        const val KEY_JOIN_MUTED = "pref_join_muted"
        const val KEY_JOIN_VIDEO = "pref_join_video"
        const val KEY_NOTIFICATION_PREVIEWS = "pref_notification_previews"
        const val KEY_NOTIFICATION_ASKED = "pref_notification_permission_asked"
    }
}
