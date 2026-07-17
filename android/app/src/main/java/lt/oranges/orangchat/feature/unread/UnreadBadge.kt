package lt.oranges.orangchat.feature.unread

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import lt.oranges.orangchat.data.model.UNREAD_COUNT_CAP
import lt.oranges.orangchat.ui.theme.OrangTheme

/**
 * Red count pill. Mirrors the web client's UnreadBadge — capped so a runaway
 * number cannot stretch the row. Renders nothing at zero, so callers can drop it
 * in unconditionally.
 */
@Composable
fun CountBadge(count: Int, modifier: Modifier = Modifier) {
    if (count <= 0) return
    val c = OrangTheme.colors
    Box(
        modifier = modifier
            .defaultMinSize(minWidth = 16.dp, minHeight = 16.dp)
            .background(c.danger, CircleShape)
            .padding(horizontal = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (count >= UNREAD_COUNT_CAP) "${UNREAD_COUNT_CAP - 1}+" else count.toString(),
            color = androidx.compose.ui.graphics.Color.White,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
    }
}

/** How many times the user was @mentioned in a channel they haven't opened. */
@Composable
fun MentionBadge(count: Int, modifier: Modifier = Modifier) = CountBadge(count, modifier)

/** How many unread messages a conversation holds. */
@Composable
fun UnreadCountBadge(count: Int, modifier: Modifier = Modifier) = CountBadge(count, modifier)

/** The plain "there is something new here" dot. */
@Composable
fun UnreadDot(modifier: Modifier = Modifier) {
    val c = OrangTheme.colors
    Box(modifier = modifier.size(8.dp).background(c.ink, CircleShape))
}
