package lt.oranges.orangchat.feature.updates

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

/** Startup-only prompt: the existing About screen remains the manual fallback. */
@Composable
fun UpdateAvailableDialog(
    manifest: UpdateManifest,
    onDismiss: () -> Unit,
    onUpdate: () -> Unit,
) {
    val c = OrangTheme.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New update available", color = c.ink) },
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
            TextButton(onClick = onUpdate) { Text("Update now", color = c.primary) }
        },
    )
}
