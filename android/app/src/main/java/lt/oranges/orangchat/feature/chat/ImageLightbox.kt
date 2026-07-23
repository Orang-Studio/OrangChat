package lt.oranges.orangchat.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material3.Icon
import lt.oranges.orangchat.ui.components.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import lt.oranges.orangchat.data.model.Attachment
import lt.oranges.orangchat.util.absoluteUrl

/**
 * Full-screen viewer for an image attachment.
 *
 * Tapping a thumbnail used to hand the URL to the browser, which — since nginx
 * serves attachments as `Content-Disposition: attachment` — meant "look closer"
 * silently downloaded the file and left the app. Expanding in place restores
 * the obvious reading, and downloading stays behind the button that says so.
 *
 * Coil loads the image itself, so the download header never comes into it.
 */
@Composable
fun ImageLightbox(attachment: Attachment, onDismiss: () -> Unit) {
    val download = rememberAttachmentDownloader()
    val href = absoluteUrl(attachment.url)

    // Only GIFs get the button - it feeds the picker's existing Favorites tab,
    // so a favourited PNG would have nowhere to appear. Reuses
    // GifFavoritesStore rather than keeping a second list: the lightbox and the
    // picker must agree on what's favourited.
    val gifViewModel: KlipyGifViewModel = hiltViewModel()
    val favorites by gifViewModel.favorites.collectAsState()
    val gif = attachment.contentType == "image/gif" ||
        attachment.filename.lowercase().endsWith(".gif")
    // Attachments have no Klipy slug, so the URL stands in as the identity.
    val asKlipy = KlipyGif(
        slug = attachment.url,
        title = attachment.filename,
        previewUrl = attachment.url,
        url = attachment.url,
        width = attachment.width ?: 0,
        height = attachment.height ?: 0,
    )
    val saved = gif && favorites.any { it.slug == asKlipy.slug }

    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    Dialog(
        onDismissRequest = onDismiss,
        // The point of this screen is the image, so it gets the whole screen —
        // the default dialog insets would letterbox it for nothing.
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.92f)),
        ) {
            AsyncImage(
                model = href,
                contentDescription = attachment.filename,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(vertical = 56.dp)
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(1f, 5f)
                            // Panning a zoomed-out image would slide it off for
                            // no reason; there's nothing hidden to reach.
                            offset = if (scale > 1f) offset + pan else Offset.Zero
                        }
                    }
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onDoubleTap = {
                                if (scale > 1f) {
                                    scale = 1f
                                    offset = Offset.Zero
                                } else {
                                    scale = 2.5f
                                }
                            },
                            // Tapping the image at rest dismisses, as every
                            // other viewer does — but not once it's zoomed, or
                            // a look around would keep closing it.
                            onTap = { if (scale <= 1f) onDismiss() },
                        )
                    }
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    },
            )

            // Over the image rather than beside it: the bar is worth a strip of
            // a photo, not a permanent slice of the screen.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .background(Color.Black.copy(alpha = 0.55f))
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
                if (gif) {
                    LightboxAction(
                        icon = if (saved) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
                        label = if (saved) "Remove from favourites" else "Favourite this GIF",
                        onClick = { gifViewModel.toggleFavorite(asKlipy) },
                    )
                    Spacer(Modifier.width(4.dp))
                }
                LightboxAction(
                    icon = Icons.Default.Download,
                    label = "Download ${attachment.filename}",
                    enabled = href != null,
                    onClick = { download(attachment) },
                )
                Spacer(Modifier.width(4.dp))
                LightboxAction(
                    icon = Icons.Default.Close,
                    label = "Close",
                    onClick = onDismiss,
                )
            }
        }
    }
}

@Composable
private fun LightboxAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .size(36.dp)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = if (enabled) Color.White else Color.White.copy(alpha = 0.4f),
            modifier = Modifier.size(20.dp),
        )
    }
}
