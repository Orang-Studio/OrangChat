package lt.oranges.orangchat.feature.chat

import lt.oranges.orangchat.R
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.minimumInteractiveComponentSize
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import lt.oranges.orangchat.crypto.E2eeKeystore
import lt.oranges.orangchat.data.model.Attachment
import lt.oranges.orangchat.ui.components.ButtonSize
import lt.oranges.orangchat.ui.components.OrangButton
import lt.oranges.orangchat.ui.theme.OrangRadius
import lt.oranges.orangchat.ui.theme.OrangTheme
import lt.oranges.orangchat.util.AppStrings
import lt.oranges.orangchat.util.absoluteUrl
import java.time.Instant
import java.time.format.DateTimeParseException


private val MAX_IMAGE_WIDTH = 240.dp
private val MAX_IMAGE_HEIGHT = 360.dp
private const val DEFAULT_IMAGE_ASPECT = 4f / 3f

fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB")
    var value = bytes.toDouble() / 1024
    var unit = 0
    while (value >= 1024 && unit < units.size - 1) {
        value /= 1024
        unit++
    }
    return if (value < 10) "%.1f %s".format(value, units[unit]) else "%.0f %s".format(value, units[unit])
}

private fun expiryInstant(attachment: Attachment): Instant? =
    attachment.expiresAt?.let {
        try {
            Instant.parse(it)
        } catch (_: DateTimeParseException) {
            null
        }
    }

internal fun expiryLabel(expiresAt: Instant, now: Instant): String {
    val remaining = expiresAt.epochSecond - now.epochSecond
    if (remaining <= 0) return "Expired"
    val minutes = Math.round(remaining / 60.0)
    return if (minutes < 1) "Expires in under a minute" else "Expires in $minutes min"
}

@Composable
private fun rememberNow(active: Boolean): Instant {
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(active) {
        while (active) {
            delay(30_000)
            now = Instant.now()
        }
    }
    return now
}


@Composable
fun ComposerAttachments(
    uploads: List<AttachmentDraftViewModel.PendingUpload>,
    onRemove: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (uploads.isEmpty()) return
    LazyRow(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(uploads, key = { it.key }) { upload ->
            UploadChip(upload, onRemove = { onRemove(upload.key) })
        }
    }
}

@Composable
private fun UploadChip(
    upload: AttachmentDraftViewModel.PendingUpload,
    onRemove: () -> Unit,
) {
        val context = LocalContext.current
    val c = OrangTheme.colors
    val shape = RoundedCornerShape(OrangRadius.xl)

    Column(
        modifier = Modifier
            .width(180.dp)
            .background(c.surface4, shape)
            .border(1.dp, if (upload.error != null) c.danger else c.border, shape)
            .padding(8.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            if (upload.previewUri != null) {
                AsyncImage(
                    model = upload.previewUri,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(OrangRadius.md))
                        .background(c.surface2),
                )
            } else {
                Box(
                    modifier = Modifier.size(36.dp).background(c.surface2, RoundedCornerShape(OrangRadius.md)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Default.AttachFile, null, tint = c.inkMuted, modifier = Modifier.size(16.dp))
                }
            }
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    upload.name,
                    color = c.ink,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(formatBytes(upload.size), color = c.inkMuted, fontSize = 11.sp)
            }
            Icon(
                Icons.Default.Close,
                contentDescription = if (upload.settled) "Remove ${upload.name}" else "Cancel upload of ${upload.name}",
                tint = c.inkMuted,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .size(16.dp)
                    .clickable(onClick = onRemove),
            )
        }

        if (!upload.settled) {
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { upload.progress },
                color = c.primary,
                trackColor = c.surface1,
                modifier = Modifier.fillMaxWidth().height(3.dp),
            )
        }

        upload.error?.let {
            Spacer(Modifier.height(4.dp))
            Text(it, color = c.danger, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }

        if (upload.error == null && upload.ephemeral) {
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Schedule, null, tint = c.warning, modifier = Modifier.size(11.dp))
                Spacer(Modifier.width(3.dp))
                Text(AppStrings.get(context, R.string.catalog_expires_in_1_hour_26e2eae7), color = c.warning, fontSize = 11.sp)
            }
        }
    }
}


@Composable
fun MessageAttachments(attachments: List<Attachment>, modifier: Modifier = Modifier) {
    if (attachments.isEmpty()) return
    val expiries = attachments.mapNotNull { expiryInstant(it) }
    val now = rememberNow(active = expiries.any { it.isAfter(Instant.now()) })
    val context = LocalContext.current
    val keystore = remember(context) { E2eeKeystore.get(context) }

    Column(modifier = modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (attachment in attachments) {
            if (keystore.isSealedThumbnail(attachment.id)) continue
            val expiresAt = expiryInstant(attachment)
            when {
                attachment.flagged -> FlaggedAttachment(attachment, expiresAt, now)
                expiresAt != null && !expiresAt.isAfter(now) -> ExpiredCard(attachment)
                attachment.isImage -> ImagePreview(attachment, expiresAt, now)
                attachment.isAudio -> AudioCard(attachment, expiresAt, now)
                attachment.isVideo -> VideoAttachment(attachment, expiresAt, now, attachments)
                else -> FileCard(attachment, expiresAt, now)
            }
        }
    }
}

@Composable
private fun FlaggedAttachment(attachment: Attachment, expiresAt: Instant?, now: Instant) {
        val context = LocalContext.current
    var revealed by remember(attachment.id) { mutableStateOf(false) }
    if (revealed) {
        ImagePreview(attachment, expiresAt, now)
        return
    }

    val c = OrangTheme.colors
    val shape = RoundedCornerShape(OrangRadius.lg)
    Row(
        modifier = Modifier
            .widthIn(max = 280.dp)
            .background(c.surface1, shape)
            .border(1.dp, c.border, shape)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.Warning, null, tint = c.danger, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(AppStrings.get(context, R.string.catalog_inappropriate_content_62db8957), color = c.ink, fontSize = 12.sp)
            Text(
                "Hidden by automatic moderation · ${attachment.filename}",
                color = c.inkMuted,
                fontSize = 11.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        OrangButton(text = "Show", onClick = { revealed = true }, size = ButtonSize.Sm)
    }
}

@Composable
private fun ExpiredCard(attachment: Attachment) {
        val context = LocalContext.current
    val c = OrangTheme.colors
    val shape = RoundedCornerShape(OrangRadius.lg)
    Row(
        modifier = Modifier
            .widthIn(max = 280.dp)
            .background(c.surface1, shape)
            .border(1.dp, c.border, shape)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.Schedule, null, tint = c.inkMuted, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Column {
            Text(
                attachment.filename,
                color = c.inkMuted,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textDecoration = TextDecoration.LineThrough,
            )
            Text(AppStrings.get(context, R.string.catalog_expired_large_files_are_only_kept_for_a9b2c12d), color = c.inkMuted, fontSize = 11.sp)
        }
    }
}

@Composable
internal fun FileCard(attachment: Attachment, expiresAt: Instant?, now: Instant) {
    val c = OrangTheme.colors
    val shape = RoundedCornerShape(OrangRadius.lg)
    val download = rememberAttachmentDownloader()

    Row(
        modifier = Modifier
            .widthIn(max = 280.dp)
            .background(c.surface1, shape)
            .border(1.dp, c.border, shape)
            .clickable { download(attachment) }
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.AttachFile, null, tint = c.inkMuted, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                attachment.filename,
                color = c.ink,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                buildString {
                    append(formatBytes(attachment.size))
                    expiresAt?.let { append(" · ${expiryLabel(it, now)}") }
                },
                color = c.inkMuted,
                fontSize = 11.sp,
            )
        }
        Icon(Icons.Default.Download, "Download ${attachment.filename}", tint = c.inkMuted, modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun ImagePreview(attachment: Attachment, expiresAt: Instant?, now: Instant) {
    val c = OrangTheme.colors
    val context = LocalContext.current
    val view = LocalView.current
    val origin = rememberMediaOrigin()
    var broken by remember(attachment.id) { mutableStateOf(false) }
    val source = rememberAttachmentSource(attachment)
    if (broken || source.unavailable) {
        FileCard(attachment, expiresAt, now)
        return
    }
    val href = source.url
    val shape = RoundedCornerShape(OrangRadius.xl2)
    val aspect = remember(attachment.width, attachment.height) {
        val w = attachment.width
        val h = attachment.height
        if (w != null && h != null && w > 0 && h > 0) w.toFloat() / h.toFloat() else DEFAULT_IMAGE_ASPECT
    }
    val widthFromHeightCap = MAX_IMAGE_HEIGHT * aspect
    val sizeModifier = if (widthFromHeightCap <= MAX_IMAGE_WIDTH) {
        Modifier.width(widthFromHeightCap).height(MAX_IMAGE_HEIGHT)
    } else {
        Modifier.width(MAX_IMAGE_WIDTH).height(MAX_IMAGE_WIDTH / aspect)
    }

    Column {
        if (href == null) {
            Box(
                modifier = sizeModifier
                    .clip(shape)
                    .background(c.surface1),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (source.progress > 0f) {
                        CircularProgressIndicator(
                            progress = { source.progress },
                            color = c.primary,
                            modifier = Modifier.size(20.dp),
                        )
                    } else {
                        CircularProgressIndicator(color = c.primary, modifier = Modifier.size(20.dp))
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(source.phaseLabel, color = c.inkMuted, fontSize = 10.sp)
                }
            }
        } else {
            AsyncImage(
                model = href,
                contentDescription = attachment.filename,
                contentScale = ContentScale.Fit,
                onError = { broken = true },
                modifier = sizeModifier
                    .clip(shape)
                    .background(c.surface1)
                    .mediaOrigin(origin)
                    .clickable {
                        openMediaPreview(context, view, origin, attachment)
                    },
            )
        }
        expiresAt?.let {
            Spacer(Modifier.height(2.dp))
            Text(expiryLabel(it, now), color = c.warning, fontSize = 11.sp)
        }
    }

}

@Composable
internal fun AudioCard(attachment: Attachment, expiresAt: Instant?, now: Instant) {
    val c = OrangTheme.colors
    val shape = RoundedCornerShape(OrangRadius.lg)
    val download = rememberAttachmentDownloader()
    var requested by remember(attachment.id) { mutableStateOf(false) }
    val source = rememberAttachmentSource(attachment, wanted = requested)
    val href = source.url

    var broken by remember(attachment.id) { mutableStateOf(false) }
    if (broken || source.unavailable) {
        FileCard(attachment, expiresAt, now)
        return
    }

    val context = LocalContext.current
    val active = MediaPlayback.currentId == attachment.id
    val isPlaying = active && MediaPlayback.isPlaying
    val loading = source.resolving || (active && MediaPlayback.buffering)

    LaunchedEffect(href, requested) {
        if (requested && href != null && MediaPlayback.currentId != attachment.id) {
            MediaPlayback.toggle(context, attachment.id, href) { broken = true }
        }
    }
    val durationMs = if (active) {
        MediaPlayback.durationMs
    } else {
        ((attachment.duration ?: 0.0) * 1000).toLong()
    }
    val seekable = active && MediaPlayback.ready

    LaunchedEffect(active, isPlaying) {
        while (active && isPlaying) {
            MediaPlayback.syncPosition()
            delay(200)
        }
    }

    var scrub by remember(attachment.id) { mutableStateOf<Long?>(null) }
    val positionMs = if (active) MediaPlayback.positionMs else 0L

    Row(
        modifier = Modifier
            .widthIn(max = 280.dp)
            .background(c.surface1, shape)
            .border(1.dp, c.border, shape)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .background(c.primary, CircleShape)
                .tapToToggle {
                    if (href == null) requested = true
                    else MediaPlayback.toggle(context, attachment.id, href) { broken = true }
                },
            contentAlignment = Alignment.Center,
        ) {
            if (loading) {
                CircularProgressIndicator(
                    color = c.inkOnPrimary,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(14.dp),
                )
            } else {
                Icon(
                    if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (isPlaying) "Pause ${attachment.filename}" else "Play ${attachment.filename}",
                    tint = c.inkOnPrimary,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        Spacer(Modifier.width(8.dp))

        Column(Modifier.weight(1f)) {
            Text(
                attachment.filename,
                color = c.ink,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Scrubber(
                positionMs = positionMs,
                durationMs = durationMs,
                enabled = seekable,
                onSeek = { MediaPlayback.seekTo(it) },
                onScrubChange = { scrub = it },
                accent = if (seekable) c.primary else c.inkMuted,
                track = c.surface4,
            )
            Text(
                buildString {
                    append(formatDuration(scrub ?: positionMs))
                    if (durationMs > 0) append(" / ${formatDuration(durationMs)}")
                    if (attachment.size > 0) append(" · ${formatBytes(attachment.size)}")
                    expiresAt?.let { append(" · ${expiryLabel(it, now)}") }
                },
                color = c.inkMuted,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(6.dp))
        Box(
            modifier = Modifier.size(32.dp).tapToToggle { download(attachment) },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Default.Download,
                contentDescription = "Download ${attachment.filename}",
                tint = c.inkMuted,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}
