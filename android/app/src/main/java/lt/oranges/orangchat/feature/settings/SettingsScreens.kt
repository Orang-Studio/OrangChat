package lt.oranges.orangchat.feature.settings

import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.TextButton
import lt.oranges.orangchat.ui.components.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import lt.oranges.orangchat.BuildConfig
import lt.oranges.orangchat.R
import lt.oranges.orangchat.data.model.DmPrivacy
import lt.oranges.orangchat.data.model.FriendRequestPrivacy
import lt.oranges.orangchat.data.model.SelfUser
import lt.oranges.orangchat.data.remote.Passkey
import lt.oranges.orangchat.feature.e2ee.EncryptionExplainerDialog
import lt.oranges.orangchat.feature.e2ee.HowEncryptionWorksLink
import lt.oranges.orangchat.feature.updates.UpdateUiState
import lt.oranges.orangchat.feature.updates.UpdateViewModel
import lt.oranges.orangchat.notifications.appNotificationSettingsIntent
import lt.oranges.orangchat.notifications.rememberNotificationPermissionState
import lt.oranges.orangchat.ui.components.ButtonSize
import lt.oranges.orangchat.ui.components.ButtonVariant
import lt.oranges.orangchat.ui.components.OrangButton
import lt.oranges.orangchat.ui.components.OrangTextField
import lt.oranges.orangchat.ui.theme.OrangRadius
import lt.oranges.orangchat.util.AppStrings
import lt.oranges.orangchat.util.absoluteUrl
import lt.oranges.orangchat.util.formatFullTime
import lt.oranges.orangchat.ui.theme.OrangTheme

private fun screenModifier(c: lt.oranges.orangchat.ui.theme.OrangColors) =
    Modifier.fillMaxSize().background(c.surface2)


@Composable
fun PrivacyScreen(self: SelfUser, onBack: () -> Unit, vm: SettingsViewModel = hiltViewModel()) {
    val c = OrangTheme.colors
    val error by vm.privacyError.collectAsStateWithLifecycle()
    val prefs by vm.prefs.collectAsStateWithLifecycle()
    var explainerOpen by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val notificationsAllowed = rememberNotificationPermissionState()

    Column(modifier = screenModifier(c)) {
        SettingsTopBar(AppStrings.get(context, R.string.catalog_privacy_cf01481f), onBack)
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            SettingSection(AppStrings.get(context, R.string.catalog_who_can_message_you_e9356e11)) {
                SettingsChoiceRow(
                    value = self.dmPrivacy,
                    options = listOf(
                        Triple(DmPrivacy.EVERYONE, AppStrings.get(context, R.string.catalog_everyone_c756f6af), AppStrings.get(context, R.string.catalog_anyone_can_start_a_conversation_fa59af86)),
                        Triple(DmPrivacy.FRIENDS, AppStrings.get(context, R.string.catalog_friends_only_0b2d5f25), AppStrings.get(context, R.string.catalog_only_people_on_your_friends_list_a0690c48)),
                        Triple(DmPrivacy.NONE, AppStrings.get(context, R.string.catalog_no_one_41c06c15), AppStrings.get(context, R.string.catalog_nobody_new_can_message_you_d72cdf15)),
                    ),
                    onSelect = vm::setDmPrivacy,
                )
            }
            SettingSection(AppStrings.get(context, R.string.catalog_who_can_add_you_07e171de)) {
                SettingsChoiceRow(
                    value = self.friendRequestPrivacy,
                    options = listOf(
                        Triple(FriendRequestPrivacy.EVERYONE, AppStrings.get(context, R.string.catalog_everyone_c756f6af), AppStrings.get(context, R.string.catalog_anyone_who_knows_your_username_b7505a81)),
                        Triple(FriendRequestPrivacy.MUTUAL, AppStrings.get(context, R.string.catalog_friends_of_friends_9abdf2fe), AppStrings.get(context, R.string.catalog_only_people_you_share_a_friend_with_dd960ba7)),
                        Triple(FriendRequestPrivacy.NONE, AppStrings.get(context, R.string.catalog_no_one_41c06c15), AppStrings.get(context, R.string.catalog_nobody_can_send_you_requests_5279aaea)),
                    ),
                    onSelect = vm::setFriendRequestPrivacy,
                )
            }
            SettingSection(AppStrings.get(context, R.string.catalog_what_you_share_6998553a)) {
                SettingsToggleRow(
                    label = AppStrings.get(context, R.string.catalog_send_typing_indicators_6ca26372),
                    hint = AppStrings.get(context, R.string.catalog_let_people_see_when_you_re_typing_eb96518c),
                    checked = self.typingIndicators,
                    onCheckedChange = vm::setTypingIndicators,
                )
            }
            SettingSection(AppStrings.get(context, R.string.catalog_notifications_753a22b2)) {
                if (!notificationsAllowed) {
                    SettingsNavRow(
                        label = AppStrings.get(context, R.string.catalog_notifications_are_turned_off_e638becc),
                        subtitle = AppStrings.get(context, R.string.catalog_android_is_not_letting_orangchat_notify_you_eb9878eb),
                        onClick = { context.startActivity(appNotificationSettingsIntent(context)) },
                    )
                }
                SettingsToggleRow(
                    label = AppStrings.get(context, R.string.catalog_show_message_text_66bf26b1),
                    hint = AppStrings.get(context, R.string.catalog_off_the_shade_and_lock_screen_show_29ea09db),
                    checked = prefs.notificationPreviews,
                    onCheckedChange = vm::setNotificationPreviews,
                )
            }
            SettingSection(AppStrings.get(context, R.string.catalog_encryption_0af149c2)) {
                Text(
                    AppStrings.get(context, R.string.catalog_every_direct_message_is_encrypted_always_and_bacae7ec),
                    color = c.inkSecondary,
                    fontSize = 13.sp,
                )
                SettingsToggleRow(
                    label = AppStrings.get(context, R.string.catalog_check_people_before_messaging_them_986118e4),
                    hint = AppStrings.get(context, R.string.catalog_with_someone_new_your_messages_wait_on_376112de),
                    checked = self.e2eeStrict,
                    onCheckedChange = vm::setE2eeStrict,
                )
                Text(
                    AppStrings.get(context, R.string.catalog_leaving_it_off_is_not_unprotected_every_94d76940),
                    color = c.inkMuted,
                    fontSize = 12.sp,
                )
                HowEncryptionWorksLink(onClick = { explainerOpen = true })
            }
            if (error != null) {
                Text(error!!, color = c.danger, fontSize = 13.sp)
            }
        }
    }

    if (explainerOpen) {
        EncryptionExplainerDialog(onDismiss = { explainerOpen = false })
    }
}


@Composable
fun SharingScreen(onBack: () -> Unit, vm: SettingsViewModel = hiltViewModel()) {
        val context = LocalContext.current
    val c = OrangTheme.colors
    val prefs by vm.prefs.collectAsStateWithLifecycle()

    Column(modifier = screenModifier(c)) {
        SettingsTopBar(AppStrings.get(context, R.string.catalog_camera_microphone_abf47618), onBack)
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text(
                AppStrings.get(context, R.string.catalog_orangchat_only_opens_your_camera_and_microphone_16da857f) +
                    AppStrings.get(context, R.string.catalog_for_permission_the_first_time_you_join_1cbeb90e),
                color = c.inkSecondary,
                fontSize = 13.sp,
            )
            SettingSection(AppStrings.get(context, R.string.catalog_when_joining_a_call_9f17cb1a)) {
                SettingsToggleRow(
                    label = AppStrings.get(context, R.string.catalog_join_muted_8ccaf5fe),
                    hint = AppStrings.get(context, R.string.catalog_start_every_call_with_your_microphone_off_087b5564),
                    checked = prefs.joinMuted,
                    onCheckedChange = vm::setJoinMuted,
                )
                SettingsToggleRow(
                    label = AppStrings.get(context, R.string.catalog_join_with_camera_on_c39f4e79),
                    hint = AppStrings.get(context, R.string.catalog_start_calls_with_video_already_publishing_04f2d03f),
                    checked = prefs.joinWithVideo,
                    onCheckedChange = vm::setJoinWithVideo,
                )
            }
            Text(
                AppStrings.get(context, R.string.catalog_these_choices_are_stored_on_this_device_ac326393),
                color = c.inkMuted,
                fontSize = 12.sp,
            )
        }
    }
}


private fun describeDevice(context: Context, userAgent: String?): String {
    if (userAgent.isNullOrBlank()) return AppStrings.get(context, R.string.catalog_unknown_device_7af13b29)
    val ua = userAgent.lowercase()
    return when {
        ua.contains("orangchat-android") -> AppStrings.get(context, R.string.catalog_orangchat_for_android_3f20ca31)
        ua.contains("electron") -> AppStrings.get(context, R.string.catalog_orangchat_desktop_app_ffb92600)
        ua.contains("android") -> AppStrings.get(context, R.string.catalog_android_browser_05f5cb3d)
        ua.contains("iphone") || ua.contains("ipad") -> AppStrings.get(context, R.string.catalog_ios_browser_1da182c5)
        else -> {
            val browser = when {
                ua.contains("firefox") -> "Firefox"
                ua.contains("edg/") -> "Edge"
                ua.contains("chrome") -> "Chrome"
                ua.contains("safari") -> "Safari"
                else -> AppStrings.get(context, R.string.catalog_browser_54a2cf5e)
            }
            val os = when {
                ua.contains("windows") -> "Windows"
                ua.contains("mac os") || ua.contains("macintosh") -> "macOS"
                ua.contains("linux") -> "Linux"
                else -> null
            }
            if (os != null) AppStrings.get(context, R.string.catalog_1_s_on_2_s_105d6e4b, browser, os) else browser
        }
    }
}

@Composable
fun DevicesScreen(onBack: () -> Unit, vm: SettingsViewModel = hiltViewModel()) {
        val context = LocalContext.current
    val c = OrangTheme.colors
    val state by vm.sessions.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { vm.refreshSessions() }

    Column(modifier = screenModifier(c)) {
        SettingsTopBar(AppStrings.get(context, R.string.catalog_devices_df485c87), onBack)
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                AppStrings.get(context, R.string.catalog_each_entry_is_a_device_with_a_8778c193) +
                    AppStrings.get(context, R.string.catalog_renewing_it_loses_access_within_a_few_1d7c7abe),
                color = c.inkSecondary,
                fontSize = 13.sp,
            )

            when (val s = state) {
                is SessionsUi.Loading -> Text(AppStrings.get(context, R.string.catalog_loading_33ce4174), color = c.inkMuted, fontSize = 14.sp)
                is SessionsUi.Failed -> Text(s.error, color = c.danger, fontSize = 13.sp)
                is SessionsUi.Loaded -> {
                    s.sessions.forEach { session ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(OrangRadius.lg))
                                .background(c.surface1)
                                .padding(12.dp),
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    describeDevice(context, session.userAgent) +
                                        if (session.current) AppStrings.get(context, R.string.catalog_this_device_82229094) else "",
                                    color = c.ink,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium,
                                )
                                Text(
                                    buildString {
                                        append(session.ip ?: AppStrings.get(context, R.string.catalog_unknown_ip_70fef55a))
                                        session.lastSeenAt?.let {
                                            append(AppStrings.get(context, R.string.catalog_last_active_1_s_df09e3bb, formatFullTime(it)))
                                        }
                                    },
                                    color = c.inkMuted,
                                    fontSize = 12.sp,
                                )
                                session.createdAt?.let {
                                    Text(
                                        AppStrings.get(context, R.string.catalog_signed_in_1_s_3b0f6db2, formatFullTime(it)),
                                        color = c.inkMuted,
                                        fontSize = 12.sp,
                                    )
                                }
                            }
                            OrangButton(
                                text = if (session.current) AppStrings.get(context, R.string.catalog_sign_out_dc1649a1) else AppStrings.get(context, R.string.catalog_revoke_0be72075),
                                onClick = { vm.revokeSession(session.id) },
                                variant = ButtonVariant.Ghost,
                                size = ButtonSize.Sm,
                            )
                        }
                    }

                    val others = s.sessions.count { !it.current }
                    if (others > 0) {
                        OrangButton(
                            text = if (others == 1) AppStrings.get(context, R.string.catalog_sign_out_1_s_other_device_24648e43, others)
                            else AppStrings.get(context, R.string.catalog_sign_out_1_s_other_devices_5bd5b0ba, others),
                            onClick = { vm.revokeOtherSessions() },
                            variant = ButtonVariant.Secondary,
                            size = ButtonSize.Sm,
                        )
                    }
                }
            }
        }
    }
}


@Composable
private fun PasskeysSection(self: SelfUser, vm: SettingsViewModel) {
    val c = OrangTheme.colors
    val context = LocalContext.current
    val ui by vm.passkeys.collectAsStateWithLifecycle()

    var adding by remember { mutableStateOf(false) }
    var addName by remember { mutableStateOf("") }
    var addPassword by remember { mutableStateOf("") }
    var addCode by remember { mutableStateOf("") }
    var removing by remember { mutableStateOf<String?>(null) }
    var renaming by remember { mutableStateOf<String?>(null) }
    var renameDraft by remember { mutableStateOf("") }

    val needsCode = self.twoFactorEnabled
    val full = !ui.loading && ui.max > 0 && ui.passkeys.size >= ui.max

    LaunchedEffect(Unit) { vm.refreshPasskeys() }

    fun closeAdd() {
        adding = false
        addName = ""
        addPassword = ""
        addCode = ""
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(AppStrings.get(context, R.string.catalog_passkeys_caab7827), color = c.ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Text(
            AppStrings.get(context, R.string.catalog_sign_in_with_your_fingerprint_face_or_ec24e91a) +
                AppStrings.get(context, R.string.catalog_a_passkey_only_works_on_the_site_ca725d27),
            color = c.inkSecondary,
            fontSize = 13.sp,
        )

        val sectionError = ui.error
        if (sectionError != null) {
            Text(sectionError, color = c.danger, fontSize = 13.sp)
        }

        when {
            ui.loading -> Text(AppStrings.get(context, R.string.catalog_loading_33ce4174), color = c.inkMuted, fontSize = 14.sp)
            ui.passkeys.isEmpty() -> Text(AppStrings.get(context, R.string.catalog_no_passkeys_yet_6dc4fe0f), color = c.inkSecondary, fontSize = 14.sp)
            else -> ui.passkeys.forEach { passkey ->
                PasskeyRow(
                    passkey = passkey,
                    needsCode = needsCode,
                    busy = ui.busy,
                    removing = removing == passkey.id,
                    onRemoveOpen = {
                        vm.clearPasskeyError()
                        removing = passkey.id
                    },
                    onRemoveCancel = { removing = null },
                    onRemove = { password, code ->
                        vm.removePasskey(passkey.id, password, code) { removing = null }
                    },
                    onRenameOpen = {
                        vm.clearPasskeyError()
                        renaming = passkey.id
                        renameDraft = passkey.name
                    },
                )
            }
        }

        if (adding) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OrangTextField(
                    value = addName,
                    onValueChange = { addName = it.take(60); vm.clearPasskeyError() },
                    label = AppStrings.get(context, R.string.catalog_name_709a2322),
                    placeholder = AppStrings.get(context, R.string.catalog_personal_phone_a154b2eb),
                    hint = AppStrings.get(context, R.string.catalog_so_you_can_tell_it_apart_from_5878d69c),
                )
                OrangTextField(
                    value = addPassword,
                    onValueChange = { addPassword = it; vm.clearPasskeyError() },
                    label = AppStrings.get(context, R.string.catalog_current_password_19dff4da),
                    isPassword = true,
                )
                if (needsCode) {
                    OrangTextField(
                        value = addCode,
                        onValueChange = { addCode = it.take(32); vm.clearPasskeyError() },
                        label = AppStrings.get(context, R.string.catalog_authenticator_code_2908b4e9),
                        placeholder = "123456",
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OrangButton(
                        text = AppStrings.get(context, R.string.catalog_create_passkey_500022b0),
                        onClick = {
                            vm.addPasskey(context, addName, addPassword, addCode) { closeAdd() }
                        },
                        enabled = addPassword.isNotBlank() && !ui.busy,
                        loading = ui.busy,
                        size = ButtonSize.Sm,
                    )
                    OrangButton(
                        text = AppStrings.get(context, R.string.catalog_cancel_77dfd213),
                        onClick = { closeAdd(); vm.clearPasskeyError() },
                        variant = ButtonVariant.Ghost,
                        size = ButtonSize.Sm,
                    )
                }
            }
        } else {
            OrangButton(
                text = AppStrings.get(context, R.string.catalog_add_a_passkey_0daca495),
                onClick = { vm.clearPasskeyError(); adding = true },
                variant = ButtonVariant.Secondary,
                enabled = !full && !ui.busy,
                size = ButtonSize.Sm,
            )
            if (full) {
                Text(
                    AppStrings.get(context, R.string.catalog_you_ve_reached_the_limit_of_1_e25f813b, ui.max),
                    color = c.inkSecondary,
                    fontSize = 12.sp,
                )
            }
        }
    }

    renaming?.let { id ->
        AlertDialog(
            onDismissRequest = { if (!ui.busy) renaming = null },
            title = { Text(AppStrings.get(context, R.string.catalog_rename_passkey_cb9298c9), color = c.ink) },
            text = {
                OrangTextField(
                    value = renameDraft,
                    onValueChange = { renameDraft = it.take(60) },
                    label = AppStrings.get(context, R.string.catalog_name_709a2322),
                )
            },
            confirmButton = {
                TextButton(
                    enabled = renameDraft.isNotBlank() && !ui.busy,
                    onClick = { vm.renamePasskey(id, renameDraft.trim()) { renaming = null } },
                ) { Text(AppStrings.get(context, R.string.catalog_rename_d3f4cb89), color = c.primary) }
            },
            dismissButton = {
                TextButton(onClick = { renaming = null }) {
                    Text(AppStrings.get(context, R.string.catalog_cancel_77dfd213), color = c.inkSecondary)
                }
            },
        )
    }
}

@Composable
private fun PasskeyRow(
    passkey: Passkey,
    needsCode: Boolean,
    busy: Boolean,
    removing: Boolean,
    onRemoveOpen: () -> Unit,
    onRemoveCancel: () -> Unit,
    onRemove: (String, String) -> Unit,
    onRenameOpen: () -> Unit,
) {
        val context = LocalContext.current
    val c = OrangTheme.colors
    var password by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(c.surface1, RoundedCornerShape(OrangRadius.lg))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(passkey.name.ifBlank { AppStrings.get(context, R.string.catalog_passkey_e35828da) }, color = c.ink, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Text(
                    AppStrings.get(context, R.string.catalog_added_1_s_76044af1, formatFullTime(passkey.createdAt)) +
                        (passkey.lastUsedAt?.let { AppStrings.get(context, R.string.catalog_last_used_1_s_c8f76185, formatFullTime(it)) } ?: AppStrings.get(context, R.string.catalog_never_used_8c1c3645)),
                    color = c.inkSecondary,
                    fontSize = 12.sp,
                )
                if (passkey.backedUp) {
                    Text(AppStrings.get(context, R.string.catalog_synced_to_your_device_s_keychain_4f237bce), color = c.inkSecondary, fontSize = 12.sp)
                }
            }
            Spacer(Modifier.width(8.dp))
            OrangButton(
                text = AppStrings.get(context, R.string.catalog_rename_d3f4cb89),
                onClick = onRenameOpen,
                variant = ButtonVariant.Ghost,
                size = ButtonSize.Sm,
                enabled = !busy,
            )
            OrangButton(
                text = AppStrings.get(context, R.string.catalog_remove_e963907d),
                onClick = onRemoveOpen,
                variant = ButtonVariant.Danger,
                size = ButtonSize.Sm,
                enabled = !busy,
            )
        }

        if (removing) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    AppStrings.get(context, R.string.catalog_removing_this_only_affects_orangchat_delete_it_28979691) +
                        AppStrings.get(context, R.string.catalog_passkey_settings_too_or_it_will_keep_26130742),
                    color = c.inkSecondary,
                    fontSize = 13.sp,
                )
                OrangTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = AppStrings.get(context, R.string.catalog_current_password_19dff4da),
                    isPassword = true,
                )
                if (needsCode) {
                    OrangTextField(
                        value = code,
                        onValueChange = { code = it.take(32) },
                        label = AppStrings.get(context, R.string.catalog_authenticator_code_2908b4e9),
                        placeholder = "123456",
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OrangButton(
                        text = AppStrings.get(context, R.string.catalog_remove_passkey_988b1b7e),
                        onClick = { onRemove(password, code) },
                        variant = ButtonVariant.Danger,
                        enabled = password.isNotBlank() && !busy,
                        loading = busy,
                        size = ButtonSize.Sm,
                    )
                    OrangButton(
                        text = AppStrings.get(context, R.string.catalog_cancel_77dfd213),
                        onClick = onRemoveCancel,
                        variant = ButtonVariant.Ghost,
                        size = ButtonSize.Sm,
                    )
                }
            }
        }
    }
}


@Composable
fun SecurityScreen(
    self: SelfUser,
    hasPassword: Boolean,
    onBack: () -> Unit,
    vm: SettingsViewModel = hiltViewModel(),
) {
        val context = LocalContext.current
    val c = OrangTheme.colors
    val state by vm.twoFactor.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { vm.refreshTwoFactor() }

    Column(modifier = screenModifier(c)) {
        SettingsTopBar(AppStrings.get(context, R.string.catalog_security_f25ce1b8), onBack)
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            AccountStandingSection(vm)

            HorizontalDivider(color = c.border)

            CredentialsSection(self, hasPassword, vm)

            HorizontalDivider(color = c.border)

            PasskeysSection(self, vm)

            HorizontalDivider(color = c.border)

            Text(AppStrings.get(context, R.string.catalog_two_factor_authentication_edfd617a), color = c.ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(
                AppStrings.get(context, R.string.catalog_ask_for_a_code_from_your_phone_b6d62586),
                color = c.inkSecondary,
                fontSize = 13.sp,
            )
            when (val s = state) {
                is TwoFactorUi.Loading -> Text(AppStrings.get(context, R.string.catalog_loading_33ce4174), color = c.inkMuted, fontSize = 14.sp)
                is TwoFactorUi.Off -> TwoFactorEnroll(hasPassword, s.error, vm)
                is TwoFactorUi.Setup -> TwoFactorVerify(s, vm)
                is TwoFactorUi.ShowCodes -> BackupCodes(s.codes) { vm.dismissCodes() }
                is TwoFactorUi.On -> TwoFactorManage(s, hasPassword, vm)
            }

            HorizontalDivider(color = c.border)

            LockdownSection(self, hasPassword, vm)

            HorizontalDivider(color = c.border)

            LeaveAllServersSection(vm)

            HorizontalDivider(color = c.border)

            DeleteAllMessagesSection(self, hasPassword, vm)

            HorizontalDivider(color = c.border)

            DeleteAccountSection(self, hasPassword, vm)
        }
    }
}

@Composable
private fun CredentialsSection(self: SelfUser, hasPassword: Boolean, vm: SettingsViewModel) {
        val context = LocalContext.current
    val c = OrangTheme.colors
    val ui by vm.credentials.collectAsStateWithLifecycle()

    var mode by remember { mutableStateOf<String?>(null) }
    var password by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }

    fun reset() {
        mode = null
        password = ""
        code = ""
        email = ""
        newPassword = ""
        confirm = ""
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(AppStrings.get(context, R.string.catalog_email_password_8973dcae), color = c.ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Text(AppStrings.get(context, R.string.catalog_signed_in_as_1_s_21660e7d, self.email), color = c.inkSecondary, fontSize = 13.sp)

        ui.done?.let { Text(it, color = c.success, fontSize = 13.sp) }

        if (mode == null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OrangButton(
                    text = AppStrings.get(context, R.string.catalog_change_email_8f1514b5),
                    onClick = { vm.clearCredentialsMessages(); mode = "email" },
                    variant = ButtonVariant.Secondary,
                    size = ButtonSize.Sm,
                )
                OrangButton(
                    text = if (hasPassword) AppStrings.get(context, R.string.catalog_change_password_8c684290) else AppStrings.get(context, R.string.catalog_set_a_password_f5c8f412),
                    onClick = { vm.clearCredentialsMessages(); mode = "password" },
                    variant = ButtonVariant.Secondary,
                    size = ButtonSize.Sm,
                )
            }
        } else {
            val mismatch = newPassword.isNotEmpty() && confirm.isNotEmpty() && newPassword != confirm
            val canSubmit = if (mode == "email") {
                email.isNotBlank()
            } else {
                newPassword.length >= 8 && newPassword == confirm
            }

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (mode == "email") {
                    OrangTextField(
                        value = email,
                        onValueChange = { email = it },
                        label = AppStrings.get(context, R.string.catalog_new_email_b07e22b0),
                        hint = AppStrings.get(context, R.string.catalog_used_to_sign_in_there_s_no_51a10c36),
                    )
                } else {
                    OrangTextField(
                        value = newPassword,
                        onValueChange = { newPassword = it },
                        label = AppStrings.get(context, R.string.catalog_new_password_d850ee18),
                        isPassword = true,
                        hint = AppStrings.get(context, R.string.catalog_at_least_8_characters_6089e5f3),
                    )
                    OrangTextField(
                        value = confirm,
                        onValueChange = { confirm = it },
                        label = AppStrings.get(context, R.string.catalog_confirm_new_password_f85039fd),
                        isPassword = true,
                        error = if (mismatch) AppStrings.get(context, R.string.catalog_those_don_t_match_5820ec54) else null,
                    )
                }

                if (hasPassword) {
                    OrangTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = AppStrings.get(context, R.string.catalog_your_current_password_6658f206),
                        isPassword = true,
                    )
                }
                if (self.twoFactorEnabled) {
                    OrangTextField(
                        value = code,
                        onValueChange = { code = it },
                        label = AppStrings.get(context, R.string.catalog_code_from_your_app_or_a_recovery_c6308bc3),
                        placeholder = "123456",
                    )
                }

                if (mode == "password") {
                    Text(
                        AppStrings.get(context, R.string.catalog_changing_your_password_signs_out_every_other_062a10ab),
                        color = c.inkMuted,
                        fontSize = 12.sp,
                    )
                }
                ui.error?.let { Text(it, color = c.danger, fontSize = 13.sp) }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OrangButton(
                        text = if (mode == "email") AppStrings.get(context, R.string.catalog_change_email_8f1514b5) else if (hasPassword) AppStrings.get(context, R.string.catalog_change_password_8c684290) else AppStrings.get(context, R.string.catalog_set_password_94408e41),
                        onClick = {
                            if (mode == "email") {
                                vm.changeEmail(password, email, code) { reset() }
                            } else {
                                vm.changePassword(password, newPassword, code) { reset() }
                            }
                        },
                        enabled = canSubmit && !ui.busy,
                        loading = ui.busy,
                        size = ButtonSize.Sm,
                    )
                    OrangButton(
                        text = AppStrings.get(context, R.string.catalog_cancel_77dfd213),
                        onClick = { reset() },
                        variant = ButtonVariant.Ghost,
                        size = ButtonSize.Sm,
                    )
                }
            }
        }
    }
}

@Composable
private fun AccountStandingSection(vm: SettingsViewModel) {
        val context = LocalContext.current
    val c = OrangTheme.colors
    val state by vm.standing.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { vm.refreshStanding() }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(AppStrings.get(context, R.string.catalog_account_standing_2b18a30b), color = c.ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)

        when (val s = state) {
            is StandingUi.Loading -> Text(AppStrings.get(context, R.string.catalog_loading_33ce4174), color = c.inkMuted, fontSize = 14.sp)
            is StandingUi.Failed -> Text(s.error, color = c.danger, fontSize = 13.sp)
            is StandingUi.Loaded -> if (s.standing.good) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(OrangRadius.lg))
                        .background(c.success.copy(alpha = 0.10f))
                        .padding(12.dp),
                ) {
                    Text(
                        AppStrings.get(context, R.string.catalog_your_account_is_in_good_standing_1806a501),
                        color = c.ink,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        AppStrings.get(context, R.string.catalog_no_server_is_currently_restricting_you_061000ea),
                        color = c.inkSecondary,
                        fontSize = 12.sp,
                    )
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    s.standing.entries.forEach { entry ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(OrangRadius.lg))
                                .background(c.danger.copy(alpha = 0.10f))
                                .padding(12.dp),
                        ) {
                            val verb = if (entry.kind == "ban") AppStrings.get(context, R.string.catalog_banned_from_b1b7bda2) else AppStrings.get(context, R.string.catalog_timed_out_in_64510a0d)
                            Text(
                                AppStrings.get(context, R.string.catalog_1_s_2_s_78c505fe, verb, entry.serverName),
                                color = c.ink,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                            )
                            entry.reason?.let {
                                Text(AppStrings.get(context, R.string.catalog_reason_1_s_bce5b2c7, it), color = c.inkSecondary, fontSize = 12.sp)
                            }
                            entry.expiresAt?.let {
                                Text(AppStrings.get(context, R.string.catalog_until_1_s_5ec80ce8, formatFullTime(it)), color = c.inkSecondary, fontSize = 12.sp)
                            }
                            if (entry.kind == "ban") {
                                entry.createdAt?.let {
                                    Text(formatFullTime(it), color = c.inkMuted, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LockdownSection(self: SelfUser, hasPassword: Boolean, vm: SettingsViewModel) {
        val context = LocalContext.current
    val c = OrangTheme.colors
    val ui by vm.lockdown.collectAsStateWithLifecycle()
    val locked = self.lockdown

    var confirming by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(AppStrings.get(context, R.string.catalog_lockdown_8ba04349), color = c.ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)

        if (locked) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(OrangRadius.lg))
                    .background(c.warning.copy(alpha = 0.10f))
                    .padding(12.dp),
            ) {
                Text(
                    AppStrings.get(context, R.string.catalog_your_account_is_locked_down_d129bf10),
                    color = c.ink,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    AppStrings.get(context, R.string.catalog_nothing_can_sign_in_and_no_new_e0c687ac) +
                        AppStrings.get(context, R.string.catalog_this_device_stays_signed_in_795f19a1),
                    color = c.inkSecondary,
                    fontSize = 12.sp,
                )
            }
        } else {
            Text(
                AppStrings.get(context, R.string.catalog_freezes_the_account_if_you_think_someone_09f45852) +
                    AppStrings.get(context, R.string.catalog_other_device_blocks_new_sign_ins_and_6199dab7),
                color = c.inkSecondary,
                fontSize = 13.sp,
            )
        }

        ui.done?.let { Text(it, color = c.success, fontSize = 13.sp) }
        ui.error?.let { Text(it, color = c.danger, fontSize = 13.sp) }

        if (confirming) {
            if (locked && hasPassword) {
                OrangTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = AppStrings.get(context, R.string.catalog_your_password_26d745d4),
                    isPassword = true,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OrangButton(
                    text = if (locked) AppStrings.get(context, R.string.catalog_lift_lockdown_4131d82c) else AppStrings.get(context, R.string.catalog_lock_down_my_account_c7b52afc),
                    onClick = {
                        vm.setLockdown(!locked, password) {
                            confirming = false
                            password = ""
                        }
                    },
                    variant = if (locked) ButtonVariant.Primary else ButtonVariant.Danger,
                    size = ButtonSize.Sm,
                    enabled = !ui.busy,
                    loading = ui.busy,
                )
                OrangButton(
                    text = AppStrings.get(context, R.string.catalog_cancel_77dfd213),
                    onClick = { confirming = false; password = "" },
                    variant = ButtonVariant.Ghost,
                    size = ButtonSize.Sm,
                )
            }
        } else {
            OrangButton(
                text = if (locked) AppStrings.get(context, R.string.catalog_lift_lockdown_4131d82c) else AppStrings.get(context, R.string.catalog_lock_down_my_account_c7b52afc),
                onClick = { vm.resetLockdown(); confirming = true },
                variant = if (locked) ButtonVariant.Primary else ButtonVariant.Secondary,
                size = ButtonSize.Sm,
            )
        }
    }
}

@Composable
private fun LeaveAllServersSection(vm: SettingsViewModel) {
        val context = LocalContext.current
    val c = OrangTheme.colors
    val state by vm.leaveAll.collectAsStateWithLifecycle()
    var confirming by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(AppStrings.get(context, R.string.catalog_leave_all_servers_65e7741b), color = c.ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Text(
            AppStrings.get(context, R.string.catalog_leaves_every_server_you_re_in_except_bcbf7a17) +
                AppStrings.get(context, R.string.catalog_invite_to_get_back_into_any_of_016b015f),
            color = c.inkSecondary,
            fontSize = 13.sp,
        )

        when (val s = state) {
            is LeaveAllUi.Done -> {
                val kept = if (s.keptOwned.isEmpty()) "" else AppStrings.get(context, R.string.catalog_still_yours_1_s_42da6dc4, s.keptOwned.joinToString(", "))
                Text(
                    if (s.left == 1) AppStrings.get(context, R.string.catalog_left_1_s_server_2619a08f, s.left) + kept
                    else AppStrings.get(context, R.string.catalog_left_1_s_servers_8833549c, s.left) + kept,
                    color = c.success,
                    fontSize = 13.sp,
                )
            }
            is LeaveAllUi.Failed -> Text(s.error, color = c.danger, fontSize = 13.sp)
            else -> Unit
        }

        if (confirming) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OrangButton(
                    text = AppStrings.get(context, R.string.catalog_yes_leave_them_all_8520c6e8),
                    onClick = { vm.leaveAllServers(); confirming = false },
                    variant = ButtonVariant.Danger,
                    size = ButtonSize.Sm,
                    loading = state is LeaveAllUi.Busy,
                )
                OrangButton(
                    text = AppStrings.get(context, R.string.catalog_cancel_77dfd213),
                    onClick = { confirming = false },
                    variant = ButtonVariant.Ghost,
                    size = ButtonSize.Sm,
                )
            }
        } else {
            OrangButton(
                text = AppStrings.get(context, R.string.catalog_leave_all_servers_65e7741b),
                onClick = { vm.resetLeaveAll(); confirming = true },
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Sm,
            )
        }
    }
}

@Composable
private fun DeleteAllMessagesSection(self: SelfUser, hasPassword: Boolean, vm: SettingsViewModel) {
        val context = LocalContext.current
    val c = OrangTheme.colors
    val ui by vm.wipe.collectAsStateWithLifecycle()

    var open by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            AppStrings.get(context, R.string.catalog_delete_all_your_messages_f9463fad),
            color = c.ink,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            AppStrings.get(context, R.string.catalog_removes_every_message_you_ve_sent_in_f63902f1) +
                AppStrings.get(context, R.string.catalog_the_ones_you_ve_left_attachments_you_87419c2e),
            color = c.inkSecondary,
            fontSize = 13.sp,
        )

        ui.done?.let { Text(it, color = c.success, fontSize = 13.sp) }

        if (!open) {
            OrangButton(
                text = AppStrings.get(context, R.string.catalog_delete_all_my_messages_3549d600),
                onClick = { vm.resetWipe(); open = true },
                variant = ButtonVariant.Ghost,
                size = ButtonSize.Sm,
            )
        } else {
            if (hasPassword) {
                OrangTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = AppStrings.get(context, R.string.catalog_your_password_26d745d4),
                    isPassword = true,
                )
            }
            if (self.twoFactorEnabled) {
                OrangTextField(
                    value = code,
                    onValueChange = { code = it },
                    label = AppStrings.get(context, R.string.catalog_code_from_your_app_or_a_recovery_c6308bc3),
                    placeholder = "123456",
                )
            }
            ui.error?.let { Text(it, color = c.danger, fontSize = 13.sp) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OrangButton(
                    text = AppStrings.get(context, R.string.catalog_delete_them_all_a3132ab8),
                    onClick = {
                        vm.deleteAllMessages(password, code) {
                            open = false
                            password = ""
                            code = ""
                        }
                    },
                    variant = ButtonVariant.Danger,
                    size = ButtonSize.Sm,
                    enabled = !ui.busy,
                    loading = ui.busy,
                )
                OrangButton(
                    text = AppStrings.get(context, R.string.catalog_cancel_77dfd213),
                    onClick = { open = false; password = ""; code = "" },
                    variant = ButtonVariant.Ghost,
                    size = ButtonSize.Sm,
                )
            }
        }
    }
}

@Composable
private fun DeleteAccountSection(self: SelfUser, hasPassword: Boolean, vm: SettingsViewModel) {
        val context = LocalContext.current
    val c = OrangTheme.colors
    val ui by vm.credentials.collectAsStateWithLifecycle()

    var open by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(AppStrings.get(context, R.string.catalog_delete_account_1753c206), color = c.ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Text(
            AppStrings.get(context, R.string.catalog_your_messages_stay_in_the_conversations_they_34246f3c) +
                AppStrings.get(context, R.string.catalog_deleted_user_everything_else_profile_connections_friends_6e1c6e09),
            color = c.inkSecondary,
            fontSize = 13.sp,
        )

        if (!open) {
            OrangButton(
                text = AppStrings.get(context, R.string.catalog_delete_my_account_2ae3a019),
                onClick = { vm.clearCredentialsMessages(); open = true },
                variant = ButtonVariant.Ghost,
                size = ButtonSize.Sm,
            )
        } else {
            OrangTextField(
                value = username,
                onValueChange = { username = it },
                label = AppStrings.get(context, R.string.catalog_type_1_s_to_confirm_700d64a1, self.username),
            )
            if (hasPassword) {
                OrangTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = AppStrings.get(context, R.string.catalog_your_password_26d745d4),
                    isPassword = true,
                )
            }
            if (self.twoFactorEnabled) {
                OrangTextField(
                    value = code,
                    onValueChange = { code = it },
                    label = AppStrings.get(context, R.string.catalog_code_from_your_app_or_a_recovery_c6308bc3),
                    placeholder = "123456",
                )
            }
            ui.error?.let { Text(it, color = c.danger, fontSize = 13.sp) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OrangButton(
                    text = AppStrings.get(context, R.string.catalog_permanently_delete_2eea1fa8),
                    onClick = { vm.deleteAccount(password, username, code) },
                    variant = ButtonVariant.Danger,
                    size = ButtonSize.Sm,
                    enabled = username == self.username && !ui.busy,
                    loading = ui.busy,
                )
                OrangButton(
                    text = AppStrings.get(context, R.string.catalog_cancel_77dfd213),
                    onClick = { open = false; username = ""; password = ""; code = "" },
                    variant = ButtonVariant.Ghost,
                    size = ButtonSize.Sm,
                )
            }
        }
    }
}

@Composable
private fun TwoFactorEnroll(hasPassword: Boolean, error: String?, vm: SettingsViewModel) {
        val context = LocalContext.current
    val c = OrangTheme.colors
    var password by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            AppStrings.get(context, R.string.catalog_you_ll_need_an_authenticator_app_such_60b7dd86),
            color = c.inkSecondary,
            fontSize = 13.sp,
        )
        if (hasPassword) {
            OrangTextField(
                value = password,
                onValueChange = { password = it },
                label = AppStrings.get(context, R.string.catalog_confirm_your_password_198b73ff),
                isPassword = true,
            )
        }
        if (error != null) Text(error, color = c.danger, fontSize = 13.sp)
        OrangButton(text = AppStrings.get(context, R.string.catalog_set_up_two_factor_a6722c51), onClick = { vm.beginSetup(password) })
    }
}

@Composable
private fun TwoFactorVerify(state: TwoFactorUi.Setup, vm: SettingsViewModel) {
        val context = LocalContext.current
    val c = OrangTheme.colors
    val clipboard = LocalClipboardManager.current
    var code by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SettingSection(AppStrings.get(context, R.string.catalog_1_add_this_key_to_your_app_c5c6bb28)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(c.surface1, RoundedCornerShape(OrangRadius.lg))
                    .clickable { clipboard.setText(AnnotatedString(state.setup.secret)) }
                    .padding(14.dp),
            ) {
                Text(
                    state.setup.secret,
                    color = c.ink,
                    fontSize = 15.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
            Text(AppStrings.get(context, R.string.catalog_tap_the_key_to_copy_it_1965f6e7), color = c.inkMuted, fontSize = 12.sp)
        }
        SettingSection(AppStrings.get(context, R.string.catalog_2_enter_the_6_digit_code_183ee7cb)) {
            OrangTextField(
                value = code,
                onValueChange = { code = it.filter(Char::isDigit).take(6) },
                label = AppStrings.get(context, R.string.catalog_code_from_your_app_2e089607),
                placeholder = "123456",
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
        }
        if (state.error != null) Text(state.error, color = c.danger, fontSize = 13.sp)
        OrangButton(
            text = AppStrings.get(context, R.string.catalog_turn_on_two_factor_2d7b5797),
            onClick = { vm.confirmSetup(code) },
            enabled = code.length == 6,
            loading = state.verifying,
        )
    }
}

@Composable
private fun TwoFactorManage(state: TwoFactorUi.On, hasPassword: Boolean, vm: SettingsViewModel) {
        val context = LocalContext.current
    val c = OrangTheme.colors
    var mode by remember { mutableStateOf("idle") }
    var password by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(c.primarySoft, RoundedCornerShape(OrangRadius.lg))
                .padding(14.dp),
        ) {
            Column {
                Text(AppStrings.get(context, R.string.catalog_two_factor_is_on_65e04e1d), color = c.ink, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Text(
                    if (state.backupCodesRemaining == 1)
                        AppStrings.get(context, R.string.catalog_1_s_recovery_code_left_fb553535, state.backupCodesRemaining)
                    else AppStrings.get(context, R.string.catalog_1_s_recovery_codes_left_da20cfb2, state.backupCodesRemaining),
                    color = c.inkSecondary,
                    fontSize = 12.sp,
                )
            }
        }

        if (mode == "idle") {
            OrangButton(
                text = AppStrings.get(context, R.string.catalog_new_recovery_codes_90b8afc8),
                onClick = { mode = "regen" },
                variant = ButtonVariant.Secondary,
            )
            OrangButton(
                text = AppStrings.get(context, R.string.catalog_turn_off_two_factor_ec64c330),
                onClick = { mode = "disable" },
                variant = ButtonVariant.Danger,
            )
        } else {
            val disabling = mode == "disable"
            Text(
                if (disabling)
                    AppStrings.get(context, R.string.catalog_turning_off_two_factor_leaves_your_password_7c7e274a)
                else AppStrings.get(context, R.string.catalog_this_replaces_all_of_your_existing_recovery_1ee473c2),
                color = c.inkSecondary,
                fontSize = 13.sp,
            )
            if (hasPassword) {
                OrangTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = AppStrings.get(context, R.string.catalog_your_password_26d745d4),
                    isPassword = true,
                )
            }
            OrangTextField(
                value = code,
                onValueChange = { code = it.take(32) },
                label = if (disabling) AppStrings.get(context, R.string.catalog_code_from_your_app_or_a_recovery_c6308bc3) else AppStrings.get(context, R.string.catalog_code_from_your_app_2e089607),
                placeholder = "123456",
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            if (state.error != null) Text(state.error, color = c.danger, fontSize = 13.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OrangButton(
                    text = if (disabling) AppStrings.get(context, R.string.catalog_turn_off_8807c2b3) else AppStrings.get(context, R.string.catalog_generate_fc45f9b7),
                    onClick = {
                        if (disabling) vm.disable(password, code) else vm.regenerateCodes(password, code)
                    },
                    variant = if (disabling) ButtonVariant.Danger else ButtonVariant.Primary,
                    enabled = code.isNotBlank(),
                    loading = state.busy,
                )
                OrangButton(
                    text = AppStrings.get(context, R.string.catalog_cancel_77dfd213),
                    onClick = { mode = "idle"; password = ""; code = "" },
                    variant = ButtonVariant.Ghost,
                )
            }
        }
    }
}

@Composable
private fun BackupCodes(codes: List<String>, onDone: () -> Unit) {
        val context = LocalContext.current
    val c = OrangTheme.colors
    val clipboard = LocalClipboardManager.current
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(AppStrings.get(context, R.string.catalog_recovery_codes_b3f5f8ca), color = c.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Text(
            AppStrings.get(context, R.string.catalog_save_these_now_each_works_once_if_2a1aad82),
            color = c.inkSecondary,
            fontSize = 13.sp,
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(c.surface1, RoundedCornerShape(OrangRadius.lg))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            codes.forEach { code ->
                Text(code, color = c.ink, fontSize = 15.sp, fontFamily = FontFamily.Monospace)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OrangButton(
                text = AppStrings.get(context, R.string.catalog_copy_af74f7c5),
                onClick = { clipboard.setText(AnnotatedString(codes.joinToString("\n"))) },
                variant = ButtonVariant.Secondary,
            )
            OrangButton(text = AppStrings.get(context, R.string.catalog_i_ve_saved_them_d4c0b9ed), onClick = onDone)
        }
    }
}


@Composable
fun AccessibilityScreen(onBack: () -> Unit, vm: SettingsViewModel = hiltViewModel()) {
        val context = LocalContext.current
    val c = OrangTheme.colors
    val prefs by vm.prefs.collectAsStateWithLifecycle()

    Column(modifier = screenModifier(c)) {
        SettingsTopBar(AppStrings.get(context, R.string.catalog_accessibility_d660049b), onBack)
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            SettingSection(AppStrings.get(context, R.string.catalog_text_size_3cc6e124)) {
                Text(
                    "${(prefs.fontScale * 100).toInt()}%",
                    color = c.inkSecondary,
                    fontSize = 13.sp,
                )
                Slider(
                    value = prefs.fontScale,
                    onValueChange = vm::setFontScale,
                    valueRange = FONT_SCALE_MIN..FONT_SCALE_MAX,
                    colors = SliderDefaults.colors(
                        thumbColor = c.primary,
                        activeTrackColor = c.primary,
                        inactiveTrackColor = c.surface4,
                    ),
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(c.surface3, RoundedCornerShape(OrangRadius.lg))
                        .padding(14.dp),
                ) {
                    Text(
                        AppStrings.get(context, R.string.catalog_the_quick_brown_fox_jumps_over_the_408d9438),
                        color = c.ink,
                        fontSize = (15 * prefs.fontScale).sp,
                    )
                }
            }
            SettingSection(AppStrings.get(context, R.string.catalog_display_574ff9b0)) {
                SettingsToggleRow(
                    label = AppStrings.get(context, R.string.catalog_compact_messages_8efe5e6b),
                    hint = AppStrings.get(context, R.string.catalog_tighter_spacing_between_messages_58e3bf31),
                    checked = prefs.compactMessages,
                    onCheckedChange = vm::setCompactMessages,
                )
                SettingsToggleRow(
                    label = AppStrings.get(context, R.string.catalog_reduce_motion_25a5aef5),
                    hint = AppStrings.get(context, R.string.catalog_skip_the_scroll_and_swipe_animations_3bb4a091),
                    checked = prefs.reducedMotion,
                    onCheckedChange = vm::setReducedMotion,
                )
            }
            OrangButton(
                text = AppStrings.get(context, R.string.catalog_reset_to_defaults_ddefe47d),
                onClick = vm::resetPrefs,
                variant = ButtonVariant.Secondary,
            )
        }
    }
}


@Composable
fun SystemScreen(
    connected: Boolean,
    onBack: () -> Unit,
    vm: SettingsViewModel = hiltViewModel(),
) {
        val context = LocalContext.current
    val c = OrangTheme.colors
    val backend by vm.backend.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.refreshBackend() }
    Column(modifier = screenModifier(c)) {
        SettingsTopBar(AppStrings.get(context, R.string.catalog_system_bc0792d8), onBack)
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            SettingSection(AppStrings.get(context, R.string.catalog_connection_6512ee15)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(c.surface3, RoundedCornerShape(OrangRadius.lg))
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .background(if (connected) c.success else c.danger, RoundedCornerShape(5.dp)),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        if (connected) AppStrings.get(context, R.string.catalog_connected_c2f9b7b4) else AppStrings.get(context, R.string.catalog_disconnected_771e05f2),
                        color = c.ink,
                        fontSize = 14.sp,
                    )
                }
            }
            SettingSection(AppStrings.get(context, R.string.catalog_server_cb0cb170)) {
                InfoRow(
                    AppStrings.get(context, R.string.catalog_backend_e758ca64),
                    when (val b = backend) {
                        is BackendUi.Loading -> AppStrings.get(context, R.string.catalog_checking_820d6004)
                        is BackendUi.Loaded -> AppStrings.get(context, R.string.catalog_v_1_s_2682e065, b.version)
                        is BackendUi.Unknown -> AppStrings.get(context, R.string.catalog_unknown_bc7819b3)
                    },
                )
                InfoRow(AppStrings.get(context, R.string.catalog_api_d93d10ff), BuildConfig.API_BASE_URL)
                InfoRow(AppStrings.get(context, R.string.catalog_realtime_5973df61), BuildConfig.SOCKET_URL)
            }
        }
    }
}


@Composable
fun AboutScreen(appIconUrl: String?, onBack: () -> Unit) {
        val context = LocalContext.current
    val c = OrangTheme.colors
    Column(modifier = screenModifier(c)) {
        SettingsTopBar(AppStrings.get(context, R.string.catalog_about_6b21fb79), onBack)
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AppMark(appIconUrl)
                Text(AppStrings.get(context, R.string.app_name), color = c.ink, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text(AppStrings.get(context, R.string.catalog_version_1_s_d2f210e6, BuildConfig.VERSION_NAME), color = c.inkMuted, fontSize = 13.sp)
                Text(
                    AppStrings.get(context, R.string.catalog_a_fast_self_hosted_chat_for_servers_fa05634c),
                    color = c.inkSecondary,
                    fontSize = 13.sp,
                )
            }
            SettingSection(AppStrings.get(context, R.string.catalog_updates_c76d1807)) { UpdateSection() }
            SettingSection(AppStrings.get(context, R.string.catalog_build_bbd80cf7)) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(c.surface3, RoundedCornerShape(OrangRadius.lg))
                        .padding(horizontal = 14.dp),
                ) {
                    InfoRow(AppStrings.get(context, R.string.catalog_version_2da600bf), BuildConfig.VERSION_NAME)
                    InfoRow(AppStrings.get(context, R.string.catalog_build_bbd80cf7), if (BuildConfig.DEBUG) AppStrings.get(context, R.string.catalog_debug_bd604d99) else AppStrings.get(context, R.string.catalog_release_d41f56ce))
                    InfoRow(AppStrings.get(context, R.string.catalog_package_7431e3df), BuildConfig.APPLICATION_ID)
                    InfoRow(AppStrings.get(context, R.string.catalog_client_1bdd79b1), AppStrings.get(context, R.string.catalog_android_native_a2dee752))
                }
            }
            Text(
                AppStrings.get(context, R.string.catalog_2026_oranges_lt_made_with_c3b7fea5),
                color = c.inkMuted,
                fontSize = 12.sp,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun AppMark(appIconUrl: String?) {
    val markModifier = Modifier.size(64.dp).clip(RoundedCornerShape(OrangRadius.lg))
    if (!appIconUrl.isNullOrBlank()) {
        AsyncImage(
            model = absoluteUrl(appIconUrl),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = markModifier,
        )
    } else {
        Image(
            painter = painterResource(R.mipmap.ic_launcher),
            contentDescription = null,
            modifier = markModifier,
        )
    }
}

@Composable
private fun UpdateSection() {
    val c = OrangTheme.colors
    val vm: UpdateViewModel = hiltViewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(c.surface3, RoundedCornerShape(OrangRadius.lg))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        val status = when (val s = state) {
            is UpdateUiState.Idle -> AppStrings.get(context, R.string.catalog_version_1_s_build_2_s_e3646b68, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)
            is UpdateUiState.Checking -> AppStrings.get(context, R.string.catalog_checking_820d6004)
            is UpdateUiState.UpToDate -> AppStrings.get(context, R.string.catalog_you_re_on_the_latest_version_182b7c69)
            is UpdateUiState.Available -> AppStrings.get(context, R.string.catalog_version_1_s_is_available_e41095b0, s.manifest.versionName)
            is UpdateUiState.Downloading -> AppStrings.get(context, R.string.catalog_downloading_1_s_66b84142, s.manifest.versionName)
            is UpdateUiState.ReadyToInstall -> AppStrings.get(context, R.string.catalog_follow_the_installer_to_finish_updating_2742868e)
            is UpdateUiState.Failed -> s.message
        }
        Text(
            status,
            color = if (state is UpdateUiState.Failed) c.danger else c.inkSecondary,
            fontSize = 13.sp,
        )

        (state as? UpdateUiState.Downloading)?.let { s ->
            LinearProgressIndicator(
                progress = { s.progress },
                color = c.primary,
                trackColor = c.surface1,
                modifier = Modifier.fillMaxWidth().height(3.dp),
            )
        }

        when (val s = state) {
            is UpdateUiState.Available ->
                OrangButton(
                    text = if (vm.canInstall()) AppStrings.get(context, R.string.catalog_download_install_7db0bddc) else AppStrings.get(context, R.string.catalog_allow_installs_first_a339d45d),
                    onClick = {
                        if (vm.canInstall()) vm.download(s.manifest)
                        else context.startActivity(vm.installPermissionIntent())
                    },
                )

            is UpdateUiState.Downloading -> Unit

            else ->
                OrangButton(
                    text = AppStrings.get(context, R.string.catalog_check_for_updates_736b9062),
                    variant = ButtonVariant.Secondary,
                    loading = state is UpdateUiState.Checking,
                    onClick = vm::check,
                )
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    val c = OrangTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = c.inkSecondary, fontSize = 14.sp)
        Spacer(Modifier.weight(1f))
        Text(value, color = c.ink, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
    }
    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(c.border))
}
