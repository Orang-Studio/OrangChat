package lt.oranges.orangchat.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import lt.oranges.orangchat.data.model.PresenceDevice
import lt.oranges.orangchat.data.model.PresenceStatus
import lt.oranges.orangchat.ui.theme.OrangTheme

/** Device kinds currently keeping an online user connected. */
@Composable
fun DeviceIndicators(
    status: PresenceStatus,
    devices: Set<PresenceDevice>,
    modifier: Modifier = Modifier,
) {
    if (status == PresenceStatus.OFFLINE || devices.isEmpty()) return

    val metadata: List<Triple<PresenceDevice, String, ImageVector>> = listOf(
        Triple(PresenceDevice.DESKTOP, "Desktop app", Icons.Default.Computer),
        Triple(PresenceDevice.BROWSER, "Browser", Icons.Default.Language),
        Triple(PresenceDevice.MOBILE, "Mobile", Icons.Default.Smartphone),
    )
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        metadata.filter { (device) -> device in devices }.forEach { (_, label, icon) ->
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = when (status) {
                    PresenceStatus.ONLINE -> OrangTheme.colors.success
                    PresenceStatus.IDLE -> OrangTheme.colors.warning
                    PresenceStatus.DND -> OrangTheme.colors.danger
                    PresenceStatus.OFFLINE -> OrangTheme.colors.inkMuted
                },
                modifier = Modifier.size(14.dp),
            )
        }
    }
}
