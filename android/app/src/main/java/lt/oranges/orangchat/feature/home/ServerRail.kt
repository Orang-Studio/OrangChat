package lt.oranges.orangchat.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material3.Icon
import lt.oranges.orangchat.ui.components.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import lt.oranges.orangchat.data.model.Server
import lt.oranges.orangchat.data.model.UnreadState
import lt.oranges.orangchat.feature.unread.MentionBadge
import lt.oranges.orangchat.feature.unread.UnreadDot
import lt.oranges.orangchat.feature.unread.hasUnreadInServer
import lt.oranges.orangchat.feature.unread.mentionsForServer
import lt.oranges.orangchat.feature.unread.unreadDmCount
import lt.oranges.orangchat.ui.theme.OrangRadius
import lt.oranges.orangchat.ui.theme.OrangTheme
import lt.oranges.orangchat.util.absoluteUrl

/** The leftmost server rail: Home/DMs button, server icons, and add-server. */
@Composable
fun ServerRail(
    servers: List<Server>,
    selectedServerId: String?,
    homeSelected: Boolean,
    onHome: () -> Unit,
    onSelectServer: (String) -> Unit,
    onAddServer: () -> Unit,
    modifier: Modifier = Modifier,
    unreads: Map<String, UnreadState> = emptyMap(),
) {
    val c = OrangTheme.colors
    Column(
        modifier = modifier
            .fillMaxHeight()
            .width(72.dp)
            .background(c.surface0)
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box {
            RailButton(selected = homeSelected, onClick = onHome) {
                Icon(Icons.Default.Chat, contentDescription = "Direct messages", tint = it)
            }
            // Unread DMs badge, Discord-style, on the home button.
            MentionBadge(
                count = unreads.unreadDmCount(),
                modifier = Modifier.align(Alignment.TopEnd),
            )
        }
        Box(
            modifier = Modifier
                .width(32.dp)
                .padding(vertical = 2.dp)
                .background(c.border, RoundedCornerShape(1.dp))
                .size(width = 32.dp, height = 2.dp),
        )
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.weight(1f, fill = false),
        ) {
            items(servers, key = { it.id }) { server ->
                Box {
                    ServerIcon(
                        server = server,
                        selected = server.id == selectedServerId && !homeSelected,
                        onClick = { onSelectServer(server.id) },
                    )
                    if (unreads.hasUnreadInServer(server.id)) {
                        UnreadDot(modifier = Modifier.align(Alignment.CenterStart))
                    }
                    MentionBadge(
                        count = unreads.mentionsForServer(server.id),
                        modifier = Modifier.align(Alignment.TopEnd),
                    )
                }
            }
            item {
                RailButton(selected = false, accent = true, onClick = onAddServer) {
                    Icon(Icons.Default.Add, contentDescription = "Add a server", tint = it)
                }
            }
        }
    }
}

@Composable
private fun ServerIcon(server: Server, selected: Boolean, onClick: () -> Unit) {
    val c = OrangTheme.colors
    val shape = RoundedCornerShape(if (selected) OrangRadius.md else OrangRadius.squircle)
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(48.dp)) {
        if (!server.iconUrl.isNullOrBlank()) {
            AsyncImage(
                model = absoluteUrl(server.iconUrl),
                contentDescription = server.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(48.dp)
                    .clip(shape)
                    .clickable(onClick = onClick)
                    .then(if (selected) Modifier.border(2.dp, c.primary, shape) else Modifier),
            )
        } else {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(shape)
                    .background(if (selected) c.primary else c.surface3)
                    .clickable(onClick = onClick),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = server.name.take(2).uppercase(),
                    color = if (selected) c.inkOnPrimary else c.inkSecondary,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                )
            }
        }
    }
}

@Composable
private fun RailButton(
    selected: Boolean,
    accent: Boolean = false,
    onClick: () -> Unit,
    content: @Composable (tint: Color) -> Unit,
) {
    val c = OrangTheme.colors
    val bg = when {
        selected -> c.primary
        accent -> c.surface3
        else -> c.surface3
    }
    val tint = when {
        selected -> c.inkOnPrimary
        accent -> c.success
        else -> c.inkSecondary
    }
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(bg)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content(tint) }
}
