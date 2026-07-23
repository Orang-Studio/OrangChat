package lt.oranges.orangchat.feature.share

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import lt.oranges.orangchat.data.model.PresenceStatus
import lt.oranges.orangchat.ui.components.Avatar
import lt.oranges.orangchat.ui.components.OrangButton
import lt.oranges.orangchat.ui.components.Text
import lt.oranges.orangchat.ui.theme.OrangRadius
import lt.oranges.orangchat.ui.theme.OrangTheme

@Composable
fun ShareScreen(
    share: PendingShare,
    onDismiss: () -> Unit,
    viewModel: ShareViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    var note by remember(share) { mutableStateOf(share.text) }
    var selected by remember { mutableStateOf<ShareDestination?>(null) }
    val activity = LocalContext.current as? Activity
    val colors = OrangTheme.colors

    LaunchedEffect(Unit) { viewModel.load() }
    LaunchedEffect(state.sent) {
        if (state.sent) {
            onDismiss()
            activity?.finish()
        }
    }
    BackHandler {
        onDismiss()
        activity?.finish()
    }

    val normalized = query.trim().lowercase()
    val friends = state.friends.filter {
        normalized.isEmpty() || it.user.displayName.lowercase().contains(normalized) ||
            it.user.username.lowercase().contains(normalized)
    }
    val channels = state.channels.filter {
        normalized.isEmpty() || it.title.lowercase().contains(normalized) || it.subtitle.lowercase().contains(normalized)
    }

    Column(Modifier.fillMaxSize().background(colors.surface1)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { onDismiss(); activity?.finish() }) {
                Icon(Icons.Default.Close, "Close", tint = colors.ink)
            }
            Text("Share to OrangChat", color = colors.ink, fontWeight = FontWeight.Bold, fontSize = 19.sp)
        }
        HorizontalDivider(color = colors.border)

        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("Search friends and channels") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                shape = RoundedCornerShape(OrangRadius.lg),
            )
            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                maxLines = 4,
                label = { Text(if (share.text.isBlank()) "Add a message" else "Shared text") },
                shape = RoundedCornerShape(OrangRadius.lg),
            )
            if (share.uris.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.AttachFile, null, tint = colors.primary, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (share.uris.size == 1) "1 attachment" else "${share.uris.size} attachments",
                        color = colors.inkSecondary,
                        fontSize = 14.sp,
                    )
                }
            }
        }

        when {
            state.loading -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = colors.primary)
            }
            else -> LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 12.dp)) {
                if (channels.isNotEmpty()) {
                    item { SectionTitle("Recent channels") }
                    items(channels, key = { it.id }) { destination ->
                        DestinationRow(
                            title = destination.title,
                            subtitle = destination.subtitle,
                            selected = selected?.id == destination.id,
                            icon = { Icon(Icons.Default.Tag, null, tint = colors.inkSecondary) },
                            onClick = { selected = destination },
                        )
                    }
                }
                if (friends.isNotEmpty()) {
                    item { SectionTitle("All friends") }
                    items(friends, key = { it.id }) { friend ->
                        val destination = ShareDestination.FriendDestination(friend)
                        DestinationRow(
                            title = friend.user.displayName,
                            subtitle = "@${friend.user.username}",
                            selected = selected?.id == destination.id,
                            icon = { Avatar(friend.user, size = 38.dp, status = friend.user.status.takeUnless { it == PresenceStatus.OFFLINE }) },
                            onClick = { selected = destination },
                        )
                    }
                }
                if (channels.isEmpty() && friends.isEmpty()) {
                    item { Text("No matching friends or channels", color = colors.inkMuted, modifier = Modifier.padding(24.dp)) }
                }
            }
        }

        state.error?.let {
            Text(it, color = colors.danger, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
        }
        if (state.sending && share.uris.isNotEmpty()) {
            LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth(), color = colors.primary)
        }
        Surface(color = colors.surface2, tonalElevation = 3.dp) {
            OrangButton(
                text = if (state.sending) "Sharing…" else "Share",
                onClick = { selected?.let { viewModel.send(it, note, share.uris) } },
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                enabled = selected != null && (note.isNotBlank() || share.uris.isNotEmpty()),
                loading = state.sending,
            )
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text.uppercase(),
        color = OrangTheme.colors.inkMuted,
        fontWeight = FontWeight.Bold,
        fontSize = 12.sp,
        modifier = Modifier.padding(start = 18.dp, top = 16.dp, bottom = 6.dp),
    )
}

@Composable
private fun DestinationRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    icon: @Composable () -> Unit,
    onClick: () -> Unit,
) {
    val colors = OrangTheme.colors
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(OrangRadius.md))
            .background(if (selected) colors.primarySoft else colors.surface1)
            .clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) { icon() }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = colors.ink, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, color = colors.inkMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        RadioButton(selected = selected, onClick = onClick)
    }
}
