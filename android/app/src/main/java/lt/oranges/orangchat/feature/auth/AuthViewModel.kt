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
            authRepository.login(email, password, totpCode)
        }
    }

    fun signup(email: String, username: String, password: String, displayName: String) {
        when {
            email.isBlank() || password.length < 8 || username.length < 2 -> {
                _state.value = AuthFormState(error = "Check your email, a username (2+ chars), and an 8+ char password.")
                return
            }
        }
        run { authRepository.signup(email, username, password, displayName.ifBlank { null }) }
    }

    private fun validate(email: String, password: String): Boolean {
        if (email.isBlank() || password.isBlank()) {
            _state.value = AuthFormState(error = "Enter your email and password.")
            return false
        }
        return true
    }

    private fun run(keepTwoFactor: Boolean = false, block: suspend () -> Unit) {
        _state.value = AuthFormState(loading = true, needsTwoFactor = keepTwoFactor)
        viewModelScope.launch {
            try {
                block()
                // On success the session flips and the auth screens are replaced.
                _state.value = AuthFormState()
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
                    _state.value = AuthFormState(error = mapHttpError(e))
                }
            } catch (e: Exception) {
                _state.value = AuthFormState(error = e.message ?: "Something went wrong. Try again.")
            }
        }
    }

    private fun mapHttpError(e: HttpException): String = when (e.code()) {
        400 -> "Invalid input. Double-check the fields."
        401 -> "Invalid email or password."
        409 -> "That email or username is already taken."
        else -> "Request failed (${e.code()})."
    }

    fun clearError() { _state.value = _state.value.copy(error = null) }

    fun cancelTwoFactor() { _state.value = AuthFormState() }
}
