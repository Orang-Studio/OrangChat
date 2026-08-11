package lt.oranges.orangchat.feature.auth

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import lt.oranges.orangchat.data.remote.PasskeyChallenge
import lt.oranges.orangchat.data.repository.AuthRepository
import retrofit2.HttpException
import javax.inject.Inject

data class AuthFormState(
    val loading: Boolean = false,
    val error: String? = null,
    val needsTwoFactor: Boolean = false,
    val loginToken: String? = null,
    val passkeyPrompt: PasskeyChallenge? = null,
    val skipPasskey: Boolean = false,
    val notice: String? = null,
    val verificationSent: Boolean = false,
)

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val authRepository: AuthRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(AuthFormState())
    val state: StateFlow<AuthFormState> = _state.asStateFlow()

    fun login(
        email: String,
        password: String,
        totpCode: String? = null,
        skipPasskey: Boolean = false,
        lostAuthenticator: Boolean = false,
    ) {
        if (!validate(email, password)) return
        val bypassPasskey = skipPasskey || _state.value.skipPasskey
        val needs2fa = _state.value.needsTwoFactor && !lostAuthenticator
        run(keepTwoFactor = needs2fa, skipPasskey = bypassPasskey) {
            val challenge = authRepository.login(email, password, totpCode, bypassPasskey, lostAuthenticator)
            when {
                challenge.user != null -> AuthFormState()
                challenge.passkeyRequired && challenge.ceremonyToken.isNotBlank() && challenge.challenge != null ->
                    AuthFormState(passkeyPrompt = PasskeyChallenge(challenge.challenge, challenge.ceremonyToken))
                challenge.loginToken.isBlank() ->
                    AuthFormState(error = "Could not start the sign-in. Try again.")
                else -> AuthFormState(loginToken = challenge.loginToken)
            }
        }
    }

    fun verifyEmailCode(code: String) {
        val token = _state.value.loginToken ?: return
        run(keepLoginToken = true) {
            authRepository.verifyEmailCode(token, code)
            AuthFormState()
        }
    }


    fun signInWithPasskey(context: Context) {
        if (_state.value.loading) return
        run {
            val started = authRepository.startPasskeySignIn()
            val response = Passkeys.get(context, optionsOf(started))
            authRepository.finishPasskeySignIn(started.ceremonyToken, response)
            AuthFormState()
        }
    }

    fun answerPasskey(context: Context) {
        val prompt = _state.value.passkeyPrompt ?: return
        run(keepPasskey = true) {
            val response = Passkeys.get(context, optionsOf(prompt))
            authRepository.finishPasskeySignIn(prompt.ceremonyToken, response)
            AuthFormState()
        }
    }

    fun cancelPasskey() { _state.value = AuthFormState() }

    private fun optionsOf(challenge: PasskeyChallenge): String =
        Passkeys.optionsOf(challenge.challenge)
            ?: throw IllegalStateException("The server sent an empty passkey request.")

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
        keepPasskey: Boolean = false,
        skipPasskey: Boolean = false,
        block: suspend () -> AuthFormState,
    ) {
        val token = _state.value.loginToken.takeIf { keepLoginToken }
        val prompt = _state.value.passkeyPrompt.takeIf { keepPasskey }
        _state.value = AuthFormState(
            loading = true,
            needsTwoFactor = keepTwoFactor,
            loginToken = token,
            passkeyPrompt = prompt,
            skipPasskey = skipPasskey || _state.value.skipPasskey,
        )
        val bypassPasskey = _state.value.skipPasskey
        viewModelScope.launch {
            try {
                _state.value = block()
            } catch (e: HttpException) {
                val body = runCatching { e.response()?.errorBody()?.string() }.getOrNull().orEmpty()
                if (e.code() == 401 && body.contains("2fa_required")) {
                    _state.value = AuthFormState(
                        needsTwoFactor = true,
                        error = if (keepTwoFactor) "That code isn't right. Try the current one." else null,
                        skipPasskey = bypassPasskey,
                    )
                } else {
                    _state.value = AuthFormState(
                        loginToken = token,
                        error = serverMessage(e, body, token != null),
                        skipPasskey = bypassPasskey,
                    )
                }
            } catch (e: Passkeys.Cancelled) {
                _state.value = AuthFormState(
                    passkeyPrompt = prompt,
                    error = "That was cancelled. Try again, or use an email code.",
                    skipPasskey = bypassPasskey,
                )
            } catch (e: Passkeys.NoneAvailable) {
                _state.value = AuthFormState(
                    passkeyPrompt = prompt,
                    error = "No passkey for OrangChat on this device. Try again, or use an email code.",
                    skipPasskey = bypassPasskey,
                )
            } catch (e: Exception) {
                _state.value = AuthFormState(
                    loginToken = token,
                    passkeyPrompt = prompt,
                    error = e.message ?: "Something went wrong. Try again.",
                    skipPasskey = bypassPasskey,
                )
            }
        }
    }

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

    fun cancelEmailCode() { _state.value = AuthFormState() }

    fun dismissVerificationNotice() { _state.value = AuthFormState() }
}
