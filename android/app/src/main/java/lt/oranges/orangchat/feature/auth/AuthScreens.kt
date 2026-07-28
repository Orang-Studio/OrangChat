package lt.oranges.orangchat.feature.auth

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
                if (showSignup) "Create your account" else "Welcome back",
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
                )
            }

            Spacer(Modifier.height(16.dp))
            Text(
                text = if (showSignup) "Already have an account? Log in"
                else "Need an account? Sign up",
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
) {
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }
    var emailCode by rememberSaveable { mutableStateOf("") }
    var reveal by remember { mutableStateOf(false) }

    // Every login ends here: the password (and any authenticator code) checked
    // out, and the server mailed a one-time code to finish the sign-in with.
    if (state.loginToken != null) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth()) {
            Text(
                "We emailed you a sign-in code. It expires in 10 minutes.",
                color = OrangTheme.colors.inkSecondary,
                fontSize = 14.sp,
            )
            OrangTextField(
                value = emailCode,
                onValueChange = { emailCode = it.take(12); onChange() },
                label = "Email code",
                placeholder = "123456",
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            NoticeText(state.notice)
            ErrorText(state.error)
            OrangButton(
                text = "Sign in",
                onClick = { onVerifyEmailCode(emailCode) },
                size = ButtonSize.Lg,
                enabled = emailCode.isNotBlank(),
                loading = state.loading,
                modifier = Modifier.fillMaxWidth(),
            )
            OrangButton(
                text = "Send a new code",
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
                label = "Authenticator code",
                placeholder = "123456",
                hint = "Enter the 6-digit code from your app, or a recovery code.",
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
            placeholder = "you@example.com",
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
            text = "Log in",
            onClick = { onSubmit(email, password, null) },
            size = ButtonSize.Lg,
            loading = state.loading,
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
                "Account created. Check your email for a verification link - " +
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
            placeholder = "you@example.com",
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
        )
        OrangTextField(
            value = username,
            onValueChange = { username = it.lowercase(); onChange() },
            label = "Username",
            hint = "Lowercase letters, digits, _ and . - 2 to 32 chars",
        )
        OrangTextField(
            value = displayName,
            onValueChange = { displayName = it; onChange() },
            label = "Display name (optional)",
        )
        OrangTextField(
            value = password,
            onValueChange = { password = it; onChange() },
            label = "Password",
            isPassword = !reveal,
            hint = "At least 8 characters",
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            trailing = { RevealToggle(reveal) { reveal = !reveal } },
        )
        ErrorText(state.error)
        OrangButton(
            text = "Create account",
            onClick = { onSubmit(email, username, password, displayName) },
            size = ButtonSize.Lg,
            loading = state.loading,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun RevealToggle(revealed: Boolean, onToggle: () -> Unit) {
    val c = OrangTheme.colors
    Icon(
        imageVector = if (revealed) Icons.Default.VisibilityOff else Icons.Default.Visibility,
        contentDescription = if (revealed) "Hide password" else "Show password",
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
