package lt.oranges.orangchat.feature.chat

import androidx.compose.ui.platform.LocalContext
import lt.oranges.orangchat.R
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.PhoneMissed
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import lt.oranges.orangchat.data.model.Message
import lt.oranges.orangchat.data.model.ServerMember
import lt.oranges.orangchat.ui.components.Avatar
import lt.oranges.orangchat.ui.components.MenuItem
import lt.oranges.orangchat.ui.components.OrangDropdownMenu
import lt.oranges.orangchat.ui.components.Text
import lt.oranges.orangchat.ui.theme.OrangRadius
import lt.oranges.orangchat.ui.theme.OrangTheme
import lt.oranges.orangchat.util.AppStrings
import lt.oranges.orangchat.util.formatTime

/** How many faces fit on the card before the rest are a count. */
private const val CALL_FACES = 5

/**
 * A call, as it appears in the history.
 *
 * One card per call, rewritten by the server as the call runs, rather than a
 * message per state change - so a conversation that has been called a lot reads
 * as a list of calls instead of a transcript of ringing. While it is live the
 * card is the way into the call; afterwards it is the record of one: who was on
 * it, when, and for how long, or that nobody picked up.
 */
@Composable
fun CallCardRow(
    message: Message,
    notice: CallNotice,
    selfId: String,
    members: List<ServerMember>,
    onCall: Boolean,
    onStartCall: ((video: Boolean) -> Unit)?,
) {
        val context = LocalContext.current
    val c = OrangTheme.colors
    var menuOpen by remember { mutableStateOf(false) }

    val faces = remember(notice.joined, members) {
        notice.joined.mapNotNull { id -> members.firstOrNull { it.userId == id }?.user }
    }
    val caller = if (notice.callerId == selfId) "You" else message.author.displayName

    val title = when {
        notice.live && notice.joined.size > 1 -> AppStrings.get(context, R.string.catalog_ongoing_call_5ae739b8)
        notice.live -> "$caller is calling"
        // "No answer" from our side, "Missed call" from theirs: the same call,
        // but only one of them is something the reader failed to pick up.
        notice.missed && notice.callerId == selfId -> AppStrings.get(context, R.string.catalog_no_answer_a9c16dd0)
        notice.missed -> AppStrings.get(context, R.string.catalog_missed_call_0200c293)
        notice.video -> "$caller started a video call"
        else -> "$caller started a call"
    }
    val detail = when {
        notice.live && notice.ringing.isNotEmpty() -> "Ringing..."
        notice.live -> "${notice.joined.size} on the call"
        notice.durationSec != null ->
            "${formatTime(message.createdAt)} · ${formatCallDuration(notice.durationSec)}"
        else -> formatTime(message.createdAt)
    }

    val icon = when {
        notice.missed -> Icons.Default.PhoneMissed
        notice.video -> Icons.Default.Videocam
        else -> Icons.Default.Call
    }
    val accent = when {
        notice.missed -> c.danger
        notice.live -> c.success
        else -> c.inkMuted
    }

    // A live card is a way into the call; a finished one has nothing to rejoin,
    // so tapping it offers to start a new call instead - which is what anyone
    // reaching for an old call actually wants.
    val clickable = onStartCall != null && (!notice.live || !onCall)
    val onClick: () -> Unit = {
        if (notice.live) onStartCall?.invoke(notice.video) else menuOpen = true
    }

    Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .align(Alignment.Center)
                .widthIn(max = 420.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(OrangRadius.xl))
                .background(c.surface3, RoundedCornerShape(OrangRadius.xl))
                .border(1.dp, c.border, RoundedCornerShape(OrangRadius.xl))
                .clickable(enabled = clickable, onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(32.dp)
                    .background(accent.copy(alpha = 0.15f), CircleShape),
            ) {
                Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = if (notice.missed) c.danger else c.ink,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = detail,
                    color = c.inkMuted,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (faces.isNotEmpty()) {
                Spacer(Modifier.width(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy((-8).dp)) {
                    faces.take(CALL_FACES).forEach { user ->
                        Avatar(
                            user = user,
                            size = 24.dp,
                            modifier = Modifier.border(2.dp, c.surface3, CircleShape),
                        )
                    }
                }
                if (faces.size > CALL_FACES) {
                    Spacer(Modifier.width(6.dp))
                    Text("+${faces.size - CALL_FACES}", color = c.inkMuted, fontSize = 12.sp)
                }
            }
            if (notice.live) {
                Spacer(Modifier.width(10.dp))
                Text(
                    text = if (onCall) AppStrings.get(context, R.string.catalog_on_call_fdd219b2) else "Join",
                    color = c.inkOnPrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .background(
                            if (onCall) c.success.copy(alpha = 0.4f) else c.success,
                            RoundedCornerShape(OrangRadius.lg),
                        )
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                )
            }
        }
        if (onStartCall != null) {
            OrangDropdownMenu(
                expanded = menuOpen,
                onDismiss = { menuOpen = false },
                items = listOf(
                    MenuItem(AppStrings.get(context, R.string.catalog_audio_call_fb04a1a1), Icons.Default.Call) { onStartCall(false) },
                    MenuItem(AppStrings.get(context, R.string.catalog_video_call_ad60f43c), Icons.Default.Videocam) { onStartCall(true) },
                ),
            )
        }
    }
}
