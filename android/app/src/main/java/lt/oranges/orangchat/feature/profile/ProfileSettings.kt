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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.ui.draw.alpha
import lt.oranges.orangchat.ui.components.Badge
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
import lt.oranges.orangchat.ui.components.OrangButton
import lt.oranges.orangchat.ui.components.OrangTextField
import lt.oranges.orangchat.ui.theme.OrangRadius
import lt.oranges.orangchat.ui.theme.OrangTheme
import lt.oranges.orangchat.util.PROFILE_CSS_MAX_LEN
import lt.oranges.orangchat.util.absoluteUrl

private val ACCENT_COLORS = listOf(
    0xFF6A1A, 0xE85D04, 0xF0554C, 0xE2AB35,
    0x3FBD6E, 0x4F9FF2, 0x7C6FF0, 0xC45BD6,
)

/**
 * Edit your own profile — display name, pronouns, bio, avatar and banner.
 * Port of the web client's UserSettingsDialog Profile tab; images go through
 * the same POST /uploads/image the web client uses, which resizes and strips
 * EXIF server-side.
 */
@Composable
fun ProfileSettingsSection(
    self: SelfUser,
    modifier: Modifier = Modifier,
    vm: ProfileViewModel = hiltViewModel(),
) {
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
            label = "Display name",
            modifier = Modifier.fillMaxWidth(),
        )
        OrangTextField(
            value = username,
            onValueChange = { username = it.lowercase().take(32) },
            label = "Username",
            hint = "Lowercase letters, numbers, underscores, and dots.",
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
            label = "About me",
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
            Text("Accent color", color = c.inkSecondary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
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
            text = "Style your card for everyone who views it. Target .oc-profile-card, " +
                ".oc-pf-banner, .oc-pf-avatar, .oc-pf-body, .oc-pf-name, .oc-pf-pronouns, " +
                ".oc-pf-username, .oc-pf-bio and .oc-pf-member. External images, fonts and " +
                "@import are blocked; the preview above shows what others see.",
            color = c.inkMuted,
            fontSize = 12.sp,
        )
        OrangTextField(
            value = profileCss,
            onValueChange = { profileCss = it.take(PROFILE_CSS_MAX_LEN) },
            label = "CSS",
            placeholder = ".oc-pf-name { color: #ff6a1a; }",
            singleLine = false,
            modifier = Modifier.fillMaxWidth(),
        )
        if (profileCss.isNotBlank()) {
            OrangButton(
                text = "Clear CSS",
                onClick = { profileCss = "" },
                variant = ButtonVariant.Ghost,
            )
        }

        ProfileSectionTitle("Badges")
        BadgeCatalog(self.badges)

        state.error?.let { Text(it, color = c.danger, fontSize = 12.sp) }

        OrangButton(
            text = "Save profile",
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

/**
 * The whole badge catalog with the held ones lit up and the rest dimmed. Badges
 * are awarded server-side, so this is read-only - it exists to say what each one
 * is and how it's earned.
 */
@Composable
private fun BadgeCatalog(owned: List<String>) {
    val c = OrangTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Badge.entries.forEach { badge ->
            val has = badge.slug in owned
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(OrangRadius.lg))
                    .background(c.surface1)
                    .padding(horizontal = 12.dp, vertical = 10.dp)
                    .alpha(if (has) 1f else 0.5f),
            ) {
                Icon(
                    imageVector = badge.icon,
                    contentDescription = null,
                    tint = if (has) badge.color else c.inkMuted,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(badge.label, color = c.ink, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Text(badge.description, color = c.inkMuted, fontSize = 12.sp)
                }
                if (has) {
                    Text("Earned", color = c.success, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                }
            }
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

@Composable
private fun ImageField(
    label: String,
    url: String?,
    height: androidx.compose.ui.unit.Dp,
    square: Boolean = false,
    busy: Boolean,
    onPick: () -> Unit,
    onRemove: () -> Unit,
) {
    val c = OrangTheme.colors
    val previewModifier = if (square) Modifier.size(height) else Modifier.fillMaxWidth().height(height)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, color = c.inkMuted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        Box(
            modifier = previewModifier
                .clip(RoundedCornerShape(OrangRadius.lg))
                .background(c.surface3),
            contentAlignment = Alignment.Center,
        ) {
            if (!url.isNullOrBlank()) {
                AsyncImage(
                    model = absoluteUrl(url),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = previewModifier,
                )
            } else {
                Text("None set", color = c.inkMuted, fontSize = 12.sp)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OrangButton(
                text = if (busy) "Uploading…" else "Upload",
                onClick = onPick,
                enabled = !busy,
                loading = busy,
                variant = ButtonVariant.Secondary,
            )
            if (!url.isNullOrBlank()) {
                OrangButton(text = "Remove", onClick = onRemove, variant = ButtonVariant.Ghost)
            }
        }
    }
}
