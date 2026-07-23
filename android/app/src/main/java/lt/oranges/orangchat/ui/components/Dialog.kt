package lt.oranges.orangchat.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import lt.oranges.orangchat.ui.components.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import lt.oranges.orangchat.ui.theme.OrangRadius
import lt.oranges.orangchat.ui.theme.OrangTheme

/**
 * Port of components/ui/Dialog.tsx — centered surface-2 card with a title bar,
 * close affordance, and rounded-2xl corners over a dimmed scrim.
 */
@Composable
fun OrangDialog(
    onDismiss: () -> Unit,
    title: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    content: @Composable () -> Unit,
) {
    val c = OrangTheme.colors
    val closeInteraction = androidx.compose.runtime.remember { MutableInteractionSource() }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = modifier
                    .fillMaxWidth()
                    .background(c.surface2, RoundedCornerShape(OrangRadius.xl2))
                    .border(1.dp, c.border, RoundedCornerShape(OrangRadius.xl2))
                    .padding(20.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = title,
                        color = c.ink,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                        tint = c.inkMuted,
                        modifier = Modifier
                            .clickable(
                                interactionSource = closeInteraction,
                                indication = null,
                                onClick = onDismiss,
                            ),
                    )
                }
                if (description != null) {
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = description,
                        color = c.inkSecondary,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                Box(modifier = Modifier.padding(top = 16.dp)) { content() }
            }
        }
    }
}

/** Port of components/ui/ConfirmDialog.tsx. */
@Composable
fun ConfirmDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    title: String,
    message: String,
    confirmText: String = "Confirm",
    cancelText: String = "Cancel",
    destructive: Boolean = false,
    loading: Boolean = false,
) {
    val c = OrangTheme.colors
    OrangDialog(onDismiss = onDismiss, title = title) {
        Column {
            Text(message, color = c.inkSecondary, fontSize = 14.sp)
            Spacer(Modifier.padding(top = 20.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                OrangButton(cancelText, onClick = onDismiss, variant = ButtonVariant.Secondary)
                OrangButton(
                    confirmText,
                    onClick = onConfirm,
                    variant = if (destructive) ButtonVariant.Danger else ButtonVariant.Primary,
                    loading = loading,
                )
            }
        }
    }
}
