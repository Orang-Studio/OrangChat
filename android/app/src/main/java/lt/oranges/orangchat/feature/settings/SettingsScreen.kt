package lt.oranges.orangchat.feature.settings

import android.content.Context
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import lt.oranges.orangchat.R
import lt.oranges.orangchat.data.model.PresenceStatus
import lt.oranges.orangchat.data.model.SelfUser
import lt.oranges.orangchat.feature.profile.ProfileSettingsSection
import lt.oranges.orangchat.feature.profile.ProfileCard
import lt.oranges.orangchat.ui.components.ButtonVariant
import lt.oranges.orangchat.ui.components.OrangButton
import lt.oranges.orangchat.ui.components.ImageField
import lt.oranges.orangchat.ui.components.StatusIcon
import lt.oranges.orangchat.ui.theme.OrangRadius
import lt.oranges.orangchat.ui.theme.OrangTheme
import lt.oranges.orangchat.ui.theme.ThemePreference
import lt.oranges.orangchat.util.AppStrings
import lt.oranges.orangchat.util.LocalePreferences

private enum class SettingsPage {
    ROOT, PROFILE, PROFILE_THEMES, APPEARANCE, PRIVACY, SHARING, RINGTONE, SECURITY, DEVICES, ENCRYPTION, ACCESSIBILITY, LANGUAGE, SYSTEM, ABOUT
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
    val context = LocalContext.current

    BackHandler(enabled = page != SettingsPage.ROOT, onBack = toRoot)

    when (page) {
        SettingsPage.ROOT -> SettingsRoot(
            self = self,
            onStatusChange = onStatusChange,
            onOpen = { page = it },
            onBack = onBack,
            onLogout = onLogout,
            scrollState = rootScroll,
            themeSummary = themePreference.label(context),
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
        SettingsPage.LANGUAGE -> LanguagePage(toRoot, modifier)
        SettingsPage.SYSTEM -> SystemScreen(connected = connected, onBack = toRoot)
        SettingsPage.ABOUT -> AboutScreen(appIconUrl = self.appIconUrl, onBack = toRoot)
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
    val context = LocalContext.current
    Column(modifier = modifier.fillMaxSize().background(c.surface2)) {
        SettingsTopBar(AppStrings.get(context, R.string.catalog_settings_c7f73bb5), onBack)

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

            SettingSection(AppStrings.get(context, R.string.catalog_status_bae7d5be)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PresenceStatus.entries.filter { it != PresenceStatus.OFFLINE }.forEach { status ->
                        val selected = self.status == status
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clip(RoundedCornerShape(OrangRadius.lg))
                                .background(if (selected) c.primary else c.surface3)
                                .clickable { onStatusChange(status) }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                        ) {
                            StatusIcon(status = status, size = 14.dp)
                            Text(
                                text = status.label(context),
                                color = if (selected) c.inkOnPrimary else c.inkSecondary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                    }
                }
            }

            SettingSection(AppStrings.get(context, R.string.catalog_account_85dfa32c)) {
                SettingsNavRow(
                    AppStrings.get(context, R.string.catalog_profile_ff4fc027),
                    AppStrings.get(context, R.string.catalog_display_name_avatar_bio_accent_css_e2684739),
                    onClick = { onOpen(SettingsPage.PROFILE) },
                )
                SettingsNavRow(
                    AppStrings.get(context, R.string.catalog_profile_themes_3d7fccd2),
                    AppStrings.get(context, R.string.catalog_ready_made_styles_for_your_profile_card_83af5cba),
                    onClick = { onOpen(SettingsPage.PROFILE_THEMES) },
                )
            }

            SettingSection(AppStrings.get(context, R.string.catalog_privacy_security_9bb6e9c0)) {
                SettingsNavRow(
                    AppStrings.get(context, R.string.catalog_privacy_cf01481f),
                    AppStrings.get(context, R.string.catalog_messages_friend_requests_typing_notifications_17b64746),
                    onClick = { onOpen(SettingsPage.PRIVACY) },
                )
                SettingsNavRow(
                    AppStrings.get(context, R.string.catalog_security_f25ce1b8),
                    if (self.twoFactorEnabled) AppStrings.get(context, R.string.catalog_two_factor_is_on_65e04e1d) else AppStrings.get(context, R.string.catalog_two_factor_authentication_edfd617a),
                    onClick = { onOpen(SettingsPage.SECURITY) },
                    trailing = if (self.twoFactorEnabled) AppStrings.get(context, R.string.catalog_on_e0049a66) else null,
                )
                SettingsNavRow(
                    AppStrings.get(context, R.string.catalog_devices_df485c87),
                    AppStrings.get(context, R.string.catalog_where_you_re_signed_in_af00ae96),
                    onClick = { onOpen(SettingsPage.DEVICES) },
                )
                SettingsNavRow(
                    AppStrings.get(context, R.string.catalog_encryption_0af149c2),
                    AppStrings.get(context, R.string.catalog_keys_device_log_verifying_people_0828f752),
                    onClick = { onOpen(SettingsPage.ENCRYPTION) },
                )
            }

            SettingSection(AppStrings.get(context, R.string.catalog_appearance_41def7a0)) {
                SettingsNavRow(
                    AppStrings.get(context, R.string.catalog_appearance_41def7a0),
                    AppStrings.get(context, R.string.catalog_theme_a797e309),
                    onClick = { onOpen(SettingsPage.APPEARANCE) },
                    trailing = themeSummary,
                )
                SettingsNavRow(
                    AppStrings.get(context, R.string.catalog_accessibility_d660049b),
                    AppStrings.get(context, R.string.catalog_text_size_motion_density_a9cc0369),
                    onClick = { onOpen(SettingsPage.ACCESSIBILITY) },
                )
                SettingsNavRow(
                    AppStrings.get(context, R.string.language_title),
                    AppStrings.get(context, R.string.language_subtitle),
                    onClick = { onOpen(SettingsPage.LANGUAGE) },
                    trailing = LocalePreferences.label(context, LocalePreferences.getTag(context)),
                )
            }

            SettingSection(AppStrings.get(context, R.string.catalog_communication_ade0d50c)) {
                SettingsNavRow(
                    AppStrings.get(context, R.string.catalog_camera_microphone_abf47618),
                    AppStrings.get(context, R.string.catalog_call_sharing_defaults_c39b7d94),
                    onClick = { onOpen(SettingsPage.SHARING) },
                )
                SettingsNavRow(
                    AppStrings.get(context, R.string.catalog_call_ringtone_0618e939),
                    AppStrings.get(context, R.string.catalog_what_incoming_calls_sound_like_5a0f66a3),
                    onClick = { onOpen(SettingsPage.RINGTONE) },
                )
            }

            SettingSection(AppStrings.get(context, R.string.catalog_system_bc0792d8)) {
                SettingsNavRow(
                    AppStrings.get(context, R.string.catalog_system_bc0792d8),
                    AppStrings.get(context, R.string.catalog_connection_and_server_info_b1f3ef22),
                    onClick = { onOpen(SettingsPage.SYSTEM) },
                )
                SettingsNavRow(
                    AppStrings.get(context, R.string.catalog_about_6b21fb79),
                    AppStrings.get(context, R.string.catalog_version_and_build_3da1c1d8),
                    onClick = { onOpen(SettingsPage.ABOUT) },
                )
            }

            Spacer(Modifier.height(8.dp))
            OrangButton(text = AppStrings.get(context, R.string.catalog_log_out_6e78c91f), onClick = onLogout, variant = ButtonVariant.Danger, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun ProfilePage(self: SelfUser, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val c = OrangTheme.colors
    val context = LocalContext.current
    Column(modifier = modifier.fillMaxSize().background(c.surface2)) {
        SettingsTopBar(AppStrings.get(context, R.string.catalog_profile_ff4fc027), onBack)
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
        val context = LocalContext.current
    val c = OrangTheme.colors
    val iconUploading by vm.appIconUploading.collectAsStateWithLifecycle()
    val iconError by vm.appIconError.collectAsStateWithLifecycle()
    val iconPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri -> uri?.let(vm::uploadAppIcon) }
    Column(modifier = modifier.fillMaxSize().background(c.surface2)) {
        SettingsTopBar(AppStrings.get(context, R.string.catalog_appearance_41def7a0), onBack)
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            SettingSection(AppStrings.get(context, R.string.catalog_theme_a797e309)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ThemePreference.entries.forEach { pref ->
                        val selected = themePreference == pref
                        Text(
                            text = pref.label(context),
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

            SettingSection(AppStrings.get(context, R.string.catalog_app_icon_abde7a74)) {
                Text(
                    AppStrings.get(context, R.string.catalog_replaces_the_orangchat_mark_for_you_everywhere_109fab70) +
                        AppStrings.get(context, R.string.catalog_android_cannot_repoint_its_own_launcher_icon_cdbe2f10),
                    color = c.inkMuted,
                    fontSize = 12.sp,
                )
                ImageField(
                    label = AppStrings.get(context, R.string.catalog_icon_716f63b9),
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
        val context = LocalContext.current
    val c = OrangTheme.colors
    Column(modifier = modifier.fillMaxSize().background(c.surface2)) {
        SettingsTopBar(AppStrings.get(context, R.string.catalog_call_ringtone_0618e939), onBack)
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            SettingSection(AppStrings.get(context, R.string.catalog_call_ringtone_0618e939)) { RingtoneSetting() }
        }
    }
}

/**
 * Pick an audio file to ring with. The file stays on the device - only its URI
 * is stored locally, and nothing is uploaded.
 */
@Composable
private fun RingtoneSetting() {
        val context = LocalContext.current
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
            text = name ?: AppStrings.get(context, R.string.catalog_device_default_f9aac6c5),
            color = c.ink,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
        )
        Text(
            text = AppStrings.get(context, R.string.catalog_stored_on_this_device_only_never_uploaded_1373e7ad),
            color = c.inkMuted,
            fontSize = 11.sp,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OrangButton(
                text = AppStrings.get(context, R.string.catalog_choose_78b7c9f6),
                onClick = { picker.launch(arrayOf("audio/*")) },
                variant = ButtonVariant.Secondary,
            )
            OrangButton(
                text = AppStrings.get(context, R.string.catalog_preview_f1fbb2b4),
                onClick = { vm.previewRingtone() },
                variant = ButtonVariant.Secondary,
            )
            OrangButton(
                text = AppStrings.get(context, R.string.catalog_stop_9e253470),
                onClick = { vm.stopPreview() },
                variant = ButtonVariant.Ghost,
            )
            if (name != null) {
                OrangButton(
                    text = AppStrings.get(context, R.string.catalog_reset_44c57abd),
                    onClick = { vm.useDefaultRingtone() },
                    variant = ButtonVariant.Ghost,
                )
            }
        }
    }
}

private fun ThemePreference.label(context: Context): String = when (this) {
    ThemePreference.SYSTEM -> AppStrings.get(context, R.string.catalog_system_bc0792d8)
    ThemePreference.DARK -> AppStrings.get(context, R.string.catalog_dark_ae1ef014)
    ThemePreference.LIGHT -> AppStrings.get(context, R.string.catalog_light_a36ef8ab)
}

private fun PresenceStatus.label(context: Context): String = when (this) {
    PresenceStatus.ONLINE -> AppStrings.get(context, R.string.catalog_online_c3e839df)
    PresenceStatus.IDLE -> AppStrings.get(context, R.string.catalog_idle_cc1ebdd0)
    PresenceStatus.DND -> AppStrings.get(context, R.string.catalog_do_not_disturb_875ba794)
    PresenceStatus.OFFLINE -> AppStrings.get(context, R.string.catalog_offline_e01fa717)
}
