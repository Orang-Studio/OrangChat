package lt.oranges.orangchat.feature.chat

import androidx.compose.ui.platform.LocalContext
import lt.oranges.orangchat.R
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Search
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import lt.oranges.orangchat.util.AppStrings
import lt.oranges.orangchat.util.EmojiRef
import lt.oranges.orangchat.util.EmojiSearch
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
    private val recentEmojiStore: RecentEmojiStore,
) : ViewModel() {
    private val mutableState = MutableStateFlow(KlipyGifState())
    val state = mutableState.asStateFlow()
    val favorites = favoritesStore.favorites
    val recentEmojis = recentEmojiStore.recent
    private var requestJob: Job? = null

    fun toggleFavorite(gif: KlipyGif) = favoritesStore.toggle(gif)

    fun recordEmoji(insert: String) = recentEmojiStore.record(insert)
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
                    else AppStrings.get(context, R.string.catalog_couldn_t_load_gifs_e47c284a),
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

/** A server's own emoji, kept together so the picker can label them by server. */
data class CustomEmojiGroup(
    val serverId: String,
    val name: String,
    val iconUrl: String?,
    val emojis: List<EmojiRef>,
)

/**
 * The picker as a keyboard replacement: it sits where the IME was, so it is laid
 * out inline at [height] rather than floating over the conversation.
 */
@Composable
fun ExpressionPickerSheet(
    height: Dp,
    onDismiss: () -> Unit,
    gifsEnabled: Boolean,
    onEmoji: (String) -> Unit,
    onGif: (String) -> Unit,
    onBackspace: () -> Unit,
    customGroups: List<CustomEmojiGroup> = emptyList(),
    viewModel: KlipyGifViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val c = OrangTheme.colors
    var tab by remember { mutableStateOf(PickerTabId.EMOJI) }
    var query by remember { mutableStateOf("") }
    val gifState by viewModel.state.collectAsState()
    val favorites by viewModel.favorites.collectAsState()
    val recentEmojis by viewModel.recentEmojis.collectAsState()

    LaunchedEffect(tab, query, gifsEnabled) {
        if (tab == PickerTabId.GIFS && gifsEnabled) viewModel.search(query)
    }

    val sendGif: (KlipyGif) -> Unit = { gif ->
        viewModel.recordShare(gif)
        onGif(gif.url)
        onDismiss()
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .clipToBounds()
            .background(c.surface2)
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(c.surface1, RoundedCornerShape(OrangRadius.lg))
                .padding(3.dp),
        ) {
            PickerTab(
                AppStrings.get(context, R.string.catalog_emoji_5090a9e7),
                tab == PickerTabId.EMOJI,
                Modifier.weight(1f),
            ) {
                tab = PickerTabId.EMOJI
            }
            if (gifsEnabled) {
                PickerTab(
                    AppStrings.get(context, R.string.catalog_gifs_e98d00db),
                    tab == PickerTabId.GIFS,
                    Modifier.weight(1f),
                ) {
                    tab = PickerTabId.GIFS
                }
                PickerTab(
                    AppStrings.get(context, R.string.catalog_favorite_gifs_109d1438),
                    tab == PickerTabId.FAVORITES,
                    Modifier.weight(1f),
                ) {
                    tab = PickerTabId.FAVORITES
                }
            }
        }
        Spacer(Modifier.height(6.dp))

        when {
            tab == PickerTabId.EMOJI || !gifsEnabled -> {
                EmojiPanel(
                    groups = customGroups,
                    recent = recentEmojis,
                    onPick = { insert ->
                        viewModel.recordEmoji(insert)
                        onEmoji(insert)
                    },
                    onBackspace = onBackspace,
                )
            }

            tab == PickerTabId.FAVORITES -> {
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    if (favorites.isEmpty()) {
                        Text(
                            AppStrings.get(context, R.string.catalog_no_saved_gifs_yet_tap_the_bookmark_75705898),
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
                    placeholder = { Text(AppStrings.get(context, R.string.catalog_search_klipy_9e867d1a), fontSize = 13.sp) },
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
                            AppStrings.get(context, R.string.catalog_no_gifs_found_0d358df4),
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

private enum class PickerTabId { EMOJI, GIFS, FAVORITES }

private sealed interface EmojiSection {
    val title: String
    val count: Int

    data class Custom(
        override val title: String,
        val iconUrl: String?,
        val emojis: List<EmojiRef>,
    ) : EmojiSection {
        override val count get() = emojis.size
    }

    data class Standard(
        override val title: String,
        val emojis: List<String>,
    ) : EmojiSection {
        override val count get() = emojis.size
    }

    data class Recent(
        override val title: String,
        val entries: List<RecentEntry>,
    ) : EmojiSection {
        override val count get() = entries.size
    }
}

/** A remembered pick: `url` is set when it resolves to a custom emoji. */
private data class RecentEntry(val insert: String, val url: String?)

private val SHORTCODE = Regex("^:([^:\\s]+):$")

/**
 * Recent picks paired back up with the emoji they name. A shortcode whose
 * custom emoji is gone - deleted, or on a server this user has left - has
 * nothing left to draw, so it drops out.
 */
private fun recentEntries(
    recent: List<String>,
    groups: List<CustomEmojiGroup>,
): List<RecentEntry> = recent.mapNotNull { insert ->
    val name = SHORTCODE.find(insert)?.groupValues?.get(1)
        ?: return@mapNotNull RecentEntry(insert, null)
    groups.asSequence()
        .flatMap { it.emojis }
        .firstOrNull { it.name.equals(name, ignoreCase = true) }
        ?.let { RecentEntry(insert, it.url) }
}

private fun emojiSections(
    groups: List<CustomEmojiGroup>,
    recent: List<String>,
    query: String,
): List<EmojiSection> {
    val q = query.trim().lowercase()
    if (q.isEmpty()) {
        val entries = recentEntries(recent, groups)
        return listOfNotNull(
            if (entries.isEmpty()) null else EmojiSection.Recent(RECENT_SECTION, entries),
        ) +
            groups.map { EmojiSection.Custom(it.name, it.iconUrl, it.emojis) } +
            EMOJI_CATEGORIES.map { EmojiSection.Standard(it.name, it.emojis) }
    }
    val custom = groups.mapNotNull { group ->
        val hits = group.emojis.filter { it.name.lowercase().contains(q) }
        if (hits.isEmpty()) null else EmojiSection.Custom(group.name, group.iconUrl, hits)
    }
    val hits = EmojiSearch.search(q, EMOJI_SEARCH_LIMIT).map { it.char }
    return custom + listOfNotNull(
        if (hits.isEmpty()) null else EmojiSection.Standard(SEARCH_SECTION, hits),
    )
}

private const val EMOJI_SEARCH_LIMIT = 64
private const val SEARCH_SECTION = "search"
private const val RECENT_SECTION = "recent"

@Composable
private fun ColumnScope.EmojiPanel(
    groups: List<CustomEmojiGroup>,
    recent: List<String>,
    onPick: (String) -> Unit,
    onBackspace: () -> Unit,
) {
    val c = OrangTheme.colors
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    // Recents are read once per opening: reshuffling the grid under the finger
    // that just picked something is worse than showing it on the next visit.
    val openingRecent = remember(groups) { recent }
    val sections = remember(groups, openingRecent, query) {
        emojiSections(groups, openingRecent, query)
    }
    val grid = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    // Each section is one header item plus its emoji, so a shortcut can jump to it.
    val starts = remember(sections) {
        var index = 0
        sections.map { section -> index.also { index += section.count + 1 } }
    }

    TextField(
        value = query,
        onValueChange = { query = it },
        placeholder = {
            Text(
                AppStrings.get(context, R.string.catalog_find_the_perfect_emoji_81f282bc),
                fontSize = 13.sp,
            )
        },
        singleLine = true,
        leadingIcon = {
            Icon(
                Icons.Default.Search,
                contentDescription = null,
                tint = c.inkMuted,
                modifier = Modifier.size(18.dp),
            )
        },
        colors = TextFieldDefaults.colors(
            focusedContainerColor = c.surface1,
            unfocusedContainerColor = c.surface1,
            focusedTextColor = c.ink,
            unfocusedTextColor = c.ink,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
        ),
        shape = RoundedCornerShape(OrangRadius.lg),
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
    )
    Spacer(Modifier.height(4.dp))

    LazyVerticalGrid(
        columns = GridCells.Fixed(8),
        state = grid,
        modifier = Modifier.weight(1f).fillMaxWidth(),
    ) {
        sections.forEachIndexed { index, section ->
            item(key = "header-$index", span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    sectionTitle(context, section).uppercase(),
                    color = c.inkMuted,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(c.surface2)
                        .padding(horizontal = 4.dp, vertical = 6.dp),
                )
            }
            when (section) {
                is EmojiSection.Custom -> items(
                    section.emojis,
                    key = { "custom-$index-${it.id}" },
                ) { emoji ->
                    Box(
                        modifier = Modifier
                            .aspectRatio(1f)
                            .clickable { onPick(":${emoji.name}:") },
                        contentAlignment = Alignment.Center,
                    ) {
                        AsyncImage(
                            model = absoluteUrl(emoji.url),
                            contentDescription = ":${emoji.name}:",
                            modifier = Modifier.size(26.dp),
                        )
                    }
                }

                is EmojiSection.Standard -> items(
                    section.emojis,
                    key = { "standard-$index-$it" },
                ) { emoji ->
                    Box(
                        modifier = Modifier
                            .aspectRatio(1f)
                            .clickable { onPick(emoji) },
                        contentAlignment = Alignment.Center,
                    ) { Text(emoji, fontSize = 22.sp) }
                }

                is EmojiSection.Recent -> items(
                    section.entries,
                    key = { "recent-$index-${it.insert}" },
                ) { entry ->
                    Box(
                        modifier = Modifier
                            .aspectRatio(1f)
                            .clickable { onPick(entry.insert) },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (entry.url != null) {
                            AsyncImage(
                                model = absoluteUrl(entry.url),
                                contentDescription = entry.insert,
                                modifier = Modifier.size(26.dp),
                            )
                        } else {
                            Text(entry.insert, fontSize = 22.sp)
                        }
                    }
                }
            }
        }
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(c.surface1, RoundedCornerShape(OrangRadius.lg))
            .padding(horizontal = 4.dp),
    ) {
        LazyRow(modifier = Modifier.weight(1f)) {
            itemsIndexed(sections, key = { index, _ -> "tab-$index" }) { index, section ->
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(OrangRadius.md))
                        .clickable { scope.launch { grid.scrollToItem(starts[index]) } },
                    contentAlignment = Alignment.Center,
                ) {
                    when (section) {
                        is EmojiSection.Custom -> if (section.iconUrl != null) {
                            AsyncImage(
                                model = absoluteUrl(section.iconUrl),
                                contentDescription = section.title,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.size(22.dp).clip(CircleShape),
                            )
                        } else {
                            Text(
                                section.title.take(1).uppercase(),
                                color = c.inkSecondary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }

                        is EmojiSection.Standard -> Text(
                            section.emojis.firstOrNull().orEmpty(),
                            fontSize = 18.sp,
                        )

                        is EmojiSection.Recent -> Icon(
                            Icons.Default.History,
                            contentDescription = sectionTitle(context, section),
                            tint = c.inkSecondary,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        }
        Icon(
            Icons.AutoMirrored.Filled.Backspace,
            contentDescription = AppStrings.get(context, R.string.catalog_backspace_88d130a6),
            tint = c.inkMuted,
            modifier = Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(OrangRadius.md))
                .clickable(onClick = onBackspace)
                .padding(9.dp),
        )
    }
}

@Composable
private fun sectionTitle(context: Context, section: EmojiSection): String = when (section.title) {
    SEARCH_SECTION -> AppStrings.get(context, R.string.catalog_search_results_0144dae8)
    RECENT_SECTION -> AppStrings.get(context, R.string.catalog_recently_used_2f8a19c4)
    else -> section.title
}

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
