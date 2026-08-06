package lt.oranges.orangchat.feature.chat

import android.content.Context
import android.content.Intent
import android.graphics.Color as AndroidColor
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import lt.oranges.orangchat.data.model.Attachment
import lt.oranges.orangchat.ui.components.Text
import lt.oranges.orangchat.ui.theme.OrangChatTheme
import lt.oranges.orangchat.util.absoluteUrl
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

/**
 * A real full-screen window for attachment previews.
 *
 * This deliberately is not a Compose Dialog. Dialog windows kept a platform
 * wrap-content height on some devices, which moved the media centre and bottom
 * controls beyond the physical display. An activity owns the display bounds,
 * system bars and back gesture directly, so there is one coordinate system.
 */
@AndroidEntryPoint
class MediaPreviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.statusBarColor = AndroidColor.BLACK
        window.navigationBarColor = AndroidColor.BLACK

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

    companion object {
        private const val EXTRA_ATTACHMENT = "attachment"

        fun intent(context: Context, attachment: Attachment): Intent =
            Intent(context, MediaPreviewActivity::class.java)
                .putExtra(EXTRA_ATTACHMENT, MediaPreviewTransport.encode(attachment))
    }
}

@Composable
private fun FullscreenMediaPreview(attachment: Attachment, onClose: () -> Unit) {
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
            )
            else -> FullscreenImage(
                attachment = attachment,
                url = source.url,
                onToggleChrome = { chromeVisible = !chromeVisible },
            )
        }

        if (chromeVisible) {
            PreviewTopBar(
                attachment = attachment,
                resolvedUrl = source.url,
                onDownload = { download(attachment) },
                onClose = onClose,
            )
        }
    }
}

@Composable
private fun PreviewLoading(source: AttachmentSource, onToggleChrome: () -> Unit) {
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
                if (source.unavailable) "This media is unavailable" else source.phaseLabel,
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
            Text("Could not open this image", color = Color.White.copy(alpha = 0.75f))
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
) {
    val context = LocalContext.current
    var broken by remember(attachment.id) { mutableStateOf(false) }

    LaunchedEffect(attachment.id, url) {
        if (MediaPlayback.currentId == attachment.id) {
            // Claim errors for the visible owner even when playback started in
            // the message row before this activity opened.
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
                "Could not play this video",
                color = Color.White.copy(alpha = 0.75f),
                modifier = Modifier.align(Alignment.Center),
            )
        } else if (player == null || MediaPlayback.buffering) {
            CircularProgressIndicator(
                color = Color.White,
                modifier = Modifier.align(Alignment.Center),
            )
        }

        // The media itself owns chrome toggling. Controls are rendered after
        // this layer, so their gestures do not leak through and hide the UI.
        Box(Modifier.fillMaxSize().tapToToggle(onToggleChrome))

        if (chromeVisible) {
            VideoControlBar(
                attachment = attachment,
                url = url,
                active = active,
                isPlaying = isPlaying,
                durationMs = durationMs,
                onBroken = { broken = true },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
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
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.72f))
            .tapToToggle { }
            .navigationBarsPadding()
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
        label = if (saved) "Remove from favourites" else "Favourite this GIF",
    )
}

private fun isGif(attachment: Attachment): Boolean =
    attachment.contentType == "image/gif" || attachment.filename.endsWith(".gif", ignoreCase = true)
