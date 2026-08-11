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

@Serializable
data class RemoteLanguage(val code: String, val endonym: String, val rev: String)

@Serializable
data class RemoteCatalog(
    val code: String,
    val endonym: String,
    val rev: String,
    val strings: Map<String, String> = emptyMap(),
)

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

    fun start() {
        scope.launch {
            while (true) {
                refresh()
                delay(1.hours)
            }
        }
    }

    fun refreshNow() {
        scope.launch { refresh() }
    }

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

    fun languages(): List<RemoteLanguage> = languages

    fun catalogFor(context: Context): Map<String, String>? {
        val code = effectiveLanguage(context) ?: return null
        return catalogs[code]?.strings
    }

    fun effectiveLanguage(context: Context): String? =
        LocalePreferences.getTag(context) ?: systemCode()

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
                response.code == 304 -> return
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
