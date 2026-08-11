package lt.oranges.orangchat.feature.verify
import lt.oranges.orangchat.util.AppStrings
import androidx.compose.ui.platform.LocalContext
import lt.oranges.orangchat.R
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import lt.oranges.orangchat.feature.home.AppViewModel

@Composable
fun VerifyContactDialog(raw: String, appViewModel: AppViewModel) {
        val context = LocalContext.current
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var done by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!busy) appViewModel.clearPendingVerify() },
        title = { Text(if (done) "Verified" else AppStrings.get(context, R.string.catalog_verify_this_contact_ee09ef5c)) },
        text = {
            Column {
                Text(
                    if (done) {
                        "You have verified them on this device. They still need to scan your code to verify you - open your own code and have them point their camera at it."
                    } else {
                        "You scanned someone's OrangChat code. Confirming checks it against the identity this server hands over for that account, and pins what you actually saw."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                error?.let {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                if (busy) {
                    CircularProgressIndicator(modifier = Modifier.padding(top = 12.dp))
                }
            }
        },
        confirmButton = {
            if (done) {
                TextButton(onClick = { appViewModel.clearPendingVerify() }) { Text("Done") }
            } else {
                TextButton(
                    enabled = !busy,
                    onClick = {
                        busy = true
                        error = null
                        appViewModel.verifyScannedContact(raw) { ok, message ->
                            busy = false
                            if (ok) done = true else error = message
                        }
                    },
                ) { Text("Verify") }
            }
        },
        dismissButton = {
            if (!done) {
                TextButton(
                    enabled = !busy,
                    onClick = { appViewModel.clearPendingVerify() },
                ) { Text(AppStrings.get(context, R.string.catalog_not_now_e4571490)) }
            }
        },
    )
}
