package lt.oranges.orangchat.feature.chat

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.view.TextureView
import android.net.Uri
import androidx.annotation.OptIn
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fullscreen
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import lt.oranges.orangchat.crypto.E2ee
import lt.oranges.orangchat.crypto.E2eeKeystore
import lt.oranges.orangchat.data.model.Attachment
import lt.oranges.orangchat.ui.theme.OrangRadius
import lt.oranges.orangchat.ui.theme.OrangTheme
import lt.oranges.orangchat.util.videoPosterUrl
import java.io.File
import java.io.FileOutputStream
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

/**
 * What stands behind the play button on an encrypted clip, from three sources,
 * because none of them is dependable on its own:
 *
 * * the **sealed thumbnail blob** - sharp, but a fetch and a decrypt away, and
 *   missing outright on every clip whose second upload failed, was never
 *   claimed, or has since been swept;
 * * the **inline blur** carried in the message payload - 24px, but it arrives
 *   with the text, needs no network, and cannot go missing separately;
 * * a **frame pulled out of the decrypted file** - free once the clip has been
 *   played, and the only thing that ever rescues a clip sent before the blur
 *   existed and without a surviving thumbnail.
 *
 * They are tried in that order because that is the order of quality, not the
 * order of arrival: the blur is on screen first and is replaced when something
 * better resolves.
 */
@Composable
private fun rememberSealedPoster(
    attachment: Attachment,
    all: List<Attachment>,
    decrypted: String?,
): String? {
    val context = LocalContext.current
    val keystore = remember(context) { E2eeKeystore.get(context) }
    val sealedRef = remember(attachment.id) { keystore.sealedAttachment(attachment.id) }

    var sharp by remember(attachment.id) { mutableStateOf<String?>(null) }
    var blur by remember(attachment.id) { mutableStateOf<String?>(null) }
    var local by remember(attachment.id) { mutableStateOf<String?>(null) }

    // The thumbnail's key is right here in the parent ref; the row is looked up
    // only to learn the url its ciphertext is served from.
    val thumbRow = remember(sealedRef, all) {
        sealedRef?.thumb?.let { t -> all.firstOrNull { it.id == t.attachmentId } }
    }
    LaunchedEffect(thumbRow?.id) {
        val row = thumbRow ?: return@LaunchedEffect
        val thumb = sealedRef?.thumb ?: return@LaunchedEffect
        val opened = SealedFiles.open(
            context,
            row,
            sealedRef.copy(
                fileId = thumb.fileId,
                attachmentId = thumb.attachmentId,
                key = thumb.key,
                nonce = thumb.nonce,
                contentType = thumb.contentType,
                size = thumb.size,
                blur = null,
                thumb = null,
            ),
        ) ?: return@LaunchedEffect
        // Coil guesses a file's type from its extension, and the decrypted
        // cache file has none - so keep a .jpg copy for the loader.
        sharp = withContext(Dispatchers.IO) {
            runCatching { cachedPoster(context, "poster-${row.id}.jpg", opened) }.getOrNull()
        }
    }

    LaunchedEffect(attachment.id, sealedRef?.blur) {
        val encoded = sealedRef?.blur ?: return@LaunchedEffect
        blur = withContext(Dispatchers.IO) {
            runCatching {
                val file = File(context.cacheDir, "blur-${attachment.id}.jpg")
                if (!file.isFile || file.length() == 0L) file.writeBytes(E2ee.fromBase64(encoded))
                Uri.fromFile(file).toString()
            }.getOrNull()
        }
    }

    // Last resort, and the only one that works on a clip nothing was ever
    // uploaded for: the bytes are already decrypted on this device, so pull a
    // frame out of them.
    LaunchedEffect(attachment.id, decrypted, sharp) {
        if (sealedRef == null || decrypted == null || sharp != null) return@LaunchedEffect
        local = withContext(Dispatchers.IO) { extractPoster(context, attachment.id, decrypted) }
    }

    return sharp ?: local ?: blur
}

/** A decrypted blob under a name Coil will recognise as a JPEG. */
private fun cachedPoster(context: Context, name: String, opened: File): String {
    val poster = File(context.cacheDir, name)
    if (!poster.exists() || poster.lastModified() < opened.lastModified()) {
        opened.copyTo(poster, overwrite = true)
    }
    return Uri.fromFile(poster).toString()
}

/**
 * A still taken from an already-decrypted clip. A hair past the very start,
 * because some encoders paint their first frames black. Costs nothing on the
 * network and nothing on the server, which cannot decode these bytes anyway.
 */
private fun extractPoster(context: Context, attachmentId: String, source: String): String? {
    val poster = File(context.cacheDir, "frame-$attachmentId.jpg")
    if (poster.isFile && poster.length() > 0) return Uri.fromFile(poster).toString()

    val retriever = MediaMetadataRetriever()
    return try {
        runCatching { retriever.setDataSource(context, Uri.parse(source)) }
            .getOrElse { return null }
        val frame = runCatching {
            retriever.getFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        }.getOrNull() ?: return null
        try {
            FileOutputStream(poster).use { frame.compress(Bitmap.CompressFormat.JPEG, 80, it) }
            Uri.fromFile(poster).toString()
        } catch (_: Exception) {
            poster.delete()
            null
        } finally {
            frame.recycle()
        }
    } finally {
        runCatching { retriever.release() }
    }
}

@Composable
fun VideoAttachment(
    attachment: Attachment,
    expiresAt: Instant?,
    now: Instant,
    all: List<Attachment> = emptyList(),
) {
    val c = OrangTheme.colors
    val context = LocalContext.current
    // A sealed clip is not fetched until it is asked for: decrypting one means
    // downloading all of it, and a channel of videos doing that on sight is
    // what made previews take longer the bigger the file was.
    var requested by remember(attachment.id) { mutableStateOf(false) }
    val source = rememberAttachmentSource(attachment, wanted = requested)
    val href = source.url
    var broken by remember(attachment.id) { mutableStateOf(false) }
    var previewOpen by remember(attachment.id) { mutableStateOf(false) }
    val previewLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        previewOpen = false
    }

    val sealedPoster = rememberSealedPoster(attachment, all, decrypted = href)

    if (broken || source.unavailable) {
        FileCard(attachment, expiresAt, now)
        return
    }

    // Play was pressed before the bytes were readable; start as soon as they are.
    LaunchedEffect(href, requested) {
        if (requested && href != null && MediaPlayback.currentId != attachment.id) {
            MediaPlayback.toggle(context, attachment.id, href) { broken = true }
        }
    }

    val active = MediaPlayback.currentId == attachment.id
    val player = if (active) MediaPlayback.playerFor(attachment.id) else null
    val storedPoster = remember(attachment.id) { videoPosterUrl(attachment) }
    val posterUrl = sealedPoster ?: storedPoster?.url

    val metadataAspect = remember(attachment.width, attachment.height) {
        val w = attachment.width
        val h = attachment.height
        if (w != null && h != null && w > 0 && h > 0) w.toFloat() / h else null
    }
    // The still is a frame of this clip, so its shape is the clip's shape - which
    // is what keeps the row from jumping to a different size on the first press.
    var posterAspect by remember(attachment.id) { mutableStateOf<Float?>(null) }
    val knownAspect = metadataAspect ?: posterAspect
    val decoderAspect = if (active && MediaPlayback.videoAspect > 0f) MediaPlayback.videoAspect else null
    val trueAspect = decoderAspect ?: knownAspect ?: DEFAULT_ASPECT
    val boxAspect = (knownAspect ?: trueAspect).coerceIn(MIN_INLINE_ASPECT, MAX_INLINE_ASPECT)
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
                    bind = !previewOpen,
                    aspect = trueAspect,
                    modifier = Modifier.fillMaxSize(),
                )
            } else if (posterUrl != null) {
                // Standing in until playback starts, so an unplayed clip shows
                // what it is rather than a black rectangle. Only ever a real
                // image - see videoPosterUrl for why the clip itself is not one.
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(posterUrl)
                        .crossfade(true)
                        .build(),
                    contentDescription = attachment.filename,
                    contentScale = ContentScale.Fit,
                    onSuccess = { state ->
                        val size = state.painter.intrinsicSize
                        if (size.width > 0f && size.height > 0f) {
                            posterAspect = size.width / size.height
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }

            InlineOverlay(
                attachment = attachment,
                active = active,
                // Nothing to toggle yet when the file still has to be fetched;
                // asking for it is what the first press means.
                fetching = source.resolving,
                progress = source.progress,
                phaseLabel = source.phaseLabel,
                onToggle = {
                    if (href == null) requested = true
                    else MediaPlayback.toggle(context, attachment.id, href) { broken = true }
                },
                onExpand = {
                    // Resolve sealed media in parallel with the activity. The
                    // launcher result keeps the inline TextureView from stealing
                    // the shared player surface while fullscreen owns it.
                    requested = true
                    previewOpen = true
                    previewLauncher.launch(MediaPreviewActivity.intent(context, attachment))
                },
            )
        }
        expiresAt?.let {
            Spacer(Modifier.size(2.dp))
            Text(expiryLabel(it, now), color = c.warning, fontSize = 11.sp)
        }
    }

}

@Composable
private fun InlineOverlay(
    attachment: Attachment,
    active: Boolean,
    /** The file is still being fetched and decrypted, before playback can start. */
    fetching: Boolean = false,
    /** 0-1 of that fetch, so a large clip isn't a spinner with nothing behind it. */
    progress: Float = 0f,
    /** Which half of it - "Downloading" or "Decrypting". */
    phaseLabel: String = "Downloading",
    onToggle: () -> Unit,
    onExpand: () -> Unit,
) {
    val isPlaying = active && MediaPlayback.isPlaying
    val buffering = fetching || (active && MediaPlayback.buffering)
    val seekable = active && MediaPlayback.ready
    // The sender measured the length at upload, so it is on the still before
    // any of the clip is.
    val durationMs = if (active) {
        MediaPlayback.durationMs
    } else {
        ((attachment.duration ?: 0.0) * 1000).toLong()
    }

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
                    // Determinate while the file itself is coming down: a 40 MB
                    // clip behind a spinner that never moves reads as broken.
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        if (fetching && progress > 0f) {
                            CircularProgressIndicator(
                                progress = { progress },
                                color = Color.White,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(28.dp),
                            )
                        } else {
                            CircularProgressIndicator(
                                color = Color.White,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(28.dp),
                            )
                        }
                        // Which step it is on. Downloading and decrypting both
                        // run 0-100%, and without this the bar looks like it
                        // restarted itself for no reason halfway through.
                        if (fetching) {
                            Spacer(Modifier.size(6.dp))
                            Text(
                                phaseLabel,
                                color = Color.White.copy(alpha = 0.85f),
                                fontSize = 10.sp,
                            )
                        }
                    }
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
internal fun VideoSurface(
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
