package lt.oranges.orangchat.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import lt.oranges.orangchat.data.model.UserActivity
import lt.oranges.orangchat.ui.theme.OrangTheme

/** Spotify now; the same shape/render path is ready for future game activity. */
@Composable
fun ActivityStatus(activities: List<UserActivity>, modifier: Modifier = Modifier) {
    val activity = activities.firstOrNull { it.kind == "spotify" } ?: activities.firstOrNull() ?: return
    val spotify = activity.kind == "spotify"
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = if (spotify) Icons.Default.MusicNote else Icons.Default.SportsEsports,
            contentDescription = null,
            tint = OrangTheme.colors.inkMuted,
            modifier = Modifier.width(12.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = buildString {
                append(if (spotify) "Listening to " else "Playing ")
                append(activity.name)
                activity.details?.let { append(" - "); append(it) }
            },
            color = OrangTheme.colors.inkMuted,
            fontWeight = FontWeight.Medium,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
