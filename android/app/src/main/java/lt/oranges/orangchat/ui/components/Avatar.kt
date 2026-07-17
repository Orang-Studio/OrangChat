package lt.oranges.orangchat.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import lt.oranges.orangchat.data.model.PresenceStatus
import lt.oranges.orangchat.data.model.User
import lt.oranges.orangchat.ui.theme.OrangTheme
import lt.oranges.orangchat.util.absoluteUrl

/** Presence dot colors, mirroring Avatar.tsx STATUS_COLOR. */
@Composable
fun statusColor(status: PresenceStatus): Color {
    val c = OrangTheme.colors
    return when (status) {
        PresenceStatus.ONLINE -> c.success
        PresenceStatus.IDLE -> c.warning
        PresenceStatus.DND -> c.danger
        PresenceStatus.OFFLINE -> c.inkMuted
    }
}

/** Port of components/Avatar.tsx: image with initial fallback + optional dot. */
@Composable
fun Avatar(
    user: User,
    modifier: Modifier = Modifier,
    size: Dp = 36.dp,
    status: PresenceStatus? = null,
    shape: Shape = CircleShape,
) {
    Avatar(
        displayName = user.displayName,
        avatarUrl = user.avatarUrl,
        modifier = modifier,
        size = size,
        status = status,
        shape = shape,
    )
}

@Composable
fun Avatar(
    displayName: String,
    avatarUrl: String?,
    modifier: Modifier = Modifier,
    size: Dp = 36.dp,
    status: PresenceStatus? = null,
    shape: Shape = CircleShape,
) {
    val c = OrangTheme.colors
    Box(modifier = modifier.size(size)) {
        if (!avatarUrl.isNullOrBlank()) {
            AsyncImage(
                model = absoluteUrl(avatarUrl),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(size).clip(shape),
            )
        } else {
            Box(
                modifier = Modifier.size(size).clip(shape).background(c.primarySoft),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = displayName.take(1).uppercase(),
                    color = c.primary,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = (size.value * 0.42f).sp,
                )
            }
        }
        if (status != null) {
            val dot = (size.value * 0.33f).coerceAtLeast(10f).dp
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(dot)
                    .clip(CircleShape)
                    .background(statusColor(status))
                    .border(2.dp, c.surface2, CircleShape),
            )
        }
    }
}
