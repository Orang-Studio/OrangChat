package lt.oranges.orangchat.feature.roles
import lt.oranges.orangchat.util.AppStrings
import androidx.compose.ui.platform.LocalContext
import lt.oranges.orangchat.R
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import lt.oranges.orangchat.ui.components.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import lt.oranges.orangchat.data.model.Hierarchy
import lt.oranges.orangchat.data.model.PERMISSION_GROUPS
import lt.oranges.orangchat.data.model.Permissions
import lt.oranges.orangchat.data.model.Role
import lt.oranges.orangchat.data.model.ServerDetail
import lt.oranges.orangchat.data.model.hasPermission
import lt.oranges.orangchat.data.model.toPermissionBits
import lt.oranges.orangchat.data.remote.UpdateRoleRequest
import lt.oranges.orangchat.ui.components.ButtonVariant
import lt.oranges.orangchat.ui.components.ConfirmDialog
import lt.oranges.orangchat.ui.components.OrangButton
import lt.oranges.orangchat.ui.components.OrangDialog
import lt.oranges.orangchat.ui.components.OrangTextField
import lt.oranges.orangchat.ui.theme.OrangRadius
import lt.oranges.orangchat.ui.theme.OrangTheme

/** Discord's role palette, matching the web client's swatches. */
private val ROLE_COLORS = listOf(
    0x99AAB5, 0x1ABC9C, 0x2ECC71, 0x3498DB, 0x9B59B6, 0xE91E63,
    0xF1C40F, 0xE67E22, 0xE74C3C, 0x95A5A6, 0x11806A, 0x1F8B4C,
    0x206694, 0x71368A, 0xAD1457, 0xC27C0E, 0xA84300, 0x992D22,
)

fun roleColor(value: Int, fallback: Color): Color =
    if (value == 0) fallback else Color(0xFF000000L.toInt() or value)

/**
 * Role list + editor. Every gate here mirrors services/membership.rs; the server
 * re-checks all of it, so this only decides what is offered, never what is
 * allowed.
 */
@Composable
fun RolesScreen(
    detail: ServerDetail,
    selfId: String,
    onBack: () -> Unit,
    onCreate: (String) -> Unit,
    onSave: (String, UpdateRoleRequest) -> Unit,
    onDelete: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
        val context = LocalContext.current
    val c = OrangTheme.colors
    var editingId by remember(detail.server.id) { mutableStateOf<String?>(null) }
    var creating by remember { mutableStateOf(false) }

    val editing = editingId?.let { id -> detail.roles.firstOrNull { it.id == id } }
    if (editing != null) {
        RoleEditor(
            detail = detail,
            selfId = selfId,
            role = editing,
            onBack = { editingId = null },
            onSave = { patch -> onSave(editing.id, patch) },
            onDelete = { onDelete(editing.id); editingId = null },
            modifier = modifier,
        )
        return
    }

    val canManage = Hierarchy.effectivePermissions(detail, selfId).hasPermission(Permissions.MANAGE_ROLES)
    val sorted = detail.roles.sortedByDescending { it.position }

    Column(modifier = modifier.fillMaxSize().background(c.surface2)) {
        Header(title = "Roles", onBack = onBack)

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Text(
                    AppStrings.get(context, R.string.catalog_roles_grant_permissions_and_a_colour_members_51c7e585),
                    color = c.inkMuted,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
            if (canManage) {
                item {
                    OrangButton(
                        text = AppStrings.get(context, R.string.catalog_create_role_db859bad),
                        onClick = { creating = true },
                        variant = ButtonVariant.Secondary,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            items(sorted, key = { it.id }) { role ->
                val editable = Hierarchy.canManageRole(detail, selfId, role)
                val memberCount = detail.members.count { role.id in it.roleIds }
                RoleRow(
                    role = role,
                    memberCount = memberCount,
                    editable = editable,
                    onClick = { if (editable) editingId = role.id },
                )
            }
        }
    }

    if (creating) {
        CreateRoleDialog(
            onDismiss = { creating = false },
            onCreate = { name -> creating = false; onCreate(name) },
        )
    }
}

@Composable
private fun RoleRow(role: Role, memberCount: Int, editable: Boolean, onClick: () -> Unit) {
    val c = OrangTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(c.surface3, RoundedCornerShape(OrangRadius.lg))
            .then(if (editable) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(14.dp).background(roleColor(role.color, c.inkMuted), CircleShape))
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(role.name, color = c.ink, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(
                "$memberCount member${if (memberCount == 1) "" else "s"}",
                color = c.inkMuted,
                fontSize = 12.sp,
            )
        }
        if (!editable) {
            Text("Locked", color = c.inkMuted, fontSize = 11.sp)
        }
    }
}

@Composable
private fun CreateRoleDialog(onDismiss: () -> Unit, onCreate: (String) -> Unit) {
        val context = LocalContext.current
    var name by remember { mutableStateOf("") }
    OrangDialog(
        onDismiss = onDismiss,
        title = AppStrings.get(context, R.string.catalog_create_role_db859bad),
        description = AppStrings.get(context, R.string.catalog_new_roles_start_just_above_everyone_so_700d0555),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            OrangTextField(
                value = name,
                onValueChange = { name = it },
                label = AppStrings.get(context, R.string.catalog_role_name_4204d818),
                placeholder = "Moderator",
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OrangButton(
                    text = "Cancel",
                    onClick = onDismiss,
                    variant = ButtonVariant.Secondary,
                    modifier = Modifier.weight(1f),
                )
                OrangButton(
                    text = "Create",
                    onClick = { onCreate(name.trim()) },
                    enabled = name.isNotBlank(),
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun RoleEditor(
    detail: ServerDetail,
    selfId: String,
    role: Role,
    onBack: () -> Unit,
    onSave: (UpdateRoleRequest) -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
        val context = LocalContext.current
    val c = OrangTheme.colors
    val isEveryone = role.position == Permissions.EVERYONE_POSITION

    var name by remember(role.id) { mutableStateOf(role.name) }
    var color by remember(role.id) { mutableStateOf(role.color) }
    var hoist by remember(role.id) { mutableStateOf(role.hoist) }
    var mentionable by remember(role.id) { mutableStateOf(role.mentionable) }
    var perms by remember(role.id) { mutableStateOf(role.permissions.toPermissionBits()) }
    var confirmDelete by remember { mutableStateOf(false) }

    val original = role.permissions.toPermissionBits()
    // Bits the actor may flip. Anything else renders disabled rather than
    // failing at save time with a 403.
    val allowed = Hierarchy.togglableBits(detail, selfId)

    val dirty = name.trim() != role.name || color != role.color || hoist != role.hoist ||
        mentionable != role.mentionable || perms != original

    Column(modifier = modifier.fillMaxSize().background(c.surface2)) {
        Header(title = if (isEveryone) AppStrings.get(context, R.string.catalog_everyone_930575f5) else AppStrings.get(context, R.string.catalog_edit_role_61dd63e9), onBack = onBack)

        Column(
            modifier = Modifier.padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            if (!isEveryone) {
                Section("Display") {
                    OrangTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = AppStrings.get(context, R.string.catalog_role_name_4204d818),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text("Colour", color = c.inkMuted, fontSize = 12.sp)
                    ColorSwatches(selected = color, onSelect = { color = it })
                    ToggleRow(
                        label = AppStrings.get(context, R.string.catalog_display_separately_ecf9d1a7),
                        hint = AppStrings.get(context, R.string.catalog_show_members_with_this_role_in_their_f0fb058c),
                        checked = hoist,
                        onCheckedChange = { hoist = it },
                    )
                    ToggleRow(
                        label = AppStrings.get(context, R.string.catalog_allow_anyone_to_mention_c6fe8761),
                        hint = AppStrings.get(context, R.string.catalog_otherwise_only_members_with_mention_everyone_can_a0476dcf),
                        checked = mentionable,
                        onCheckedChange = { mentionable = it },
                    )
                }
            } else {
                Text(
                    AppStrings.get(context, R.string.catalog_everyone_applies_to_every_member_it_has_12e47659),
                    color = c.inkMuted,
                    fontSize = 12.sp,
                )
            }

            PERMISSION_GROUPS.forEach { group ->
                Section(group.title) {
                    group.permissions.forEach { info ->
                        val canToggle = allowed and info.bit != 0L
                        ToggleRow(
                            label = info.label,
                            hint = if (canToggle) info.description else AppStrings.get(context, R.string.catalog_you_do_not_have_this_permission_d4f08a3c),
                            checked = perms and info.bit != 0L,
                            enabled = canToggle,
                            onCheckedChange = { on ->
                                perms = if (on) perms or info.bit else perms and info.bit.inv()
                            },
                        )
                    }
                }
            }

            OrangButton(
                text = AppStrings.get(context, R.string.catalog_save_changes_179359b3),
                onClick = {
                    onSave(
                        UpdateRoleRequest(
                            name = if (isEveryone) null else name.trim(),
                            color = if (isEveryone) null else color,
                            permissions = perms.toString(),
                            hoist = if (isEveryone) null else hoist,
                            mentionable = if (isEveryone) null else mentionable,
                        ),
                    )
                },
                enabled = dirty && (isEveryone || name.isNotBlank()),
                modifier = Modifier.fillMaxWidth(),
            )

            if (!isEveryone) {
                Section(AppStrings.get(context, R.string.catalog_danger_zone_963a652e)) {
                    OrangButton(
                        text = AppStrings.get(context, R.string.catalog_delete_role_fbf0667e),
                        onClick = { confirmDelete = true },
                        variant = ButtonVariant.Danger,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (confirmDelete) {
        ConfirmDialog(
            onDismiss = { confirmDelete = false },
            onConfirm = { confirmDelete = false; onDelete() },
            title = "Delete ${role.name}?",
            message = "Members keep their other roles. This cannot be undone.",
            confirmText = "Delete",
            destructive = true,
        )
    }
}

@Composable
private fun ColorSwatches(selected: Int, onSelect: (Int) -> Unit) {
    val c = OrangTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ROLE_COLORS.chunked(6).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { value ->
                    val isSelected = value == selected
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .background(Color(0xFF000000L.toInt() or value), CircleShape)
                            .then(
                                if (isSelected) {
                                    Modifier.border(2.dp, c.ink, CircleShape)
                                } else {
                                    Modifier
                                },
                            )
                            .clickable { onSelect(value) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    hint: String? = null,
    enabled: Boolean = true,
) {
    val c = OrangTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(c.surface3, RoundedCornerShape(OrangRadius.lg))
            .then(if (enabled) Modifier.clickable { onCheckedChange(!checked) } else Modifier)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, color = if (enabled) c.ink else c.inkMuted, fontSize = 14.sp)
            if (hint != null) Text(hint, color = c.inkMuted, fontSize = 12.sp)
        }
        Spacer(Modifier.width(12.dp))
        androidx.compose.material3.Switch(
            checked = checked,
            onCheckedChange = if (enabled) onCheckedChange else null,
            enabled = enabled,
            colors = androidx.compose.material3.SwitchDefaults.colors(
                checkedThumbColor = c.inkOnPrimary,
                checkedTrackColor = c.primary,
                uncheckedThumbColor = c.inkMuted,
                uncheckedTrackColor = c.surface4,
                uncheckedBorderColor = c.border,
            ),
        )
    }
}

@Composable
internal fun Header(title: String, onBack: () -> Unit) {
    val c = OrangTheme.colors
    Column {
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
            Text(title, color = c.ink, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        }
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(c.border))
    }
}

@Composable
internal fun Section(title: String, content: @Composable () -> Unit) {
    val c = OrangTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title.uppercase(), color = c.inkMuted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        content()
    }
}
