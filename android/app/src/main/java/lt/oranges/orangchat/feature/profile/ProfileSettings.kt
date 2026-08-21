package lt.oranges.orangchat.feature.profile

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import lt.oranges.orangchat.R
import lt.oranges.orangchat.data.model.SelfUser
import lt.oranges.orangchat.ui.components.ButtonVariant
import lt.oranges.orangchat.ui.components.OrangButton
import lt.oranges.orangchat.ui.components.OrangTabs
import lt.oranges.orangchat.ui.components.OrangTextField
import lt.oranges.orangchat.ui.components.Text
import lt.oranges.orangchat.ui.theme.OrangRadius
import lt.oranges.orangchat.ui.theme.OrangTheme
import lt.oranges.orangchat.util.AppStrings
import lt.oranges.orangchat.util.PROFILE_CSS_MAX_LEN
import lt.oranges.orangchat.util.resolveWidgetLayout

private val ACCENT_COLORS = listOf(
    0xFF6A1A, 0xE85D04, 0xF0554C, 0xE2AB35,
    0x3FBD6E, 0x4F9FF2, 0x7C6FF0, 0xC45BD6,
)

private const val PANE_IDENTITY = 0
private const val PANE_WIDGETS = 1
private const val PANE_THEME = 2

/**
 * Everything about your own profile, edited on the card itself: the banner and
 * the avatar are pickers where they are shown, and the panes below only hold
 * what cannot be tapped directly.
 */
@Composable
fun ProfileSettingsSection(
    self: SelfUser,
    modifier: Modifier = Modifier,
    vm: ProfileViewModel = hiltViewModel(),
    catalogVm: WidgetCatalogViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val c = OrangTheme.colors
    val state by vm.state.collectAsStateWithLifecycle()
    val catalog by catalogVm.state.collectAsStateWithLifecycle()

    var pane by remember { mutableStateOf(PANE_IDENTITY) }
    var displayName by remember(self.displayName) { mutableStateOf(self.displayName) }
    var username by remember(self.username) { mutableStateOf(self.username) }
    var pronouns by remember(self.pronouns) { mutableStateOf(self.pronouns.orEmpty()) }
    var bio by remember(self.bio) { mutableStateOf(self.bio.orEmpty()) }
    var accentColor by remember(self.accentColor) { mutableStateOf(self.accentColor) }
    var profileCss by remember(self.profileCss) { mutableStateOf(self.profileCss.orEmpty()) }
    var widgets by remember(self.profileWidgets) {
        mutableStateOf(resolveWidgetLayout(self.profileWidgets))
    }

    val avatarPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri -> uri?.let { vm.uploadImage(it, ImageKind.AVATAR) } }

    val bannerPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri -> uri?.let { vm.uploadImage(it, ImageKind.BANNER) } }

    val imageRequest = PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)

    val previewUser = self.asUser().copy(
        displayName = displayName.ifBlank { self.displayName },
        username = username.ifBlank { self.username },
        pronouns = pronouns.trim().ifBlank { null },
        bio = bio.trim().ifBlank { null },
        accentColor = accentColor,
        profileCss = profileCss.ifBlank { null },
        profileWidgets = widgets,
    )
    val baseline = resolveWidgetLayout(self.profileWidgets)
    val dirty = displayName.trim() != self.displayName ||
        username.trim() != self.username ||
        pronouns.trim().ifBlank { null } != self.pronouns ||
        bio.trim().ifBlank { null } != self.bio ||
        accentColor != self.accentColor ||
        profileCss.ifBlank { null } != self.profileCss ||
        widgets != baseline

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        ProfileCard(
            user = previewUser,
            presence = self.status,
            edit = if (pane == PANE_THEME) null else ProfileCardEdit(
                onPickAvatar = { avatarPicker.launch(imageRequest) },
                onPickBanner = { bannerPicker.launch(imageRequest) },
                busy = state.uploading,
            ),
        )

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = AppStrings.get(context, R.string.profile_preview_hint),
                color = c.inkMuted,
                fontSize = 12.sp,
                modifier = Modifier.weight(1f),
            )
            if (!self.avatarUrl.isNullOrBlank()) {
                Text(
                    text = AppStrings.get(context, R.string.profile_remove_avatar),
                    color = c.inkMuted,
                    fontSize = 12.sp,
                    modifier = Modifier.clickable { vm.removeImage(ImageKind.AVATAR) },
                )
            }
            if (!self.bannerUrl.isNullOrBlank()) {
                Text(
                    text = AppStrings.get(context, R.string.profile_remove_banner),
                    color = c.inkMuted,
                    fontSize = 12.sp,
                    modifier = Modifier.clickable { vm.removeImage(ImageKind.BANNER) },
                )
            }
        }

        OrangTabs(
            tabs = listOf(
                AppStrings.get(context, R.string.profile_pane_identity),
                AppStrings.get(context, R.string.profile_pane_widgets),
                AppStrings.get(context, R.string.profile_pane_theme),
            ),
            selectedIndex = pane,
            onSelect = { pane = it },
        )

        when (pane) {
            PANE_IDENTITY -> IdentityPane(
                displayName = displayName,
                onDisplayName = { displayName = it },
                username = username,
                onUsername = { username = it },
                pronouns = pronouns,
                onPronouns = { pronouns = it },
                bio = bio,
                onBio = { bio = it },
                accentColor = accentColor,
                onAccentColor = { accentColor = it },
            )

            PANE_WIDGETS -> Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                WidgetEditor(
                    value = widgets,
                    onChange = { widgets = it },
                    catalog = catalog,
                )
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    ProfileSectionTitle(AppStrings.get(context, R.string.field_tokens_title))
                    FieldTokensSection(fields = self.profileFields)
                }
            }

            else -> ThemePane(
                css = profileCss,
                onCss = { profileCss = it },
            )
        }

        state.error?.let { Text(it, color = c.danger, fontSize = 12.sp) }

        if (dirty) {
            Text(
                text = AppStrings.get(context, R.string.profile_unsaved_changes),
                color = c.inkMuted,
                fontSize = 12.sp,
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OrangButton(
                text = AppStrings.get(context, R.string.catalog_save_profile_f597c0e8),
                onClick = {
                    vm.save(
                        username = username.trim().takeIf { it != self.username },
                        displayName = displayName.trim().ifBlank { null },
                        pronouns = pronouns.trim(),
                        bio = bio.trim(),
                        accentColor = accentColor,
                        profileCss = profileCss,
                        profileWidgets = widgets,
                    )
                },
                loading = state.saving,
                enabled = dirty,
                modifier = Modifier.weight(1f),
            )
            OrangButton(
                text = AppStrings.get(context, R.string.profile_reset),
                onClick = {
                    displayName = self.displayName
                    username = self.username
                    pronouns = self.pronouns.orEmpty()
                    bio = self.bio.orEmpty()
                    accentColor = self.accentColor
                    profileCss = self.profileCss.orEmpty()
                    widgets = baseline
                },
                variant = ButtonVariant.Ghost,
                enabled = dirty,
            )
        }
    }
}

@Composable
private fun IdentityPane(
    displayName: String,
    onDisplayName: (String) -> Unit,
    username: String,
    onUsername: (String) -> Unit,
    pronouns: String,
    onPronouns: (String) -> Unit,
    bio: String,
    onBio: (String) -> Unit,
    accentColor: Int?,
    onAccentColor: (Int) -> Unit,
) {
    val context = LocalContext.current
    val c = OrangTheme.colors

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        OrangTextField(
            value = displayName,
            onValueChange = { onDisplayName(it.take(64)) },
            label = AppStrings.get(context, R.string.catalog_display_name_c7874aaa),
            modifier = Modifier.fillMaxWidth(),
        )
        OrangTextField(
            value = username,
            onValueChange = { onUsername(it.lowercase().take(32)) },
            label = AppStrings.get(context, R.string.profile_username),
            hint = AppStrings.get(
                context,
                R.string.catalog_lowercase_letters_numbers_underscores_and_dots_57155ecc,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        OrangTextField(
            value = pronouns,
            onValueChange = { onPronouns(it.take(40)) },
            label = AppStrings.get(context, R.string.widget_pronouns),
            placeholder = "they/them",
            modifier = Modifier.fillMaxWidth(),
        )
        OrangTextField(
            value = bio,
            onValueChange = { onBio(it.take(4000)) },
            label = AppStrings.get(context, R.string.catalog_about_me_e3ba4ef3),
            singleLine = false,
            modifier = Modifier.fillMaxWidth(),
        )

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = AppStrings.get(context, R.string.catalog_accent_color_e49578ed),
                color = c.inkSecondary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
            )
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ACCENT_COLORS.chunked(4).forEach { colors ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        colors.forEach { color ->
                            val selected = accentColor == color
                            Box(
                                modifier = Modifier
                                    .size(34.dp)
                                    .clip(RoundedCornerShape(OrangRadius.lg))
                                    .background(Color(color).copy(alpha = 1f))
                                    .border(
                                        if (selected) 3.dp else 1.dp,
                                        if (selected) c.ink else c.borderStrong,
                                        RoundedCornerShape(OrangRadius.lg),
                                    )
                                    .clickable { onAccentColor(color) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ThemePane(css: String, onCss: (String) -> Unit) {
    val context = LocalContext.current
    val c = OrangTheme.colors

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = AppStrings.get(context, R.string.catalog_style_your_card_for_everyone_who_views_08c9bf5c) +
                ".oc-profile-card, .oc-pf-banner(-img), .oc-pf-avatar(-frame/-img/-fallback), " +
                ".oc-pf-body, .oc-pf-head, .oc-pf-name, .oc-pf-identity, .oc-pf-username, " +
                ".oc-pf-devices/-device, .oc-pf-widgets, .oc-pf-widget[data-widget], " +
                ".oc-pf-section, .oc-pf-heading, .oc-pf-pronouns, .oc-pf-activity(-name), " +
                ".oc-pf-badges/-badge(-label), .oc-pf-text, .oc-pf-rows/-row(-label/-value), " +
                ".oc-pf-links/-link, .oc-pf-image and .oc-pf-divider - plus [data-status] and " +
                "var(--oc-pf-accent) on the card. @media, @container, @starting-style, " +
                "@layer and @keyframes work; external images, fonts and @import are blocked. " +
                "The preview above shows what others see.",
            color = c.inkMuted,
            fontSize = 12.sp,
        )
        OrangTextField(
            value = css,
            onValueChange = { onCss(it.take(PROFILE_CSS_MAX_LEN)) },
            label = "CSS",
            placeholder = AppStrings.get(context, R.string.catalog_oc_pf_name_color_ff6a1a_9442cf4e),
            singleLine = false,
            modifier = Modifier.fillMaxWidth(),
        )
        if (css.isNotBlank()) {
            OrangButton(
                text = AppStrings.get(context, R.string.catalog_clear_css_6a933bda),
                onClick = { onCss("") },
                variant = ButtonVariant.Ghost,
            )
        }
    }
}

@Composable
private fun ProfileSectionTitle(text: String) {
    Text(
        text = text.uppercase(),
        color = OrangTheme.colors.inkMuted,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
    )
}
