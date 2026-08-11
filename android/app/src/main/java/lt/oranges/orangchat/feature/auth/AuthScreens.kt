package lt.oranges.orangchat.feature.auth
import lt.oranges.orangchat.util.AppStrings
import lt.oranges.orangchat.R
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import lt.oranges.orangchat.ui.components.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.foundation.clickable
import lt.oranges.orangchat.ui.components.ButtonSize
import lt.oranges.orangchat.ui.components.ButtonVariant
import lt.oranges.orangchat.ui.components.OrangButton
import lt.oranges.orangchat.ui.components.OrangTextField
import lt.oranges.orangchat.ui.theme.OrangTheme

@Composable
fun AuthScreens(viewModel: AuthViewModel = hiltViewModel()) {
    var showSignup by rememberSaveable { mutableStateOf(false) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val c = OrangTheme.colors
    // Credential Manager raises its sheet over the Activity, so the ceremony
    // needs this context rather than the application one.
    val context = LocalContext.current

    Box(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 420.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("OrangChat", color = c.primary, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(
                if (showSignup) AppStrings.get(context, R.string.catalog_create_your_account_4046b581) else AppStrings.get(context, R.string.catalog_welcome_back_b807833e),
                color = c.inkSecondary,
                fontSize = 15.sp,
            )
            Spacer(Modifier.height(28.dp))

            if (showSignup) {
                SignupForm(
                    state = state,
                    onSubmit = viewModel::signup,
                    onChange = viewModel::clearError,
                )
            } else {
                LoginForm(
                    state = state,
                    onSubmit = { e, p, code -> viewModel.login(e, p, code) },
                    onChange = viewModel::clearError,
                    onCancelTwoFactor = viewModel::cancelTwoFactor,
                    onVerifyEmailCode = viewModel::verifyEmailCode,
                    onResendEmailCode = viewModel::resendEmailCode,
                    onCancelEmailCode = viewModel::cancelEmailCode,
                    onSignInWithPasskey = { viewModel.signInWithPasskey(context) },
                    onAnswerPasskey = { viewModel.answerPasskey(context) },
                    onEmailCodeInstead = { e, p -> viewModel.login(e, p, skipPasskey = true) },
                    onLostAuthenticator = { e, p -> viewModel.login(e, p, lostAuthenticator = true) },
                    onCancelPasskey = viewModel::cancelPasskey,
                )
            }

            Spacer(Modifier.height(16.dp))
            Text(
                text = if (showSignup) AppStrings.get(context, R.string.catalog_already_have_an_account_log_in_b3a34236)
                else AppStrings.get(context, R.string.catalog_need_an_account_sign_up_77472065),
                color = c.primary,
                fontSize = 14.sp,
                modifier = Modifier.clickable {
                    viewModel.dismissVerificationNotice()
                    showSignup = !showSignup
                },
            )
        }
    }
}

@Composable
private fun LoginForm(
    state: AuthFormState,
    onSubmit: (String, String, String?) -> Unit,
    onChange: () -> Unit,
    onCancelTwoFactor: () -> Unit,
    onVerifyEmailCode: (String) -> Unit,
    onResendEmailCode: () -> Unit,
    onCancelEmailCode: () -> Unit,
    onSignInWithPasskey: () -> Unit,
    onAnswerPasskey: () -> Unit,
    onEmailCodeInstead: (String, String) -> Unit,
    onLostAuthenticator: (String, String) -> Unit,
    onCancelPasskey: () -> Unit,
) {
        val context = LocalContext.current
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }
    var emailCode by rememberSaveable { mutableStateOf("") }
    var reveal by remember { mutableStateOf(false) }

    // The password checked out and the account wants its passkey: hold the
    // ceremony open, and dive straight into the system sheet - the click that
    // submitted the password is the gesture, and a second click would only add
    // a step. Keyed on the token so a retry of the same ceremony doesn't refire.
    if (state.passkeyPrompt != null) {
        LaunchedEffect(state.passkeyPrompt.ceremonyToken) {
            onAnswerPasskey()
        }
        Column(verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth()) {
            Text(
                AppStrings.get(context, R.string.catalog_confirm_with_your_passkey_to_finish_signing_a7480f38),
                color = OrangTheme.colors.inkSecondary,
                fontSize = 14.sp,
            )
            ErrorText(state.error)
            OrangButton(
                text = AppStrings.get(context, R.string.catalog_use_passkey_a93f1827),
                onClick = onAnswerPasskey,
                size = ButtonSize.Lg,
                loading = state.loading,
                modifier = Modifier.fillMaxWidth(),
            )
            // The way out for someone whose authenticator isn't to hand. It
            // re-submits the password, so this is a fallback and not a bypass.
            OrangButton(
                text = AppStrings.get(context, R.string.catalog_email_me_a_code_instead_44cc1833),
                onClick = { onEmailCodeInstead(email, password) },
                variant = ButtonVariant.Ghost,
                modifier = Modifier.fillMaxWidth(),
            )
            OrangButton(
                text = "Back",
                onClick = onCancelPasskey,
                variant = ButtonVariant.Ghost,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        return
    }

    // The bottom rung: no passkey, no authenticator (or neither to hand), so the
    // server mailed a one-time code to finish the sign-in with.
    if (state.loginToken != null) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth()) {
            Text(
                AppStrings.get(context, R.string.catalog_we_emailed_you_a_sign_in_code_e283ce1e),
                color = OrangTheme.colors.inkSecondary,
                fontSize = 14.sp,
            )
            OrangTextField(
                value = emailCode,
                onValueChange = { emailCode = it.take(12); onChange() },
                label = AppStrings.get(context, R.string.catalog_email_code_5cb76e8c),
                placeholder = "123456",
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            NoticeText(state.notice)
            ErrorText(state.error)
            OrangButton(
                text = AppStrings.get(context, R.string.catalog_sign_in_ada2e9e9),
                onClick = { onVerifyEmailCode(emailCode) },
                size = ButtonSize.Lg,
                enabled = emailCode.isNotBlank(),
                loading = state.loading,
                modifier = Modifier.fillMaxWidth(),
            )
            OrangButton(
                text = AppStrings.get(context, R.string.catalog_send_a_new_code_91904a28),
                onClick = { emailCode = ""; onResendEmailCode() },
                variant = ButtonVariant.Ghost,
                modifier = Modifier.fillMaxWidth(),
            )
            OrangButton(
                text = "Back",
                onClick = { emailCode = ""; code = ""; password = ""; onCancelEmailCode() },
                variant = ButtonVariant.Ghost,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        return
    }

    // The password already checked out; all that's left is the second factor.
    if (state.needsTwoFactor) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth()) {
            OrangTextField(
                value = code,
                onValueChange = { code = it.take(32); onChange() },
                label = AppStrings.get(context, R.string.catalog_authenticator_code_2908b4e9),
                placeholder = "123456",
                hint = AppStrings.get(context, R.string.catalog_enter_the_6_digit_code_from_your_0a98bcca),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            ErrorText(state.error)
            OrangButton(
                text = "Verify",
                onClick = { onSubmit(email, password, code) },
                size = ButtonSize.Lg,
                enabled = code.isNotBlank(),
                loading = state.loading,
                modifier = Modifier.fillMaxWidth(),
            )
            // A phone left at home must not be a locked account. This drops to
            // the emailed code, which is weaker - so it is a button somebody has
            // to reach for, never the default.
            OrangButton(
                text = AppStrings.get(context, R.string.catalog_lost_your_authenticator_af902070),
                onClick = { code = ""; onLostAuthenticator(email, password) },
                variant = ButtonVariant.Ghost,
                modifier = Modifier.fillMaxWidth(),
            )
            OrangButton(
                text = "Back",
                onClick = { code = ""; onCancelTwoFactor() },
                variant = ButtonVariant.Ghost,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        return
    }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth()) {
        OrangTextField(
            value = email,
            onValueChange = { email = it; onChange() },
            label = "Email",
            placeholder = AppStrings.get(context, R.string.catalog_you_example_com_50e2b46e),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
        )
        OrangTextField(
            value = password,
            onValueChange = { password = it; onChange() },
            label = "Password",
            isPassword = !reveal,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            trailing = { RevealToggle(reveal) { reveal = !reveal } },
        )
        ErrorText(state.error)
        OrangButton(
            text = AppStrings.get(context, R.string.catalog_log_in_f7c400ed),
            onClick = { onSubmit(email, password, null) },
            size = ButtonSize.Lg,
            loading = state.loading,
            modifier = Modifier.fillMaxWidth(),
        )
        OrangButton(
            text = AppStrings.get(context, R.string.catalog_sign_in_with_a_passkey_cf1c7263),
            onClick = onSignInWithPasskey,
            variant = ButtonVariant.Ghost,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun SignupForm(
    state: AuthFormState,
    onSubmit: (String, String, String, String) -> Unit,
    onChange: () -> Unit,
) {
        val context = LocalContext.current
    var email by rememberSaveable { mutableStateOf("") }
    var username by rememberSaveable { mutableStateOf("") }
    var displayName by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var reveal by remember { mutableStateOf(false) }

    // Signup no longer signs anyone in - the account is unusable until the
    // emailed link is opened, so say that instead of dropping them on a form
    // that would just answer "verify your email first".
    if (state.verificationSent) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth()) {
            Text(
                AppStrings.get(context, R.string.catalog_account_created_check_your_email_for_a_7d4a9ecd) +
                    "you'll need to open it before you can log in.",
                color = OrangTheme.colors.inkSecondary,
                fontSize = 14.sp,
            )
        }
        return
    }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth()) {
        OrangTextField(
            value = email,
            onValueChange = { email = it; onChange() },
            label = "Email",
            placeholder = AppStrings.get(context, R.string.catalog_you_example_com_50e2b46e),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
        )
        OrangTextField(
            value = username,
            onValueChange = { username = it.lowercase(); onChange() },
            label = "Username",
            hint = AppStrings.get(context, R.string.catalog_lowercase_letters_digits_and_2_to_32_7bec3c28),
        )
        OrangTextField(
            value = displayName,
            onValueChange = { displayName = it; onChange() },
            label = AppStrings.get(context, R.string.catalog_display_name_optional_23d1974a),
        )
        OrangTextField(
            value = password,
            onValueChange = { password = it; onChange() },
            label = "Password",
            isPassword = !reveal,
            hint = AppStrings.get(context, R.string.catalog_at_least_8_characters_1fe494b2),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            trailing = { RevealToggle(reveal) { reveal = !reveal } },
        )
        ErrorText(state.error)
        OrangButton(
            text = AppStrings.get(context, R.string.catalog_create_account_aaf37447),
            onClick = { onSubmit(email, username, password, displayName) },
            size = ButtonSize.Lg,
            loading = state.loading,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun RevealToggle(revealed: Boolean, onToggle: () -> Unit) {
        val context = LocalContext.current
    val c = OrangTheme.colors
    Icon(
        imageVector = if (revealed) Icons.Default.VisibilityOff else Icons.Default.Visibility,
        contentDescription = if (revealed) AppStrings.get(context, R.string.catalog_hide_password_e40123b4) else AppStrings.get(context, R.string.catalog_show_password_044b852f),
        tint = c.inkMuted,
        modifier = Modifier.clickable(onClick = onToggle).padding(4.dp),
    )
}

@Composable
private fun NoticeText(notice: String?) {
    if (notice != null) {
        Text(notice, color = OrangTheme.colors.inkSecondary, fontSize = 14.sp)
    }
}

@Composable
private fun ErrorText(error: String?) {
    if (error != null) {
        Text(error, color = OrangTheme.colors.danger, fontSize = 14.sp)
    }
}
