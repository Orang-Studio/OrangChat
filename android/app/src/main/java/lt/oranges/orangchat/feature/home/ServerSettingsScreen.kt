package lt.oranges.orangchat.feature.home

import lt.oranges.orangchat.R
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import lt.oranges.orangchat.data.model.Hierarchy
import lt.oranges.orangchat.data.model.Permissions
import lt.oranges.orangchat.data.model.ServerDetail
import lt.oranges.orangchat.data.model.hasPermission
import lt.oranges.orangchat.util.AppStrings
import lt.oranges.orangchat.util.InviteLink
import lt.oranges.orangchat.ui.components.ButtonVariant
import lt.oranges.orangchat.ui.components.ConfirmDialog
import lt.oranges.orangchat.ui.components.ImageField
import lt.oranges.orangchat.ui.components.OrangButton
import lt.oranges.orangchat.ui.components.OrangTextField
import lt.oranges.orangchat.ui.theme.OrangRadius
import lt.oranges.orangchat.ui.theme.OrangTheme

@Composable
fun ServerSettingsScreen(
    detail: ServerDetail,
    selfId: String,
    onBack: () -> Unit,
    onRename: (String) -> Unit,
    onSaveDescription: (String) -> Unit,
    iconUploading: Boolean,
    onUploadIcon: (android.net.Uri) -> Unit,
    onRemoveIcon: () -> Unit,
    onOpenRoles: () -> Unit,
    onOpenMembers: () -> Unit,
    onOpenAuditLog: () -> Unit,
    onCreateInvite: ((String) -> Unit) -> Unit,
    onLeave: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = OrangTheme.colors
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val isOwner = detail.server.ownerId == selfId
    val myPerms = Hierarchy.effectivePermissions(detail, selfId)

    var name by remember(detail.server.id) { mutableStateOf(detail.server.name) }
    var description by remember(detail.server.id) {
        mutableStateOf(detail.server.description.orEmpty())
    }
    var inviteCode by remember(detail.server.id) { mutableStateOf<String?>(null) }
    val iconPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri -> uri?.let(onUploadIcon) }
    var confirmLeave by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

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
            Text(AppStrings.get(context, R.string.catalog_server_settings_28af3cc7), color = c.ink, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        }
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(c.border))

        Column(
            modifier = Modifier.padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Section("Overview") {
                OrangTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = AppStrings.get(context, R.string.catalog_server_name_738825fc),
                    enabled = isOwner,
                    hint = if (isOwner) null else AppStrings.get(context, R.string.catalog_only_the_owner_can_rename_this_server_3db192cb),
                    modifier = Modifier.fillMaxWidth(),
                )
                OrangTextField(
                    value = description,
                    onValueChange = { if (it.length <= 1024) description = it },
                    label = "Description",
                    enabled = isOwner,
                    hint = AppStrings.get(context, R.string.catalog_shown_on_the_invite_page_28e6606d),
                    modifier = Modifier.fillMaxWidth(),
                )
                ImageField(
                    label = AppStrings.get(context, R.string.catalog_server_icon_8bf2f95a),
                    url = detail.server.iconUrl,
                    height = 72.dp,
                    square = true,
                    busy = iconUploading,
                    enabled = isOwner,
                    onPick = {
                        iconPicker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                        )
                    },
                    onRemove = onRemoveIcon,
                )
                if (isOwner) {
                    OrangButton(
                        text = "Save",
                        onClick = {
                            if (name.trim() != detail.server.name) onRename(name.trim())
                            if (description.trim() != detail.server.description.orEmpty()) {
                                onSaveDescription(description.trim())
                            }
                        },
                        enabled = name.isNotBlank() && (
                            name.trim() != detail.server.name ||
                                description.trim() != detail.server.description.orEmpty()
                            ),
                    )
                }
            }

            Section(AppStrings.get(context, R.string.catalog_invite_people_e1eb97af)) {
                inviteCode?.let { code ->
                    val link = InviteLink.urlFor(code)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(c.surface3, RoundedCornerShape(OrangRadius.lg))
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = link,
                            color = c.ink,
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = "Copy",
                            color = c.primary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier
                                .clickable { clipboard.setText(AnnotatedString(link)) }
                                .padding(horizontal = 8.dp),
                        )
                        Text(
                            text = "Share",
                            color = c.primary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.clickable {
                                val send = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, link)
                                }
                                context.startActivity(Intent.createChooser(send, "Share invite"))
                            },
                        )
                    }
                }
                OrangButton(
                    text = if (inviteCode == null) AppStrings.get(context, R.string.catalog_create_invite_a8404622) else AppStrings.get(context, R.string.catalog_create_another_8b69f57c),
                    onClick = { onCreateInvite { code -> inviteCode = code } },
                    variant = ButtonVariant.Secondary,
                )
            }

            Section("People") {
                NavRow(
                    label = "Members",
                    value = "${detail.members.size}",
                    onClick = onOpenMembers,
                )
                if (myPerms.hasPermission(Permissions.MANAGE_ROLES)) {
                    NavRow(
                        label = "Roles",
                        value = "${detail.roles.size}",
                        onClick = onOpenRoles,
                    )
                }
                if (myPerms.hasPermission(Permissions.VIEW_AUDIT_LOG)) {
                    NavRow(label = AppStrings.get(context, R.string.catalog_audit_log_3cfc5f1c), value = "", onClick = onOpenAuditLog)
                }
            }

            Section(AppStrings.get(context, R.string.catalog_danger_zone_963a652e)) {
                if (isOwner) {
                    Text(
                        text = AppStrings.get(context, R.string.catalog_as_the_owner_you_cannot_leave_delete_030b7d7e),
                        color = c.inkMuted,
                        fontSize = 12.sp,
                    )
                    OrangButton(
                        text = AppStrings.get(context, R.string.catalog_delete_server_fb695fc6),
                        onClick = { confirmDelete = true },
                        variant = ButtonVariant.Danger,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    OrangButton(
                        text = AppStrings.get(context, R.string.catalog_leave_server_da6fea13),
                        onClick = { confirmLeave = true },
                        variant = ButtonVariant.Danger,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }

    if (confirmLeave) {
        ConfirmDialog(
            onDismiss = { confirmLeave = false },
            onConfirm = { confirmLeave = false; onLeave() },
            title = "Leave ${detail.server.name}?",
            message = "You will stop receiving its messages. You can rejoin with an invite.",
            confirmText = "Leave",
            destructive = true,
        )
    }
    if (confirmDelete) {
        ConfirmDialog(
            onDismiss = { confirmDelete = false },
            onConfirm = { confirmDelete = false; onDelete() },
            title = "Delete ${detail.server.name}?",
            message = "This removes the server, its channels and every message in them. It cannot be undone.",
            confirmText = "Delete",
            destructive = true,
        )
    }
}

@Composable
private fun NavRow(label: String, value: String, onClick: () -> Unit) {
    val c = OrangTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(c.surface3, RoundedCornerShape(OrangRadius.lg))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = c.ink, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Text(value, color = c.inkMuted, fontSize = 13.sp)
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    val c = OrangTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title.uppercase(), color = c.inkMuted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        content()
    }
}
