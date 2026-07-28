package lt.oranges.orangchat.feature.invite

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import lt.oranges.orangchat.ui.components.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import lt.oranges.orangchat.data.model.Server
import lt.oranges.orangchat.ui.components.ButtonVariant
import lt.oranges.orangchat.ui.components.OrangButton
import lt.oranges.orangchat.ui.components.OrangDialog
import lt.oranges.orangchat.ui.components.OrangTabs
import lt.oranges.orangchat.ui.components.OrangTextField
import lt.oranges.orangchat.ui.theme.OrangTheme

private val TABS = listOf("Create", "Join")

/**
 * Create a server, or join one from an invite link - the two things the rail's
 * plus button can mean. Joining previews the server first, so nobody accepts a
 * link without seeing where it goes.
 */
@Composable
fun AddServerDialog(
    onDismiss: () -> Unit,
    onCreate: (String) -> Unit,
    onJoined: (Server) -> Unit,
) {
    var tab by remember { mutableStateOf(0) }

    OrangDialog(onDismiss = onDismiss, title = if (tab == 0) "Create a server" else "Join a server") {
        Column {
            OrangTabs(tabs = TABS, selectedIndex = tab, onSelect = { tab = it })
            Spacer(Modifier.height(16.dp))
            if (tab == 0) {
                CreateServerForm(onDismiss = onDismiss, onCreate = onCreate)
            } else {
                JoinServerForm(onDismiss = onDismiss, onJoined = onJoined)
            }
        }
    }
}

@Composable
private fun CreateServerForm(onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    Column {
        OrangTextField(
            value = name,
            onValueChange = { name = it },
            label = "Server name",
            placeholder = "My orange grove",
        )
        Spacer(Modifier.height(20.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        ) {
            OrangButton(text = "Cancel", onClick = onDismiss, variant = ButtonVariant.Secondary)
            OrangButton(
                text = "Create",
                onClick = { if (name.isNotBlank()) onCreate(name.trim()) },
                enabled = name.isNotBlank(),
            )
        }
    }
}

/**
 * Paste a link, see the server, join it. The preview resolves as you type, so
 * the Join button only ever appears once there is something real to join.
 */
@Composable
private fun JoinServerForm(onDismiss: () -> Unit, onJoined: (Server) -> Unit) {
    val vm: InviteViewModel = hiltViewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    var input by remember { mutableStateOf("") }
    val c = OrangTheme.colors

    LaunchedEffect(input) { vm.onInputChanged(input) }

    Column {
        OrangTextField(
            value = input,
            onValueChange = { input = it },
            label = "Invite link",
            placeholder = "https://chat.oranges.lt/invite/dQw4w9Wg",
        )

        when {
            state.resolving -> Hint("Resolving invite…")
            state.invalid -> Hint("That invite is expired, revoked, or never existed.")
            state.preview != null -> {
                Spacer(Modifier.height(12.dp))
                InviteCard(preview = state.preview!!)
            }
        }
        state.error?.let {
            Text(it, color = c.danger, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
        }

        Spacer(Modifier.height(20.dp))
        val preview = state.preview
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        ) {
            OrangButton(text = "Cancel", onClick = onDismiss, variant = ButtonVariant.Secondary)
            OrangButton(
                text = if (preview?.isMember == true) "Open" else "Join",
                onClick = {
                    // Already a member: the join is a no-op the server would
                    // happily accept, but going straight there is the honest
                    // thing to do.
                    if (preview?.isMember == true) onJoined(preview.server) else vm.join(onJoined)
                },
                enabled = preview != null && preview.blockedReason == null,
                loading = state.joining,
            )
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(
        text = text,
        color = OrangTheme.colors.inkMuted,
        fontSize = 12.sp,
        modifier = Modifier.padding(top = 8.dp),
    )
}

/**
 * The sheet a tapped invite link raises: same join flow, but the code is a given
 * rather than something to type, so the card is all there is to show.
 */
@Composable
fun DeepLinkInviteDialog(
    code: String,
    onDismiss: () -> Unit,
    onJoined: (Server) -> Unit,
) {
    val vm: InviteViewModel = hiltViewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    val c = OrangTheme.colors

    LaunchedEffect(code) { vm.resolve(code) }

    OrangDialog(onDismiss = onDismiss, title = "Join a server") {
        Column {
            when {
                state.resolving -> Hint("Resolving invite…")
                state.invalid -> Text(
                    text = "That invite is expired, revoked, or never existed.",
                    color = c.inkSecondary,
                    fontSize = 13.sp,
                )
                state.preview != null -> InviteCard(preview = state.preview!!)
            }
            state.error?.let {
                Text(it, color = c.danger, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
            }

            Spacer(Modifier.height(20.dp))
            val preview = state.preview
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                OrangButton(
                    text = if (state.invalid) "Close" else "Not now",
                    onClick = onDismiss,
                    variant = ButtonVariant.Secondary,
                )
                if (!state.invalid) {
                    OrangButton(
                        text = if (preview?.isMember == true) "Open" else "Join",
                        onClick = {
                            if (preview?.isMember == true) onJoined(preview.server)
                            else vm.join(onJoined)
                        },
                        enabled = preview != null && preview.blockedReason == null,
                        loading = state.joining,
                    )
                }
            }
        }
    }
}
