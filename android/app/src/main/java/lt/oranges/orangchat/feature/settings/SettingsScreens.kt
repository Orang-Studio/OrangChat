package lt.oranges.orangchat.feature.settings

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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import lt.oranges.orangchat.BuildConfig
import lt.oranges.orangchat.data.model.DmPrivacy
import lt.oranges.orangchat.data.model.FriendRequestPrivacy
import lt.oranges.orangchat.data.model.SelfUser
import lt.oranges.orangchat.data.remote.Passkey
import lt.oranges.orangchat.feature.e2ee.EncryptionExplainerDialog
import lt.oranges.orangchat.feature.e2ee.HowEncryptionWorksLink
import lt.oranges.orangchat.feature.updates.UpdateUiState
import lt.oranges.orangchat.feature.updates.UpdateViewModel
import lt.oranges.orangchat.ui.components.ButtonSize
import lt.oranges.orangchat.ui.components.ButtonVariant
import lt.oranges.orangchat.ui.components.OrangButton
import lt.oranges.orangchat.ui.components.OrangTextField
import lt.oranges.orangchat.ui.theme.OrangRadius
import lt.oranges.orangchat.util.formatFullTime
import lt.oranges.orangchat.ui.theme.OrangTheme

private fun screenModifier(c: lt.oranges.orangchat.ui.theme.OrangColors) =
    Modifier.fillMaxSize().background(c.surface2)

// ── Privacy ─────────────────────────────────────────────

@Composable
fun PrivacyScreen(self: SelfUser, onBack: () -> Unit, vm: SettingsViewModel = hiltViewModel()) {
    val c = OrangTheme.colors
    val error by vm.privacyError.collectAsStateWithLifecycle()
    val prefs by vm.prefs.collectAsStateWithLifecycle()
    var explainerOpen by remember { mutableStateOf(false) }

    Column(modifier = screenModifier(c)) {
        SettingsTopBar("Privacy", onBack)
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            SettingSection("Who can message you") {
                SettingsChoiceRow(
                    value = self.dmPrivacy,
                    options = listOf(
                        Triple(DmPrivacy.EVERYONE, "Everyone", "Anyone can start a conversation."),
                        Triple(DmPrivacy.FRIENDS, "Friends only", "Only people on your friends list."),
                        Triple(DmPrivacy.NONE, "No one", "Nobody new can message you."),
                    ),
                    onSelect = vm::setDmPrivacy,
                )
            }
            SettingSection("Who can add you") {
                SettingsChoiceRow(
                    value = self.friendRequestPrivacy,
                    options = listOf(
                        Triple(FriendRequestPrivacy.EVERYONE, "Everyone", "Anyone who knows your username."),
                        Triple(FriendRequestPrivacy.MUTUAL, "Friends of friends", "Only people you share a friend with."),
                        Triple(FriendRequestPrivacy.NONE, "No one", "Nobody can send you requests."),
                    ),
                    onSelect = vm::setFriendRequestPrivacy,
                )
            }
            SettingSection("What you share") {
                SettingsToggleRow(
                    label = "Send typing indicators",
                    hint = "Let people see when you're typing.",
                    checked = self.typingIndicators,
                    onCheckedChange = vm::setTypingIndicators,
                )
            }
            SettingSection("Notifications") {
                SettingsToggleRow(
                    label = "Show message text",
                    hint = "Off, the shade and lock screen show who wrote and nothing more. Encrypted messages are then never unlocked to make a notification at all. This phone only.",
                    checked = prefs.notificationPreviews,
                    onCheckedChange = vm::setNotificationPreviews,
                )
            }
            SettingSection("Encryption") {
                Text(
                    "Every direct message is encrypted, always, and that part cannot be switched off. This setting is about how carefully the other person's lock is checked before your messages are sent to it.",
                    color = c.inkSecondary,
                    fontSize = 13.sp,
                )
                SettingsToggleRow(
                    label = "Check people before messaging them",
                    hint = "With someone new, your messages wait on this phone - locked, sent nowhere - until you have seen their code in person or read the numbers to each other on a call.",
                    checked = self.e2eeStrict,
                    onCheckedChange = vm::setE2eeStrict,
                )
                Text(
                    "Leaving it off is not \"unprotected\". Every lock is still checked against a logbook that can only be added to, which your own devices read on every start. The difference is whether a swapped lock is stopped before it can be used, or caught right after. Group conversations always send straight away, either way.",
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

// ── Camera & Microphone ─────────────────────────────────

@Composable
fun SharingScreen(onBack: () -> Unit, vm: SettingsViewModel = hiltViewModel()) {
    val c = OrangTheme.colors
    val prefs by vm.prefs.collectAsStateWithLifecycle()

    Column(modifier = screenModifier(c)) {
        SettingsTopBar("Camera & Microphone", onBack)
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text(
                "OrangChat only opens your camera and microphone during a call. Android asks " +
                    "for permission the first time you join one.",
                color = c.inkSecondary,
                fontSize = 13.sp,
            )
            SettingSection("When joining a call") {
                SettingsToggleRow(
                    label = "Join muted",
                    hint = "Start every call with your microphone off.",
                    checked = prefs.joinMuted,
                    onCheckedChange = vm::setJoinMuted,
                )
                SettingsToggleRow(
                    label = "Join with camera on",
                    hint = "Start calls with video already publishing.",
                    checked = prefs.joinWithVideo,
                    onCheckedChange = vm::setJoinWithVideo,
                )
            }
            Text(
                "These choices are stored on this device only.",
                color = c.inkMuted,
                fontSize = 12.sp,
            )
        }
    }
}

// ── Devices ─────────────────────────────────────────────

/**
 * Best-effort device name from a User-Agent. Deliberately coarse: the string is
 * attacker-controlled and only ever a label, so it's matched against a short
 * list of substrings rather than parsed.
 */
private fun describeDevice(userAgent: String?): String {
    if (userAgent.isNullOrBlank()) return "Unknown device"
    val ua = userAgent.lowercase()
    return when {
        ua.contains("orangchat-android") -> "OrangChat for Android"
        ua.contains("electron") -> "OrangChat desktop app"
        ua.contains("android") -> "Android browser"
        ua.contains("iphone") || ua.contains("ipad") -> "iOS browser"
        else -> {
            val browser = when {
                ua.contains("firefox") -> "Firefox"
                ua.contains("edg/") -> "Edge"
                ua.contains("chrome") -> "Chrome"
                ua.contains("safari") -> "Safari"
                else -> "Browser"
            }
            val os = when {
                ua.contains("windows") -> "Windows"
                ua.contains("mac os") || ua.contains("macintosh") -> "macOS"
                ua.contains("linux") -> "Linux"
                else -> null
            }
            if (os != null) "$browser on $os" else browser
        }
    }
}

/**
 * Live sessions, one per signed-in device. A session is a refresh token, so
 * revoking one stops that device renewing - it keeps working until its current
 * access token expires, which is minutes, not indefinitely.
 */
@Composable
fun DevicesScreen(onBack: () -> Unit, vm: SettingsViewModel = hiltViewModel()) {
    val c = OrangTheme.colors
    val state by vm.sessions.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { vm.refreshSessions() }

    Column(modifier = screenModifier(c)) {
        SettingsTopBar("Devices", onBack)
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Each entry is a device with a live sign-in. Revoking one stops it " +
                    "renewing; it loses access within a few minutes.",
                color = c.inkSecondary,
                fontSize = 13.sp,
            )

            when (val s = state) {
                is SessionsUi.Loading -> Text("Loading…", color = c.inkMuted, fontSize = 14.sp)
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
                                    describeDevice(session.userAgent) +
                                        if (session.current) "  ·  This device" else "",
                                    color = c.ink,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium,
                                )
                                Text(
                                    buildString {
                                        append(session.ip ?: "unknown IP")
                                        session.lastSeenAt?.let {
                                            append(" · last active ")
                                            append(formatFullTime(it))
                                        }
                                    },
                                    color = c.inkMuted,
                                    fontSize = 12.sp,
                                )
                                session.createdAt?.let {
                                    Text(
                                        "Signed in ${formatFullTime(it)}",
                                        color = c.inkMuted,
                                        fontSize = 12.sp,
                                    )
                                }
                            }
                            OrangButton(
                                text = if (session.current) "Sign out" else "Revoke",
                                onClick = { vm.revokeSession(session.id) },
                                variant = ButtonVariant.Ghost,
                                size = ButtonSize.Sm,
                            )
                        }
                    }

                    val others = s.sessions.count { !it.current }
                    if (others > 0) {
                        OrangButton(
                            text = "Sign out $others other device${if (others == 1) "" else "s"}",
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

// ── Passkeys ─────────────────────────────────────────────

@Composable
private fun PasskeysSection(self: SelfUser, vm: SettingsViewModel) {
    val c = OrangTheme.colors
    // Credential Manager raises its sheet over the Activity, so the ceremony
    // needs this context rather than the application one.
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
        Text("Passkeys", color = c.ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Text(
            "Sign in with your fingerprint, face or device PIN instead of a password. " +
                "A passkey only works on the site that created it, so it can't be phished - " +
                "which is why we ask for one instead of an emailed code when your account has one.",
            color = c.inkSecondary,
            fontSize = 13.sp,
        )

        val sectionError = ui.error
        if (sectionError != null) {
            Text(sectionError, color = c.danger, fontSize = 13.sp)
        }

        when {
            ui.loading -> Text("Loading…", color = c.inkMuted, fontSize = 14.sp)
            ui.passkeys.isEmpty() -> Text("No passkeys yet.", color = c.inkSecondary, fontSize = 14.sp)
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
                    label = "Name",
                    placeholder = "Personal phone",
                    hint = "So you can tell it apart from your other devices later.",
                )
                OrangTextField(
                    value = addPassword,
                    onValueChange = { addPassword = it; vm.clearPasskeyError() },
                    label = "Current password",
                    isPassword = true,
                )
                if (needsCode) {
                    OrangTextField(
                        value = addCode,
                        onValueChange = { addCode = it.take(32); vm.clearPasskeyError() },
                        label = "Authenticator code",
                        placeholder = "123456",
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OrangButton(
                        text = "Create passkey",
                        onClick = {
                            vm.addPasskey(context, addName, addPassword, addCode) { closeAdd() }
                        },
                        enabled = addPassword.isNotBlank() && !ui.busy,
                        loading = ui.busy,
                        size = ButtonSize.Sm,
                    )
                    OrangButton(
                        text = "Cancel",
                        onClick = { closeAdd(); vm.clearPasskeyError() },
                        variant = ButtonVariant.Ghost,
                        size = ButtonSize.Sm,
                    )
                }
            }
        } else {
            OrangButton(
                text = "Add a passkey",
                onClick = { vm.clearPasskeyError(); adding = true },
                variant = ButtonVariant.Secondary,
                enabled = !full && !ui.busy,
                size = ButtonSize.Sm,
            )
            if (full) {
                Text(
                    "You've reached the limit of ${ui.max} passkeys. Remove one to add another.",
                    color = c.inkSecondary,
                    fontSize = 12.sp,
                )
            }
        }
    }

    renaming?.let { id ->
        AlertDialog(
            onDismissRequest = { if (!ui.busy) renaming = null },
            title = { Text("Rename passkey", color = c.ink) },
            text = {
                OrangTextField(
                    value = renameDraft,
                    onValueChange = { renameDraft = it.take(60) },
                    label = "Name",
                )
            },
            confirmButton = {
                TextButton(
                    enabled = renameDraft.isNotBlank() && !ui.busy,
                    onClick = { vm.renamePasskey(id, renameDraft.trim()) { renaming = null } },
                ) { Text("Rename", color = c.primary) }
            },
            dismissButton = {
                TextButton(onClick = { renaming = null }) {
                    Text("Cancel", color = c.inkSecondary)
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
                Text(passkey.name.ifBlank { "Passkey" }, color = c.ink, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Text(
                    "Added ${formatFullTime(passkey.createdAt)}" +
                        (passkey.lastUsedAt?.let { " · last used ${formatFullTime(it)}" } ?: " · never used"),
                    color = c.inkSecondary,
                    fontSize = 12.sp,
                )
                if (passkey.backedUp) {
                    Text("Synced to your device's keychain.", color = c.inkSecondary, fontSize = 12.sp)
                }
            }
            Spacer(Modifier.width(8.dp))
            OrangButton(
                text = "Rename",
                onClick = onRenameOpen,
                variant = ButtonVariant.Ghost,
                size = ButtonSize.Sm,
                enabled = !busy,
            )
            OrangButton(
                text = "Remove",
                onClick = onRemoveOpen,
                variant = ButtonVariant.Danger,
                size = ButtonSize.Sm,
                enabled = !busy,
            )
        }

        if (removing) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Removing this only affects OrangChat. Delete it from the device's own " +
                        "passkey settings too, or it will keep showing up there.",
                    color = c.inkSecondary,
                    fontSize = 13.sp,
                )
                OrangTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = "Current password",
                    isPassword = true,
                )
                if (needsCode) {
                    OrangTextField(
                        value = code,
                        onValueChange = { code = it.take(32) },
                        label = "Authenticator code",
                        placeholder = "123456",
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OrangButton(
                        text = "Remove passkey",
                        onClick = { onRemove(password, code) },
                        variant = ButtonVariant.Danger,
                        enabled = password.isNotBlank() && !busy,
                        loading = busy,
                        size = ButtonSize.Sm,
                    )
                    OrangButton(
                        text = "Cancel",
                        onClick = onRemoveCancel,
                        variant = ButtonVariant.Ghost,
                        size = ButtonSize.Sm,
                    )
                }
            }
        }
    }
}

// ── Security (2FA) ──────────────────────────────────────

@Composable
fun SecurityScreen(
    self: SelfUser,
    hasPassword: Boolean,
    onBack: () -> Unit,
    vm: SettingsViewModel = hiltViewModel(),
) {
    val c = OrangTheme.colors
    val state by vm.twoFactor.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { vm.refreshTwoFactor() }

    Column(modifier = screenModifier(c)) {
        SettingsTopBar("Security", onBack)
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

            Text("Two-factor authentication", color = c.ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "Ask for a code from your phone in addition to your password when you sign in.",
                color = c.inkSecondary,
                fontSize = 13.sp,
            )
            when (val s = state) {
                is TwoFactorUi.Loading -> Text("Loading…", color = c.inkMuted, fontSize = 14.sp)
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

/**
 * Change email / set-or-change password. Both are gated on the current password
 * (except on OAuth-only accounts) plus a 2FA code when it's on, so the two forms
 * share one credential block - mirrors the web CredentialsSection.
 */
@Composable
private fun CredentialsSection(self: SelfUser, hasPassword: Boolean, vm: SettingsViewModel) {
    val c = OrangTheme.colors
    val ui by vm.credentials.collectAsStateWithLifecycle()

    // null = neither form open.
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
        Text("Email & password", color = c.ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Text("Signed in as ${self.email}.", color = c.inkSecondary, fontSize = 13.sp)

        ui.done?.let { Text(it, color = c.success, fontSize = 13.sp) }

        if (mode == null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OrangButton(
                    text = "Change email",
                    onClick = { vm.clearCredentialsMessages(); mode = "email" },
                    variant = ButtonVariant.Secondary,
                    size = ButtonSize.Sm,
                )
                OrangButton(
                    text = if (hasPassword) "Change password" else "Set a password",
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
                        label = "New email",
                        // Nothing confirms the address afterwards - there's no
                        // mail transport - so don't imply a confirmation email.
                        hint = "Used to sign in. There's no confirmation email, so double-check it.",
                    )
                } else {
                    OrangTextField(
                        value = newPassword,
                        onValueChange = { newPassword = it },
                        label = "New password",
                        isPassword = true,
                        hint = "At least 8 characters.",
                    )
                    OrangTextField(
                        value = confirm,
                        onValueChange = { confirm = it },
                        label = "Confirm new password",
                        isPassword = true,
                        error = if (mismatch) "Those don't match." else null,
                    )
                }

                if (hasPassword) {
                    OrangTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = "Your current password",
                        isPassword = true,
                    )
                }
                if (self.twoFactorEnabled) {
                    OrangTextField(
                        value = code,
                        onValueChange = { code = it },
                        label = "Code from your app (or a recovery code)",
                        placeholder = "123456",
                    )
                }

                if (mode == "password") {
                    Text(
                        "Changing your password signs out every other session.",
                        color = c.inkMuted,
                        fontSize = 12.sp,
                    )
                }
                ui.error?.let { Text(it, color = c.danger, fontSize = 13.sp) }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OrangButton(
                        text = if (mode == "email") "Change email" else if (hasPassword) "Change password" else "Set password",
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
                        text = "Cancel",
                        onClick = { reset() },
                        variant = ButtonVariant.Ghost,
                        size = ButtonSize.Sm,
                    )
                }
            }
        }
    }
}

/**
 * Bans and live timeouts against the account. Moderation is per-server - there
 * is no instance-wide sanction - so "good standing" means no server currently
 * restricts you.
 */
@Composable
private fun AccountStandingSection(vm: SettingsViewModel) {
    val c = OrangTheme.colors
    val state by vm.standing.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { vm.refreshStanding() }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Account standing", color = c.ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)

        when (val s = state) {
            is StandingUi.Loading -> Text("Loading…", color = c.inkMuted, fontSize = 14.sp)
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
                        "Your account is in good standing",
                        color = c.ink,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        "No server is currently restricting you.",
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
                            val verb = if (entry.kind == "ban") "Banned from" else "Timed out in"
                            Text(
                                "$verb ${entry.serverName}",
                                color = c.ink,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                            )
                            entry.reason?.let {
                                Text("Reason: $it", color = c.inkSecondary, fontSize = 12.sp)
                            }
                            entry.expiresAt?.let {
                                Text("Until ${formatFullTime(it)}", color = c.inkSecondary, fontSize = 12.sp)
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

/**
 * Freezes the account: nothing can sign in, no new DM reaches it, no friend
 * request lands. For "I think someone's in my account" - a step short of
 * deleting it, and reversible.
 */
@Composable
private fun LockdownSection(self: SelfUser, hasPassword: Boolean, vm: SettingsViewModel) {
    val c = OrangTheme.colors
    val ui by vm.lockdown.collectAsStateWithLifecycle()
    val locked = self.lockdown

    var confirming by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Lockdown", color = c.ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)

        if (locked) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(OrangRadius.lg))
                    .background(c.warning.copy(alpha = 0.10f))
                    .padding(12.dp),
            ) {
                Text(
                    "Your account is locked down",
                    color = c.ink,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    "Nothing can sign in, and no new DMs or friend requests reach you. " +
                        "This device stays signed in.",
                    color = c.inkSecondary,
                    fontSize = 12.sp,
                )
            }
        } else {
            Text(
                "Freezes the account if you think someone else is in it: signs out every " +
                    "other device, blocks new sign-ins, and closes new DMs and friend " +
                    "requests until you lift it.",
                color = c.inkSecondary,
                fontSize = 13.sp,
            )
        }

        ui.done?.let { Text(it, color = c.success, fontSize = 13.sp) }
        ui.error?.let { Text(it, color = c.danger, fontSize = 13.sp) }

        if (confirming) {
            // Lifting needs the password; turning it on deliberately doesn't, so
            // nothing slows you down in the moment you actually need it.
            if (locked && hasPassword) {
                OrangTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = "Your password",
                    isPassword = true,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OrangButton(
                    text = if (locked) "Lift lockdown" else "Lock down my account",
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
                    text = "Cancel",
                    onClick = { confirming = false; password = "" },
                    variant = ButtonVariant.Ghost,
                    size = ButtonSize.Sm,
                )
            }
        } else {
            OrangButton(
                text = if (locked) "Lift lockdown" else "Lock down my account",
                onClick = { vm.resetLockdown(); confirming = true },
                variant = if (locked) ButtonVariant.Primary else ButtonVariant.Secondary,
                size = ButtonSize.Sm,
            )
        }
    }
}

/**
 * Bulk-leaves every server the user doesn't own. Two-step: destructive enough
 * that one tap shouldn't do it, cheap enough not to need a password.
 */
@Composable
private fun LeaveAllServersSection(vm: SettingsViewModel) {
    val c = OrangTheme.colors
    val state by vm.leaveAll.collectAsStateWithLifecycle()
    var confirming by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Leave all servers", color = c.ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Text(
            "Leaves every server you're in except the ones you own. You'll need a new " +
                "invite to get back into any of them.",
            color = c.inkSecondary,
            fontSize = 13.sp,
        )

        when (val s = state) {
            is LeaveAllUi.Done -> {
                val kept = if (s.keptOwned.isEmpty()) "" else " Still yours: ${s.keptOwned.joinToString(", ")}."
                Text(
                    "Left ${s.left} server${if (s.left == 1) "" else "s"}.$kept",
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
                    text = "Yes, leave them all",
                    onClick = { vm.leaveAllServers(); confirming = false },
                    variant = ButtonVariant.Danger,
                    size = ButtonSize.Sm,
                    loading = state is LeaveAllUi.Busy,
                )
                OrangButton(
                    text = "Cancel",
                    onClick = { confirming = false },
                    variant = ButtonVariant.Ghost,
                    size = ButtonSize.Sm,
                )
            }
        } else {
            OrangButton(
                text = "Leave all servers",
                onClick = { vm.resetLeaveAll(); confirming = true },
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Sm,
            )
        }
    }
}

/**
 * Wipes every message the user has written, anywhere - including servers and
 * group DMs they've left. Password-gated: unlike leaving a server, none of this
 * can be undone or re-obtained.
 */
@Composable
private fun DeleteAllMessagesSection(self: SelfUser, hasPassword: Boolean, vm: SettingsViewModel) {
    val c = OrangTheme.colors
    val ui by vm.wipe.collectAsStateWithLifecycle()

    var open by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            "Delete all your messages",
            color = c.ink,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            "Removes every message you've sent, in every server and group - including " +
                "the ones you've left. Attachments you uploaded aren't removed from " +
                "storage. This can't be undone.",
            color = c.inkSecondary,
            fontSize = 13.sp,
        )

        ui.done?.let { Text(it, color = c.success, fontSize = 13.sp) }

        if (!open) {
            OrangButton(
                text = "Delete all my messages",
                onClick = { vm.resetWipe(); open = true },
                variant = ButtonVariant.Ghost,
                size = ButtonSize.Sm,
            )
        } else {
            if (hasPassword) {
                OrangTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = "Your password",
                    isPassword = true,
                )
            }
            if (self.twoFactorEnabled) {
                OrangTextField(
                    value = code,
                    onValueChange = { code = it },
                    label = "Code from your app (or a recovery code)",
                    placeholder = "123456",
                )
            }
            ui.error?.let { Text(it, color = c.danger, fontSize = 13.sp) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OrangButton(
                    text = "Delete them all",
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
                    text = "Cancel",
                    onClick = { open = false; password = ""; code = "" },
                    variant = ButtonVariant.Ghost,
                    size = ButtonSize.Sm,
                )
            }
        }
    }
}

/**
 * Irreversible account deletion. The account is tombstoned rather than removed:
 * messages stay in the conversations they're part of and everything identifying
 * is scrubbed. Owning a server blocks it; the server names which ones.
 */
@Composable
private fun DeleteAccountSection(self: SelfUser, hasPassword: Boolean, vm: SettingsViewModel) {
    val c = OrangTheme.colors
    val ui by vm.credentials.collectAsStateWithLifecycle()

    var open by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Delete account", color = c.ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Text(
            "Your messages stay in the conversations they're part of, shown as from a " +
                "deleted user. Everything else - profile, connections, friends, server " +
                "memberships - is erased. This can't be undone.",
            color = c.inkSecondary,
            fontSize = 13.sp,
        )

        if (!open) {
            OrangButton(
                text = "Delete my account",
                onClick = { vm.clearCredentialsMessages(); open = true },
                variant = ButtonVariant.Ghost,
                size = ButtonSize.Sm,
            )
        } else {
            OrangTextField(
                value = username,
                onValueChange = { username = it },
                label = "Type ${self.username} to confirm",
            )
            if (hasPassword) {
                OrangTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = "Your password",
                    isPassword = true,
                )
            }
            if (self.twoFactorEnabled) {
                OrangTextField(
                    value = code,
                    onValueChange = { code = it },
                    label = "Code from your app (or a recovery code)",
                    placeholder = "123456",
                )
            }
            ui.error?.let { Text(it, color = c.danger, fontSize = 13.sp) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OrangButton(
                    text = "Permanently delete",
                    onClick = { vm.deleteAccount(password, username, code) },
                    variant = ButtonVariant.Danger,
                    size = ButtonSize.Sm,
                    enabled = username == self.username && !ui.busy,
                    loading = ui.busy,
                )
                OrangButton(
                    text = "Cancel",
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
    val c = OrangTheme.colors
    var password by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            "You'll need an authenticator app such as Google Authenticator, 1Password, or Aegis.",
            color = c.inkSecondary,
            fontSize = 13.sp,
        )
        if (hasPassword) {
            OrangTextField(
                value = password,
                onValueChange = { password = it },
                label = "Confirm your password",
                isPassword = true,
            )
        }
        if (error != null) Text(error, color = c.danger, fontSize = 13.sp)
        OrangButton(text = "Set up two-factor", onClick = { vm.beginSetup(password) })
    }
}

@Composable
private fun TwoFactorVerify(state: TwoFactorUi.Setup, vm: SettingsViewModel) {
    val c = OrangTheme.colors
    val clipboard = LocalClipboardManager.current
    var code by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SettingSection("1. Add this key to your app") {
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
            Text("Tap the key to copy it.", color = c.inkMuted, fontSize = 12.sp)
        }
        SettingSection("2. Enter the 6-digit code") {
            OrangTextField(
                value = code,
                onValueChange = { code = it.filter(Char::isDigit).take(6) },
                label = "Code from your app",
                placeholder = "123456",
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
        }
        if (state.error != null) Text(state.error, color = c.danger, fontSize = 13.sp)
        OrangButton(
            text = "Turn on two-factor",
            onClick = { vm.confirmSetup(code) },
            enabled = code.length == 6,
            loading = state.verifying,
        )
    }
}

@Composable
private fun TwoFactorManage(state: TwoFactorUi.On, hasPassword: Boolean, vm: SettingsViewModel) {
    val c = OrangTheme.colors
    var mode by remember { mutableStateOf("idle") } // idle | disable | regen
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
                Text("Two-factor is on", color = c.ink, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Text(
                    "${state.backupCodesRemaining} recovery code" +
                        (if (state.backupCodesRemaining == 1) "" else "s") + " left.",
                    color = c.inkSecondary,
                    fontSize = 12.sp,
                )
            }
        }

        if (mode == "idle") {
            OrangButton(
                text = "New recovery codes",
                onClick = { mode = "regen" },
                variant = ButtonVariant.Secondary,
            )
            OrangButton(
                text = "Turn off two-factor",
                onClick = { mode = "disable" },
                variant = ButtonVariant.Danger,
            )
        } else {
            val disabling = mode == "disable"
            Text(
                if (disabling)
                    "Turning off two-factor leaves your password as the only protection."
                else "This replaces all of your existing recovery codes.",
                color = c.inkSecondary,
                fontSize = 13.sp,
            )
            if (hasPassword) {
                OrangTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = "Your password",
                    isPassword = true,
                )
            }
            OrangTextField(
                value = code,
                onValueChange = { code = it.take(32) },
                label = if (disabling) "Code from your app (or a recovery code)" else "Code from your app",
                placeholder = "123456",
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            if (state.error != null) Text(state.error, color = c.danger, fontSize = 13.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OrangButton(
                    text = if (disabling) "Turn off" else "Generate",
                    onClick = {
                        if (disabling) vm.disable(password, code) else vm.regenerateCodes(password, code)
                    },
                    variant = if (disabling) ButtonVariant.Danger else ButtonVariant.Primary,
                    enabled = code.isNotBlank(),
                    loading = state.busy,
                )
                OrangButton(
                    text = "Cancel",
                    onClick = { mode = "idle"; password = ""; code = "" },
                    variant = ButtonVariant.Ghost,
                )
            }
        }
    }
}

@Composable
private fun BackupCodes(codes: List<String>, onDone: () -> Unit) {
    val c = OrangTheme.colors
    val clipboard = LocalClipboardManager.current
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Recovery codes", color = c.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Text(
            "Save these now - each works once if you lose your phone, and they're shown only this once.",
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
                text = "Copy",
                onClick = { clipboard.setText(AnnotatedString(codes.joinToString("\n"))) },
                variant = ButtonVariant.Secondary,
            )
            OrangButton(text = "I've saved them", onClick = onDone)
        }
    }
}

// ── Accessibility ───────────────────────────────────────

@Composable
fun AccessibilityScreen(onBack: () -> Unit, vm: SettingsViewModel = hiltViewModel()) {
    val c = OrangTheme.colors
    val prefs by vm.prefs.collectAsStateWithLifecycle()

    Column(modifier = screenModifier(c)) {
        SettingsTopBar("Accessibility", onBack)
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            SettingSection("Text size") {
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
                        "The quick brown fox jumps over the lazy dog.",
                        color = c.ink,
                        fontSize = (15 * prefs.fontScale).sp,
                    )
                }
            }
            SettingSection("Display") {
                SettingsToggleRow(
                    label = "Compact messages",
                    hint = "Tighter spacing between messages.",
                    checked = prefs.compactMessages,
                    onCheckedChange = vm::setCompactMessages,
                )
                SettingsToggleRow(
                    label = "Reduce motion",
                    hint = "Skip the scroll and swipe animations.",
                    checked = prefs.reducedMotion,
                    onCheckedChange = vm::setReducedMotion,
                )
            }
            OrangButton(
                text = "Reset to defaults",
                onClick = vm::resetPrefs,
                variant = ButtonVariant.Secondary,
            )
        }
    }
}

// ── System ──────────────────────────────────────────────

@Composable
fun SystemScreen(
    connected: Boolean,
    onBack: () -> Unit,
    vm: SettingsViewModel = hiltViewModel(),
) {
    val c = OrangTheme.colors
    val backend by vm.backend.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.refreshBackend() }
    Column(modifier = screenModifier(c)) {
        SettingsTopBar("System", onBack)
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            SettingSection("Connection") {
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
                        if (connected) "Connected" else "Disconnected",
                        color = c.ink,
                        fontSize = 14.sp,
                    )
                }
            }
            SettingSection("Server") {
                InfoRow(
                    "Backend",
                    when (val b = backend) {
                        is BackendUi.Loading -> "Checking…"
                        is BackendUi.Loaded -> "v${b.version}"
                        is BackendUi.Unknown -> "Unknown"
                    },
                )
                InfoRow("API", BuildConfig.API_BASE_URL)
                InfoRow("Realtime", BuildConfig.SOCKET_URL)
            }
        }
    }
}

// ── About ───────────────────────────────────────────────

@Composable
fun AboutScreen(onBack: () -> Unit) {
    val c = OrangTheme.colors
    Column(modifier = screenModifier(c)) {
        SettingsTopBar("About", onBack)
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("OrangChat", color = c.ink, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text("Version ${BuildConfig.VERSION_NAME}", color = c.inkMuted, fontSize = 13.sp)
                Text(
                    "A fast, self-hosted chat for servers, DMs, and voice - part of the Oranges.LT family.",
                    color = c.inkSecondary,
                    fontSize = 13.sp,
                )
            }
            SettingSection("Updates") { UpdateSection() }
            SettingSection("Build") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(c.surface3, RoundedCornerShape(OrangRadius.lg))
                        .padding(horizontal = 14.dp),
                ) {
                    InfoRow("Version", BuildConfig.VERSION_NAME)
                    InfoRow("Build", if (BuildConfig.DEBUG) "Debug" else "Release")
                    InfoRow("Package", BuildConfig.APPLICATION_ID)
                    InfoRow("Client", "Android (native)")
                }
            }
            Text(
                "© 2026 Oranges.LT · Made with 🍊",
                color = c.inkMuted,
                fontSize = 12.sp,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * Check / download / install, in one row of the About screen. OrangChat is
 * sideloaded, so this is the only way an update ever arrives.
 */
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
            is UpdateUiState.Idle -> "Version ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})"
            is UpdateUiState.Checking -> "Checking…"
            is UpdateUiState.UpToDate -> "You're on the latest version"
            is UpdateUiState.Available -> "Version ${s.manifest.versionName} is available"
            is UpdateUiState.Downloading -> "Downloading ${s.manifest.versionName}…"
            is UpdateUiState.ReadyToInstall -> "Follow the installer to finish updating"
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
                    // The per-app "install unknown apps" toggle has no runtime
                    // dialog, so send the user to it before downloading 30 MB
                    // they could not install at the end of.
                    text = if (vm.canInstall()) "Download & install" else "Allow installs first",
                    onClick = {
                        if (vm.canInstall()) vm.download(s.manifest)
                        else context.startActivity(vm.installPermissionIntent())
                    },
                )

            is UpdateUiState.Downloading -> Unit

            else ->
                OrangButton(
                    text = "Check for updates",
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
