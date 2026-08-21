package lt.oranges.orangchat.feature.dms
import lt.oranges.orangchat.util.AppStrings
import androidx.compose.ui.platform.LocalContext
import lt.oranges.orangchat.R
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.GroupAdd
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.PersonRemove
import androidx.compose.material.icons.filled.Search
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import lt.oranges.orangchat.data.model.ChannelType
import lt.oranges.orangchat.data.model.Conversation
import lt.oranges.orangchat.data.model.Message
import lt.oranges.orangchat.data.model.PresenceDevice
import lt.oranges.orangchat.data.model.PresenceStatus
import lt.oranges.orangchat.data.model.SelfUser
import lt.oranges.orangchat.data.model.UnreadState
import lt.oranges.orangchat.data.model.User
import lt.oranges.orangchat.data.model.UserActivity
import lt.oranges.orangchat.feature.unread.UnreadCountBadge
import lt.oranges.orangchat.ui.components.ActivityStatus
import lt.oranges.orangchat.ui.components.Avatar
import lt.oranges.orangchat.ui.components.GroupIcon
import lt.oranges.orangchat.notifications.MuteDuration
import lt.oranges.orangchat.notifications.isActiveMute
import lt.oranges.orangchat.ui.components.MenuItem
import lt.oranges.orangchat.ui.components.OfflineBanner
import lt.oranges.orangchat.ui.components.OrangDropdownMenu
import lt.oranges.orangchat.ui.components.muteDurationItems
import lt.oranges.orangchat.ui.components.UserFooter
import lt.oranges.orangchat.ui.theme.OrangRadius
import lt.oranges.orangchat.ui.theme.OrangTheme
import lt.oranges.orangchat.util.formatShortRelativeTime
import androidx.compose.runtime.collectAsState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive

/** Ticks once a minute for the relative-time labels. */
private val minuteClock = flow {
    while (currentCoroutineContext().isActive) {
        emit(System.currentTimeMillis())
        delay(60_000)
    }
}.stateIn(
    CoroutineScope(SupervisorJob() + Dispatchers.Default),
    SharingStarted.WhileSubscribed(5_000),
    System.currentTimeMillis(),
)

@Composable
fun HomePane(
    self: SelfUser,
    online: Boolean = true,
    conversations: List<Conversation>,
    presence: Map<String, PresenceStatus>,
    presenceDevices: Map<String, Set<PresenceDevice>> = emptyMap(),
    presenceActivities: Map<String, List<UserActivity>>,
    friendIds: Set<String>,
    activeConversationId: String? = null,
    onOpenFriends: () -> Unit,
    onOpenConversation: (Conversation) -> Unit,
    onOpenSettings: () -> Unit,
    onStatusChange: (PresenceStatus) -> Unit = {},
    onSearch: () -> Unit,
    onNewGroup: () -> Unit,
    onMarkRead: (String) -> Unit,
    onOpenProfile: (User) -> Unit,
    onStartCall: (Conversation) -> Unit,
    onRemoveFriend: (String) -> Unit,
    onLeaveConversation: (Conversation) -> Unit,
    modifier: Modifier = Modifier,
    unreads: Map<String, UnreadState> = emptyMap(),
    typing: Map<String, Set<String>> = emptyMap(),
    mutes: Map<String, Long> = emptyMap(),
    onMute: (String, MuteDuration) -> Unit = { _, _ -> },
    onUnmute: (String) -> Unit = {},
) {
        val context = LocalContext.current
    val c = OrangTheme.colors
    Column(modifier = modifier.fillMaxSize().background(c.surface1)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(AppStrings.get(context, R.string.catalog_direct_messages_f1b1f5c2), color = c.ink, fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.weight(1f))
            Icon(
                Icons.Default.Search,
                contentDescription = AppStrings.get(context, R.string.catalog_search_messages_abea65ae),
                tint = c.inkSecondary,
                modifier = Modifier
                    .clip(RoundedCornerShape(OrangRadius.md))
                    .clickable(onClick = onSearch)
                    .padding(4.dp)
                    .size(22.dp),
            )
            Spacer(Modifier.width(8.dp))
            Icon(
                Icons.Default.GroupAdd,
                contentDescription = AppStrings.get(context, R.string.catalog_new_group_f9850c0b),
                tint = c.inkSecondary,
                modifier = Modifier
                    .clip(RoundedCornerShape(OrangRadius.md))
                    .clickable(onClick = onNewGroup)
                    .padding(4.dp)
                    .size(22.dp),
            )
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
                    active = convo.id == activeConversationId,
                    presence = presence,
                    presenceDevices = presenceDevices,
                    presenceActivities = presenceActivities,
                    unread = unreads[convo.id]?.unread == true,
                    unreadCount = unreads[convo.id]?.unreadCount ?: 0,
                    typingUserIds = typing[convo.id].orEmpty() - self.id,
                    friendIds = friendIds,
                    muted = mutes[convo.id].isActiveMute(),
                    onMute = onMute,
                    onUnmute = onUnmute,
                    onClick = onOpenConversation,
                    onMarkRead = onMarkRead,
                    onOpenProfile = onOpenProfile,
                    onStartCall = onStartCall,
                    onRemoveFriend = onRemoveFriend,
                    onLeaveConversation = onLeaveConversation,
                )
            }
        }

        UserFooter(
            self = self,
            onOpenSettings = onOpenSettings,
            onStatusChange = onStatusChange,
        )
        if (!online) {
            OfflineBanner()
        }
    }
}

private fun latestMessagePreview(message: Message?, selfId: String): String? {
    if (message == null) return null
    val author = if (message.author.id == selfId) "You" else message.author.displayName
    val content = message.content
        .replace(Regex("\\s+"), " ")
        .trim()
        .ifBlank { if (message.attachments.isNotEmpty()) "Sent an attachment" else "Message" }
    return "$author: $content"
}

private fun typingPreview(
    typingIds: Set<String>,
    convo: Conversation,
    isGroup: Boolean,
): String? {
    if (typingIds.isEmpty()) return null
    if (!isGroup) return "typing…"
    val names = typingIds.mapNotNull { id ->
        convo.participants.firstOrNull { it.id == id }?.displayName
    }
    return when (names.size) {
        0 -> "typing…"
        1 -> "${names[0]} is typing…"
        2 -> "${names[0]} and ${names[1]} are typing…"
        else -> "Several people are typing…"
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConversationRow(
    convo: Conversation,
    selfId: String,
    active: Boolean,
    presence: Map<String, PresenceStatus>,
    presenceDevices: Map<String, Set<PresenceDevice>>,
    presenceActivities: Map<String, List<UserActivity>>,
    unread: Boolean,
    unreadCount: Int,
    typingUserIds: Set<String>,
    friendIds: Set<String>,
    muted: Boolean,
    onMute: (String, MuteDuration) -> Unit,
    onUnmute: (String) -> Unit,
    onClick: (Conversation) -> Unit,
    onMarkRead: (String) -> Unit,
    onOpenProfile: (User) -> Unit,
    onStartCall: (Conversation) -> Unit,
    onRemoveFriend: (String) -> Unit,
    onLeaveConversation: (Conversation) -> Unit,
) {
        val context = LocalContext.current
    val c = OrangTheme.colors
    val others = convo.participants.filter { it.id != selfId }
    val title = convo.name ?: others.joinToString(", ") { it.displayName }.ifBlank { "Direct Message" }
    val lead = others.firstOrNull()
    val latestPreview = latestMessagePreview(convo.latestMessage, selfId)
    val other = if (convo.type == ChannelType.GROUP_DM) null else lead
    val typingLine = typingPreview(typingUserIds, convo, convo.type == ChannelType.GROUP_DM)
    val haptics = LocalHapticFeedback.current
    val clipboard = LocalClipboardManager.current
    var menuOpen by remember { mutableStateOf(false) }
    var muteMenuOpen by remember { mutableStateOf(false) }
    val now by minuteClock.collectAsState()
    val lastActivity = formatShortRelativeTime(convo.latestMessage?.createdAt, now)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 1.dp)
            .clip(RoundedCornerShape(OrangRadius.md))
            .then(if (active) Modifier.background(c.surface3) else Modifier)
            .combinedClickable(
                onClick = { onClick(convo) },
                onLongClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    menuOpen = true
                },
            )
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.CenterVertically)
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
            GroupIcon(iconUrl = convo.iconUrl, size = 38.dp)
        } else {
            Avatar(
                lead.copy(devices = presenceDevices[lead.id]?.toList() ?: lead.devices),
                size = 38.dp,
                status = presence[lead.id] ?: PresenceStatus.OFFLINE,
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = title,
                        color = if (unread || active) c.ink else c.inkSecondary,
                        fontWeight = if (unread) FontWeight.Bold else FontWeight.Medium,
                        fontSize = 15.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (muted) {
                        Spacer(Modifier.width(6.dp))
                        Icon(
                            Icons.Default.NotificationsOff,
                            contentDescription = AppStrings.get(context, R.string.catalog_muted_b9e78ced),
                            tint = c.inkMuted,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
                if (lastActivity != null) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = lastActivity,
                        color = c.inkMuted,
                        fontSize = 11.sp,
                        maxLines = 1,
                    )
                }
            }
            if (typingLine != null) {
                Text(
                    text = typingLine,
                    color = c.inkSecondary,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            } else if (latestPreview != null) {
                Text(
                    text = latestPreview,
                    color = c.inkMuted,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            } else if (others.size == 1 && lead != null) {
                ActivityStatus(
                    activities = presenceActivities[lead.id] ?: lead.activities,
                )
            }
        }
        UnreadCountBadge(unreadCount, modifier = Modifier.align(Alignment.CenterVertically))
        Box {
            OrangDropdownMenu(
                expanded = menuOpen,
                onDismiss = { menuOpen = false },
                items = buildList {
                    add(
                        MenuItem(AppStrings.get(context, R.string.catalog_mark_as_read_c1ee860b), Icons.Default.Check, enabled = unread) {
                            onMarkRead(convo.id)
                        },
                    )
                    other?.let { user ->
                        add(MenuItem("Profile", Icons.Default.Person) { onOpenProfile(user) })
                    }
                    add(MenuItem(AppStrings.get(context, R.string.catalog_start_a_call_d7f39160), Icons.Default.Call) { onStartCall(convo) })
                    if (muted) {
                        add(
                            MenuItem(
                                AppStrings.get(context, R.string.catalog_unmute_conversation_2c24b950),
                                Icons.Default.NotificationsActive,
                            ) { onUnmute(convo.id) },
                        )
                    } else {
                        add(
                            MenuItem(
                                AppStrings.get(context, R.string.catalog_mute_conversation_d261547e),
                                Icons.Default.NotificationsOff,
                            ) { muteMenuOpen = true },
                        )
                    }
                    if (other != null && other.id in friendIds) {
                        add(
                            MenuItem(AppStrings.get(context, R.string.catalog_remove_friend_b16fc7ff), Icons.Default.PersonRemove, destructive = true) {
                                onRemoveFriend(other.id)
                            },
                        )
                    }
                    other?.let { user ->
                        add(
                            MenuItem(AppStrings.get(context, R.string.catalog_copy_user_id_6fff306b), Icons.Default.ContentCopy) {
                                clipboard.setText(AnnotatedString(user.id))
                            },
                        )
                    }
                    add(
                        MenuItem(AppStrings.get(context, R.string.catalog_copy_channel_id_c32cdc5d), Icons.Default.ContentCopy) {
                            clipboard.setText(AnnotatedString(convo.id))
                        },
                    )
                    val isGroup = convo.type == ChannelType.GROUP_DM
                    add(
                        MenuItem(
                            if (isGroup) AppStrings.get(context, R.string.catalog_leave_group_92578912) else AppStrings.get(context, R.string.catalog_close_dm_43823b56),
                            if (isGroup) Icons.AutoMirrored.Filled.Logout else Icons.Default.Close,
                            destructive = true,
                        ) { onLeaveConversation(convo) },
                    )
                },
            )
            OrangDropdownMenu(
                expanded = muteMenuOpen,
                onDismiss = { muteMenuOpen = false },
                items = muteDurationItems(context) { onMute(convo.id, it) },
            )
        }
    }
}
