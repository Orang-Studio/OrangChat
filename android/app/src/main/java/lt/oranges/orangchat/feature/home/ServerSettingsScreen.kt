package lt.oranges.orangchat.feature.home

import android.content.Intent
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
import androidx.compose.material3.Text
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
import lt.oranges.orangchat.util.InviteLink
import lt.oranges.orangchat.ui.components.ButtonVariant
import lt.oranges.orangchat.ui.components.ConfirmDialog
import lt.oranges.orangchat.ui.components.OrangButton
import lt.oranges.orangchat.ui.components.OrangTextField
import lt.oranges.orangchat.ui.theme.OrangRadius
import lt.oranges.orangchat.ui.theme.OrangTheme

/**
 * Server management: rename, invite, leave, delete. Counterpart of the web
 * client's ServerSettingsDialog Overview tab.
 *
 * Owners see Delete and cannot leave (the backend refuses — a server without an
 * owner would be orphaned); everyone else sees Leave.
 */
@Composable
fun ServerSettingsScreen(
    detail: ServerDetail,
    selfId: String,
    onBack: () -> Unit,
    onRename: (String) -> Unit,
    onOpenRoles: () -> Unit,
    onOpenMembers: () -> Unit,
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
    var inviteCode by remember(detail.server.id) { mutableStateOf<String?>(null) }
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
            Text("Server settings", color = c.ink, fontWeight = FontWeight.Bold, fontSize = 18.sp)
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
                    label = "Server name",
                    enabled = isOwner,
                    hint = if (isOwner) null else "Only the owner can rename this server",
                    modifier = Modifier.fillMaxWidth(),
                )
                if (isOwner) {
                    OrangButton(
                        text = "Save",
                        onClick = { onRename(name.trim()) },
                        enabled = name.isNotBlank() && name.trim() != detail.server.name,
                    )
                }
            }

            Section("Invite people") {
                inviteCode?.let { code ->
                    // A link rather than the bare code: it unfurls into a join
                    // card when posted in chat, and opens straight into the app
                    // for anyone who has it installed.
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
                    text = if (inviteCode == null) "Create invite" else "Create another",
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
            }

            Section("Danger zone") {
                if (isOwner) {
                    Text(
                        text = "As the owner you cannot leave — delete the server instead.",
                        color = c.inkMuted,
                        fontSize = 12.sp,
                    )
                    OrangButton(
                        text = "Delete server",
                        onClick = { confirmDelete = true },
                        variant = ButtonVariant.Danger,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    OrangButton(
                        text = "Leave server",
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
