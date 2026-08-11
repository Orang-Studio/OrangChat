package lt.oranges.orangchat.feature.home

import androidx.compose.ui.platform.LocalContext
import lt.oranges.orangchat.R
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material3.Icon
import lt.oranges.orangchat.ui.components.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
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
import lt.oranges.orangchat.notifications.MuteDuration
import lt.oranges.orangchat.notifications.isActiveMute
import lt.oranges.orangchat.ui.components.MenuItem
import lt.oranges.orangchat.ui.components.OrangDropdownMenu
import lt.oranges.orangchat.ui.components.muteDurationItems
import lt.oranges.orangchat.ui.theme.OrangRadius
import lt.oranges.orangchat.ui.theme.OrangTheme
import lt.oranges.orangchat.util.AppStrings
import lt.oranges.orangchat.util.absoluteUrl

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
    mutes: Map<String, Long> = emptyMap(),
    onMute: (String, MuteDuration) -> Unit = { _, _ -> },
    onUnmute: (String) -> Unit = {},
) {
        val context = LocalContext.current
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
                Icon(Icons.Default.Chat, contentDescription = AppStrings.get(context, R.string.catalog_direct_messages_e7596a09), tint = it)
            }
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
                    val muted = mutes[server.id].isActiveMute()
                    var menuOpen by remember { mutableStateOf(false) }
                    var muteMenuOpen by remember { mutableStateOf(false) }
                    val haptics = LocalHapticFeedback.current
                    ServerIcon(
                        server = server,
                        selected = server.id == selectedServerId && !homeSelected,
                        onClick = { onSelectServer(server.id) },
                        onLongClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            menuOpen = true
                        },
                    )
                    OrangDropdownMenu(
                        expanded = menuOpen,
                        onDismiss = { menuOpen = false },
                        items = listOf(
                            if (muted) {
                                MenuItem(
                                    AppStrings.get(context, R.string.catalog_unmute_server_b4a70262),
                                    Icons.Default.NotificationsActive,
                                ) { onUnmute(server.id) }
                            } else {
                                MenuItem(
                                    AppStrings.get(context, R.string.catalog_mute_server_b02c984e),
                                    Icons.Default.NotificationsOff,
                                ) { muteMenuOpen = true }
                            },
                        ),
                    )
                    OrangDropdownMenu(
                        expanded = muteMenuOpen,
                        onDismiss = { muteMenuOpen = false },
                        items = muteDurationItems(context) { onMute(server.id, it) },
                    )
                    if (muted) {
                        Icon(
                            Icons.Default.NotificationsOff,
                            contentDescription = AppStrings.get(context, R.string.catalog_muted_b9e78ced),
                            tint = c.inkMuted,
                            modifier = Modifier.align(Alignment.BottomEnd).size(14.dp),
                        )
                    }
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
                    Icon(Icons.Default.Add, contentDescription = AppStrings.get(context, R.string.catalog_add_a_server_5ee1e413), tint = it)
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ServerIcon(
    server: Server,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
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
                    .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                    .then(if (selected) Modifier.border(2.dp, c.primary, shape) else Modifier),
            )
        } else {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(shape)
                    .background(if (selected) c.primary else c.surface3)
                    .combinedClickable(onClick = onClick, onLongClick = onLongClick),
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
