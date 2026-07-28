package lt.oranges.orangchat.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import lt.oranges.orangchat.data.model.SelfUser
import lt.oranges.orangchat.feature.profile.ProfileViewModel
import lt.oranges.orangchat.ui.components.OrangTextField
import lt.oranges.orangchat.ui.components.Text
import lt.oranges.orangchat.ui.theme.OrangRadius
import lt.oranges.orangchat.ui.theme.OrangTheme

/**
 * The profile-theme marketplace: PR-reviewed profile-card CSS bundled with the
 * app. Installing points your profileCss at the theme; the card renderer
 * sanitizes and scopes it exactly as it does hand-written CSS. Mirrors the web
 * client's ProfileThemesTab.
 */
@Composable
fun ProfileThemesScreen(
    self: SelfUser,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    vm: ProfileViewModel = hiltViewModel(),
) {
    val c = OrangTheme.colors
    val state by vm.state.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }

    val filtered = remember(query) {
        val q = query.trim().lowercase()
        if (q.isBlank()) PROFILE_THEMES
        else PROFILE_THEMES.filter {
            it.name.lowercase().contains(q) ||
                it.description.lowercase().contains(q) ||
                it.authors.any { a -> a.lowercase().contains(q) }
        }
    }

    Column(modifier = modifier.fillMaxSize().background(c.surface2)) {
        SettingsTopBar("Profile themes", onBack)

        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(OrangRadius.lg))
                    .background(c.surface1)
                    .padding(12.dp),
            ) {
                Text(
                    "Profile themes style your public profile card. Every entry is reviewed through a pull request, bundled with the app, and sanitized before it renders.",
                    color = c.inkSecondary,
                    fontSize = 12.sp,
                )
            }
            Spacer(Modifier.height(12.dp))
            OrangTextField(
                value = query,
                onValueChange = { query = it },
                label = "Search",
                placeholder = "Search profile themes",
            )
            state.error?.let { err ->
                Spacer(Modifier.height(8.dp))
                Text(err, color = c.danger, fontSize = 13.sp)
            }
        }

        Spacer(Modifier.height(12.dp))

        if (filtered.isEmpty()) {
            Text(
                "No profile themes match “$query”.",
                color = c.inkMuted,
                fontSize = 14.sp,
                modifier = Modifier.fillMaxWidth().padding(32.dp),
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(filtered, key = { it.id }) { theme ->
                    ThemeCard(
                        theme = theme,
                        active = self.profileCss == theme.css,
                        saving = state.saving,
                        // "" clears the field; explicitNulls=false would drop a
                        // Kotlin null, so an empty string is how a theme comes off.
                        onToggle = { active -> vm.save(profileCss = if (active) "" else theme.css) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ThemeCard(
    theme: ProfileTheme,
    active: Boolean,
    saving: Boolean,
    onToggle: (active: Boolean) -> Unit,
) {
    val c = OrangTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(OrangRadius.lg))
            .background(c.surface1)
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(theme.name, color = c.ink, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                Text("by ${theme.authors.joinToString(", ")}", color = c.inkMuted, fontSize = 12.sp)
            }
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(OrangRadius.lg))
                    .background(if (active) c.surface3 else c.primary)
                    .then(
                        if (saving) Modifier
                        else Modifier.clickable(onClick = { onToggle(active) }),
                    )
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    if (active) Icons.Default.Check else Icons.Default.Download,
                    contentDescription = null,
                    tint = if (active) c.ink else c.inkOnPrimary,
                    modifier = Modifier.width(16.dp).height(16.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    if (active) "Installed" else "Install",
                    color = if (active) c.ink else c.inkOnPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(theme.description, color = c.inkSecondary, fontSize = 13.sp)
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(OrangRadius.md))
                .background(c.surface2)
                .horizontalScroll(rememberScrollState())
                .padding(8.dp),
        ) {
            Text(
                theme.css,
                color = c.inkMuted,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}
