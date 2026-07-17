package lt.oranges.orangchat.feature.invite

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import lt.oranges.orangchat.data.model.InvitePreview
import lt.oranges.orangchat.ui.theme.OrangRadius
import lt.oranges.orangchat.ui.theme.OrangTheme
import lt.oranges.orangchat.util.absoluteUrl

/** "12 members", but "1 member". */
private fun memberLabel(count: Int) = "$count ${if (count == 1) "member" else "members"}"

/**
 * The server an invite leads to. The action beside it is the caller's, so this
 * same card serves the add-server sheet, the deep-link sheet, and an invite
 * posted in chat.
 */
@Composable
fun InviteCard(
    preview: InvitePreview,
    modifier: Modifier = Modifier,
    action: @Composable (() -> Unit)? = null,
) {
    val c = OrangTheme.colors
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(OrangRadius.xl2))
            .background(c.surface1)
            .border(1.dp, c.border, RoundedCornerShape(OrangRadius.xl2))
            .padding(12.dp),
    ) {
        Text(
            text = preview.inviterName?.let { "$it invited you to join" }
                ?: "You've been invited to join",
            color = c.inkMuted,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 0.5.sp,
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ServerBadge(preview)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = preview.server.name,
                    color = c.ink,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(memberLabel(preview.memberCount), color = c.inkMuted, fontSize = 12.sp)
            }
            action?.invoke()
        }

        preview.blockedReason?.let {
            Text(it, color = c.danger, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

@Composable
private fun ServerBadge(preview: InvitePreview) {
    val c = OrangTheme.colors
    val shape = RoundedCornerShape(OrangRadius.squircle)
    val icon = preview.server.iconUrl
    if (!icon.isNullOrBlank()) {
        AsyncImage(
            model = absoluteUrl(icon),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(48.dp).clip(shape),
        )
    } else {
        Box(
            modifier = Modifier.size(48.dp).clip(shape).background(c.surface3),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = preview.server.name.take(2).uppercase(),
                color = c.inkSecondary,
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp,
            )
        }
    }
}
