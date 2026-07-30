package lt.oranges.orangchat.feature.updates

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.DialogProperties
import lt.oranges.orangchat.ui.components.Text
import lt.oranges.orangchat.ui.theme.OrangTheme

/**
 * Shown when the server has stopped accepting this build entirely.
 *
 * Unlike [UpdateAvailableDialog] this has no "Later": the app behind it cannot
 * load anything, so dismissing would only reveal a screen of failures. Back and
 * outside-taps are refused for the same reason - the dialog is the explanation
 * for a state the user is already in, not a request for permission.
 */
@Composable
fun UpdateRequiredDialog(
    latestVersion: String?,
    onUpdate: () -> Unit,
) {
    val c = OrangTheme.colors
    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
        ),
        title = { Text("Update required", color = c.ink) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    "This version of OrangChat is no longer supported and can no longer connect." +
                        (latestVersion?.let { " Update to $it to continue." } ?: " Update to continue."),
                    color = c.inkSecondary,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onUpdate) { Text("Update", color = c.primary) }
        },
    )
}
