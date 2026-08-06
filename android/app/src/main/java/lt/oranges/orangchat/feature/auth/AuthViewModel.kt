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
    /** Set once the server says the account needs its authenticator code. */
    val needsTwoFactor: Boolean = false,
    /**
     * Non-null once the password checked out and a code is on its way by email.
     * It is the only handle on that half-finished login, so losing it means
     * starting over.
     */
    val loginToken: String? = null,
    /**
     * Non-null once the password checked out and the account wants a passkey
     * instead of the emailed code - the phishing-resistant path is offered
     * first. The ceremony's other half is the challenge the device signs.
     */
    val passkeyPrompt: PasskeyChallenge? = null,
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

    /**
     * The password, plus whichever second factor the account can reach.
     *
     * [skipPasskey] and [lostAuthenticator] each step one rung down that ladder,
     * towards the emailed code - a device that isn't to hand must not lock the
     * account out, so both exist, and both are weaker than what they replace.
     */
    fun login(
        email: String,
        password: String,
        totpCode: String? = null,
        skipPasskey: Boolean = false,
        lostAuthenticator: Boolean = false,
    ) {
        if (!validate(email, password)) return
        val needs2fa = _state.value.needsTwoFactor && !lostAuthenticator
        run(keepTwoFactor = needs2fa) {
            // Success here is usually only the first step of a login; the session
            // comes later, via an emailed code or a passkey. The one exception is
            // a correct authenticator code, which ends the login on the spot -
            // the repository has already opened the session by then.
            val challenge =
                authRepository.login(email, password, totpCode, skipPasskey, lostAuthenticator)
            when {
                // Signed in outright. The session flips and these screens go away.
                challenge.user != null -> AuthFormState()
                // The password checked out and this account prefers a passkey to
                // the mailed code: hold the ceremony open until the device signs.
                challenge.passkeyRequired && challenge.ceremonyToken.isNotBlank() && challenge.challenge != null ->
                    AuthFormState(passkeyPrompt = PasskeyChallenge(challenge.challenge, challenge.ceremonyToken))
                // A blank token would strand the code screen with nothing to send.
                challenge.loginToken.isBlank() ->
                    AuthFormState(error = "Could not start the sign-in. Try again.")
                else -> AuthFormState(loginToken = challenge.loginToken)
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

    // ── Passkeys ──────────────────────────────────────────

    /**
     * A whole sign-in with nothing but a passkey: the device proves the person,
     * so no password or code is asked for on top. `context` must be the
     * Activity - Credential Manager raises its sheet over it.
     */
    fun signInWithPasskey(context: Context) {
        if (_state.value.loading) return
        run {
            val started = authRepository.startPasskeySignIn()
            val response = Passkeys.get(context, optionsOf(started))
            authRepository.finishPasskeySignIn(started.ceremonyToken, response)
            // The session flips and the auth screens are replaced.
            AuthFormState()
        }
    }

    /**
     * Answers a pending ceremony - the one the password bought when the account
     * has a passkey. Dismissing the sheet leaves the prompt in place so the
     * user can retry or pick the emailed code instead.
     */
    fun answerPasskey(context: Context) {
        val prompt = _state.value.passkeyPrompt ?: return
        run(keepPasskey = true) {
            val response = Passkeys.get(context, optionsOf(prompt))
            authRepository.finishPasskeySignIn(prompt.ceremonyToken, response)
            AuthFormState()
        }
    }

    /** Abandons the passkey step; the next login starts from scratch. */
    fun cancelPasskey() { _state.value = AuthFormState() }

    /** The platform wants the inside of `publicKey`, not the envelope. */
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
        block: suspend () -> AuthFormState,
    ) {
        val token = _state.value.loginToken.takeIf { keepLoginToken }
        val prompt = _state.value.passkeyPrompt.takeIf { keepPasskey }
        _state.value = AuthFormState(
            loading = true,
            needsTwoFactor = keepTwoFactor,
            loginToken = token,
            passkeyPrompt = prompt,
        )
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
            } catch (e: Passkeys.Cancelled) {
                // Dismissing the sheet is the common outcome and not a failure.
                _state.value = AuthFormState(
                    passkeyPrompt = prompt,
                    error = "That was cancelled. Try again, or use an email code.",
                )
            } catch (e: Passkeys.NoneAvailable) {
                _state.value = AuthFormState(
                    passkeyPrompt = prompt,
                    error = "No passkey for OrangChat on this device. Try again, or use an email code.",
                )
            } catch (e: Exception) {
                _state.value = AuthFormState(
                    loginToken = token,
                    passkeyPrompt = prompt,
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
