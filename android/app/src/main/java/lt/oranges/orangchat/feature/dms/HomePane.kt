package lt.oranges.orangchat.feature.dms

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material3.Icon
import lt.oranges.orangchat.ui.components.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import lt.oranges.orangchat.data.model.Conversation
import lt.oranges.orangchat.data.model.PresenceStatus
import lt.oranges.orangchat.data.model.SelfUser
import lt.oranges.orangchat.data.model.UnreadState
import lt.oranges.orangchat.data.model.UserActivity
import lt.oranges.orangchat.feature.unread.UnreadCountBadge
import lt.oranges.orangchat.ui.components.ActivityStatus
import lt.oranges.orangchat.ui.components.Avatar
import lt.oranges.orangchat.ui.components.UserFooter
import lt.oranges.orangchat.ui.theme.OrangRadius
import lt.oranges.orangchat.ui.theme.OrangTheme

/** Home middle pane: entry points to Friends and the DM conversation list. */
@Composable
fun HomePane(
    self: SelfUser,
    conversations: List<Conversation>,
    presence: Map<String, PresenceStatus>,
    presenceActivities: Map<String, List<UserActivity>>,
    onOpenFriends: () -> Unit,
    onOpenConversation: (Conversation) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    unreads: Map<String, UnreadState> = emptyMap(),
) {
    val c = OrangTheme.colors
    Column(modifier = modifier.fillMaxSize().background(c.surface1)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Direct Messages", color = c.ink, fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.weight(1f))
        }
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(c.border))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .clip(RoundedCornerShape(OrangRadius.md))
                .clickable(onClick = onOpenFriends)
                .padding(horizontal = 8.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.PersonAdd, contentDescription = null, tint = c.inkSecondary, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Text("Friends", color = c.ink, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        }

        LazyColumn(modifier = Modifier.weight(1f)) {
            items(conversations, key = { it.id }) { convo ->
                ConversationRow(
                    convo = convo,
                    selfId = self.id,
                    presence = presence,
                    presenceActivities = presenceActivities,
                    unread = unreads[convo.id]?.unread == true,
                    unreadCount = unreads[convo.id]?.unreadCount ?: 0,
                    onClick = onOpenConversation,
                )
            }
        }

        UserFooter(self = self, onOpenSettings = onOpenSettings)
    }
}

@Composable
private fun ConversationRow(
    convo: Conversation,
    selfId: String,
    presence: Map<String, PresenceStatus>,
    presenceActivities: Map<String, List<UserActivity>>,
    unread: Boolean,
    unreadCount: Int,
    onClick: (Conversation) -> Unit,
) {
    val c = OrangTheme.colors
    val others = convo.participants.filter { it.id != selfId }
    val title = convo.name ?: others.joinToString(", ") { it.displayName }.ifBlank { "Direct Message" }
    val lead = others.firstOrNull()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 1.dp)
            .clip(RoundedCornerShape(OrangRadius.md))
            .clickable { onClick(convo) }
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Leading pip, as on the web: the row reads as unread even before the
        // eye reaches the count on the far side. The Box reserves the slot
        // either way so titles stay aligned down the list.
        Box(
            modifier = Modifier
                .size(width = 4.dp, height = 8.dp)
                .then(
                    if (unread) {
                        Modifier.background(c.ink, RoundedCornerShape(OrangRadius.sm))
                    } else {
                        Modifier
                    },
                ),
        )
        Spacer(Modifier.width(6.dp))
        if (others.size > 1 || lead == null) {
            Box(modifier = Modifier.size(38.dp)) {
                Icon(Icons.Default.Group, contentDescription = null, tint = c.inkSecondary)
            }
        } else {
            // Conversation DTOs carry the user's saved preference, not proof
            // of a live socket. Only the realtime presence map may show them
            // as online; unknown presence is safely treated as offline.
            Avatar(
                lead,
                size = 38.dp,
                status = presence[lead.id] ?: PresenceStatus.OFFLINE,
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = c.ink,
                // Unread conversations read bolder, as on the web.
                fontWeight = if (unread) FontWeight.Bold else FontWeight.Medium,
                fontSize = 15.sp,
                maxLines = 1,
            )
            if (others.size == 1 && lead != null) {
                ActivityStatus(
                    activities = presenceActivities[lead.id] ?: lead.activities,
                )
            }
        }
        UnreadCountBadge(unreadCount)
    }
}
