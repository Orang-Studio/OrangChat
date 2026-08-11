package lt.oranges.orangchat.feature.updates
import lt.oranges.orangchat.util.AppStrings
import androidx.compose.ui.platform.LocalContext
import lt.oranges.orangchat.R
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import lt.oranges.orangchat.ui.components.Text
import lt.oranges.orangchat.ui.theme.OrangTheme

@Composable
fun UpdateAvailableDialog(
    manifest: UpdateManifest,
    onDismiss: () -> Unit,
    onUpdate: () -> Unit,
) {
        val context = LocalContext.current
    val c = OrangTheme.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(AppStrings.get(context, R.string.catalog_new_update_available_b0be29b0), color = c.ink) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
            ) {
                Text("OrangChat ${manifest.versionName}", color = c.inkSecondary)
                if (manifest.changelog.isNotBlank()) {
                    Text(
                        manifest.changelog.trim(),
                        color = c.inkSecondary,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Later", color = c.inkSecondary) }
        },
        confirmButton = {
            TextButton(onClick = onUpdate) { Text(AppStrings.get(context, R.string.catalog_update_now_c4cbac00), color = c.primary) }
        },
    )
}
