package lt.oranges.orangchat.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import lt.oranges.orangchat.data.model.Channel
import lt.oranges.orangchat.data.model.ChannelType
import lt.oranges.orangchat.data.remote.PatchChannelRequest
import lt.oranges.orangchat.feature.settings.SettingsToggleRow
import lt.oranges.orangchat.ui.components.ButtonVariant
import lt.oranges.orangchat.ui.components.OrangButton
import lt.oranges.orangchat.ui.components.OrangTextField
import lt.oranges.orangchat.ui.components.Text
import lt.oranges.orangchat.ui.theme.OrangTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChannelSettingsSheet(
    channel: Channel,
    onDismiss: () -> Unit,
    onSave: (PatchChannelRequest) -> Unit,
) {
    val c = OrangTheme.colors
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val voice = channel.type == ChannelType.VOICE

    var name by remember(channel.id) { mutableStateOf(channel.name.orEmpty()) }
    var topic by remember(channel.id) { mutableStateOf(channel.topic.orEmpty()) }
    var nsfw by remember(channel.id) { mutableStateOf(channel.nsfw) }
    var slowMode by remember(channel.id) { mutableStateOf(channel.rateLimitPerUser.toString()) }
    var userLimit by remember(channel.id) { mutableStateOf(channel.userLimit.toString()) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = c.surface3,
        contentColor = c.ink,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Edit Channel", color = c.ink, fontWeight = FontWeight.Bold, fontSize = 18.sp)

            OrangTextField(
                value = name,
                onValueChange = { name = it },
                label = "Name",
                modifier = Modifier.fillMaxWidth(),
            )

            if (voice) {
                OrangTextField(
                    value = userLimit,
                    onValueChange = { value -> userLimit = value.filter { it.isDigit() } },
                    label = "User limit",
                    hint = "0 for no limit",
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                OrangTextField(
                    value = topic,
                    onValueChange = { topic = it },
                    label = "Topic",
                    placeholder = "What is this channel about?",
                    singleLine = false,
                    modifier = Modifier.fillMaxWidth(),
                )
                OrangTextField(
                    value = slowMode,
                    onValueChange = { value -> slowMode = value.filter { it.isDigit() } },
                    label = "Slow mode",
                    hint = "Seconds between messages, 0 to turn it off",
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                SettingsToggleRow(
                    label = "Age-restricted",
                    checked = nsfw,
                    onCheckedChange = { nsfw = it },
                    hint = "Members confirm their age before opening this channel.",
                )
            }

            OrangButton(
                text = "Save",
                enabled = name.isNotBlank(),
                onClick = {
                    onSave(
                        PatchChannelRequest(
                            name = name.trim().takeIf { it != channel.name },
                            topic = if (voice) null else topic.trim().takeIf { it != channel.topic.orEmpty() },
                            nsfw = if (voice) null else nsfw.takeIf { it != channel.nsfw },
                            rateLimitPerUser = if (voice) {
                                null
                            } else {
                                slowMode.toIntOrNull()?.takeIf { it != channel.rateLimitPerUser }
                            },
                            userLimit = if (voice) {
                                userLimit.toIntOrNull()?.takeIf { it != channel.userLimit }
                            } else {
                                null
                            },
                        ),
                    )
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth(),
            )
            OrangButton(
                text = "Cancel",
                variant = ButtonVariant.Ghost,
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
