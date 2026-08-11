package lt.oranges.orangchat.ui.components
import androidx.compose.ui.platform.LocalContext
import lt.oranges.orangchat.R

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import lt.oranges.orangchat.util.AppStrings

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

fun statusLabel(context: android.content.Context, status: PresenceStatus): String = when (status) {
    PresenceStatus.ONLINE -> AppStrings.get(context, R.string.catalog_online_c3e839df)
    PresenceStatus.IDLE -> AppStrings.get(context, R.string.catalog_idle_cc1ebdd0)
    PresenceStatus.DND -> AppStrings.get(context, R.string.catalog_do_not_disturb_875ba794)
    PresenceStatus.OFFLINE -> AppStrings.get(context, R.string.catalog_offline_e01fa717)
}

@Composable
fun Avatar(
    user: User,
    modifier: Modifier = Modifier,
    size: Dp = 36.dp,
    status: PresenceStatus? = null,
    shape: Shape = CircleShape,
    onStatusClick: (() -> Unit)? = null,
) {
    Avatar(
        displayName = user.displayName,
        avatarUrl = user.avatarUrl,
        modifier = modifier,
        size = size,
        status = status,
        devices = user.devices,
        shape = shape,
        onStatusClick = onStatusClick,
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
    onStatusClick: (() -> Unit)? = null,
) {
    val context = LocalContext.current
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
            val badge = (size.value * 0.34f).coerceAtLeast(10f).dp
            val device = primaryDevice(devices)
            val label = statusLabel(context, status)
                .let { if (device == null) it else "$it · ${deviceLabel(context, device)}" }
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(badge)
                    .clip(CircleShape)
                    .clickable(enabled = onStatusClick != null) { onStatusClick?.invoke() }
                    .background(c.surface2),
                contentAlignment = Alignment.Center,
            ) {
                StatusIcon(
                    status = status,
                    size = badge * if (device == PresenceDevice.MOBILE) 0.92f else 0.75f,
                    mobile = device == PresenceDevice.MOBILE,
                    contentDescription = label,
                )
            }
        }
    }
}
