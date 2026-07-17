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
import androidx.compose.material3.Text
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
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(self.asUser(), size = 34.dp, status = self.status)
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(self.displayName, color = c.ink, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Text("@${self.username}", color = c.inkMuted, fontSize = 12.sp)
        }
        Icon(
            Icons.Default.Settings,
            contentDescription = "User settings",
            tint = c.inkMuted,
            modifier = Modifier
                .size(32.dp)
                .clickable(onClick = onOpenSettings)
                .padding(7.dp),
        )
    }
}
