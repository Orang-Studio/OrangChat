package lt.oranges.orangchat.feature.settings

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import lt.oranges.orangchat.ui.components.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import lt.oranges.orangchat.data.model.PresenceStatus
import lt.oranges.orangchat.data.model.SelfUser
import lt.oranges.orangchat.feature.profile.ProfileSettingsSection
import lt.oranges.orangchat.feature.profile.ProfileCard
import lt.oranges.orangchat.ui.components.ButtonVariant
import lt.oranges.orangchat.ui.components.OrangButton
import lt.oranges.orangchat.ui.components.ImageField
import lt.oranges.orangchat.ui.theme.OrangRadius
import lt.oranges.orangchat.ui.theme.OrangTheme
import lt.oranges.orangchat.ui.theme.ThemePreference

private enum class SettingsPage {
    ROOT, PROFILE, PROFILE_THEMES, APPEARANCE, PRIVACY, SHARING, RINGTONE, SECURITY, DEVICES, ENCRYPTION, ACCESSIBILITY, SYSTEM, ABOUT
}

@Composable
fun SettingsScreen(
    self: SelfUser,
    themePreference: ThemePreference,
    onThemeChange: (ThemePreference) -> Unit,
    onStatusChange: (PresenceStatus) -> Unit,
    onBack: () -> Unit,
    onLogout: () -> Unit,
    modifier: Modifier = Modifier,
    connected: Boolean = false,
) {
    var page by rememberSaveable { mutableStateOf(SettingsPage.ROOT) }
    val toRoot = { page = SettingsPage.ROOT }

    // Hoisted so it outlives the root leaving composition: opening a group and
    // coming back should land where you were, not at the top of the list.
    val rootScroll = rememberScrollState()

    BackHandler(enabled = page != SettingsPage.ROOT, onBack = toRoot)

    when (page) {
        SettingsPage.ROOT -> SettingsRoot(
            self = self,
            onStatusChange = onStatusChange,
            onOpen = { page = it },
            onBack = onBack,
            onLogout = onLogout,
            scrollState = rootScroll,
            themeSummary = themePreference.label(),
            modifier = modifier,
        )
        SettingsPage.PROFILE -> ProfilePage(self, toRoot, modifier)
        SettingsPage.PROFILE_THEMES -> ProfileThemesScreen(self, toRoot, modifier)
        SettingsPage.APPEARANCE -> AppearancePage(self, themePreference, onThemeChange, toRoot, modifier)
        SettingsPage.PRIVACY -> PrivacyScreen(self, toRoot)
        SettingsPage.SHARING -> SharingScreen(toRoot)
        SettingsPage.RINGTONE -> RingtonePage(toRoot, modifier)
        SettingsPage.SECURITY -> SecurityScreen(self = self, hasPassword = self.hasPassword, onBack = toRoot)
        SettingsPage.DEVICES -> DevicesScreen(onBack = toRoot)
        SettingsPage.ENCRYPTION -> EncryptionScreen(onBack = toRoot)
        SettingsPage.ACCESSIBILITY -> AccessibilityScreen(toRoot)
        SettingsPage.SYSTEM -> SystemScreen(connected = connected, onBack = toRoot)
        SettingsPage.ABOUT -> AboutScreen(toRoot)
    }
}

@Composable
private fun SettingsRoot(
    self: SelfUser,
    onStatusChange: (PresenceStatus) -> Unit,
    onOpen: (SettingsPage) -> Unit,
    onBack: () -> Unit,
    onLogout: () -> Unit,
    scrollState: ScrollState,
    themeSummary: String,
    modifier: Modifier = Modifier,
) {
    val c = OrangTheme.colors
    Column(modifier = modifier.fillMaxSize().background(c.surface2)) {
        SettingsTopBar("Settings", onBack)

        Column(
            modifier = Modifier.verticalScroll(scrollState).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            // Use the same profile card as the web preview and profile dialog.
            ProfileCard(
                user = self.asUser(),
                presence = self.status,
                modifier = Modifier
                    .clickable { onOpen(SettingsPage.PROFILE) },
            )

            SettingSection("Status") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PresenceStatus.entries.filter { it != PresenceStatus.OFFLINE }.forEach { status ->
                        val selected = self.status == status
                        Text(
                            text = status.label(),
                            color = if (selected) c.inkOnPrimary else c.inkSecondary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier
                                .background(if (selected) c.primary else c.surface3, RoundedCornerShape(OrangRadius.lg))
                                .clickable { onStatusChange(status) }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                    }
                }
            }

            SettingSection("Settings") {
                SettingsNavRow(
                    "Profile",
                    "Display name, avatar, bio, accent, CSS",
                    onClick = { onOpen(SettingsPage.PROFILE) },
                )
                SettingsNavRow(
                    "Profile themes",
                    "Ready-made styles for your profile card",
                    onClick = { onOpen(SettingsPage.PROFILE_THEMES) },
                )
                SettingsNavRow(
                    "Appearance",
                    "Theme",
                    onClick = { onOpen(SettingsPage.APPEARANCE) },
                    trailing = themeSummary,
                )
                SettingsNavRow(
                    "Privacy",
                    "Messages, friend requests, typing",
                    onClick = { onOpen(SettingsPage.PRIVACY) },
                )
                SettingsNavRow(
                    "Camera & Microphone",
                    "Call sharing defaults",
                    onClick = { onOpen(SettingsPage.SHARING) },
                )
                SettingsNavRow(
                    "Call ringtone",
                    "What incoming calls sound like",
                    onClick = { onOpen(SettingsPage.RINGTONE) },
                )
                SettingsNavRow(
                    "Security",
                    if (self.twoFactorEnabled) "Two-factor is on" else "Two-factor authentication",
                    onClick = { onOpen(SettingsPage.SECURITY) },
                    trailing = if (self.twoFactorEnabled) "On" else null,
                )
                SettingsNavRow(
                    "Devices",
                    "Where you're signed in",
                    onClick = { onOpen(SettingsPage.DEVICES) },
                )
                SettingsNavRow(
                    "Encryption",
                    "Keys, device log, verifying people",
                    onClick = { onOpen(SettingsPage.ENCRYPTION) },
                )
                SettingsNavRow(
                    "Accessibility",
                    "Text size, motion, density",
                    onClick = { onOpen(SettingsPage.ACCESSIBILITY) },
                )
                SettingsNavRow(
                    "System",
                    "Connection and server info",
                    onClick = { onOpen(SettingsPage.SYSTEM) },
                )
                SettingsNavRow(
                    "About",
                    "Version and build",
                    onClick = { onOpen(SettingsPage.ABOUT) },
                )
            }

            Spacer(Modifier.height(8.dp))
            OrangButton(text = "Log out", onClick = onLogout, variant = ButtonVariant.Danger, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun ProfilePage(self: SelfUser, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val c = OrangTheme.colors
    Column(modifier = modifier.fillMaxSize().background(c.surface2)) {
        SettingsTopBar("Profile", onBack)
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            ProfileSettingsSection(self = self)
        }
    }
}

@Composable
private fun AppearancePage(
    self: SelfUser,
    themePreference: ThemePreference,
    onThemeChange: (ThemePreference) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    vm: SettingsViewModel = hiltViewModel(),
) {
    val c = OrangTheme.colors
    val iconUploading by vm.appIconUploading.collectAsStateWithLifecycle()
    val iconError by vm.appIconError.collectAsStateWithLifecycle()
    val iconPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri -> uri?.let(vm::uploadAppIcon) }
    Column(modifier = modifier.fillMaxSize().background(c.surface2)) {
        SettingsTopBar("Appearance", onBack)
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            SettingSection("Theme") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ThemePreference.entries.forEach { pref ->
                        val selected = themePreference == pref
                        Text(
                            text = pref.label(),
                            color = if (selected) c.inkOnPrimary else c.inkSecondary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier
                                .background(
                                    if (selected) c.primary else c.surface3,
                                    RoundedCornerShape(OrangRadius.lg),
                                )
                                .clickable { onThemeChange(pref) }
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                        )
                    }
                }
            }

            SettingSection("App icon") {
                Text(
                    "Replaces the OrangChat mark for you everywhere you are signed in. " +
                        "Android cannot repoint its own launcher icon, so on this device " +
                        "the change shows up in the web app and the desktop app.",
                    color = c.inkMuted,
                    fontSize = 12.sp,
                )
                ImageField(
                    label = "Icon",
                    url = self.appIconUrl,
                    height = 72.dp,
                    square = true,
                    busy = iconUploading,
                    onPick = {
                        iconPicker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                        )
                    },
                    onRemove = vm::removeAppIcon,
                )
                iconError?.let { Text(it, color = c.danger, fontSize = 12.sp) }
            }
        }
    }
}

@Composable
private fun RingtonePage(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val c = OrangTheme.colors
    Column(modifier = modifier.fillMaxSize().background(c.surface2)) {
        SettingsTopBar("Call ringtone", onBack)
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            SettingSection("Call ringtone") { RingtoneSetting() }
        }
    }
}

/**
 * Pick an audio file to ring with. The file stays on the device - only its URI
 * is stored locally, and nothing is uploaded.
 */
@Composable
private fun RingtoneSetting() {
    val c = OrangTheme.colors
    val vm: RingtoneViewModel = hiltViewModel()
    val name by vm.ringtoneName.collectAsStateWithLifecycle()

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(vm::setRingtone) }

    // Never leave a preview ringing after the screen goes away.
    DisposableEffect(Unit) { onDispose { vm.stopPreview() } }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = name ?: "Device default",
            color = c.ink,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
        )
        Text(
            text = "Stored on this device only - never uploaded.",
            color = c.inkMuted,
            fontSize = 11.sp,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OrangButton(
                text = "Choose",
                onClick = { picker.launch(arrayOf("audio/*")) },
                variant = ButtonVariant.Secondary,
            )
            OrangButton(
                text = "Preview",
                onClick = { vm.previewRingtone() },
                variant = ButtonVariant.Secondary,
            )
            OrangButton(
                text = "Stop",
                onClick = { vm.stopPreview() },
                variant = ButtonVariant.Ghost,
            )
            if (name != null) {
                OrangButton(
                    text = "Reset",
                    onClick = { vm.useDefaultRingtone() },
                    variant = ButtonVariant.Ghost,
                )
            }
        }
    }
}

private fun ThemePreference.label(): String =
    name.lowercase().replaceFirstChar { it.uppercase() }

private fun PresenceStatus.label(): String = when (this) {
    PresenceStatus.ONLINE -> "Online"
    PresenceStatus.IDLE -> "Idle"
    PresenceStatus.DND -> "Do Not Disturb"
    PresenceStatus.OFFLINE -> "Offline"
}
