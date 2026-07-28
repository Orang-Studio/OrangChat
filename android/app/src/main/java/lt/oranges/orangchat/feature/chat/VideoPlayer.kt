package lt.oranges.orangchat.feature.chat

import android.view.TextureView
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import lt.oranges.orangchat.ui.components.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.request.videoFrameMillis
import kotlinx.coroutines.delay
import lt.oranges.orangchat.data.model.Attachment
import lt.oranges.orangchat.ui.theme.OrangRadius
import lt.oranges.orangchat.ui.theme.OrangTheme
import lt.oranges.orangchat.util.absoluteUrl
import lt.oranges.orangchat.util.videoPosterUrl
import java.time.Instant

/*
 * Two constraints this must not regress back into:
 *
 * TextureView, never SurfaceView (i.e. never VideoView). A SurfaceView is a
 * separate window punched through the one Compose draws into, so it ignores
 * z-order and clipping and paints over whatever is above it.
 *
 * Compose controls, never MediaController. MediaController is a PopupWindow and
 * takes window focus when shown, which leaves the composer's keyboard unable to
 * open while a video is on screen.
 */

/** Layout bounds for the inline box. Beyond these the video letterboxes inside it. */
private const val MIN_INLINE_ASPECT = 0.6f
private const val MAX_INLINE_ASPECT = 2.0f
private const val DEFAULT_ASPECT = 16f / 9f

/**
 * Deliberately about half the width images get. A clip is a thing you tap to
 * watch, and at image size it dominated the channel before anyone chose to.
 */
private val INLINE_VIDEO_WIDTH = 150.dp

@Composable
fun VideoAttachment(attachment: Attachment, expiresAt: Instant?, now: Instant) {
    val c = OrangTheme.colors
    val context = LocalContext.current
    val href = rememberResolvedAttachmentUrl(attachment)
    var broken by remember(attachment.id) { mutableStateOf(false) }
    var expanded by remember(attachment.id) { mutableStateOf(false) }

    if (broken || href == null) {
        FileCard(attachment, expiresAt, now)
        return
    }

    val active = MediaPlayback.currentId == attachment.id
    val player = if (active) MediaPlayback.playerFor(attachment.id) else null
    val poster = remember(attachment.id) { videoPosterUrl(attachment) }

    // Upload metadata is only a guess to lay out with until the decoder reports
    // the real shape.
    val metadataAspect = remember(attachment.width, attachment.height) {
        val w = attachment.width
        val h = attachment.height
        if (w != null && h != null && w > 0 && h > 0) w.toFloat() / h else DEFAULT_ASPECT
    }
    val trueAspect = if (active && MediaPlayback.videoAspect > 0f) MediaPlayback.videoAspect else metadataAspect
    val boxAspect = trueAspect.coerceIn(MIN_INLINE_ASPECT, MAX_INLINE_ASPECT)
    val shape = RoundedCornerShape(OrangRadius.lg)

    Column {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = INLINE_VIDEO_WIDTH)
                .aspectRatio(boxAspect)
                .clip(shape)
                .background(Color.Black),
        ) {
            if (player != null) {
                // While the lightbox is up it owns the output; this must not
                // snatch it back.
                VideoSurface(
                    player = player,
                    bind = !expanded,
                    aspect = trueAspect,
                    modifier = Modifier.fillMaxSize(),
                )
            } else if (poster != null) {
                // Standing in until playback starts, so an unplayed clip shows
                // what it is rather than a black rectangle.
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(poster.url)
                        .apply { if (poster.decodeFrame) videoFrameMillis(0) }
                        .crossfade(true)
                        .build(),
                    contentDescription = attachment.filename,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            InlineOverlay(
                attachment = attachment,
                active = active,
                onToggle = {
                    MediaPlayback.toggle(context, attachment.id, href) { broken = true }
                },
                onExpand = {
                    if (!active) MediaPlayback.toggle(context, attachment.id, href) { broken = true }
                    expanded = true
                },
            )
        }
        expiresAt?.let {
            Spacer(Modifier.size(2.dp))
            Text(expiryLabel(it, now), color = c.warning, fontSize = 11.sp)
        }
    }

    if (expanded) {
        VideoLightbox(
            attachment = attachment,
            onDismiss = { expanded = false },
            onBroken = { broken = true },
        )
    }
}

@Composable
private fun InlineOverlay(
    attachment: Attachment,
    active: Boolean,
    onToggle: () -> Unit,
    onExpand: () -> Unit,
) {
    val isPlaying = active && MediaPlayback.isPlaying
    val buffering = active && MediaPlayback.buffering
    val seekable = active && MediaPlayback.ready
    val durationMs = if (active) MediaPlayback.durationMs else 0L

    LaunchedEffect(active, isPlaying) {
        while (active && isPlaying) {
            MediaPlayback.syncPosition()
            delay(200)
        }
    }

    Box(Modifier.fillMaxSize()) {
        if (!isPlaying || buffering) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (buffering) {
                    CircularProgressIndicator(
                        color = Color.White,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(28.dp),
                    )
                } else {
                    OverlayIconButton(
                        onClick = onToggle,
                        size = 56.dp,
                        icon = Icons.Default.PlayArrow,
                        label = "Play ${attachment.filename}",
                        iconSize = 30.dp,
                    )
                }
            }
        }

        Box(Modifier.align(Alignment.TopEnd)) {
            OverlayIconButton(
                onClick = onExpand,
                icon = Icons.Default.Fullscreen,
                label = "Expand ${attachment.filename}",
            )
        }

        if (isPlaying) {
            Box(Modifier.align(Alignment.TopStart)) {
                OverlayIconButton(
                    onClick = onToggle,
                    icon = Icons.Default.Pause,
                    label = "Pause ${attachment.filename}",
                )
            }
        }

        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.45f))
                .padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Scrubber(
                positionMs = if (active) MediaPlayback.positionMs else 0L,
                durationMs = durationMs,
                enabled = seekable,
                onSeek = { MediaPlayback.seekTo(it) },
                accent = Color.White,
                track = Color.White.copy(alpha = 0.3f),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                if (durationMs > 0) {
                    "${formatDuration(if (active) MediaPlayback.positionMs else 0L)} / ${formatDuration(durationMs)}"
                } else {
                    formatBytes(attachment.size)
                },
                color = Color.White.copy(alpha = 0.85f),
                fontSize = 10.sp,
                maxLines = 1,
            )
        }
    }
}

/**
 * A TextureView stretches its content to its own bounds, so the surface has to
 * be given exactly the video's ratio and centred - otherwise the picture
 * distorts to fill rather than letterboxing.
 */
@OptIn(UnstableApi::class)
@Composable
private fun VideoSurface(
    player: ExoPlayer,
    bind: Boolean,
    aspect: Float,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val containerAspect = if (maxHeight > 0.dp) maxWidth / maxHeight else aspect
        AndroidView(
            factory = { TextureView(it) },
            modifier = Modifier.aspectRatio(
                ratio = aspect,
                // Fit: whichever axis runs out first is the one to match.
                matchHeightConstraintsFirst = aspect < containerAspect,
            ),
            update = { view -> if (bind) player.setVideoTextureView(view) },
            // No-ops unless this view still owns the output, so the lightbox
            // taking over and then this being disposed can't blank the video.
            // runCatching: a playback error releases the player a frame before
            // this surface is disposed.
            onRelease = { view -> runCatching { player.clearVideoTextureView(view) } },
        )
    }
}

/** Full-screen playback. Downloading is intentionally only offered here. */
@Composable
private fun VideoLightbox(
    attachment: Attachment,
    onDismiss: () -> Unit,
    onBroken: () -> Unit,
) {
    val context = LocalContext.current
    val download = rememberAttachmentDownloader()
    val href = rememberResolvedAttachmentUrl(attachment) ?: return

    val active = MediaPlayback.currentId == attachment.id
    val player = if (active) MediaPlayback.playerFor(attachment.id) else null
    val isPlaying = active && MediaPlayback.isPlaying
    val durationMs = if (active) MediaPlayback.durationMs else 0L
    val aspect = if (active && MediaPlayback.videoAspect > 0f) {
        MediaPlayback.videoAspect
    } else {
        val w = attachment.width
        val h = attachment.height
        if (w != null && h != null && w > 0 && h > 0) w.toFloat() / h else DEFAULT_ASPECT
    }

    var chromeVisible by remember { mutableStateOf(true) }
    LaunchedEffect(chromeVisible, isPlaying) {
        if (chromeVisible && isPlaying) {
            delay(3000)
            chromeVisible = false
        }
    }
    LaunchedEffect(active, isPlaying) {
        while (active && isPlaying) {
            MediaPlayback.syncPosition()
            delay(200)
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            if (player != null) {
                VideoSurface(
                    player = player,
                    bind = true,
                    aspect = aspect,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            Box(
                Modifier
                    .fillMaxSize()
                    .tapToToggle { chromeVisible = !chromeVisible },
            )

            if (chromeVisible) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                        .background(Color.Black.copy(alpha = 0.55f))
                        .systemBarsPadding()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
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
                    Spacer(Modifier.width(8.dp))
                    OverlayIconButton(
                        onClick = { download(attachment) },
                        icon = Icons.Default.Download,
                        label = "Download ${attachment.filename}",
                    )
                    OverlayIconButton(
                        onClick = onDismiss,
                        icon = Icons.Default.FullscreenExit,
                        label = "Exit full screen",
                    )
                }

                Row(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.55f))
                        .systemBarsPadding()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OverlayIconButton(
                        onClick = {
                            MediaPlayback.toggle(context, attachment.id, href) { onBroken() }
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
                        onSeek = { MediaPlayback.seekTo(it) },
                        accent = Color.White,
                        track = Color.White.copy(alpha = 0.3f),
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        formatDuration(durationMs),
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 11.sp,
                    )
                }
            }
        }
    }
}
