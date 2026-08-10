package lt.oranges.orangchat.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Group
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import lt.oranges.orangchat.ui.theme.OrangTheme
import lt.oranges.orangchat.util.absoluteUrl

/**
 * Port of features/dms/GroupIcon.tsx. A group DM's picture, falling back to the
 * members glyph - which is what every group looked like before icons existed
 * and what one without an icon still looks like, so the two occupy the same
 * slot at the same size.
 */
@Composable
fun GroupIcon(
    iconUrl: String?,
    modifier: Modifier = Modifier,
    size: Dp = 38.dp,
) {
    val c = OrangTheme.colors
    if (!iconUrl.isNullOrBlank()) {
        AsyncImage(
            model = absoluteUrl(iconUrl),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier.size(size).clip(CircleShape).background(c.surface3),
        )
    } else {
        Box(
            modifier = modifier.size(size).clip(CircleShape).background(c.primarySoft),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Default.Group,
                contentDescription = null,
                tint = c.primary,
                modifier = Modifier.size(size * 0.55f),
            )
        }
    }
}
