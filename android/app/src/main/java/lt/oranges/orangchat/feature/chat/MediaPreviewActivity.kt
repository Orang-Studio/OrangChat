package lt.oranges.orangchat.feature.chat

import lt.oranges.orangchat.R
import android.content.Context
import android.content.Intent
import android.graphics.Color as AndroidColor
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddReaction
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityOptionsCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import lt.oranges.orangchat.LocalizedActivity
import lt.oranges.orangchat.data.model.Attachment
import lt.oranges.orangchat.data.model.Message
import lt.oranges.orangchat.ui.components.Avatar
import lt.oranges.orangchat.ui.components.Text
import lt.oranges.orangchat.ui.theme.OrangChatTheme
import lt.oranges.orangchat.util.AppStrings
import lt.oranges.orangchat.util.absoluteUrl
import lt.oranges.orangchat.util.formatFullTime
import lt.oranges.orangchat.util.inlineUrl

private const val DEFAULT_PREVIEW_ASPECT = 16f / 9f

internal object MediaPreviewTransport {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(attachment: Attachment): String = json.encodeToString(attachment)

    fun decode(value: String): Attachment = json.decodeFromString(value)
}

@AndroidEntryPoint
class MediaPreviewActivity : LocalizedActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.statusBarColor = AndroidColor.BLACK
        window.navigationBarColor = AndroidColor.BLACK

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, android.R.anim.fade_out)
        }

        val raw = intent.getStringExtra(EXTRA_ATTACHMENT)
        val attachment = raw?.let { runCatching { MediaPreviewTransport.decode(it) }.getOrNull() }
        if (attachment == null || (!attachment.isImage && !attachment.isVideo)) {
            finish()
            return
        }

        setContent {
            OrangChatTheme {
                FullscreenMediaPreview(attachment = attachment, onClose = ::finish)
            }
        }
    }

    @Suppress("DEPRECATION")
    override fun finish() {
        super.finish()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overridePendingTransition(0, android.R.anim.fade_out)
        }
    }

    companion object {
        private const val EXTRA_ATTACHMENT = "attachment"

        fun intent(context: Context, attachment: Attachment): Intent =
            Intent(context, MediaPreviewActivity::class.java)
                .putExtra(EXTRA_ATTACHMENT, MediaPreviewTransport.encode(attachment))
    }
}

class MediaOrigin {
    internal var bounds: Rect? = null
}

@Composable
fun rememberMediaOrigin(): MediaOrigin = remember { MediaOrigin() }

fun Modifier.mediaOrigin(origin: MediaOrigin): Modifier =
    onGloballyPositioned { origin.bounds = it.boundsInWindow() }

private fun scaleUpFrom(view: View, origin: MediaOrigin?): ActivityOptionsCompat? {
    val bounds = origin?.bounds ?: return null
    if (bounds.width <= 0f || bounds.height <= 0f) return null
    val offset = IntArray(2)
    view.getLocationInWindow(offset)
    return ActivityOptionsCompat.makeScaleUpAnimation(
        view,
        (bounds.left - offset[0]).toInt(),
        (bounds.top - offset[1]).toInt(),
        bounds.width.toInt(),
        bounds.height.toInt(),
    )
}

fun openMediaPreview(context: Context, view: View, origin: MediaOrigin?, attachment: Attachment) {
    context.startActivity(
        MediaPreviewActivity.intent(context, attachment),
        scaleUpFrom(view, origin)?.toBundle(),
    )
}

fun openMediaPreview(
    launcher: ActivityResultLauncher<Intent>,
    context: Context,
    view: View,
    origin: MediaOrigin?,
    attachment: Attachment,
) {
    launcher.launch(MediaPreviewActivity.intent(context, attachment), scaleUpFrom(view, origin))
}

@Composable
private fun FullscreenMediaPreview(attachment: Attachment, onClose: () -> Unit) {
    val messages by MediaPreviewHost.messages.collectAsState()
    val message = remember(messages, attachment.id) {
        MediaPreviewHost.messageFor(messages, attachment.id)
    }
    var chromeVisible by remember(attachment.id) { mutableStateOf(true) }
    val source = rememberAttachmentSource(attachment, wanted = true)
    val download = rememberAttachmentDownloader()
    val view = LocalView.current
    val context = LocalContext.current
    val controller = remember(view) { WindowCompat.getInsetsController((context as ComponentActivity).window, view) }

    LaunchedEffect(chromeVisible) {
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (chromeVisible) {
            controller.show(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.hide(WindowInsetsCompat.Type.systemBars())
        }
    }
    DisposableEffect(controller) {
        onDispose { controller.show(WindowInsetsCompat.Type.systemBars()) }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        when {
            source.url == null -> PreviewLoading(
                source = source,
                onToggleChrome = { chromeVisible = !chromeVisible },
            )
            attachment.isVideo -> FullscreenVideo(
                attachment = attachment,
                url = source.url,
                chromeVisible = chromeVisible,
                onToggleChrome = { chromeVisible = !chromeVisible },
                senderBar = message?.let { { PreviewSenderBar(message = it) } },
            )
            else -> FullscreenImage(
                attachment = attachment,
                url = source.url,
                onToggleChrome = { chromeVisible = !chromeVisible },
            )
        }

        AnimatedVisibility(
            visible = chromeVisible,
            enter = slideInVertically { -it } + fadeIn(),
            exit = slideOutVertically { -it } + fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            PreviewTopBar(
                attachment = attachment,
                resolvedUrl = source.url,
                onDownload = { download(attachment) },
                onClose = onClose,
            )
        }
        if (message != null && !attachment.isVideo) {
            AnimatedVisibility(
                visible = chromeVisible,
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                PreviewSenderBar(message = message)
            }
        }
    }
}

@Composable
private fun PreviewSenderBar(message: Message, modifier: Modifier = Modifier) {
        val context = LocalContext.current
    var pickerOpen by remember(message.id) { mutableStateOf(false) }
    val chipShape = RoundedCornerShape(50)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.72f))
            .tapToToggle { }
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        if (pickerOpen) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(bottom = 8.dp),
            ) {
                QUICK_EMOJIS.forEach { emoji ->
                    val mine = message.reactions.any { it.emoji == emoji && it.me }
                    Box(
                        modifier = Modifier
                            .clip(chipShape)
                            .background(
                                if (mine) Color.White.copy(alpha = 0.25f) else Color.White.copy(alpha = 0.1f),
                            )
                            .clickable {
                                MediaPreviewHost.react(message, emoji)
                                pickerOpen = false
                            }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    ) {
                        Text(emoji, fontSize = 18.sp)
                    }
                }
            }
        }

        Row(verticalAlignment = Alignment.Top) {
            Avatar(user = message.author, size = 34.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        message.author.displayName,
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        formatFullTime(message.createdAt),
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = 11.sp,
                        maxLines = 1,
                    )
                }
                if (message.content.isNotBlank()) {
                    Text(
                        message.content,
                        color = Color.White.copy(alpha = 0.85f),
                        fontSize = 13.sp,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(6.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                ) {
                    message.reactions.forEach { reaction ->
                        Row(
                            modifier = Modifier
                                .height(28.dp)
                                .clip(chipShape)
                                .background(
                                    if (reaction.me) {
                                        Color.White.copy(alpha = 0.25f)
                                    } else {
                                        Color.White.copy(alpha = 0.1f)
                                    },
                                )
                                .clickable { MediaPreviewHost.react(message, reaction.emoji) }
                                .padding(horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(reaction.emoji, fontSize = 13.sp)
                            Spacer(Modifier.width(4.dp))
                            Text(
                                "${reaction.count}",
                                color = Color.White.copy(alpha = 0.8f),
                                fontSize = 12.sp,
                            )
                        }
                    }
                    Row(
                        modifier = Modifier
                            .height(28.dp)
                            .clip(chipShape)
                            .background(Color.White.copy(alpha = 0.1f))
                            .clickable { pickerOpen = !pickerOpen }
                            .padding(horizontal = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Default.AddReaction,
                            contentDescription = AppStrings.get(context, R.string.catalog_add_reaction_cf05eca8),
                            tint = Color.White.copy(alpha = 0.85f),
                            modifier = Modifier.size(15.dp),
                        )
                        Spacer(Modifier.width(5.dp))
                        Text("React", color = Color.White, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun PreviewLoading(source: AttachmentSource, onToggleChrome: () -> Unit) {
        val context = LocalContext.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .tapToToggle(onToggleChrome),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (!source.unavailable) {
                if (source.resolving && source.progress > 0f) {
                    CircularProgressIndicator(
                        progress = { source.progress },
                        color = Color.White,
                    )
                } else {
                    CircularProgressIndicator(color = Color.White)
                }
                Spacer(Modifier.height(8.dp))
            }
            Text(
                if (source.unavailable) AppStrings.get(context, R.string.catalog_this_media_is_unavailable_1ed2c88e) else source.phaseLabel,
                color = Color.White.copy(alpha = 0.75f),
                fontSize = 12.sp,
            )
        }
    }
}

@Composable
private fun FullscreenImage(
    attachment: Attachment,
    url: String,
    onToggleChrome: () -> Unit,
) {
        val context = LocalContext.current
    var scale by remember(attachment.id) { mutableFloatStateOf(1f) }
    var offset by remember(attachment.id) { mutableStateOf(Offset.Zero) }
    var broken by remember(attachment.id) { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(attachment.id) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 5f)
                    offset = if (scale > 1f) offset + pan else Offset.Zero
                }
            }
            .pointerInput(attachment.id) {
                detectTapGestures(
                    onTap = { onToggleChrome() },
                    onDoubleTap = {
                        if (scale > 1f) {
                            scale = 1f
                            offset = Offset.Zero
                        } else {
                            scale = 2.5f
                        }
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        if (broken) {
            Text(AppStrings.get(context, R.string.catalog_could_not_open_this_image_cb0d1e2e), color = Color.White.copy(alpha = 0.75f))
        } else {
            AsyncImage(
                model = url,
                contentDescription = attachment.filename,
                contentScale = ContentScale.Fit,
                onError = { broken = true },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    },
            )
        }
    }
}

@Composable
private fun FullscreenVideo(
    attachment: Attachment,
    url: String,
    chromeVisible: Boolean,
    onToggleChrome: () -> Unit,
    senderBar: (@Composable () -> Unit)? = null,
) {
    val context = LocalContext.current
    var broken by remember(attachment.id) { mutableStateOf(false) }

    LaunchedEffect(attachment.id, url) {
        if (MediaPlayback.currentId == attachment.id) {
            MediaPlayback.open(context, attachment.id, url) { broken = true }
        } else {
            MediaPlayback.toggle(context, attachment.id, url) { broken = true }
        }
    }

    val active = MediaPlayback.currentId == attachment.id
    val player = if (active) MediaPlayback.playerFor(attachment.id) else null
    val isPlaying = active && MediaPlayback.isPlaying
    val durationMs = if (active && MediaPlayback.durationMs > 0) {
        MediaPlayback.durationMs
    } else {
        ((attachment.duration ?: 0.0) * 1000).toLong()
    }
    val aspect = if (active && MediaPlayback.videoAspect > 0f) {
        MediaPlayback.videoAspect
    } else {
        val width = attachment.width
        val height = attachment.height
        if (width != null && height != null && width > 0 && height > 0) {
            width.toFloat() / height
        } else {
            DEFAULT_PREVIEW_ASPECT
        }
    }

    LaunchedEffect(active, isPlaying) {
        while (active && isPlaying) {
            MediaPlayback.syncPosition()
            delay(200)
        }
    }

    Box(Modifier.fillMaxSize()) {
        if (player != null) {
            VideoSurface(
                player = player,
                bind = true,
                aspect = aspect,
                modifier = Modifier.fillMaxSize(),
            )
        }

        if (broken) {
            Text(
                AppStrings.get(context, R.string.catalog_could_not_play_this_video_8540287d),
                color = Color.White.copy(alpha = 0.75f),
                modifier = Modifier.align(Alignment.Center),
            )
        } else if (player == null || MediaPlayback.buffering) {
            CircularProgressIndicator(
                color = Color.White,
                modifier = Modifier.align(Alignment.Center),
            )
        }

        Box(Modifier.fillMaxSize().tapToToggle(onToggleChrome))

        AnimatedVisibility(
            visible = chromeVisible,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Column {
                VideoControlBar(
                    attachment = attachment,
                    url = url,
                    active = active,
                    isPlaying = isPlaying,
                    durationMs = durationMs,
                    onBroken = { broken = true },
                    bottomInset = senderBar == null,
                )
                senderBar?.invoke()
            }
        }
    }
}

@Composable
private fun VideoControlBar(
    attachment: Attachment,
    url: String,
    active: Boolean,
    isPlaying: Boolean,
    durationMs: Long,
    onBroken: () -> Unit,
    bottomInset: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.72f))
            .tapToToggle { }
            .then(if (bottomInset) Modifier.navigationBarsPadding() else Modifier)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OverlayIconButton(
            onClick = {
                MediaPlayback.toggle(context, attachment.id, url, onBroken)
            },
            icon = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
            label = if (isPlaying) "Pause" else "Play",
        )
        Spacer(Modifier.width(4.dp))
        Text(
            formatDuration(if (active) MediaPlayback.positionMs else 0L),
            color = Color.White,
            fontSize = 11.sp,
        )
        Spacer(Modifier.width(8.dp))
        Scrubber(
            positionMs = if (active) MediaPlayback.positionMs else 0L,
            durationMs = durationMs,
            enabled = active && MediaPlayback.ready,
            onSeek = MediaPlayback::seekTo,
            accent = Color.White,
            track = Color.White.copy(alpha = 0.3f),
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            formatDuration(durationMs),
            color = Color.White.copy(alpha = 0.75f),
            fontSize = 11.sp,
        )
        Spacer(Modifier.width(4.dp))
        OverlayIconButton(
            onClick = MediaPlayback::toggleMute,
            icon = if (MediaPlayback.muted) Icons.Default.VolumeOff else Icons.Default.VolumeUp,
            label = if (MediaPlayback.muted) "Unmute" else "Mute",
        )
    }
}

@Composable
private fun PreviewTopBar(
    attachment: Attachment,
    resolvedUrl: String?,
    onDownload: () -> Unit,
    onClose: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.72f))
            .tapToToggle { }
            .statusBarsPadding()
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OverlayIconButton(
            onClick = onClose,
            icon = Icons.Default.Close,
            label = "Close",
        )
        Spacer(Modifier.width(4.dp))
        Column(Modifier.weight(1f)) {
            Text(
                attachment.filename,
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (attachment.size > 0) {
                Text(
                    formatBytes(attachment.size),
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 11.sp,
                )
            }
        }
        if (attachment.isImage && resolvedUrl != null && isGif(attachment)) {
            GifFavoriteAction(attachment, resolvedUrl)
        }
        OverlayIconButton(
            onClick = onDownload,
            icon = Icons.Default.Download,
            label = "Download ${attachment.filename}",
        )
    }
}

@Composable
private fun GifFavoriteAction(attachment: Attachment, resolvedUrl: String?) {
        val context = LocalContext.current
    val viewModel: KlipyGifViewModel = hiltViewModel()
    val favorites by viewModel.favorites.collectAsState()
    val url = resolvedUrl ?: absoluteUrl(inlineUrl(attachment.url)) ?: attachment.url
    val gif = remember(attachment.id, url) {
        KlipyGif(
            slug = url,
            title = attachment.filename,
            previewUrl = url,
            url = url,
            width = attachment.width ?: 0,
            height = attachment.height ?: 0,
        )
    }
    val saved = favorites.any { it.slug == gif.slug }
    OverlayIconButton(
        onClick = { viewModel.toggleFavorite(gif) },
        icon = if (saved) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
        label = if (saved) AppStrings.get(context, R.string.catalog_remove_from_favourites_624f82c1) else AppStrings.get(context, R.string.catalog_favourite_this_gif_a9fd24af),
    )
}

private fun isGif(attachment: Attachment): Boolean =
    attachment.contentType == "image/gif" || attachment.filename.endsWith(".gif", ignoreCase = true)
