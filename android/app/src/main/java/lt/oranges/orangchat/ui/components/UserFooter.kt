package lt.oranges.orangchat.ui.components

import androidx.compose.ui.platform.LocalContext
import lt.oranges.orangchat.R
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.minimumInteractiveComponentSize
import lt.oranges.orangchat.ui.components.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import lt.oranges.orangchat.data.model.SelfUser
import lt.oranges.orangchat.data.model.PresenceStatus
import lt.oranges.orangchat.ui.theme.OrangTheme
import lt.oranges.orangchat.util.AppStrings

private val STATUS_OPTIONS = listOf(
    PresenceStatus.ONLINE,
    PresenceStatus.IDLE,
    PresenceStatus.DND,
)

@Composable
fun UserFooter(
    self: SelfUser,
    onOpenSettings: () -> Unit,
    onStatusChange: (PresenceStatus) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val c = OrangTheme.colors
    var statusMenuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(c.surface0)
            .padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            Avatar(
                self.asUser(),
                size = 34.dp,
                status = self.status,
                onStatusClick = { statusMenuOpen = true },
            )
            OrangDropdownMenu(
                expanded = statusMenuOpen,
                onDismiss = { statusMenuOpen = false },
                items = STATUS_OPTIONS.map { status ->
                    MenuItem(
                        label = when (status) {
                            PresenceStatus.ONLINE -> AppStrings.get(context, R.string.catalog_online_c3e839df)
                            PresenceStatus.IDLE -> AppStrings.get(context, R.string.catalog_idle_cc1ebdd0)
                            PresenceStatus.DND -> AppStrings.get(context, R.string.catalog_do_not_disturb_875ba794)
                            PresenceStatus.OFFLINE -> AppStrings.get(context, R.string.catalog_offline_e01fa717)
                        },
                    ) { onStatusChange(status) }
                },
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(self.displayName, color = c.ink, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Text("@${self.username}", color = c.inkMuted, fontSize = 12.sp)
            ActivityStatus(self.activities)
        }
        Icon(
            Icons.Default.Settings,
            contentDescription = AppStrings.get(context, R.string.catalog_user_settings_f2b85332),
            tint = c.inkMuted,
            modifier = Modifier
                .minimumInteractiveComponentSize()
                .size(32.dp)
                .clickable(onClick = onOpenSettings)
                .padding(7.dp),
        )
    }
}
