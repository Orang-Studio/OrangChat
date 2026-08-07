package lt.oranges.orangchat.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import lt.oranges.orangchat.data.model.SelfUser
import lt.oranges.orangchat.ui.theme.OrangTheme

/** Bottom-of-sidebar identity strip, matching the web client's UserFooter. */
@Composable
fun UserFooter(
    self: SelfUser,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = OrangTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(c.surface0)
            // Trimmed end padding: the settings target is 48dp wide now and carries
            // its own slack, so the glyph stays where it was.
            .padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(self.asUser(), size = 34.dp)
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(self.displayName, color = c.ink, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("@${self.username}", color = c.inkMuted, fontSize = 12.sp)
                Spacer(Modifier.width(5.dp))
                DeviceIndicators(
                    status = self.status,
                    devices = setOf(lt.oranges.orangchat.data.model.PresenceDevice.MOBILE),
                )
            }
            ActivityStatus(self.activities)
        }
        Icon(
            Icons.Default.Settings,
            contentDescription = "User settings",
            tint = c.inkMuted,
            modifier = Modifier
                .minimumInteractiveComponentSize()
                .size(32.dp)
                .clickable(onClick = onOpenSettings)
                .padding(7.dp),
        )
    }
}
