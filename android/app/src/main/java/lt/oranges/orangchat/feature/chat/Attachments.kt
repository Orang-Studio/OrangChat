package lt.oranges.orangchat.feature.chat

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
import lt.oranges.orangchat.ui.components.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import lt.oranges.orangchat.data.model.Attachment
import lt.oranges.orangchat.ui.components.ButtonSize
import lt.oranges.orangchat.ui.components.OrangButton
import lt.oranges.orangchat.ui.theme.OrangRadius
import lt.oranges.orangchat.ui.theme.OrangTheme
import lt.oranges.orangchat.util.absoluteUrl
import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * Attachment rendering, both in the composer (uploads in flight) and on sent
 * messages.
 *
 * Anything over 10MB lives on OrangMove, which deletes it within the hour, so
 * those carry an `expiresAt`. That deadline is shown rather than hidden: a
 * countdown that ends in "Expired" explains itself, where a download that
 * silently starts failing does not.
 */

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

/** Ticks while a countdown is live so it doesn't sit at a stale number. */
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

// ── Composer: uploads in flight ─────────────────────────

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
                    modifier = Modifier.size(36.dp).background(c.surface2, RoundedCornerShape(OrangRadius.md)),
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
                modifier = Modifier.size(16.dp).clickable(onClick = onRemove),
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

        // Say this while they can still change their mind, not after it's gone.
        if (upload.error == null && upload.ephemeral) {
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Schedule, null, tint = c.warning, modifier = Modifier.size(11.dp))
                Spacer(Modifier.width(3.dp))
                Text("Expires in 1 hour", color = c.warning, fontSize = 11.sp)
            }
        }
    }
}

// ── Sent messages ───────────────────────────────────────

@Composable
fun MessageAttachments(attachments: List<Attachment>, modifier: Modifier = Modifier) {
    if (attachments.isEmpty()) return
    val expiries = attachments.mapNotNull { expiryInstant(it) }
    val now = rememberNow(active = expiries.any { it.isAfter(Instant.now()) })

    Column(modifier = modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (attachment in attachments) {
            val expiresAt = expiryInstant(attachment)
            when {
                attachment.flagged -> FlaggedAttachment(attachment, expiresAt, now)
                expiresAt != null && !expiresAt.isAfter(now) -> ExpiredCard(attachment)
                attachment.isImage -> ImagePreview(attachment, expiresAt, now)
                attachment.isAudio -> AudioCard(attachment, expiresAt, now)
                attachment.isVideo -> VideoAttachment(attachment, expiresAt, now)
                else -> FileCard(attachment, expiresAt, now)
            }
        }
    }
}

/** Keep moderated pixels unloaded until this viewer explicitly opts in. */
@Composable
private fun FlaggedAttachment(attachment: Attachment, expiresAt: Instant?, now: Instant) {
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
            Text("Inappropriate content", color = c.ink, fontSize = 12.sp)
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
            Text("Expired - large files are only kept for an hour", color = c.inkMuted, fontSize = 11.sp)
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
            // Straight to the download manager rather than out to the browser:
            // the URL is served as a download either way, so the round trip
            // through a second app bought nothing but a lost place in the chat.
            //
            // Deliberately not gated on the inline source resolving. That source
            // is null for a sealed file past the inline cap - which is every
            // large OrangMove upload - yet the downloader opens exactly those by
            // streaming and decrypting them. Gating on it disabled the button on
            // the files that needed it most.
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
    var broken by remember(attachment.id) { mutableStateOf(false) }
    val source = rememberAttachmentSource(attachment)
    // A sealed image has no url until it has been decrypted. Only a real failure
    // falls back to the download card - handing the loader a null model and
    // reading the error it raises would condemn every encrypted image on sight.
    if (broken || source.unavailable) {
        FileCard(attachment, expiresAt, now)
        return
    }
    val href = source.url
    var expanded by remember(attachment.id) { mutableStateOf(false) }

    Column {
        if (href == null) {
            Box(
                modifier = Modifier
                    .widthIn(max = 280.dp)
                    .fillMaxWidth()
                    .height(200.dp)
                    .background(c.surface1, RoundedCornerShape(OrangRadius.lg)),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(color = c.primary, modifier = Modifier.size(20.dp))
            }
        } else {
            AsyncImage(
                model = href,
                contentDescription = attachment.filename,
                contentScale = ContentScale.Fit,
                onError = { broken = true },
                modifier = Modifier
                    .widthIn(max = 280.dp)
                    .height(200.dp)
                    .background(c.surface1, RoundedCornerShape(OrangRadius.lg))
                    // Expands in place: the file's own URL is served as a
                    // download, so handing it to the browser is the one thing a
                    // tap must not do.
                    .clickable { expanded = true },
            )
        }
        expiresAt?.let {
            Spacer(Modifier.height(2.dp))
            Text(expiryLabel(it, now), color = c.warning, fontSize = 11.sp)
        }
    }

    if (expanded) {
        ImageLightbox(attachment, onDismiss = { expanded = false })
    }
}

/**
 * An audio attachment, playable where it sits. Loading is deferred to the first
 * tap - a channel with a dozen clips in it should not open a dozen streams.
 */
@Composable
internal fun AudioCard(attachment: Attachment, expiresAt: Instant?, now: Instant) {
    val c = OrangTheme.colors
    val shape = RoundedCornerShape(OrangRadius.lg)
    val download = rememberAttachmentDownloader()
    val href = rememberResolvedAttachmentUrl(attachment)

    var broken by remember(attachment.id) { mutableStateOf(false) }
    if (broken || href == null) {
        FileCard(attachment, expiresAt, now)
        return
    }

    val context = LocalContext.current
    val active = MediaPlayback.currentId == attachment.id
    val isPlaying = active && MediaPlayback.isPlaying
    val loading = active && MediaPlayback.buffering
    val durationMs = if (active) MediaPlayback.durationMs else 0L
    val seekable = active && MediaPlayback.ready

    // The player is the only one who knows where it's got to, so ask it - but
    // only while it's actually moving.
    LaunchedEffect(active, isPlaying) {
        while (active && isPlaying) {
            MediaPlayback.syncPosition()
            delay(200)
        }
    }

    // Held locally while the thumb is down: seeking on every pixel would fight
    // the poll above and stutter the clip.
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
                .tapToToggle { MediaPlayback.toggle(context, attachment.id, href) { broken = true } },
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
        // Unlike the other cards, the row itself doesn't download here - tapping
        // it is how you scrub - so this icon is the only way out and needs to be
        // big enough to actually hit.
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
