package lt.oranges.orangchat.ui.components

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import lt.oranges.orangchat.data.model.PresenceDevice
import lt.oranges.orangchat.data.model.PresenceStatus
import lt.oranges.orangchat.ui.theme.OrangTheme
import lt.oranges.orangchat.util.AppStrings
import lt.oranges.orangchat.R

fun deviceLabel(context: Context, device: PresenceDevice): String = when (device) {
    PresenceDevice.DESKTOP -> AppStrings.get(context, R.string.catalog_desktop_app_f7a44c6b)
    PresenceDevice.BROWSER -> AppStrings.get(context, R.string.catalog_browser_54a2cf5e)
    PresenceDevice.MOBILE -> AppStrings.get(context, R.string.catalog_mobile_b1d70245)
}

fun primaryDevice(devices: Collection<PresenceDevice>): PresenceDevice? =
    listOf(PresenceDevice.DESKTOP, PresenceDevice.BROWSER, PresenceDevice.MOBILE)
        .firstOrNull { it in devices }

@Composable
fun DeviceIndicators(
    status: PresenceStatus,
    devices: Set<PresenceDevice>,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    if (status == PresenceStatus.OFFLINE || devices.isEmpty()) return

    val metadata: List<Pair<PresenceDevice, ImageVector>> = listOf(
        PresenceDevice.DESKTOP to Icons.Default.Computer,
        PresenceDevice.BROWSER to Icons.Default.Language,
        PresenceDevice.MOBILE to Icons.Default.Smartphone,
    )
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        metadata.filter { (device) -> device in devices }.forEach { (device, icon) ->
            val label = deviceLabel(context, device)
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
