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
import lt.oranges.orangchat.data.remote.AccountStanding
import lt.oranges.orangchat.data.remote.DeviceSession
import lt.oranges.orangchat.data.remote.TwoFactorSetup
import lt.oranges.orangchat.data.repository.AuthRepository
import lt.oranges.orangchat.data.repository.ServerRepository
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

/** Drives the backend-version line on the System screen. */
sealed interface BackendUi {
    data object Loading : BackendUi
    data class Loaded(val version: String) : BackendUi
    data object Unknown : BackendUi
}

/** Drives the devices list. */
sealed interface SessionsUi {
    data object Loading : SessionsUi
    data class Loaded(val sessions: List<DeviceSession>) : SessionsUi
    data class Failed(val error: String) : SessionsUi
}

/** Drives the account-standing panel on the Security screen. */
sealed interface StandingUi {
    data object Loading : StandingUi
    data class Loaded(val standing: AccountStanding) : StandingUi
    data class Failed(val error: String) : StandingUi
}

/** Drives the "leave all servers" control on the Security screen. */
sealed interface LeaveAllUi {
    data object Idle : LeaveAllUi
    data object Busy : LeaveAllUi
    data class Done(val left: Int, val keptOwned: List<String>) : LeaveAllUi
    data class Failed(val error: String) : LeaveAllUi
}

/** Drives the email/password forms on the Security screen. */
data class CredentialsUi(
    val busy: Boolean = false,
    val error: String? = null,
    /** Success line shown after a change; cleared when a new form is opened. */
    val done: String? = null,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val tokenStore: TokenStore,
    private val authRepository: AuthRepository,
    private val serverRepository: ServerRepository,
    private val e2eeRepository: lt.oranges.orangchat.data.repository.E2eeRepository,
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
    fun setE2eeStrict(on: Boolean) = patchPrivacy(e2eeStrict = on)

    private fun patchPrivacy(
        dmPrivacy: String? = null,
        friendRequestPrivacy: String? = null,
        typingIndicators: Boolean? = null,
        e2eeStrict: Boolean? = null,
    ) {
        viewModelScope.launch {
            _privacyError.value = null
            runCatching {
                authRepository.updatePrivacy(
                    dmPrivacy,
                    friendRequestPrivacy,
                    typingIndicators,
                    e2eeStrict,
                ).also { e2eeRepository.setGlobalStrict(it.e2eeStrict) }
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

    // ── Credentials ─────────────────────────────────────
    private val _credentials = MutableStateFlow(CredentialsUi())
    val credentials: StateFlow<CredentialsUi> = _credentials.asStateFlow()

    fun clearCredentialsMessages() {
        _credentials.value = CredentialsUi()
    }

    fun changePassword(password: String, newPassword: String, code: String, onDone: () -> Unit) {
        viewModelScope.launch {
            _credentials.value = CredentialsUi(busy = true)
            runCatching { authRepository.changePassword(password, newPassword, code) }
                .onSuccess { result ->
                    _credentials.value = CredentialsUi(
                        done = if (result.sessionsRevoked > 0) {
                            val plural = if (result.sessionsRevoked == 1) " was" else "s were"
                            "Password changed. ${result.sessionsRevoked} other session$plural signed out."
                        } else {
                            "Password changed."
                        },
                    )
                    onDone()
                }
                .onFailure {
                    _credentials.value = CredentialsUi(error = it.message ?: "Could not change password")
                }
        }
    }

    /**
     * Irreversible. The repository tears the session down on success, so the app
     * falls back to the sign-in screen on its own - there's no onDone here.
     */
    fun deleteAccount(password: String, username: String, code: String) {
        viewModelScope.launch {
            _credentials.value = CredentialsUi(busy = true)
            runCatching { authRepository.deleteAccount(password, username, code) }
                .onFailure {
                    _credentials.value = CredentialsUi(error = it.message ?: "Could not delete account")
                }
        }
    }

    // ── Lockdown ────────────────────────────────────────
    private val _lockdown = MutableStateFlow(CredentialsUi())
    val lockdown: StateFlow<CredentialsUi> = _lockdown.asStateFlow()

    fun resetLockdown() {
        _lockdown.value = CredentialsUi()
    }

    fun setLockdown(on: Boolean, password: String, onDone: () -> Unit) {
        viewModelScope.launch {
            _lockdown.value = CredentialsUi(busy = true)
            runCatching { authRepository.setLockdown(on, password) }
                .onSuccess { result ->
                    _lockdown.value = CredentialsUi(
                        done = if (result.lockdown && result.sessionsRevoked > 0) {
                            val plural = if (result.sessionsRevoked == 1) "" else "s"
                            "Locked down. Signed out ${result.sessionsRevoked} other session$plural."
                        } else if (result.lockdown) {
                            "Locked down."
                        } else {
                            "Lockdown lifted."
                        },
                    )
                    onDone()
                }
                .onFailure {
                    _lockdown.value = CredentialsUi(error = it.message ?: "Could not change lockdown")
                }
        }
    }

    // ── Devices ─────────────────────────────────────────
    private val _sessions = MutableStateFlow<SessionsUi>(SessionsUi.Loading)
    val sessions: StateFlow<SessionsUi> = _sessions.asStateFlow()

    fun refreshSessions() {
        viewModelScope.launch {
            _sessions.value = SessionsUi.Loading
            runCatching { authRepository.sessions() }
                .onSuccess { _sessions.value = SessionsUi.Loaded(it.sessions) }
                .onFailure { _sessions.value = SessionsUi.Failed(it.message ?: "Could not load devices") }
        }
    }

    fun revokeSession(jti: String) {
        viewModelScope.launch {
            runCatching { authRepository.revokeSession(jti) }
                .onSuccess { refreshSessions() }
                .onFailure { _sessions.value = SessionsUi.Failed(it.message ?: "Could not revoke") }
        }
    }

    fun revokeOtherSessions() {
        viewModelScope.launch {
            runCatching { authRepository.revokeOtherSessions() }
                .onSuccess { refreshSessions() }
                .onFailure { _sessions.value = SessionsUi.Failed(it.message ?: "Could not revoke") }
        }
    }

    // ── Backend health ──────────────────────────────────
    private val _backend = MutableStateFlow<BackendUi>(BackendUi.Loading)
    val backend: StateFlow<BackendUi> = _backend.asStateFlow()

    fun refreshBackend() {
        viewModelScope.launch {
            _backend.value = BackendUi.Loading
            runCatching { authRepository.health() }
                .onSuccess { health ->
                    _backend.value = health.version
                        ?.takeIf { it.isNotBlank() }
                        ?.let { BackendUi.Loaded(it) }
                        ?: BackendUi.Unknown
                }
                .onFailure { _backend.value = BackendUi.Unknown }
        }
    }

    // ── Message wipe ────────────────────────────────────
    private val _wipe = MutableStateFlow(CredentialsUi())
    val wipe: StateFlow<CredentialsUi> = _wipe.asStateFlow()

    fun resetWipe() {
        _wipe.value = CredentialsUi()
    }

    fun deleteAllMessages(password: String, code: String, onDone: () -> Unit) {
        viewModelScope.launch {
            _wipe.value = CredentialsUi(busy = true)
            runCatching { authRepository.deleteAllMessages(password, code) }
                .onSuccess {
                    val plural = if (it.deleted == 1L) "" else "s"
                    _wipe.value = CredentialsUi(done = "Deleted ${it.deleted} message$plural.")
                    onDone()
                }
                .onFailure {
                    _wipe.value = CredentialsUi(error = it.message ?: "Could not delete messages")
                }
        }
    }

    // ── Account standing ────────────────────────────────
    private val _standing = MutableStateFlow<StandingUi>(StandingUi.Loading)
    val standing: StateFlow<StandingUi> = _standing.asStateFlow()

    fun refreshStanding() {
        viewModelScope.launch {
            _standing.value = StandingUi.Loading
            runCatching { authRepository.accountStanding() }
                .onSuccess { _standing.value = StandingUi.Loaded(it) }
                .onFailure { _standing.value = StandingUi.Failed(it.message ?: "Could not load standing") }
        }
    }

    // ── Bulk server actions ─────────────────────────────
    private val _leaveAll = MutableStateFlow<LeaveAllUi>(LeaveAllUi.Idle)
    val leaveAll: StateFlow<LeaveAllUi> = _leaveAll.asStateFlow()

    fun leaveAllServers() {
        viewModelScope.launch {
            _leaveAll.value = LeaveAllUi.Busy
            runCatching { serverRepository.leaveAllServers() }
                .onSuccess { _leaveAll.value = LeaveAllUi.Done(it.left, it.keptOwned) }
                .onFailure { _leaveAll.value = LeaveAllUi.Failed(it.message ?: "Could not leave servers") }
        }
    }

    fun resetLeaveAll() {
        _leaveAll.value = LeaveAllUi.Idle
    }

    fun changeEmail(password: String, email: String, code: String, onDone: () -> Unit) {
        viewModelScope.launch {
            _credentials.value = CredentialsUi(busy = true)
            runCatching { authRepository.changeEmail(password, email, code) }
                .onSuccess {
                    _credentials.value = CredentialsUi(done = "Email changed to ${it.email}.")
                    onDone()
                }
                .onFailure {
                    _credentials.value = CredentialsUi(error = it.message ?: "Could not change email")
                }
        }
    }
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
