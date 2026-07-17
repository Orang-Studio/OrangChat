package lt.oranges.orangchat.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import lt.oranges.orangchat.data.local.TokenStore
import lt.oranges.orangchat.data.model.DmPrivacy
import lt.oranges.orangchat.data.model.FriendRequestPrivacy
import lt.oranges.orangchat.data.remote.TwoFactorSetup
import lt.oranges.orangchat.data.repository.AuthRepository
import javax.inject.Inject

const val FONT_SCALE_MIN = 0.85f
const val FONT_SCALE_MAX = 1.5f

/** Device-local display prefs, mirrored out of [TokenStore] as observable state. */
data class DevicePrefs(
    val fontScale: Float = 1f,
    val reducedMotion: Boolean = false,
    val compactMessages: Boolean = false,
    val joinMuted: Boolean = false,
    val joinWithVideo: Boolean = false,
)

/** Drives the 2FA enrollment wizard shown on the Security screen. */
sealed interface TwoFactorUi {
    data object Loading : TwoFactorUi
    data class Off(val error: String? = null) : TwoFactorUi
    data class Setup(val setup: TwoFactorSetup, val verifying: Boolean = false, val error: String? = null) : TwoFactorUi
    data class ShowCodes(val codes: List<String>) : TwoFactorUi
    data class On(val backupCodesRemaining: Int, val busy: Boolean = false, val error: String? = null) : TwoFactorUi
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val tokenStore: TokenStore,
    private val authRepository: AuthRepository,
) : ViewModel() {

    private val _prefs = MutableStateFlow(
        DevicePrefs(
            fontScale = tokenStore.fontScale,
            reducedMotion = tokenStore.reducedMotion,
            compactMessages = tokenStore.compactMessages,
            joinMuted = tokenStore.joinMuted,
            joinWithVideo = tokenStore.joinWithVideo,
        ),
    )
    val prefs: StateFlow<DevicePrefs> = _prefs.asStateFlow()

    fun setFontScale(value: Float) {
        val clamped = value.coerceIn(FONT_SCALE_MIN, FONT_SCALE_MAX)
        tokenStore.fontScale = clamped
        _prefs.value = _prefs.value.copy(fontScale = clamped)
    }

    fun setReducedMotion(on: Boolean) {
        tokenStore.reducedMotion = on
        _prefs.value = _prefs.value.copy(reducedMotion = on)
    }

    fun setCompactMessages(on: Boolean) {
        tokenStore.compactMessages = on
        _prefs.value = _prefs.value.copy(compactMessages = on)
    }

    fun setJoinMuted(on: Boolean) {
        tokenStore.joinMuted = on
        _prefs.value = _prefs.value.copy(joinMuted = on)
    }

    fun setJoinWithVideo(on: Boolean) {
        tokenStore.joinWithVideo = on
        _prefs.value = _prefs.value.copy(joinWithVideo = on)
    }

    fun resetPrefs() {
        _prefs.value = DevicePrefs()
        tokenStore.fontScale = 1f
        tokenStore.reducedMotion = false
        tokenStore.compactMessages = false
        tokenStore.joinMuted = false
        tokenStore.joinWithVideo = false
    }

    // ── Privacy ─────────────────────────────────────────
    private val _privacyError = MutableStateFlow<String?>(null)
    val privacyError: StateFlow<String?> = _privacyError.asStateFlow()

    fun setDmPrivacy(value: DmPrivacy) = patchPrivacy(dmPrivacy = value.wire())
    fun setFriendRequestPrivacy(value: FriendRequestPrivacy) =
        patchPrivacy(friendRequestPrivacy = value.wire())
    fun setTypingIndicators(on: Boolean) = patchPrivacy(typingIndicators = on)

    private fun patchPrivacy(
        dmPrivacy: String? = null,
        friendRequestPrivacy: String? = null,
        typingIndicators: Boolean? = null,
    ) {
        viewModelScope.launch {
            _privacyError.value = null
            runCatching {
                authRepository.updatePrivacy(dmPrivacy, friendRequestPrivacy, typingIndicators)
            }.onFailure { _privacyError.value = it.message ?: "Could not save your privacy settings" }
        }
    }

    // ── Two-factor ──────────────────────────────────────
    private val _twoFactor = MutableStateFlow<TwoFactorUi>(TwoFactorUi.Loading)
    val twoFactor: StateFlow<TwoFactorUi> = _twoFactor.asStateFlow()

    fun refreshTwoFactor() {
        viewModelScope.launch {
            _twoFactor.value = TwoFactorUi.Loading
            runCatching { authRepository.twoFactorStatus() }
                .onSuccess {
                    _twoFactor.value =
                        if (it.enabled) TwoFactorUi.On(it.backupCodesRemaining) else TwoFactorUi.Off()
                }
                .onFailure { _twoFactor.value = TwoFactorUi.Off(it.message) }
        }
    }

    fun beginSetup(password: String) {
        viewModelScope.launch {
            _twoFactor.value = TwoFactorUi.Loading
            runCatching { authRepository.setupTwoFactor(password) }
                .onSuccess { _twoFactor.value = TwoFactorUi.Setup(it) }
                .onFailure { _twoFactor.value = TwoFactorUi.Off(it.message ?: "Could not start setup") }
        }
    }

    fun confirmSetup(code: String) {
        val current = _twoFactor.value as? TwoFactorUi.Setup ?: return
        viewModelScope.launch {
            _twoFactor.value = current.copy(verifying = true, error = null)
            runCatching { authRepository.enableTwoFactor(code) }
                .onSuccess { _twoFactor.value = TwoFactorUi.ShowCodes(it.backupCodes) }
                .onFailure {
                    _twoFactor.value = current.copy(verifying = false, error = it.message ?: "That code isn't right")
                }
        }
    }

    fun disable(password: String, code: String) {
        val current = _twoFactor.value as? TwoFactorUi.On ?: return
        viewModelScope.launch {
            _twoFactor.value = current.copy(busy = true, error = null)
            runCatching { authRepository.disableTwoFactor(password, code) }
                .onSuccess { _twoFactor.value = TwoFactorUi.Off() }
                .onFailure {
                    _twoFactor.value = current.copy(busy = false, error = it.message ?: "That code isn't right")
                }
        }
    }

    fun regenerateCodes(password: String, code: String) {
        val current = _twoFactor.value as? TwoFactorUi.On ?: return
        viewModelScope.launch {
            _twoFactor.value = current.copy(busy = true, error = null)
            runCatching { authRepository.regenerateBackupCodes(password, code) }
                .onSuccess { _twoFactor.value = TwoFactorUi.ShowCodes(it.backupCodes) }
                .onFailure {
                    _twoFactor.value = current.copy(busy = false, error = it.message ?: "That code isn't right")
                }
        }
    }

    fun dismissCodes() = refreshTwoFactor()
}

private fun DmPrivacy.wire() = when (this) {
    DmPrivacy.EVERYONE -> "everyone"
    DmPrivacy.FRIENDS -> "friends"
    DmPrivacy.NONE -> "none"
}

private fun FriendRequestPrivacy.wire() = when (this) {
    FriendRequestPrivacy.EVERYONE -> "everyone"
    FriendRequestPrivacy.MUTUAL -> "mutual"
    FriendRequestPrivacy.NONE -> "none"
}
