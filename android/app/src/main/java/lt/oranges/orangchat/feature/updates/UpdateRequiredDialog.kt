package lt.oranges.orangchat.feature.updates
import lt.oranges.orangchat.util.AppStrings
import androidx.compose.ui.platform.LocalContext
import lt.oranges.orangchat.R
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
        val context = LocalContext.current
    val c = OrangTheme.colors
    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
        ),
        title = { Text(AppStrings.get(context, R.string.catalog_update_required_f440d880), color = c.ink) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    AppStrings.get(context, R.string.catalog_this_version_of_orangchat_is_no_longer_91b823d1) +
                        (latestVersion?.let { " Update to $it to continue." } ?: AppStrings.get(context, R.string.catalog_update_to_continue_27e65968)),
                    color = c.inkSecondary,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onUpdate) { Text("Update", color = c.primary) }
        },
    )
}
