package lt.oranges.orangchat.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import lt.oranges.orangchat.data.model.UserActivity
import lt.oranges.orangchat.ui.theme.OrangRadius
import lt.oranges.orangchat.ui.theme.OrangTheme
import lt.oranges.orangchat.util.absoluteUrl

private val activityClock = flow {
    while (currentCoroutineContext().isActive) {
        emit(System.currentTimeMillis())
        delay(1_000)
    }
}.stateIn(
    CoroutineScope(SupervisorJob() + Dispatchers.Default),
    SharingStarted.WhileSubscribed(5_000),
    System.currentTimeMillis(),
)

private fun formatElapsed(startedAt: String, now: Long): String? = runCatching {
    val total = ((now - Instant.parse(startedAt).toEpochMilli()) / 1_000).coerceAtLeast(0)
    val seconds = total % 60
    val minutes = (total / 60) % 60
    val hours = total / 3_600
    if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds)
    else "%d:%02d".format(minutes, seconds)
}.getOrNull()

@Composable
private fun elapsedTime(startedAt: String?): String? {
    if (startedAt.isNullOrBlank()) return null
    val now by activityClock.collectAsState()
    return formatElapsed(startedAt, now)
}

@Composable
fun ActivityStatus(
    activities: List<UserActivity>,
    modifier: Modifier = Modifier,
    compact: Boolean = true,
) {
    val activity = activities.firstOrNull { it.kind == "spotify" } ?: activities.firstOrNull() ?: return
    val spotify = activity.kind == "spotify"
    val elapsed = elapsedTime(activity.startedAt)
    val shape = RoundedCornerShape(OrangRadius.xl)
    val artworkSize = if (compact) 20.dp else 48.dp
    Row(
        modifier = if (compact) {
            modifier
        } else {
            modifier
                .fillMaxWidth()
                .clip(shape)
                .background(OrangTheme.colors.surface3)
                .border(1.dp, OrangTheme.colors.border, shape)
                .padding(12.dp)
        },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!activity.imageUrl.isNullOrBlank()) {
            AsyncImage(
                model = absoluteUrl(activity.imageUrl),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(artworkSize).clip(RoundedCornerShape(4.dp)),
            )
        } else {
            Box(
                modifier = Modifier
                    .size(artworkSize)
                    .clip(RoundedCornerShape(4.dp))
                    .background(OrangTheme.colors.surface2),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (spotify) Icons.Default.MusicNote else Icons.Default.SportsEsports,
                    contentDescription = null,
                    tint = OrangTheme.colors.inkMuted,
                    modifier = Modifier.size(if (compact) 12.dp else 28.dp),
                )
            }
        }
        Spacer(Modifier.width(if (compact) 4.dp else 10.dp))
        if (compact) {
            Text(
                text = buildString {
                    append(if (spotify) "Listening to " else "Playing ")
                    append(activity.name)
                    activity.details?.let { append(" - "); append(it) }
                    elapsed?.let { append(" · for "); append(it) }
                },
                color = OrangTheme.colors.inkMuted,
                fontWeight = FontWeight.Medium,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
        } else {
            Column(modifier = Modifier.weight(1f, fill = false)) {
                Text(
                    text = if (spotify) "LISTENING TO" else "NOW PLAYING",
                    color = OrangTheme.colors.inkMuted,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 10.sp,
                    maxLines = 1,
                )
                Text(
                    text = activity.name,
                    color = OrangTheme.colors.inkSecondary,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                activity.details?.let {
                    Text(
                        text = it,
                        color = OrangTheme.colors.inkMuted,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                elapsed?.let {
                    Text(text = "for $it", color = OrangTheme.colors.inkMuted, fontSize = 11.sp)
                }
            }
        }
    }
}
