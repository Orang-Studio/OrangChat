package lt.oranges.orangchat.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import lt.oranges.orangchat.ui.components.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import lt.oranges.orangchat.R
import lt.oranges.orangchat.ui.theme.OrangRadius
import lt.oranges.orangchat.ui.theme.OrangTheme
import lt.oranges.orangchat.util.AppStrings

@Composable
fun SettingsTopBar(title: String, onBack: () -> Unit) {
    val c = OrangTheme.colors
    val context = LocalContext.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = AppStrings.get(context, R.string.catalog_back_b52b36b7),
            tint = c.inkSecondary,
            modifier = Modifier.clickable(onClick = onBack).padding(4.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text(title, color = c.ink, fontWeight = FontWeight.Bold, fontSize = 18.sp)
    }
}

@Composable
fun SettingSection(title: String, content: @Composable () -> Unit) {
    val c = OrangTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title.uppercase(), color = c.inkMuted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        content()
    }
}

@Composable
fun SettingsToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    hint: String? = null,
) {
    val c = OrangTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(c.surface3, RoundedCornerShape(OrangRadius.lg))
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, color = c.ink, fontSize = 14.sp)
            if (hint != null) {
                Text(hint, color = c.inkMuted, fontSize = 12.sp)
            }
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = c.inkOnPrimary,
                checkedTrackColor = c.primary,
                uncheckedThumbColor = c.inkMuted,
                uncheckedTrackColor = c.surface4,
                uncheckedBorderColor = c.border,
            ),
        )
    }
}

@Composable
fun <T> SettingsChoiceRow(
    value: T,
    options: List<Triple<T, String, String>>,
    onSelect: (T) -> Unit,
) {
    val c = OrangTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (option, label, hint) ->
            val selected = option == value
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(if (selected) c.primarySoft else c.surface3, RoundedCornerShape(OrangRadius.lg))
                    .clickable { onSelect(option) }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(label, color = c.ink, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Text(hint, color = c.inkMuted, fontSize = 12.sp)
                }
                if (selected) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = null,
                        tint = c.primary,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

@Composable
fun SettingsNavRow(
    label: String,
    subtitle: String? = null,
    onClick: () -> Unit,
    trailing: String? = null,
) {
    val c = OrangTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(c.surface3, RoundedCornerShape(OrangRadius.lg))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, color = c.ink, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            if (subtitle != null) {
                Text(subtitle, color = c.inkMuted, fontSize = 12.sp)
            }
        }
        if (trailing != null) {
            Text(trailing, color = c.inkMuted, fontSize = 13.sp)
        }
        Spacer(Modifier.width(8.dp))
        Text("›", color = c.inkMuted, fontSize = 20.sp)
    }
}
