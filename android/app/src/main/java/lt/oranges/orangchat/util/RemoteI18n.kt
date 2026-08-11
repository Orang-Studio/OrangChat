package lt.oranges.orangchat.util

import android.content.Context
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import lt.oranges.orangchat.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlin.time.Duration.Companion.hours

/** One language the server can serve, as listed by `/i18n/languages`. */
@Serializable
data class RemoteLanguage(val code: String, val endonym: String, val rev: String)

/** A language's full catalogue: resource name -> translated text. */
@Serializable
data class RemoteCatalog(
    val code: String,
    val endonym: String,
    val rev: String,
    val strings: Map<String, String> = emptyMap(),
)

/**
 * Server-served string catalogs, the Android half of the i18n mechanism the
 * backend exposes on `/i18n/`.
 *
 * The APK ships ten locales; this layer lets a translation fix - or a whole
 * new language - reach devices without an app release. It polls on launch and
 * then hourly: the language list (what the picker can offer) and the full
 * catalogue for whichever language is actually in use, with the server
 * answering 304 when the cached `rev` is current. Everything is persisted to
 * filesDir so an offline launch still speaks the last-known language.
 *
 * Deliberately a plain object rather than a Hilt singleton: string resolution
 * ([AppStrings]) can be called from any thread in any context, and that path
 * must not depend on a graph being built first. [init] is called once from
 * [lt.oranges.orangchat.OrangChatApp] before any activity attaches.
 */
object RemoteI18n {

    private const val PLATFORM = "android"

    private val json = Json { ignoreUnknownKeys = true }
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private lateinit var app: Context
    private val catalogs = ConcurrentHashMap<String, RemoteCatalog>()

    @Volatile
    private var languages: List<RemoteLanguage> = emptyList()

    private val dir: File
        get() = File(app.filesDir, "i18n")

    /** Loads the cached language list and the in-use catalog from disk. */
    fun init(application: Context) {
        app = application
        dir.mkdirs()
        runCatching {
            json.decodeFromString<List<RemoteLanguage>>(File(dir, "languages.json").readText())
        }.getOrNull()?.let { if (it.isNotEmpty()) languages = it }
        effectiveCodes().forEach { code ->
            runCatching {
                json.decodeFromString<RemoteCatalog>(File(dir, "catalog-$code.json").readText())
            }.getOrNull()?.let { catalogs[code] = it }
        }
    }

    /** Begins the launch-and-hourly poll loop. Idempotent; safe to call again. */
    fun start() {
        scope.launch {
            while (true) {
                refresh()
                delay(1.hours)
            }
        }
    }

    /**
     * Fires an immediate refresh without waiting for it. Used when the picker
     * chooses a language that may not have been fetched yet (a server-added
     * one, or the device language the user just switched to): the next screens
     * will speak it even though the hourly poll would have waited too long.
     */
    fun refreshNow() {
        scope.launch { refresh() }
    }

    /**
     * Fetches the language list and refreshes the catalogs in use. Failures are
     * swallowed so a poll in a tunnel or on a plane leaves the last good state
     * in place; the next poll retries.
     */
    suspend fun refresh() = withContext(Dispatchers.IO) {
        val fetched = runCatching { fetchLanguages() }.getOrNull()
        if (!fetched.isNullOrEmpty()) {
            languages = fetched
            runCatching {
                File(dir, "languages.json").writeText(json.encodeToString(fetched))
            }
        }
        effectiveCodes().forEach { code ->
            if (languages.none { it.code == code }) return@forEach
            runCatching { fetchCatalog(code) }
        }
    }

    /** Everything the picker can offer: the server list, refreshed hourly. */
    fun languages(): List<RemoteLanguage> = languages

    /**
     * The override strings for whatever language the interface is speaking
     * right now - the picked one, or the device language - or null when there
     * is nothing fetched for it (the bundled resources then stand alone).
     */
    fun catalogFor(context: Context): Map<String, String>? {
        val code = effectiveLanguage(context) ?: return null
        return catalogs[code]?.strings
    }

    /**
     * The language tag the interface is speaking: the stored pick, else the
     * device locale. Matches server catalogue codes (lowercased; the `-x-`
     * fun locales keep their extension, plain regions collapse to the base
     * language, like `pt-BR` -> `pt`).
     */
    fun effectiveLanguage(context: Context): String? =
        LocalePreferences.getTag(context) ?: systemCode()

    /** The languages actually wanted for display: the pick plus the device. */
    private fun effectiveCodes(): Set<String> {
        val codes = LinkedHashSet<String>()
        LocalePreferences.getTag(app)?.let(codes::add)
        systemCode()?.let(codes::add)
        return codes
    }

    private fun systemCode(): String? {
        val tag = Locale.getDefault().toLanguageTag().lowercase().trim()
        if (tag.isBlank()) return null
        val base = tag.substringBefore('-')
        val known = languages.map { it.code }
        return when {
            tag in known -> tag
            base in known -> base
            else -> base
        }
    }

    private fun fetchLanguages(): List<RemoteLanguage>? {
        val url = "${BuildConfig.API_BASE_URL}i18n/languages?platform=$PLATFORM"
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            return json.decodeFromString(response.body?.string().orEmpty())
        }
    }

    private fun fetchCatalog(code: String) {
        val cachedRev = catalogs[code]?.rev.orEmpty()
        val url = "${BuildConfig.API_BASE_URL}i18n/catalog?platform=$PLATFORM&lang=$code&rev=$cachedRev"
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            when {
                response.code == 304 -> return  // cached rev is current
                !response.isSuccessful -> return
                else -> {
                    val catalog = json.decodeFromString<RemoteCatalog>(response.body?.string().orEmpty())
                    catalogs[code] = catalog
                    runCatching {
                        File(dir, "catalog-$code.json").writeText(json.encodeToString(catalog))
                    }
                }
            }
        }
    }
}
