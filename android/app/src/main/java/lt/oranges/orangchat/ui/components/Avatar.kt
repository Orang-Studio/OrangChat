package lt.oranges.orangchat.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import lt.oranges.orangchat.ui.components.Text
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
import lt.oranges.orangchat.data.model.PresenceDevice
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

/** Presence labels, mirroring Avatar.tsx STATUS_LABEL. */
fun statusLabel(status: PresenceStatus): String = when (status) {
    PresenceStatus.ONLINE -> "Online"
    PresenceStatus.IDLE -> "Idle"
    PresenceStatus.DND -> "Do not disturb"
    PresenceStatus.OFFLINE -> "Offline"
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
        devices = user.devices,
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
    devices: List<PresenceDevice> = emptyList(),
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
            // The badge scales with the avatar - a 16dp dot pinned to an 80dp
            // profile avatar reads as a rendering mistake - and the surface-2
            // disc behind it is what shows through the shape's cut-outs.
            val badge = (size.value * 0.38f).coerceAtLeast(16f).dp
            val device = when {
                PresenceDevice.DESKTOP in devices -> "Desktop app"
                PresenceDevice.BROWSER in devices -> "Browser"
                PresenceDevice.MOBILE in devices -> "Mobile"
                else -> null
            }
            val label = statusLabel(status).let { if (device == null) it else "$it · $device" }
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(badge)
                    .clip(CircleShape)
                    .background(c.surface2),
                contentAlignment = Alignment.Center,
            ) {
                StatusIcon(status = status, size = badge * 0.75f, contentDescription = label)
            }
        }
    }
}
