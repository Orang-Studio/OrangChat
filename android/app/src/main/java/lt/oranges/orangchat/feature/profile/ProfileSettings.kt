package lt.oranges.orangchat.feature.profile

import androidx.compose.ui.platform.LocalContext
import lt.oranges.orangchat.R
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import lt.oranges.orangchat.ui.components.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import lt.oranges.orangchat.data.model.SelfUser
import lt.oranges.orangchat.ui.components.ButtonVariant
import lt.oranges.orangchat.ui.components.ImageField
import lt.oranges.orangchat.ui.components.OrangButton
import lt.oranges.orangchat.ui.components.OrangTextField
import lt.oranges.orangchat.ui.theme.OrangRadius
import lt.oranges.orangchat.ui.theme.OrangTheme
import lt.oranges.orangchat.util.AppStrings
import lt.oranges.orangchat.util.PROFILE_CSS_MAX_LEN
import lt.oranges.orangchat.util.absoluteUrl

private val ACCENT_COLORS = listOf(
    0xFF6A1A, 0xE85D04, 0xF0554C, 0xE2AB35,
    0x3FBD6E, 0x4F9FF2, 0x7C6FF0, 0xC45BD6,
)

@Composable
fun ProfileSettingsSection(
    self: SelfUser,
    modifier: Modifier = Modifier,
    vm: ProfileViewModel = hiltViewModel(),
) {
        val context = LocalContext.current
    val c = OrangTheme.colors
    val state by vm.state.collectAsStateWithLifecycle()

    var displayName by remember(self.displayName) { mutableStateOf(self.displayName) }
    var username by remember(self.username) { mutableStateOf(self.username) }
    var pronouns by remember(self.pronouns) { mutableStateOf(self.pronouns.orEmpty()) }
    var bio by remember(self.bio) { mutableStateOf(self.bio.orEmpty()) }
    var accentColor by remember(self.accentColor) { mutableStateOf(self.accentColor) }
    var profileCss by remember(self.profileCss) { mutableStateOf(self.profileCss.orEmpty()) }

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
    )
    val dirty = displayName.trim() != self.displayName ||
        username.trim() != self.username ||
        pronouns.trim().ifBlank { null } != self.pronouns ||
        bio.trim().ifBlank { null } != self.bio ||
        accentColor != self.accentColor ||
        profileCss.ifBlank { null } != self.profileCss

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(20.dp)) {
        ProfileSectionTitle("Preview")
        ProfileCard(user = previewUser, presence = self.status)

        ProfileSectionTitle("Identity")
        OrangTextField(
            value = displayName,
            onValueChange = { displayName = it.take(64) },
            label = AppStrings.get(context, R.string.catalog_display_name_c7874aaa),
            modifier = Modifier.fillMaxWidth(),
        )
        OrangTextField(
            value = username,
            onValueChange = { username = it.lowercase().take(32) },
            label = "Username",
            hint = AppStrings.get(context, R.string.catalog_lowercase_letters_numbers_underscores_and_dots_57155ecc),
            modifier = Modifier.fillMaxWidth(),
        )
        ImageField(
            label = "Avatar",
            url = self.avatarUrl,
            height = 72.dp,
            square = true,
            busy = state.uploading == ImageKind.AVATAR,
            onPick = { avatarPicker.launch(imageRequest) },
            onRemove = { vm.removeImage(ImageKind.AVATAR) },
        )

        ProfileSectionTitle("About")
        OrangTextField(
            value = pronouns,
            onValueChange = { pronouns = it.take(40) },
            label = "Pronouns",
            placeholder = "they/them",
            modifier = Modifier.fillMaxWidth(),
        )
        OrangTextField(
            value = bio,
            onValueChange = { bio = it.take(4000) },
            label = AppStrings.get(context, R.string.catalog_about_me_e3ba4ef3),
            singleLine = false,
            modifier = Modifier.fillMaxWidth(),
        )
        ImageField(
            label = "Banner",
            url = self.bannerUrl,
            height = 72.dp,
            busy = state.uploading == ImageKind.BANNER,
            onPick = { bannerPicker.launch(imageRequest) },
            onRemove = { vm.removeImage(ImageKind.BANNER) },
        )

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(AppStrings.get(context, R.string.catalog_accent_color_e49578ed), color = c.inkSecondary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
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
                                    .clickable { accentColor = color },
                            )
                        }
                    }
                }
            }
        }

        ProfileSectionTitle("Profile CSS")
        Text(
            text = AppStrings.get(context, R.string.catalog_style_your_card_for_everyone_who_views_08c9bf5c) +
                ".oc-profile-card, .oc-pf-banner(-img), .oc-pf-avatar(-frame/-img/-fallback), " +
                ".oc-pf-body, .oc-pf-head, .oc-pf-name, .oc-pf-pronouns, .oc-pf-identity, " +
                ".oc-pf-username, .oc-pf-devices/-device, .oc-pf-activity(-name), " +
                ".oc-pf-badges/-badge(-label), .oc-pf-section, .oc-pf-heading, " +
                ".oc-pf-bio(-text) and .oc-pf-member(-text) - plus [data-status] and " +
                "var(--oc-pf-accent) on the card. @media, @container, @starting-style, " +
                "@layer and @keyframes work; external images, fonts and @import are blocked. " +
                "The preview above shows what others see.",
            color = c.inkMuted,
            fontSize = 12.sp,
        )
        OrangTextField(
            value = profileCss,
            onValueChange = { profileCss = it.take(PROFILE_CSS_MAX_LEN) },
            label = "CSS",
            placeholder = AppStrings.get(context, R.string.catalog_oc_pf_name_color_ff6a1a_9442cf4e),
            singleLine = false,
            modifier = Modifier.fillMaxWidth(),
        )
        if (profileCss.isNotBlank()) {
            OrangButton(
                text = AppStrings.get(context, R.string.catalog_clear_css_6a933bda),
                onClick = { profileCss = "" },
                variant = ButtonVariant.Ghost,
            )
        }

        state.error?.let { Text(it, color = c.danger, fontSize = 12.sp) }

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
                )
            },
            loading = state.saving,
            enabled = dirty,
            modifier = Modifier.fillMaxWidth(),
        )
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

