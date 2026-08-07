package lt.oranges.orangchat.feature.chat

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import lt.oranges.orangchat.ui.components.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import lt.oranges.orangchat.util.EmojiRef
import lt.oranges.orangchat.util.absoluteUrl
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import lt.oranges.orangchat.BuildConfig
import lt.oranges.orangchat.ui.theme.OrangRadius
import lt.oranges.orangchat.ui.theme.OrangTheme
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Named

@Serializable
data class KlipyGif(
    val slug: String,
    val title: String,
    val previewUrl: String,
    val url: String,
    val width: Int,
    val height: Int,
)

data class KlipyGifState(
    val loading: Boolean = false,
    val gifs: List<KlipyGif> = emptyList(),
    val error: String? = null,
)

@HiltViewModel
class KlipyGifViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    @Named("klipy") private val client: OkHttpClient,
    private val favoritesStore: GifFavoritesStore,
) : ViewModel() {
    private val mutableState = MutableStateFlow(KlipyGifState())
    val state = mutableState.asStateFlow()
    val favorites = favoritesStore.favorites
    private var requestJob: Job? = null

    fun toggleFavorite(gif: KlipyGif) = favoritesStore.toggle(gif)
    private val customerId: String by lazy {
        val preferences = context.getSharedPreferences("klipy", Context.MODE_PRIVATE)
        preferences.getString("customer_id", null) ?: UUID.randomUUID().toString().also {
            preferences.edit().putString("customer_id", it).apply()
        }
    }

    fun search(query: String) {
        requestJob?.cancel()
        requestJob = viewModelScope.launch {
            if (query.isNotBlank()) delay(300)
            mutableState.value = mutableState.value.copy(loading = true, error = null)
            try {
                mutableState.value = KlipyGifState(gifs = load(query.trim()))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.value = KlipyGifState(
                    error = if (BuildConfig.KLIPY_API_KEY.isBlank()) "GIF search is not configured"
                    else "Couldn't load GIFs",
                )
            }
        }
    }

    fun recordShare(gif: KlipyGif) {
        if (BuildConfig.KLIPY_API_KEY.isBlank()) return
        viewModelScope.launch {
            withContext(kotlinx.coroutines.Dispatchers.IO) {
                val body = JSONObject().put("customer_id", customerId).toString()
                    .toRequestBody("application/json".toMediaType())
                val request = Request.Builder()
                    .url(apiUrl("share/${gif.slug}"))
                    .post(body)
                    .build()
                runCatching { client.newCall(request).execute().close() }
            }
        }
    }

    private suspend fun load(query: String): List<KlipyGif> =
        withContext(kotlinx.coroutines.Dispatchers.IO) {
            if (BuildConfig.KLIPY_API_KEY.isBlank()) throw IOException("Missing KLIPY key")
            val endpoint = if (query.isBlank()) "trending" else "search"
            val url = apiUrl(endpoint).toHttpUrl().newBuilder()
                .addQueryParameter("page", "1")
                .addQueryParameter("per_page", "30")
                .apply { if (query.isNotBlank()) addQueryParameter("q", query) }
                .build()
            val request = Request.Builder().url(url).get().build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("KLIPY returned ${response.code}")
                parse(response.body?.string().orEmpty())
            }
        }

    private fun apiUrl(endpoint: String) =
        "https://api.klipy.com/api/v1/${BuildConfig.KLIPY_API_KEY}/gifs/$endpoint"

    private fun parse(body: String): List<KlipyGif> {
        val items = JSONObject(body).optJSONObject("data")?.optJSONArray("data")
            ?: return emptyList()
        return buildList {
            for (index in 0 until items.length()) {
                val item = items.optJSONObject(index) ?: continue
                if (item.optString("type") != "gif") continue
                val slug = item.optString("slug").takeIf(String::isNotBlank) ?: continue
                val file = item.optJSONObject("file") ?: continue
                val preview = gifFile(file, listOf("sm", "xs", "md", "hd")) ?: continue
                val full = gifFile(file, listOf("md", "hd", "sm", "xs")) ?: continue
                add(
                    KlipyGif(
                        slug = slug,
                        title = item.optString("title").ifBlank { "GIF" },
                        previewUrl = preview.url,
                        url = full.url,
                        width = full.width.coerceAtLeast(1),
                        height = full.height.coerceAtLeast(1),
                    ),
                )
            }
        }
    }

    private data class GifFile(val url: String, val width: Int, val height: Int)

    private fun gifFile(file: JSONObject, sizes: List<String>): GifFile? {
        for (size in sizes) {
            val gif = file.optJSONObject(size)?.optJSONObject("gif") ?: continue
            val url = gif.optString("url")
            if (url.isNotBlank()) return GifFile(url, gif.optInt("width", 1), gif.optInt("height", 1))
        }
        return null
    }
}

/** Categorised emoji grid shared by the composer picker and the reaction menu. */
@Composable
fun EmojiGrid(columns: Int, modifier: Modifier = Modifier, onPick: (String) -> Unit) {
    val c = OrangTheme.colors
    LazyVerticalGrid(columns = GridCells.Fixed(columns), modifier = modifier) {
        for (category in EMOJI_CATEGORIES) {
            item(key = "header-${category.name}", span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    category.name.uppercase(),
                    color = c.inkMuted,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(c.surface3)
                        .padding(horizontal = 4.dp, vertical = 6.dp),
                )
            }
            items(category.emojis, key = { "${category.name}-$it" }) { emoji ->
                Box(
                    modifier = Modifier
                        .aspectRatio(1f)
                        .clickable { onPick(emoji) },
                    contentAlignment = Alignment.Center,
                ) { Text(emoji, fontSize = 22.sp) }
            }
        }
    }
}

@Composable
fun ExpressionPickerDialog(
    expanded: Boolean,
    onDismiss: () -> Unit,
    gifsEnabled: Boolean,
    onEmoji: (String) -> Unit,
    onGif: (String) -> Unit,
    customEmojis: List<EmojiRef> = emptyList(),
    viewModel: KlipyGifViewModel = hiltViewModel(),
) {
    if (!expanded) return
    val c = OrangTheme.colors
    var tab by remember { mutableStateOf(PickerTabId.EMOJI) }
    var query by remember { mutableStateOf("") }
    val gifState by viewModel.state.collectAsState()
    val favorites by viewModel.favorites.collectAsState()

    LaunchedEffect(tab, query, gifsEnabled) {
        if (tab == PickerTabId.GIFS && gifsEnabled) viewModel.search(query)
    }

    // Sending a GIF and saving one are different intents on the same tile, so
    // both grids share this pair of handlers.
    val sendGif: (KlipyGif) -> Unit = { gif ->
        viewModel.recordShare(gif)
        onGif(gif.url)
        onDismiss()
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .widthIn(max = 380.dp)
                .height(470.dp)
                .background(c.surface3, RoundedCornerShape(OrangRadius.xl))
                .border(1.dp, c.border, RoundedCornerShape(OrangRadius.xl))
                .padding(10.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(c.surface1, RoundedCornerShape(OrangRadius.lg))
                    .padding(3.dp),
            ) {
                PickerTab("Emoji", tab == PickerTabId.EMOJI, Modifier.weight(1f)) {
                    tab = PickerTabId.EMOJI
                }
                if (customEmojis.isNotEmpty()) {
                    PickerTab("Custom", tab == PickerTabId.CUSTOM, Modifier.weight(1f)) {
                        tab = PickerTabId.CUSTOM
                    }
                }
                if (gifsEnabled) {
                    PickerTab("GIFs", tab == PickerTabId.GIFS, Modifier.weight(1f)) {
                        tab = PickerTabId.GIFS
                    }
                    PickerTab("Favorite GIFs", tab == PickerTabId.FAVORITES, Modifier.weight(1f)) {
                        tab = PickerTabId.FAVORITES
                    }
                }
            }
            Spacer(Modifier.height(8.dp))

            when {
                // Ahead of the `!gifsEnabled` fallback below, which would
                // otherwise show the unicode grid on the Custom tab.
                tab == PickerTabId.CUSTOM -> {
                    CustomEmojiGrid(
                        emojis = customEmojis,
                        modifier = Modifier.fillMaxSize(),
                    ) { emoji ->
                        // The id is what resolves, so a later rename never breaks
                        // messages that already used it.
                        onEmoji("<${if (emoji.animated) "a" else ""}:${emoji.name}:${emoji.id}>")
                        onDismiss()
                    }
                }

                tab == PickerTabId.EMOJI || !gifsEnabled -> {
                    EmojiGrid(columns = 8, modifier = Modifier.fillMaxSize()) { emoji ->
                        onEmoji(emoji)
                        onDismiss()
                    }
                }

                tab == PickerTabId.FAVORITES -> {
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        if (favorites.isEmpty()) {
                            Text(
                                "No saved GIFs yet - tap the bookmark on any GIF to keep it here.",
                                color = c.inkMuted,
                                fontSize = 12.sp,
                                modifier = Modifier.align(Alignment.Center).padding(24.dp),
                            )
                        } else {
                            GifGrid(
                                gifs = favorites,
                                favoriteSlugs = favorites.map { it.slug }.toSet(),
                                onPick = sendGif,
                                onToggleFavorite = viewModel::toggleFavorite,
                            )
                        }
                    }
                    KlipyCredit()
                }

                else -> {
                    TextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = { Text("Search KLIPY", fontSize = 13.sp) },
                        singleLine = true,
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = c.surface1,
                            unfocusedContainerColor = c.surface1,
                            focusedTextColor = c.ink,
                            unfocusedTextColor = c.ink,
                            focusedIndicatorColor = c.primary,
                            unfocusedIndicatorColor = c.border,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        when {
                            gifState.loading && gifState.gifs.isEmpty() -> CircularProgressIndicator(
                                color = c.primary,
                                modifier = Modifier.size(28.dp).align(Alignment.Center),
                            )
                            gifState.error != null -> Text(
                                gifState.error.orEmpty(),
                                color = c.danger,
                                fontSize = 12.sp,
                                modifier = Modifier.align(Alignment.Center),
                            )
                            gifState.gifs.isEmpty() -> Text(
                                "No GIFs found",
                                color = c.inkMuted,
                                fontSize = 12.sp,
                                modifier = Modifier.align(Alignment.Center),
                            )
                            else -> GifGrid(
                                gifs = gifState.gifs,
                                favoriteSlugs = favorites.map { it.slug }.toSet(),
                                onPick = sendGif,
                                onToggleFavorite = viewModel::toggleFavorite,
                            )
                        }
                    }
                    KlipyCredit()
                }
            }
        }
    }
}

private enum class PickerTabId { EMOJI, CUSTOM, GIFS, FAVORITES }

@Composable
private fun CustomEmojiGrid(
    emojis: List<EmojiRef>,
    modifier: Modifier = Modifier,
    onPick: (EmojiRef) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(6),
        modifier = modifier,
        contentPadding = PaddingValues(4.dp),
    ) {
        items(emojis, key = { it.id }) { emoji ->
            // Fills its grid cell instead of sitting as a 40dp island in it: same
            // 48dp row height as before, no dead gaps between neighbours.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clickable { onPick(emoji) }
                    .padding(4.dp),
                contentAlignment = Alignment.Center,
            ) {
                AsyncImage(
                    model = absoluteUrl(emoji.url),
                    contentDescription = ":${emoji.name}:",
                    modifier = Modifier.size(32.dp),
                )
            }
        }
    }
}

/**
 * The GIF tiles, shared by the search results and the favorites tab.
 *
 * Tapping a tile sends it; the bookmark saves it. They sit on top of each other
 * deliberately - that is where Discord puts it, and it keeps saving a GIF one
 * gesture away from wherever you found it.
 */
@Composable
private fun GifGrid(
    gifs: List<KlipyGif>,
    favoriteSlugs: Set<String>,
    onPick: (KlipyGif) -> Unit,
    onToggleFavorite: (KlipyGif) -> Unit,
) {
    val c = OrangTheme.colors
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(gifs, key = { it.slug }) { gif ->
            val saved = gif.slug in favoriteSlugs
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio((gif.width.toFloat() / gif.height).coerceIn(0.65f, 1.8f)),
            ) {
                AsyncImage(
                    model = gif.previewUrl,
                    contentDescription = gif.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(OrangRadius.md))
                        .background(c.surface1)
                        .clickable { onPick(gif) },
                )
                // A 48dp target that takes the tap, with the 26dp chip still drawn
                // in the corner where it was - growing the circle itself would have
                // buried the GIF under its own bookmark button.
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(48.dp)
                        .clickable { onToggleFavorite(gif) },
                    contentAlignment = Alignment.TopEnd,
                ) {
                    Box(
                        modifier = Modifier
                            .padding(4.dp)
                            .size(26.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.5f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            if (saved) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                            contentDescription = if (saved) "Remove ${gif.title} from favorites"
                            else "Save ${gif.title} to favorites",
                            tint = if (saved) c.primary else Color.White,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.KlipyCredit() {
    Text(
        "Powered by KLIPY",
        color = OrangTheme.colors.inkMuted,
        fontSize = 10.sp,
        fontWeight = FontWeight.Medium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.align(Alignment.End).padding(top = 4.dp),
    )
}

@Composable
private fun PickerTab(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = OrangTheme.colors
    Box(
        modifier = modifier
            .background(
                if (selected) c.surface3 else androidx.compose.ui.graphics.Color.Transparent,
                RoundedCornerShape(OrangRadius.md),
            )
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (selected) c.ink else c.inkMuted,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}
