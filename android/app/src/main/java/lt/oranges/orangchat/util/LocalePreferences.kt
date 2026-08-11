package lt.oranges.orangchat.util

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import java.util.Locale

/**
 * Device-local language choice. The server never sees this preference.
 *
 * The stored value is a BCP-47 tag: one of the bundled languages below, or -
 * once the server-added language mechanism delivers it - any code the server
 * published (e.g. "de"), which this app cannot resolve through Android
 * resources and therefore serves purely from [RemoteI18n]'s catalogues via
 * [AppStrings].
 */
enum class AppLanguage(val storageValue: String) {
    SYSTEM("system"),
    ENGLISH("en"),
    LITHUANIAN("lt"),
    CHINESE("zh"),
    HINDI("hi"),
    SPANISH("es"),
    ARABIC("ar"),
    FRENCH("fr"),
    BENGALI("bn"),
    PORTUGUESE("pt"),
    RUSSIAN("ru"),
    URDU("ur"),
    ;
}

/** The picker's label for each bundled language, in its own tongue - never translated. */
fun AppLanguage.labelRes(): Int = when (this) {
    AppLanguage.SYSTEM -> lt.oranges.orangchat.R.string.language_system
    AppLanguage.ENGLISH -> lt.oranges.orangchat.R.string.language_english
    AppLanguage.LITHUANIAN -> lt.oranges.orangchat.R.string.language_lithuanian
    AppLanguage.CHINESE -> lt.oranges.orangchat.R.string.language_chinese
    AppLanguage.HINDI -> lt.oranges.orangchat.R.string.language_hindi
    AppLanguage.SPANISH -> lt.oranges.orangchat.R.string.language_spanish
    AppLanguage.ARABIC -> lt.oranges.orangchat.R.string.language_arabic
    AppLanguage.FRENCH -> lt.oranges.orangchat.R.string.language_french
    AppLanguage.BENGALI -> lt.oranges.orangchat.R.string.language_bengali
    AppLanguage.PORTUGUESE -> lt.oranges.orangchat.R.string.language_portuguese
    AppLanguage.RUSSIAN -> lt.oranges.orangchat.R.string.language_russian
    AppLanguage.URDU -> lt.oranges.orangchat.R.string.language_urdu
}

object LocalePreferences {
    private const val FILE = "orangchat_preferences"
    private const val LANGUAGE = "language"

    /**
     * The stored language tag, or null when following the system. A code the
     * server added after this APK shipped is returned as-is - it is not in
     * [AppLanguage], but it is a perfectly valid choice.
     */
    fun getTag(context: Context): String? =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getString(LANGUAGE, null)
            ?.takeIf { it != AppLanguage.SYSTEM.storageValue }

    /** Select a language by its BCP-47 tag - bundled or server-provided. */
    fun set(context: Context, tag: String) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .putString(LANGUAGE, tag)
            .apply()
    }

    /** Follow the device language again. */
    fun setSystem(context: Context) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .putString(LANGUAGE, AppLanguage.SYSTEM.storageValue)
            .apply()
    }

    /** Applies the selected language to an Activity before Compose is created. */
    fun localizedContext(base: Context): Context {
        val tag = getTag(base) ?: return base
        val locale = Locale.forLanguageTag(tag)
        Locale.setDefault(locale)
        val configuration = Configuration(base.resources.configuration)
        configuration.setLocale(locale)
        configuration.setLocales(LocaleList(locale))
        return base.createConfigurationContext(configuration)
    }

    /**
     * What the picker and the settings summary should call a choice: the
     * system row, a bundled language's own label, or the server's endonym for
     * a language this build never knew. The bare code is the last resort, so a
     * label always exists.
     */
    fun label(context: Context, tag: String?): String {
        if (tag == null) return context.getString(lt.oranges.orangchat.R.string.language_system)
        AppLanguage.entries.firstOrNull { it.storageValue == tag }?.let {
            return context.getString(it.labelRes())
        }
        return RemoteI18n.languages().firstOrNull { it.code == tag }?.endonym ?: tag
    }
}
