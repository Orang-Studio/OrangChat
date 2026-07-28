package lt.oranges.orangchat.feature.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import lt.oranges.orangchat.data.repository.AuthRepository
import retrofit2.HttpException
import javax.inject.Inject

data class AuthFormState(
    val loading: Boolean = false,
    val error: String? = null,
    /** Set once the server says the account needs its authenticator code. */
    val needsTwoFactor: Boolean = false,
    /**
     * Non-null once the password checked out and a code is on its way by email.
     * It is the only handle on that half-finished login, so losing it means
     * starting over.
     */
    val loginToken: String? = null,
    /** Non-error status line, e.g. confirming a code was sent again. */
    val notice: String? = null,
    /** Signup went through; the account waits on its verification link. */
    val verificationSent: Boolean = false,
)

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val authRepository: AuthRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(AuthFormState())
    val state: StateFlow<AuthFormState> = _state.asStateFlow()

    fun login(email: String, password: String, totpCode: String? = null) {
        if (!validate(email, password)) return
        val needs2fa = _state.value.needsTwoFactor
        run(keepTwoFactor = needs2fa) {
            // Success here is only a mailed code; the session comes later. A
            // blank token would strand the code screen with nothing to send.
            val token = authRepository.login(email, password, totpCode)
            if (token.isBlank()) {
                AuthFormState(error = "Could not start the sign-in. Try again.")
            } else {
                AuthFormState(loginToken = token)
            }
        }
    }

    /** Finishes the login the mailed code belongs to. */
    fun verifyEmailCode(code: String) {
        val token = _state.value.loginToken ?: return
        run(keepLoginToken = true) {
            authRepository.verifyEmailCode(token, code)
            // The session flips and the auth screens are replaced.
            AuthFormState()
        }
    }

    fun resendEmailCode() {
        val token = _state.value.loginToken ?: return
        run(keepLoginToken = true) {
            authRepository.resendEmailCode(token)
            AuthFormState(loginToken = token, notice = "We sent a new code. Check your email.")
        }
    }

    fun signup(email: String, username: String, password: String, displayName: String) {
        when {
            email.isBlank() || password.length < 8 || username.length < 2 -> {
                _state.value = AuthFormState(error = "Check your email, a username (2+ chars), and an 8+ char password.")
                return
            }
        }
        run {
            authRepository.signup(email, username, password, displayName.ifBlank { null })
            AuthFormState(verificationSent = true)
        }
    }

    private fun validate(email: String, password: String): Boolean {
        if (email.isBlank() || password.isBlank()) {
            _state.value = AuthFormState(error = "Enter your email and password.")
            return false
        }
        return true
    }

    private fun run(
        keepTwoFactor: Boolean = false,
        keepLoginToken: Boolean = false,
        block: suspend () -> AuthFormState,
    ) {
        val token = _state.value.loginToken.takeIf { keepLoginToken }
        _state.value = AuthFormState(loading = true, needsTwoFactor = keepTwoFactor, loginToken = token)
        viewModelScope.launch {
            try {
                _state.value = block()
            } catch (e: HttpException) {
                // A 401 carrying code "2fa_required" means the password was right
                // but the account also needs its authenticator code.
                val body = runCatching { e.response()?.errorBody()?.string() }.getOrNull().orEmpty()
                if (e.code() == 401 && body.contains("2fa_required")) {
                    _state.value = AuthFormState(
                        needsTwoFactor = true,
                        error = if (keepTwoFactor) "That code isn't right. Try the current one." else null,
                    )
                } else {
                    _state.value = AuthFormState(loginToken = token, error = serverMessage(e, body, token != null))
                }
            } catch (e: Exception) {
                _state.value = AuthFormState(
                    loginToken = token,
                    error = e.message ?: "Something went wrong. Try again.",
                )
            }
        }
    }

    /**
     * Prefer the server's own wording where it has any: lockdown and unverified
     * email both come back as plain 401s, and "Invalid email or password" would
     * send someone hunting for a typo that isn't there.
     */
    private fun serverMessage(e: HttpException, body: String, verifyingCode: Boolean): String =
        Regex("\"error\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(body)
            ?.groupValues?.get(1)
            ?.replace("\\\"", "\"")
            ?.takeIf { it.isNotBlank() }
            ?: mapHttpError(e, verifyingCode)

    private fun mapHttpError(e: HttpException, verifyingCode: Boolean): String = when (e.code()) {
        400 -> if (verifyingCode) "That code isn't right. Check it and try again." else "Invalid input. Double-check the fields."
        401 -> if (verifyingCode) "That code is wrong or expired. Send a new one." else "Invalid email or password."
        409 -> "That email or username is already taken."
        429 -> "Too many attempts. Wait a moment and try again."
        else -> "Request failed (${e.code()})."
    }

    fun clearError() { _state.value = _state.value.copy(error = null, notice = null) }

    fun cancelTwoFactor() { _state.value = AuthFormState() }

    /** Abandons a half-finished login and goes back to email and password. */
    fun cancelEmailCode() { _state.value = AuthFormState() }

    fun dismissVerificationNotice() { _state.value = AuthFormState() }
}
