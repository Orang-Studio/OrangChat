package lt.oranges.orangchat.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import lt.oranges.orangchat.ui.components.ButtonVariant
import lt.oranges.orangchat.ui.components.OrangButton
import lt.oranges.orangchat.ui.components.OrangDialog
import lt.oranges.orangchat.ui.components.OrangTextField

@Composable
fun CreateEntityDialog(
    title: String,
    label: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    OrangDialog(onDismiss = onDismiss, title = title) {
        Column {
            OrangTextField(value = name, onValueChange = { name = it }, label = label)
            Spacer(Modifier.height(20.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                OrangButton(text = "Cancel", onClick = onDismiss, variant = ButtonVariant.Secondary)
                OrangButton(
                    text = "Create",
                    onClick = { if (name.isNotBlank()) onConfirm(name.trim()) },
                    enabled = name.isNotBlank(),
                )
            }
        }
    }
}
