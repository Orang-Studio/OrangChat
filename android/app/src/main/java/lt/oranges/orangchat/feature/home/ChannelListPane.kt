package lt.oranges.orangchat.feature.home

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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import lt.oranges.orangchat.ui.components.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import lt.oranges.orangchat.data.model.Channel
import lt.oranges.orangchat.data.model.ChannelType
import lt.oranges.orangchat.data.model.ServerDetail
import lt.oranges.orangchat.data.model.SelfUser
import lt.oranges.orangchat.data.model.UnreadState
import lt.oranges.orangchat.data.model.VoiceState
import lt.oranges.orangchat.feature.unread.MentionBadge
import lt.oranges.orangchat.ui.components.UserFooter
import lt.oranges.orangchat.ui.theme.OrangRadius
import lt.oranges.orangchat.ui.theme.OrangTheme

@Composable
fun ChannelListPane(
    detail: ServerDetail,
    self: SelfUser,
    currentChannelId: String?,
    onSelectChannel: (Channel) -> Unit,
    onAddChannel: () -> Unit,
    onSearch: () -> Unit,
    onServerSettings: () -> Unit,
    onOpenUserSettings: () -> Unit,
    modifier: Modifier = Modifier,
    unreads: Map<String, UnreadState> = emptyMap(),
    /** channelId -> who is sitting in that voice channel. */
    voiceParticipants: Map<String, Map<String, VoiceState>> = emptyMap(),
    memberNames: Map<String, String> = emptyMap(),
) {
    val c = OrangTheme.colors
    val categories = detail.channels.filter { it.type == ChannelType.CATEGORY }.sortedBy { it.position }
    val uncategorized = detail.channels
        .filter { it.type != ChannelType.CATEGORY && it.parentCategoryId == null }
        .sortedBy { it.position }

    Column(modifier = modifier.fillMaxSize().background(c.surface1)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(c.surface1)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(detail.server.name, color = c.ink, fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.weight(1f))
            Icon(
                Icons.Default.Search,
                contentDescription = "Search messages",
                tint = c.inkSecondary,
                modifier = Modifier.size(20.dp).clickable(onClick = onSearch),
            )
            Spacer(Modifier.width(12.dp))
            Icon(
                Icons.Default.Settings,
                contentDescription = "Server settings",
                tint = c.inkSecondary,
                modifier = Modifier.size(20.dp).clickable(onClick = onServerSettings),
            )
            Spacer(Modifier.width(12.dp))
            Icon(
                Icons.Default.Add,
                contentDescription = "Add channel",
                tint = c.inkSecondary,
                modifier = Modifier.size(20.dp).clickable(onClick = onAddChannel),
            )
        }
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(c.border))

        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp),
        ) {
            if (uncategorized.isNotEmpty()) {
                itemsIndexed(uncategorized, key = { _, ch -> ch.id }) { _, ch ->
                    ChannelRow(
                        channel = ch,
                        selected = ch.id == currentChannelId,
                        unread = unreads[ch.id]?.unread == true,
                        mentionCount = unreads[ch.id]?.mentionCount ?: 0,
                        voiceMembers = voiceParticipants[ch.id].orEmpty(),
                        memberNames = memberNames,
                        onClick = onSelectChannel,
                    )
                }
            }
            categories.forEach { category ->
                item(key = "cat-${category.id}") {
                    Text(
                        text = (category.name ?: "").uppercase(),
                        color = c.inkMuted,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
                    )
                }
                val children = detail.channels
                    .filter { it.parentCategoryId == category.id }
                    .sortedBy { it.position }
                itemsIndexed(children, key = { _, ch -> ch.id }) { _, ch ->
                    ChannelRow(
                        channel = ch,
                        selected = ch.id == currentChannelId,
                        unread = unreads[ch.id]?.unread == true,
                        mentionCount = unreads[ch.id]?.mentionCount ?: 0,
                        voiceMembers = voiceParticipants[ch.id].orEmpty(),
                        memberNames = memberNames,
                        onClick = onSelectChannel,
                    )
                }
            }
        }
        UserFooter(self = self, onOpenSettings = onOpenUserSettings)
    }
}

@Composable
private fun ChannelRow(
    channel: Channel,
    selected: Boolean,
    unread: Boolean,
    mentionCount: Int,
    voiceMembers: Map<String, VoiceState>,
    memberNames: Map<String, String>,
    onClick: (Channel) -> Unit,
) {
    val c = OrangTheme.colors
    val icon = if (channel.type == ChannelType.VOICE) Icons.Default.VolumeUp else Icons.Default.Tag
    Column {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 1.dp)
            .clip(RoundedCornerShape(OrangRadius.md))
            .background(if (selected) c.primarySoft else androidx.compose.ui.graphics.Color.Transparent)
            .clickable { onClick(channel) }
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = if (selected) c.primary else c.inkMuted, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            text = channel.name ?: "channel",
            color = when {
                selected -> c.ink
                // Unread channels read brighter, as on the web.
                unread -> c.ink
                else -> c.inkSecondary
            },
            fontWeight = if (selected || unread) FontWeight.SemiBold else FontWeight.Medium,
            fontSize = 14.sp,
            modifier = Modifier.weight(1f),
        )
        MentionBadge(mentionCount)
    }
    // Who is already in this voice channel, as on the web.
    voiceMembers.values.forEach { member ->
        Row(
            modifier = Modifier.padding(start = 42.dp, top = 1.dp, bottom = 1.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (member.muted) Icons.Default.MicOff else Icons.Default.Mic,
                contentDescription = null,
                tint = if (member.muted) c.danger else c.inkMuted,
                modifier = Modifier.size(12.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = memberNames[member.userId] ?: "someone",
                color = c.inkMuted,
                fontSize = 12.sp,
            )
        }
    }
    }
}
