package lt.oranges.orangchat.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import lt.oranges.orangchat.ui.theme.OrangRadius
import lt.oranges.orangchat.ui.theme.OrangTheme
import lt.oranges.orangchat.util.absoluteUrl

/**
 * Preview of a stored image with Upload and Remove actions. Shared by profile
 * avatars/banners and the server icon - the upload endpoint is the same one in
 * every case, and it re-encodes server-side.
 */
@Composable
fun ImageField(
    label: String,
    url: String?,
    height: Dp,
    busy: Boolean,
    onPick: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
    square: Boolean = false,
    enabled: Boolean = true,
) {
    val c = OrangTheme.colors
    val previewModifier =
        if (square) Modifier.size(height) else Modifier.fillMaxWidth().height(height)
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, color = c.inkMuted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        Box(
            modifier = previewModifier
                .clip(RoundedCornerShape(OrangRadius.lg))
                .background(c.surface3),
            contentAlignment = Alignment.Center,
        ) {
            if (!url.isNullOrBlank()) {
                AsyncImage(
                    model = absoluteUrl(url),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = previewModifier,
                )
            } else {
                Text("None set", color = c.inkMuted, fontSize = 12.sp)
            }
        }
        if (enabled) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OrangButton(
                    text = if (busy) "Uploading…" else "Upload",
                    onClick = onPick,
                    enabled = !busy,
                    loading = busy,
                    variant = ButtonVariant.Secondary,
                )
                if (!url.isNullOrBlank()) {
                    OrangButton(
                        text = "Remove",
                        onClick = onRemove,
                        variant = ButtonVariant.Ghost,
                    )
                }
            }
        }
    }
}
