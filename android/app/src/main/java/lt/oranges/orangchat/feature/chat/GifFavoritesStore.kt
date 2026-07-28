package lt.oranges.orangchat.feature.chat

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Saved GIFs, kept on the device.
 *
 * The whole [KlipyGif] is stored, not just its slug: KLIPY has no
 * fetch-by-slug endpoint, so a favorites tab that only remembered ids would
 * have nothing to render until the user searched for the same GIF again.
 */
@Singleton
class GifFavoritesStore @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("klipy", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    private val mutableFavorites = MutableStateFlow(read())
    val favorites: StateFlow<List<KlipyGif>> = mutableFavorites.asStateFlow()

    fun isFavorite(slug: String): Boolean = mutableFavorites.value.any { it.slug == slug }

    fun toggle(gif: KlipyGif) {
        val current = mutableFavorites.value
        // Newest first: the one you just saved is the one you want to reach for.
        val next = if (current.any { it.slug == gif.slug }) {
            current.filterNot { it.slug == gif.slug }
        } else {
            listOf(gif) + current
        }
        mutableFavorites.value = next
        prefs.edit().putString(KEY_FAVORITES, json.encodeToString(next)).apply()
    }

    // A store written by an older build, or hand-edited, is not worth crashing
    // over - an empty favorites tab is recoverable, a boot loop is not.
    private fun read(): List<KlipyGif> = runCatching {
        prefs.getString(KEY_FAVORITES, null)?.let { json.decodeFromString<List<KlipyGif>>(it) }
    }.getOrNull().orEmpty()

    private companion object {
        const val KEY_FAVORITES = "favorite_gifs"
    }
}
