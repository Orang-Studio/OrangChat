package lt.oranges.orangchat.feature.qrlogin
import lt.oranges.orangchat.util.AppStrings
import androidx.compose.ui.platform.LocalContext
import lt.oranges.orangchat.R
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import lt.oranges.orangchat.feature.home.AppViewModel
import lt.oranges.orangchat.ui.components.ButtonVariant
import lt.oranges.orangchat.ui.components.OrangButton
import lt.oranges.orangchat.ui.components.OrangDialog
import lt.oranges.orangchat.ui.components.Text
import lt.oranges.orangchat.ui.theme.OrangTheme

@Composable
fun QrLoginConfirmDialog(
    token: String,
    appViewModel: AppViewModel = hiltViewModel(),
) {
        val context = LocalContext.current
    val c = OrangTheme.colors
    val busy by appViewModel.qrApproving.collectAsStateWithLifecycle()
    val error by appViewModel.qrError.collectAsStateWithLifecycle()

    OrangDialog(
        onDismiss = { if (!busy) appViewModel.clearPendingQrLogin() },
        title = AppStrings.get(context, R.string.catalog_sign_in_on_the_web_94061c18),
        description = AppStrings.get(context, R.string.catalog_someone_scanned_this_account_s_code_to_cd6f3892),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            error?.let {
                Text(it, color = c.danger, fontSize = 13.sp, textAlign = TextAlign.Center)
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OrangButton(
                    text = AppStrings.get(context, R.string.catalog_not_me_1ad9a1eb),
                    onClick = { appViewModel.clearPendingQrLogin() },
                    variant = ButtonVariant.Ghost,
                    modifier = Modifier.weight(1f),
                    enabled = !busy,
                )
                OrangButton(
                    text = "Approve",
                    onClick = {
                        appViewModel.approveQrLogin(token) { appViewModel.clearPendingQrLogin() }
                    },
                    modifier = Modifier.weight(1f),
                    loading = busy,
                )
            }
        }
    }
}
