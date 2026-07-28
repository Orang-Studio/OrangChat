package lt.oranges.orangchat.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import lt.oranges.orangchat.ui.components.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import lt.oranges.orangchat.ui.theme.OrangRadius
import lt.oranges.orangchat.ui.theme.OrangTheme

/**
 * Port of components/ui/TextField.tsx - label above, bordered surface-1 input,
 * optional trailing slot, error/hint line below. Corner = rounded-lg.
 */
@Composable
fun OrangTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    error: String? = null,
    hint: String? = null,
    singleLine: Boolean = true,
    enabled: Boolean = true,
    isPassword: Boolean = false,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    trailing: (@Composable () -> Unit)? = null,
) {
    val c = OrangTheme.colors
    val shape = RoundedCornerShape(OrangRadius.lg)
    val borderColor = if (error != null) c.danger else c.border

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = label,
            color = c.inkSecondary,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 44.dp)
                .background(c.surface1, shape)
                .border(1.dp, borderColor, shape)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.weight(1f)) {
                    if (value.isEmpty() && placeholder != null) {
                        Text(placeholder, color = c.inkMuted, fontSize = 15.sp)
                    }
                    BasicTextField(
                        value = value,
                        onValueChange = onValueChange,
                        enabled = enabled,
                        singleLine = singleLine,
                        textStyle = androidx.compose.ui.text.TextStyle(
                            color = c.ink,
                            fontSize = 15.sp,
                        ),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(c.primary),
                        keyboardOptions = keyboardOptions,
                        visualTransformation = if (isPassword)
                            PasswordVisualTransformation() else VisualTransformation.None,
                        interactionSource = remember { MutableInteractionSource() },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (trailing != null) {
                    Box(modifier = Modifier.padding(start = 6.dp)) { trailing() }
                }
            }
        }
        when {
            error != null -> Text(error, color = c.danger, fontSize = 13.sp)
            hint != null -> Text(hint, color = c.inkMuted, fontSize = 12.sp)
        }
    }
}
