package lt.oranges.orangchat.feature.dms

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import lt.oranges.orangchat.data.model.Friend
import lt.oranges.orangchat.data.model.PresenceStatus
import lt.oranges.orangchat.ui.components.Avatar
import lt.oranges.orangchat.ui.components.ButtonSize
import lt.oranges.orangchat.ui.components.OrangButton
import lt.oranges.orangchat.ui.components.OrangTextField
import lt.oranges.orangchat.ui.components.Text
import lt.oranges.orangchat.ui.theme.OrangRadius
import lt.oranges.orangchat.ui.theme.OrangTheme

// A group tops out at 15 people: you plus 14 others, matching the server.
private const val MAX_RECIPIENTS = 14

/**
 * Pick friends to start a group DM, or to grow an existing one. Mirrors the web
 * NewDmDialog - a searchable friend list with multi-select and selected-chips.
 *
 * In add mode ([addMode] = true) the title/button say "Add", the minimum is one
 * friend instead of two, and [excludeUserIds] (a group's current participants)
 * are hidden so you only see friends who aren't in it yet.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NewGroupScreen(
    friends: List<Friend>,
    presence: Map<String, PresenceStatus>,
    onBack: () -> Unit,
    onConfirm: (List<String>) -> Unit,
    modifier: Modifier = Modifier,
    addMode: Boolean = false,
    excludeUserIds: Set<String> = emptySet(),
    /** Remaining seats: how many more people the group can take (15-person cap). */
    maxSelection: Int = MAX_RECIPIENTS,
) {
    val c = OrangTheme.colors
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<List<Friend>>(emptyList()) }
    val minSelection = if (addMode) 1 else 2

    val candidates = remember(friends, excludeUserIds) {
        friends.filter { it.user.id !in excludeUserIds }
    }
    val visible = remember(candidates, query) {
        val q = query.trim().lowercase()
        val sorted = candidates.sortedBy { it.user.displayName.lowercase() }
        if (q.isBlank()) sorted
        else sorted.filter {
            it.user.displayName.lowercase().contains(q) ||
                it.user.username.lowercase().contains(q)
        }
    }

    fun toggle(friend: Friend) {
        selected = when {
            selected.any { it.id == friend.id } -> selected.filterNot { it.id == friend.id }
            selected.size < maxSelection -> selected + friend
            else -> selected
        }
    }

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
            Text(if (addMode) "Add friends" else "New group", color = c.ink, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        }

        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            OrangTextField(
                value = query,
                onValueChange = { query = it },
                label = "Find friends",
                placeholder = "Search by name or username",
            )
            if (selected.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    selected.forEach { friend ->
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(OrangRadius.md))
                                .background(c.primarySoft)
                                .clickable { toggle(friend) }
                                .padding(horizontal = 10.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(friend.user.displayName, color = c.primary, fontSize = 13.sp)
                            Spacer(Modifier.width(5.dp))
                            Icon(Icons.Default.Close, contentDescription = "Remove", tint = c.primary, modifier = Modifier.size(14.dp))
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        LazyColumn(modifier = Modifier.weight(1f)) {
            if (candidates.isEmpty()) {
                item {
                    Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        Text(
                            if (addMode) "All your friends are already here." else "No friends yet. Add someone first.",
                            color = c.inkMuted,
                            fontSize = 14.sp,
                        )
                    }
                }
            } else if (visible.isEmpty()) {
                item {
                    Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        Text("No matches.", color = c.inkMuted, fontSize = 14.sp)
                    }
                }
            }
            items(visible, key = { it.id }) { friend ->
                val isSelected = selected.any { it.id == friend.id }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 1.dp)
                        .clip(RoundedCornerShape(OrangRadius.md))
                        .background(if (isSelected) c.primarySoft else c.surface2)
                        .clickable { toggle(friend) }
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Avatar(
                        friend.user,
                        size = 38.dp,
                        status = presence[friend.user.id] ?: friend.user.status,
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(friend.user.displayName, color = c.ink, fontWeight = FontWeight.Medium, fontSize = 15.sp, maxLines = 1)
                        Text("@${friend.user.username}", color = c.inkMuted, fontSize = 12.sp, maxLines = 1)
                    }
                    if (isSelected) {
                        Box(
                            modifier = Modifier.size(22.dp).clip(RoundedCornerShape(OrangRadius.sm)).background(c.primary),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Default.Check, contentDescription = "Selected", tint = c.surface1, modifier = Modifier.size(15.dp))
                        }
                    } else {
                        Box(
                            modifier = Modifier
                                .size(22.dp)
                                .clip(RoundedCornerShape(OrangRadius.sm))
                                .background(c.surface3),
                        )
                    }
                }
            }
        }

        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(c.border))
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            val count = selected.size
            val verb = if (addMode) "Add" else "Create group"
            OrangButton(
                text = if (count == 0) verb else "$verb ($count)",
                onClick = { if (count >= minSelection) onConfirm(selected.map { it.user.id }) },
                size = ButtonSize.Lg,
                enabled = count >= minSelection,
            )
            if (!addMode && count == 1) {
                Spacer(Modifier.height(6.dp))
                Text("Pick at least one more friend for a group.", color = c.inkMuted, fontSize = 12.sp)
            }
        }
    }
}
