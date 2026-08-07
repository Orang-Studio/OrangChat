package lt.oranges.orangchat.feature.friends

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.minimumInteractiveComponentSize
import lt.oranges.orangchat.ui.components.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import lt.oranges.orangchat.data.model.Friend
import lt.oranges.orangchat.data.model.FriendRequest
import lt.oranges.orangchat.data.model.PresenceStatus
import lt.oranges.orangchat.data.model.PresenceDevice
import lt.oranges.orangchat.data.model.UserActivity
import lt.oranges.orangchat.ui.components.Avatar
import lt.oranges.orangchat.ui.components.ButtonSize
import lt.oranges.orangchat.ui.components.DeviceIndicators
import lt.oranges.orangchat.ui.components.ActivityStatus
import lt.oranges.orangchat.ui.components.OrangButton
import lt.oranges.orangchat.ui.components.OrangTabs
import lt.oranges.orangchat.ui.components.OrangTextField
import lt.oranges.orangchat.ui.theme.OrangTheme

@Composable
fun FriendsScreen(
    friends: List<Friend>,
    incoming: List<FriendRequest>,
    outgoing: List<FriendRequest>,
    presence: Map<String, PresenceStatus>,
    presenceDevices: Map<String, Set<PresenceDevice>>,
    presenceActivities: Map<String, List<UserActivity>>,
    onBack: () -> Unit,
    onAdd: (String) -> Unit,
    onAccept: (String) -> Unit,
    onDecline: (String) -> Unit,
    onRemove: (String) -> Unit,
    onMessage: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = OrangTheme.colors
    var tab by remember { mutableIntStateOf(0) }
    var addName by remember { mutableStateOf("") }
    val tabs = listOf("Friends", "Pending", "Add")

    Column(modifier = modifier.fillMaxSize().background(c.surface2)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                tint = c.inkSecondary,
                modifier = Modifier.clickable(onClick = onBack).padding(4.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text("Friends", color = c.ink, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        }
        OrangTabs(tabs, tab, { tab = it }, modifier = Modifier.padding(horizontal = 12.dp))
        Spacer(Modifier.height(8.dp))

        when (tab) {
            0 -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                if (friends.isEmpty()) item { EmptyHint("No friends yet. Add someone from the Add tab.") }
                items(friends, key = { it.id }) { friend ->
                    FriendRow(
                        name = friend.user.displayName,
                        username = friend.user.username,
                        avatarUrl = friend.user.avatarUrl,
                        status = presence[friend.user.id] ?: friend.user.status,
                        devices = presenceDevices[friend.user.id] ?: friend.user.devices.toSet(),
                        activities = presenceActivities[friend.user.id] ?: friend.user.activities,
                        trailing = {
                            Icon(
                                Icons.AutoMirrored.Filled.Message,
                                contentDescription = "Message",
                                tint = c.inkSecondary,
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .size(22.dp)
                                    .clickable { onMessage(friend.user.id) },
                            )
                            // No spacer: the 48dp targets already sit the glyphs apart.
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Remove",
                                tint = c.danger,
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .size(22.dp)
                                    .clickable { onRemove(friend.user.id) },
                            )
                        },
                    )
                }
            }
            1 -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                if (incoming.isEmpty() && outgoing.isEmpty()) item { EmptyHint("No pending requests.") }
                if (incoming.isNotEmpty()) item { SectionLabel("Incoming") }
                items(incoming, key = { it.id }) { req ->
                    FriendRow(req.user.displayName, req.user.username, req.user.avatarUrl, null, trailing = {
                        Icon(Icons.Default.Check, "Accept", tint = c.success, modifier = Modifier.minimumInteractiveComponentSize().size(24.dp).clickable { onAccept(req.id) })
                        Icon(Icons.Default.Close, "Decline", tint = c.danger, modifier = Modifier.minimumInteractiveComponentSize().size(24.dp).clickable { onDecline(req.id) })
                    })
                }
                if (outgoing.isNotEmpty()) item { SectionLabel("Outgoing") }
                items(outgoing, key = { "out-${it.id}" }) { req ->
                    FriendRow(req.user.displayName, req.user.username, req.user.avatarUrl, null, trailing = {
                        Icon(Icons.Default.Close, "Cancel", tint = c.inkMuted, modifier = Modifier.minimumInteractiveComponentSize().size(22.dp).clickable { onDecline(req.id) })
                    })
                }
            }
            2 -> Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                Text("Add a friend by their username.", color = c.inkSecondary, fontSize = 14.sp)
                Spacer(Modifier.height(12.dp))
                OrangTextField(value = addName, onValueChange = { addName = it }, label = "Username", placeholder = "username")
                Spacer(Modifier.height(12.dp))
                OrangButton(
                    text = "Send friend request",
                    onClick = { if (addName.isNotBlank()) { onAdd(addName.trim()); addName = "" } },
                    size = ButtonSize.Lg,
                    enabled = addName.isNotBlank(),
                )
            }
        }
    }
}

@Composable
private fun FriendRow(
    name: String,
    username: String,
    avatarUrl: String?,
    status: PresenceStatus?,
    devices: Set<PresenceDevice> = emptySet(),
    activities: List<UserActivity> = emptyList(),
    trailing: @Composable () -> Unit,
) {
    val c = OrangTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(displayName = name, avatarUrl = avatarUrl, size = 40.dp)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(name, color = c.ink, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("@$username", color = c.inkMuted, fontSize = 12.sp)
                if (status != null) {
                    Spacer(Modifier.width(5.dp))
                    DeviceIndicators(status = status, devices = devices, modifier = Modifier.height(14.dp))
                }
            }
            ActivityStatus(activities = activities)
        }
        Row(verticalAlignment = Alignment.CenterVertically) { trailing() }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        color = OrangTheme.colors.inkMuted,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
    )
}

@Composable
private fun EmptyHint(text: String) {
    Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, color = OrangTheme.colors.inkMuted, fontSize = 14.sp)
    }
}
