package lt.oranges.orangchat.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import lt.oranges.orangchat.ui.components.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import lt.oranges.orangchat.ui.theme.OrangRadius
import lt.oranges.orangchat.ui.theme.OrangTheme

/** A single item spec for [OrangDropdownMenu]. */
data class MenuItem(
    val label: String,
    val icon: ImageVector? = null,
    val destructive: Boolean = false,
    val enabled: Boolean = true,
    val onClick: () -> Unit,
)

/**
 * Port of components/ui/DropdownMenu.tsx - a surface-3 popover with bordered
 * corners; destructive items render in the danger color.
 */
@Composable
fun OrangDropdownMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    items: List<MenuItem>,
    modifier: Modifier = Modifier,
) {
    val c = OrangTheme.colors
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        modifier = modifier
            .background(c.surface3, RoundedCornerShape(OrangRadius.xl))
            .border(1.dp, c.border, RoundedCornerShape(OrangRadius.xl)),
    ) {
        items.forEach { item ->
            val base = if (item.destructive) c.danger else c.ink
            val tint = if (item.enabled) base else base.copy(alpha = 0.4f)
            DropdownMenuItem(
                text = { Text(item.label, color = tint, fontSize = 14.sp) },
                enabled = item.enabled,
                leadingIcon = item.icon?.let {
                    {
                        Icon(it, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
                    }
                },
                onClick = {
                    onDismiss()
                    item.onClick()
                },
            )
        }
    }
}
