package lt.oranges.orangchat.data.repository

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import lt.oranges.orangchat.data.model.ProfileWidgetCatalog
import lt.oranges.orangchat.data.remote.ApiService
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The widget catalogue is server-owned so new widget types do not need a client
 * release. It is cached on disk and revalidated with `?rev=`, which answers
 * `304` when nothing changed; a failed refresh keeps the cached copy rather
 * than blanking everyone's profile.
 */
@Singleton
class WidgetCatalogRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: ApiService,
    private val json: Json,
) {
    private val _catalog = MutableStateFlow(ProfileWidgetCatalog())
    val catalog: StateFlow<ProfileWidgetCatalog> = _catalog.asStateFlow()

    private val mutex = Mutex()
    private var loaded = false

    private val cacheFile: File
        get() = File(context.filesDir, "profile-widgets.json")

    suspend fun ensureLoaded() = mutex.withLock {
        if (loaded) return@withLock
        loaded = true
        withContext(Dispatchers.IO) {
            runCatching {
                json.decodeFromString<ProfileWidgetCatalog>(cacheFile.readText())
            }.getOrNull()?.let { _catalog.value = it }

            runCatching {
                val response = api.getWidgetCatalog(_catalog.value.rev.takeIf { it.isNotBlank() })
                if (response.code() == 304) return@runCatching
                val fresh = response.body() ?: return@runCatching
                _catalog.value = fresh
                runCatching { cacheFile.writeText(json.encodeToString(fresh)) }
            }
        }
    }
}
