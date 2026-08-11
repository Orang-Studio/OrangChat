package lt.oranges.orangchat.feature.members

import androidx.compose.ui.platform.LocalContext
import lt.oranges.orangchat.R
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import lt.oranges.orangchat.ui.components.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import lt.oranges.orangchat.data.model.Hierarchy
import lt.oranges.orangchat.data.model.PresenceStatus
import lt.oranges.orangchat.data.model.PresenceDevice
import lt.oranges.orangchat.data.model.UserActivity
import lt.oranges.orangchat.data.model.Role
import lt.oranges.orangchat.data.model.ServerDetail
import lt.oranges.orangchat.data.model.ServerMember
import lt.oranges.orangchat.data.model.isTimedOut
import lt.oranges.orangchat.feature.roles.Header
import lt.oranges.orangchat.feature.roles.Section
import lt.oranges.orangchat.feature.roles.roleColor
import lt.oranges.orangchat.ui.components.Avatar
import lt.oranges.orangchat.ui.components.BotTag
import lt.oranges.orangchat.ui.components.ButtonVariant
import lt.oranges.orangchat.ui.components.ConfirmDialog
import lt.oranges.orangchat.ui.components.DeviceIndicators
import lt.oranges.orangchat.ui.components.ActivityStatus
import lt.oranges.orangchat.ui.components.OrangButton
import lt.oranges.orangchat.ui.components.OrangDialog
import lt.oranges.orangchat.ui.components.OrangTextField
import lt.oranges.orangchat.ui.theme.OrangRadius
import lt.oranges.orangchat.ui.theme.OrangTheme
import lt.oranges.orangchat.util.AppStrings
import lt.oranges.orangchat.util.formatDateTime

/** Discord's timeout ladder. The server caps anything above 28 days. */
private val TIMEOUT_CHOICES = listOf(
    "60 seconds" to 60L,
    "5 minutes" to 300L,
    "10 minutes" to 600L,
    "1 hour" to 3600L,
    "1 day" to 86_400L,
    "1 week" to 604_800L,
)

@Composable
fun MembersScreen(
    detail: ServerDetail,
    selfId: String,
    presence: Map<String, PresenceStatus>,
    presenceDevices: Map<String, Set<PresenceDevice>>,
    presenceActivities: Map<String, List<UserActivity>>,
    onBack: () -> Unit,
    onSetNickname: (String, String?) -> Unit,
    onAssignRole: (String, String) -> Unit,
    onUnassignRole: (String, String) -> Unit,
    onTimeout: (String, Long) -> Unit,
    onLiftTimeout: (String) -> Unit,
    onKick: (String) -> Unit,
    onBan: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
        val context = LocalContext.current
    val c = OrangTheme.colors
    var selectedId by remember(detail.server.id) { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }

    val selected = selectedId?.let { id -> detail.members.firstOrNull { it.userId == id } }

    val filtered = remember(detail.members, query) {
        val q = query.trim().lowercase()
        detail.members
            .filter {
                q.isEmpty() ||
                    it.user.displayName.lowercase().contains(q) ||
                    it.user.username.lowercase().contains(q) ||
                    (it.nickname?.lowercase()?.contains(q) == true)
            }
            .sortedBy { (it.nickname ?: it.user.displayName).lowercase() }
    }

    Column(modifier = modifier.fillMaxSize().background(c.surface2)) {
        Header(title = "Members - ${detail.members.size}", onBack = onBack)

        Box(modifier = Modifier.padding(16.dp)) {
            OrangTextField(
                value = query,
                onValueChange = { query = it },
                label = "Search",
                placeholder = AppStrings.get(context, R.string.catalog_find_a_member_036dc8dd),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(filtered, key = { it.id }) { member ->
                MemberRow(
                    member = member,
                    detail = detail,
                    status = presence[member.userId] ?: member.user.status,
                    devices = presenceDevices[member.userId] ?: member.user.devices.toSet(),
                    activities = presenceActivities[member.userId] ?: member.user.activities,
                    isOwner = Hierarchy.isOwner(detail, member.userId),
                    onClick = { selectedId = member.userId },
                )
            }
        }
    }

    if (selected != null) {
        MemberSheet(
            detail = detail,
            selfId = selfId,
            member = selected,
            onDismiss = { selectedId = null },
            onSetNickname = onSetNickname,
            onAssignRole = onAssignRole,
            onUnassignRole = onUnassignRole,
            onTimeout = onTimeout,
            onLiftTimeout = onLiftTimeout,
            onKick = { onKick(selected.userId); selectedId = null },
            onBan = { onBan(selected.userId); selectedId = null },
        )
    }
}

@Composable
private fun MemberRow(
    member: ServerMember,
    detail: ServerDetail,
    status: PresenceStatus,
    devices: Set<PresenceDevice>,
    activities: List<UserActivity>,
    isOwner: Boolean,
    onClick: () -> Unit,
) {
        val context = LocalContext.current
    val c = OrangTheme.colors
    val topRole = detail.roles
        .filter { it.id in member.roleIds && it.color != 0 }
        .maxByOrNull { it.position }
    val timedOut = member.isTimedOut()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(c.surface3, RoundedCornerShape(OrangRadius.lg))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(user = member.user, size = 34.dp)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = member.nickname ?: member.user.displayName,
                    color = topRole?.let { roleColor(it.color, c.ink) } ?: c.ink,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                )
                if (member.user.bot) {
                    Spacer(Modifier.width(6.dp))
                    BotTag()
                }
                if (isOwner) {
                    Spacer(Modifier.width(6.dp))
                    Text("Owner", color = c.warning, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                }
                if (timedOut) {
                    Spacer(Modifier.width(6.dp))
                    Text(AppStrings.get(context, R.string.catalog_timed_out_edcd3630), color = c.danger, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("@${member.user.username}", color = c.inkMuted, fontSize = 12.sp)
                Spacer(Modifier.width(5.dp))
                DeviceIndicators(status = status, devices = devices, modifier = Modifier.height(14.dp))
            }
            ActivityStatus(activities = activities)
        }
        val roleCount = member.roleIds.size
        if (roleCount > 0) {
            Text("$roleCount role${if (roleCount == 1) "" else "s"}", color = c.inkMuted, fontSize = 11.sp)
        }
    }
}

@Composable
private fun MemberSheet(
    detail: ServerDetail,
    selfId: String,
    member: ServerMember,
    onDismiss: () -> Unit,
    onSetNickname: (String, String?) -> Unit,
    onAssignRole: (String, String) -> Unit,
    onUnassignRole: (String, String) -> Unit,
    onTimeout: (String, Long) -> Unit,
    onLiftTimeout: (String) -> Unit,
    onKick: () -> Unit,
    onBan: () -> Unit,
) {
        val context = LocalContext.current
    val c = OrangTheme.colors
    var nickname by remember(member.id) { mutableStateOf(member.nickname ?: "") }
    var confirmKick by remember { mutableStateOf(false) }
    var confirmBan by remember { mutableStateOf(false) }
    var timeoutOpen by remember { mutableStateOf(false) }

    val canNickname = Hierarchy.canManageNickname(detail, selfId, member.userId)
    val canKick = Hierarchy.canKick(detail, selfId, member.userId)
    val canBan = Hierarchy.canBan(detail, selfId, member.userId)
    val canTimeout = Hierarchy.canTimeout(detail, selfId, member.userId)
    val timedOut = member.isTimedOut()

    // @everyone is never assignable, so it never appears in this list.
    val assignable = detail.roles
        .filter { it.position != lt.oranges.orangchat.data.model.Permissions.EVERYONE_POSITION }
        .sortedByDescending { it.position }

    OrangDialog(
        onDismiss = onDismiss,
        title = member.nickname ?: member.user.displayName,
        description = "@${member.user.username}",
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(18.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (timedOut) {
                Text(
                    "Timed out until ${formatDateTime(member.timedOutUntil)}.",
                    color = c.danger,
                    fontSize = 12.sp,
                )
            }

            if (canNickname) {
                Section("Nickname") {
                    OrangTextField(
                        value = nickname,
                        onValueChange = { nickname = it },
                        label = "Nickname",
                        placeholder = member.user.displayName,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OrangButton(
                        text = AppStrings.get(context, R.string.catalog_save_nickname_51ddb3ea),
                        onClick = { onSetNickname(member.userId, nickname.trim().ifBlank { null }) },
                        enabled = nickname.trim() != (member.nickname ?: ""),
                        variant = ButtonVariant.Secondary,
                    )
                }
            }

            if (assignable.isNotEmpty()) {
                Section("Roles") {
                    assignable.forEach { role ->
                        val assigned = role.id in member.roleIds
                        val allowed = Hierarchy.canChangeMemberRole(
                            detail, selfId, member.userId, role, granting = !assigned,
                        )
                        RoleToggle(
                            role = role,
                            assigned = assigned,
                            enabled = allowed,
                            onToggle = {
                                if (assigned) onUnassignRole(member.userId, role.id)
                                else onAssignRole(member.userId, role.id)
                            },
                        )
                    }
                }
            }

            if (canTimeout || timedOut) {
                Section("Moderation") {
                    if (timedOut) {
                        OrangButton(
                            text = AppStrings.get(context, R.string.catalog_lift_timeout_560ca50c),
                            onClick = { onLiftTimeout(member.userId) },
                            variant = ButtonVariant.Secondary,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    if (canTimeout) {
                        OrangButton(
                            text = AppStrings.get(context, R.string.catalog_time_out_71ed8fda),
                            onClick = { timeoutOpen = true },
                            variant = ButtonVariant.Secondary,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }

            if (canKick || canBan) {
                Section(AppStrings.get(context, R.string.catalog_danger_zone_963a652e)) {
                    if (canKick) {
                        OrangButton(
                            text = "Kick",
                            onClick = { confirmKick = true },
                            variant = ButtonVariant.Danger,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    if (canBan) {
                        OrangButton(
                            text = "Ban",
                            onClick = { confirmBan = true },
                            variant = ButtonVariant.Danger,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }

            OrangButton(
                text = "Close",
                onClick = onDismiss,
                variant = ButtonVariant.Secondary,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    if (timeoutOpen) {
        TimeoutDialog(
            name = member.nickname ?: member.user.displayName,
            onDismiss = { timeoutOpen = false },
            onPick = { seconds -> timeoutOpen = false; onTimeout(member.userId, seconds) },
        )
    }
    if (confirmKick) {
        ConfirmDialog(
            onDismiss = { confirmKick = false },
            onConfirm = { confirmKick = false; onKick() },
            title = "Kick ${member.user.displayName}?",
            message = "They can rejoin with a new invite.",
            confirmText = "Kick",
            destructive = true,
        )
    }
    if (confirmBan) {
        ConfirmDialog(
            onDismiss = { confirmBan = false },
            onConfirm = { confirmBan = false; onBan() },
            title = "Ban ${member.user.displayName}?",
            message = "They are removed and cannot rejoin until unbanned.",
            confirmText = "Ban",
            destructive = true,
        )
    }
}

@Composable
private fun RoleToggle(role: Role, assigned: Boolean, enabled: Boolean, onToggle: () -> Unit) {
    val c = OrangTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(c.surface3, RoundedCornerShape(OrangRadius.lg))
            .then(if (enabled) Modifier.clickable(onClick = onToggle) else Modifier)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(12.dp).background(roleColor(role.color, c.inkMuted), CircleShape))
        Spacer(Modifier.width(10.dp))
        Text(
            role.name,
            color = if (enabled) c.ink else c.inkMuted,
            fontSize = 14.sp,
            modifier = Modifier.weight(1f),
        )
        androidx.compose.material3.Checkbox(
            checked = assigned,
            onCheckedChange = if (enabled) { _ -> onToggle() } else null,
            enabled = enabled,
            colors = androidx.compose.material3.CheckboxDefaults.colors(
                checkedColor = c.primary,
                uncheckedColor = c.border,
                checkmarkColor = c.inkOnPrimary,
            ),
        )
    }
}

@Composable
private fun TimeoutDialog(name: String, onDismiss: () -> Unit, onPick: (Long) -> Unit) {
        val context = LocalContext.current
    val c = OrangTheme.colors
    OrangDialog(
        onDismiss = onDismiss,
        title = "Time out $name",
        description = AppStrings.get(context, R.string.catalog_they_cannot_send_messages_react_or_speak_7fcf8d0c),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            TIMEOUT_CHOICES.forEach { (label, seconds) ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(c.surface3, RoundedCornerShape(OrangRadius.lg))
                        .clickable { onPick(seconds) }
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                ) {
                    Text(label, color = c.ink, fontSize = 14.sp)
                }
            }
            Spacer(Modifier.height(4.dp))
            OrangButton(
                text = "Cancel",
                onClick = onDismiss,
                variant = ButtonVariant.Secondary,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
