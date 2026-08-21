package lt.oranges.orangchat.feature.chat

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import coil.compose.AsyncImage
import lt.oranges.orangchat.data.model.Attachment
import lt.oranges.orangchat.data.model.Message
import lt.oranges.orangchat.data.model.PresenceDevice
import lt.oranges.orangchat.data.model.PresenceStatus
import lt.oranges.orangchat.data.model.Role
import lt.oranges.orangchat.data.model.ServerMember
import lt.oranges.orangchat.data.model.User
import lt.oranges.orangchat.data.model.UserActivity
import lt.oranges.orangchat.feature.roles.roleColor
import lt.oranges.orangchat.notifications.MuteDuration
import lt.oranges.orangchat.ui.components.ActivityStatus
import lt.oranges.orangchat.ui.components.Avatar
import lt.oranges.orangchat.ui.components.BotTag
import lt.oranges.orangchat.ui.components.DeviceIndicators
import lt.oranges.orangchat.ui.components.GroupIcon
import lt.oranges.orangchat.ui.components.MenuItem
import lt.oranges.orangchat.ui.components.OrangDropdownMenu
import lt.oranges.orangchat.ui.components.OrangUnderlineTabs
import lt.oranges.orangchat.ui.components.Text
import lt.oranges.orangchat.ui.components.muteDurationItems
import lt.oranges.orangchat.ui.theme.OrangRadius
import lt.oranges.orangchat.ui.theme.OrangTheme

private val TABS = listOf("Members", "Media", "Links", "Files")

private data class MemberSection(
    val label: String,
    val members: List<ServerMember>,
)

private data class SharedLink(
    val url: String,
    val author: String,
)

private data class SharedFile(
    val attachment: Attachment,
    val author: String,
)

/**
 * What the chat header opens into: who is in the conversation and everything that has
 * been shared in it. Media, links and files are read off the messages already paged in
 * for the channel rather than re-queried.
 */
@Composable
fun ChannelDetailsScreen(
    title: String,
    kindLabel: String,
    topic: String?,
    messages: List<Message>,
    members: List<ServerMember>,
    onBack: () -> Unit,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier,
    iconUrl: String? = null,
    headerUser: User? = null,
    voice: Boolean = false,
    roles: List<Role> = emptyList(),
    presence: Map<String, PresenceStatus> = emptyMap(),
    presenceDevices: Map<String, Set<PresenceDevice>> = emptyMap(),
    presenceActivities: Map<String, List<UserActivity>> = emptyMap(),
    muted: Boolean = false,
    onMute: (MuteDuration) -> Unit = {},
    onUnmute: () -> Unit = {},
    onOpenSettings: (() -> Unit)? = null,
    onInvite: (() -> Unit)? = null,
    onOpenProfile: (User) -> Unit = {},
    backgroundUrl: String? = null,
    onSetBackground: ((Uri) -> Unit)? = null,
    onRemoveBackground: (() -> Unit)? = null,
    onSetIcon: ((Uri) -> Unit)? = null,
    onRemoveIcon: (() -> Unit)? = null,
    onAddPeople: (() -> Unit)? = null,
) {
    val c = OrangTheme.colors
    val context = LocalContext.current
    var tab by remember { mutableIntStateOf(0) }
    var muteMenuOpen by remember { mutableStateOf(false) }

    val backgroundPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri -> if (uri != null) onSetBackground?.invoke(uri) }
    val iconPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri -> if (uri != null) onSetIcon?.invoke(uri) }

    val actions = buildList {
        if (onInvite != null) add(MenuItem("Invite Members", Icons.Default.PersonAdd, onClick = onInvite))
        if (onAddPeople != null) add(MenuItem("Add People", Icons.Default.PersonAdd, onClick = onAddPeople))
        if (onSetBackground != null) {
            add(
                MenuItem(
                    if (backgroundUrl != null) "Change Background" else "Set Chat Background",
                    Icons.Default.Photo,
                ) {
                    backgroundPicker.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                    )
                },
            )
            if (backgroundUrl != null && onRemoveBackground != null) {
                add(MenuItem("Remove Background", Icons.Default.Delete, destructive = true, onClick = onRemoveBackground))
            }
        }
        if (onSetIcon != null) {
            add(
                MenuItem(
                    if (iconUrl != null) "Change Group Icon" else "Set Group Icon",
                    Icons.Default.Group,
                ) {
                    iconPicker.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                    )
                },
            )
            if (iconUrl != null && onRemoveIcon != null) {
                add(MenuItem("Remove Group Icon", Icons.Default.Delete, destructive = true, onClick = onRemoveIcon))
            }
        }
    }

    val statusOf: (ServerMember) -> PresenceStatus = { presence[it.userId] ?: it.user.status }

    val sections = remember(members, roles, presence) {
        memberSections(members, roles) { presence[it.userId] ?: it.user.status }
    }
    val newestFirst = remember(messages) { messages.asReversed() }
    val media = remember(newestFirst) {
        newestFirst.flatMap { it.attachments }.filter { it.isImage || it.isVideo }
    }
    val links = remember(newestFirst) { sharedLinks(newestFirst) }
    val files = remember(newestFirst) {
        newestFirst.flatMap { message ->
            message.attachments
                .filterNot { it.isImage || it.isVideo }
                .map { SharedFile(it, message.author.displayName) }
        }
    }

    Column(modifier = modifier.fillMaxSize().background(c.surface2)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                tint = c.inkSecondary,
                modifier = Modifier.clickable(onClick = onBack).padding(4.dp).size(24.dp),
            )
            Spacer(Modifier.weight(1f))
            Icon(
                Icons.Default.Search,
                contentDescription = "Search messages",
                tint = c.inkSecondary,
                modifier = Modifier.clickable(onClick = onSearch).padding(8.dp).size(24.dp),
            )
            Box {
                Icon(
                    if (muted) Icons.Default.NotificationsOff else Icons.Default.Notifications,
                    contentDescription = if (muted) "Unmute channel" else "Mute channel",
                    tint = if (muted) c.danger else c.inkSecondary,
                    modifier = Modifier
                        .clickable { if (muted) onUnmute() else muteMenuOpen = true }
                        .padding(8.dp)
                        .size(24.dp),
                )
                OrangDropdownMenu(
                    expanded = muteMenuOpen,
                    onDismiss = { muteMenuOpen = false },
                    items = muteDurationItems(context, onMute),
                )
            }
            if (onOpenSettings != null) {
                Icon(
                    Icons.Default.Settings,
                    contentDescription = "Channel settings",
                    tint = c.inkSecondary,
                    modifier = Modifier.clickable(onClick = onOpenSettings).padding(8.dp).size(24.dp),
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when {
                headerUser != null -> Avatar(
                    user = headerUser,
                    size = 52.dp,
                    status = presence[headerUser.id] ?: headerUser.status,
                )
                iconUrl != null -> GroupIcon(iconUrl = iconUrl, size = 52.dp)
                else -> Box(
                    modifier = Modifier
                        .size(52.dp)
                        .clip(RoundedCornerShape(OrangRadius.lg))
                        .background(c.surface3),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (voice) Icons.Default.VolumeUp else Icons.Default.Tag,
                        contentDescription = null,
                        tint = c.inkSecondary,
                        modifier = Modifier.size(26.dp),
                    )
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, color = c.ink, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                Text(kindLabel, color = c.inkMuted, fontSize = 13.sp)
            }
        }
        if (!topic.isNullOrBlank()) {
            Text(
                topic,
                color = c.inkSecondary,
                fontSize = 13.sp,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp),
            )
        }

        Spacer(Modifier.height(14.dp))
        OrangUnderlineTabs(
            tabs = TABS,
            selectedIndex = tab,
            onSelect = { tab = it },
            modifier = Modifier.padding(horizontal = 8.dp),
        )

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when (tab) {
                0 -> MembersTab(
                    sections = sections,
                    roles = roles,
                    presence = presence,
                    presenceDevices = presenceDevices,
                    presenceActivities = presenceActivities,
                    statusOf = statusOf,
                    actions = actions,
                    onOpenProfile = onOpenProfile,
                )
                1 -> MediaTab(media)
                2 -> LinksTab(links)
                else -> FilesTab(files)
            }
        }
    }
}

@Composable
private fun MembersTab(
    sections: List<MemberSection>,
    roles: List<Role>,
    presence: Map<String, PresenceStatus>,
    presenceDevices: Map<String, Set<PresenceDevice>>,
    presenceActivities: Map<String, List<UserActivity>>,
    statusOf: (ServerMember) -> PresenceStatus,
    actions: List<MenuItem>,
    onOpenProfile: (User) -> Unit,
) {
    val c = OrangTheme.colors
    if (sections.isEmpty() && actions.isEmpty()) {
        EmptyTab("No members to show")
        return
    }
    LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp)) {
        if (actions.isNotEmpty()) {
            items(actions, key = { it.label }) { action -> ActionRow(action) }
            item { Spacer(Modifier.height(20.dp)) }
        }
        sections.forEach { section ->
            item(key = "head:${section.label}") {
                Text(
                    "${section.label} — ${section.members.size}",
                    color = c.inkMuted,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(bottom = 8.dp, top = 4.dp),
                )
            }
            items(section.members, key = { "${section.label}:${it.id}" }) { member ->
                MemberRow(
                    member = member,
                    roles = roles,
                    status = statusOf(member),
                    devices = presenceDevices[member.userId].orEmpty(),
                    activities = presenceActivities[member.userId] ?: member.user.activities,
                    onClick = { onOpenProfile(member.user) },
                )
            }
            item(key = "gap:${section.label}") { Spacer(Modifier.height(18.dp)) }
        }
    }
}

@Composable
private fun ActionRow(item: MenuItem) {
    val c = OrangTheme.colors
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(OrangRadius.lg))
                .background(c.surface3)
                .clickable(onClick = item.onClick)
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (item.icon != null) {
                Icon(
                    item.icon,
                    contentDescription = null,
                    tint = if (item.destructive) c.danger else c.ink,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.width(14.dp))
            }
            Text(
                item.label,
                color = if (item.destructive) c.danger else c.ink,
                fontSize = 16.sp,
                modifier = Modifier.weight(1f),
            )
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = c.inkMuted,
                modifier = Modifier.size(20.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun MemberRow(
    member: ServerMember,
    roles: List<Role>,
    status: PresenceStatus,
    devices: Set<PresenceDevice>,
    activities: List<UserActivity>,
    onClick: () -> Unit,
) {
    val c = OrangTheme.colors
    val name = member.nickname ?: member.user.displayName
    val color = remember(member.roleIds, roles) { nameColor(member, roles, c.ink) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(OrangRadius.md))
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(
            user = member.user.copy(devices = devices.toList()),
            size = 36.dp,
            status = status,
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    name,
                    color = color,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (member.user.bot) {
                    Spacer(Modifier.width(6.dp))
                    BotTag()
                }
                DeviceIndicators(
                    status = status,
                    devices = devices,
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
            ActivityStatus(activities)
        }
    }
}

@Composable
private fun MediaTab(media: List<Attachment>) {
    if (media.isEmpty()) {
        EmptyTab("Nothing has been shared here yet")
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items(media, key = { it.id }) { attachment -> MediaTile(attachment) }
    }
}

@Composable
private fun MediaTile(attachment: Attachment) {
    val c = OrangTheme.colors
    val context = LocalContext.current
    val view = LocalView.current
    val origin = rememberMediaOrigin()
    val source = rememberAttachmentSource(attachment)
    val href = source.url
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(OrangRadius.md))
            .background(c.surface3)
            .mediaOrigin(origin)
            .clickable { openMediaPreview(context, view, origin, attachment) },
        contentAlignment = Alignment.Center,
    ) {
        if (href != null) {
            AsyncImage(
                model = href,
                contentDescription = attachment.filename,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (attachment.isVideo) {
            Icon(
                Icons.Default.PlayCircle,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(28.dp),
            )
        }
    }
}

@Composable
private fun LinksTab(links: List<SharedLink>) {
    val c = OrangTheme.colors
    val context = LocalContext.current
    if (links.isEmpty()) {
        EmptyTab("No links have been shared here yet")
        return
    }
    LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)) {
        items(links, key = { it.url }) { link ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(OrangRadius.md))
                    .clickable {
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW, link.url.toUri()))
                        }
                    }
                    .padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        hostOf(link.url),
                        color = c.ink,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        link.url,
                        color = c.primary,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text("Shared by ${link.author}", color = c.inkMuted, fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
private fun FilesTab(files: List<SharedFile>) {
    val c = OrangTheme.colors
    val download = rememberAttachmentDownloader()
    if (files.isEmpty()) {
        EmptyTab("No files have been shared here yet")
        return
    }
    LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)) {
        items(files, key = { it.attachment.id }) { file ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(OrangRadius.md))
                    .clickable { download(file.attachment) }
                    .padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(c.surface3),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.InsertDriveFile,
                        contentDescription = null,
                        tint = c.inkSecondary,
                        modifier = Modifier.size(20.dp),
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        file.attachment.filename,
                        color = c.ink,
                        fontSize = 15.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "${formatBytes(file.attachment.size)} · ${file.author}",
                        color = c.inkMuted,
                        fontSize = 12.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyTab(message: String) {
    val c = OrangTheme.colors
    Box(modifier = Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(message, color = c.inkMuted, fontSize = 14.sp)
    }
}

/**
 * Hoisted roles get their own section, highest first, and everyone else falls through to
 * plain online/offline - the grouping a server's own role setup already implies.
 */
private fun memberSections(
    members: List<ServerMember>,
    roles: List<Role>,
    statusOf: (ServerMember) -> PresenceStatus,
): List<MemberSection> {
    if (members.isEmpty()) return emptyList()
    val byName = compareBy<ServerMember> { (it.nickname ?: it.user.displayName).lowercase() }
    val online = members.filter { statusOf(it) != PresenceStatus.OFFLINE }
    val offline = members.filter { statusOf(it) == PresenceStatus.OFFLINE }
    val hoisted = roles.filter { it.hoist }.sortedByDescending { it.position }

    val sections = mutableListOf<MemberSection>()
    val claimed = mutableSetOf<String>()
    hoisted.forEach { role ->
        val group = online.filter { member ->
            member.id !in claimed &&
                role.id in member.roleIds &&
                topHoistedRole(member, hoisted)?.id == role.id
        }
        if (group.isEmpty()) return@forEach
        claimed += group.map { it.id }
        sections += MemberSection(role.name, group.sortedWith(byName))
    }
    val rest = online.filter { it.id !in claimed }
    if (rest.isNotEmpty()) sections += MemberSection("Online", rest.sortedWith(byName))
    if (offline.isNotEmpty()) sections += MemberSection("Offline", offline.sortedWith(byName))
    return sections
}

private fun topHoistedRole(member: ServerMember, hoisted: List<Role>): Role? =
    hoisted.firstOrNull { it.id in member.roleIds }

private fun nameColor(member: ServerMember, roles: List<Role>, fallback: Color): Color {
    val colored = roles
        .filter { it.id in member.roleIds && it.color != 0 }
        .maxByOrNull { it.position }
        ?: return fallback
    return roleColor(colored.color, fallback)
}

private fun sharedLinks(newestFirst: List<Message>): List<SharedLink> {
    val seen = mutableSetOf<String>()
    val out = mutableListOf<SharedLink>()
    for (message in newestFirst) {
        for (match in URL.findAll(message.content)) {
            val url = match.value.trimEnd('.', ',', ')', ']', '!', '?')
            if (seen.add(url)) out += SharedLink(url, message.author.displayName)
        }
    }
    return out
}

private fun hostOf(url: String): String =
    runCatching { url.toUri().host }.getOrNull()?.removePrefix("www.") ?: url
