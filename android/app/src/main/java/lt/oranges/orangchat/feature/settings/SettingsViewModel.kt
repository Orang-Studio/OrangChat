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
import lt.oranges.orangchat.data.remote.UpdateMeRequest
import lt.oranges.orangchat.data.remote.DeviceSession
import lt.oranges.orangchat.data.remote.Passkey
import lt.oranges.orangchat.data.remote.TwoFactorSetup
import lt.oranges.orangchat.data.repository.AuthRepository
import lt.oranges.orangchat.data.repository.ServerRepository
import lt.oranges.orangchat.feature.auth.Passkeys
import lt.oranges.orangchat.util.AppStrings
import lt.oranges.orangchat.R
import retrofit2.HttpException
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
    val notificationPreviews: Boolean = true,
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

/**
 * Drives the passkeys section on the Security screen. [busy] covers the whole
 * section so a rename or removal in flight disables the add form too.
 */
data class PasskeysUi(
    val loading: Boolean = true,
    val passkeys: List<Passkey> = emptyList(),
    val max: Int = 0,
    val busy: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val appContext: android.content.Context,
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
            notificationPreviews = tokenStore.notificationPreviews,
        ),
    )
    val prefs: StateFlow<DevicePrefs> = _prefs.asStateFlow()

    private val _appIconUploading = MutableStateFlow(false)
    val appIconUploading: StateFlow<Boolean> = _appIconUploading.asStateFlow()
    private val _appIconError = MutableStateFlow<String?>(null)
    val appIconError: StateFlow<String?> = _appIconError.asStateFlow()

    /**
     * Upload a picked image and adopt it as this account's app icon. Android
     * cannot repoint its own launcher icon at an arbitrary image (only
     * activity-alias entries with baked-in drawables can be toggled), so what
     * this changes is the web tab favicon and the desktop window/tray icon.
     */
    fun uploadAppIcon(uri: android.net.Uri) {
        viewModelScope.launch {
            _appIconUploading.value = true
            _appIconError.value = null
            runCatching {
                val part = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    lt.oranges.orangchat.util.buildImagePart(appContext, uri)
                }
                val uploaded = authRepository.uploadImage(part, "app-icon")
                authRepository.updateMe(UpdateMeRequest(appIconUrl = uploaded.url))
            }.onFailure { _appIconError.value = it.message ?: AppStrings.get(appContext, R.string.catalog_upload_failed_ad0d0603) }
            _appIconUploading.value = false
        }
    }

    /** "" clears it - a null field never reaches the wire; see UpdateMeRequest. */
    fun removeAppIcon() {
        viewModelScope.launch {
            runCatching { authRepository.updateMe(UpdateMeRequest(appIconUrl = "")) }
                .onFailure { _appIconError.value = it.message ?: AppStrings.get(appContext, R.string.catalog_could_not_remove_the_icon_fdb51d9b) }
        }
    }

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

    fun setNotificationPreviews(on: Boolean) {
        tokenStore.notificationPreviews = on
        _prefs.value = _prefs.value.copy(notificationPreviews = on)
    }

    /**
     * Whether the notification question has been put to this install already.
     *
     * Deliberately not part of [DevicePrefs]: it is a record of something that
     * happened, not a setting anyone chose, and [resetPrefs] restoring display
     * defaults should not re-open a question the user has already answered.
     */
    val notificationPermissionAsked: Boolean
        get() = tokenStore.notificationPermissionAsked

    fun markNotificationPermissionAsked() {
        tokenStore.notificationPermissionAsked = true
    }

    fun resetPrefs() {
        _prefs.value = DevicePrefs()
        tokenStore.fontScale = 1f
        tokenStore.reducedMotion = false
        tokenStore.compactMessages = false
        tokenStore.joinMuted = false
        tokenStore.joinWithVideo = false
        tokenStore.notificationPreviews = true
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
            }.onFailure { _privacyError.value = it.message ?: AppStrings.get(appContext, R.string.catalog_could_not_save_your_privacy_settings_fddd2cf0) }
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
                .onFailure { _twoFactor.value = TwoFactorUi.Off(it.message ?: AppStrings.get(appContext, R.string.catalog_could_not_start_setup_0c01e9d7)) }
        }
    }

    fun confirmSetup(code: String) {
        val current = _twoFactor.value as? TwoFactorUi.Setup ?: return
        viewModelScope.launch {
            _twoFactor.value = current.copy(verifying = true, error = null)
            runCatching { authRepository.enableTwoFactor(code) }
                .onSuccess { _twoFactor.value = TwoFactorUi.ShowCodes(it.backupCodes) }
                .onFailure {
                    _twoFactor.value = current.copy(verifying = false, error = it.message ?: AppStrings.get(appContext, R.string.catalog_that_code_isn_t_right_e78cef5d))
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
                    _twoFactor.value = current.copy(busy = false, error = it.message ?: AppStrings.get(appContext, R.string.catalog_that_code_isn_t_right_e78cef5d))
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
                    _twoFactor.value = current.copy(busy = false, error = it.message ?: AppStrings.get(appContext, R.string.catalog_that_code_isn_t_right_e78cef5d))
                }
        }
    }

    fun dismissCodes() = refreshTwoFactor()

    // ── Passkeys ────────────────────────────────────────
    private val _passkeys = MutableStateFlow(PasskeysUi())
    val passkeys: StateFlow<PasskeysUi> = _passkeys.asStateFlow()

    fun refreshPasskeys() {
        viewModelScope.launch {
            _passkeys.value = PasskeysUi(loading = true)
            runCatching { authRepository.passkeys() }
                .onSuccess {
                    _passkeys.value = PasskeysUi(loading = false, passkeys = it.passkeys, max = it.max)
                }
                .onFailure {
                    _passkeys.value = PasskeysUi(
                        loading = false,
                        error = passkeyMessage(it, AppStrings.get(appContext, R.string.catalog_could_not_load_passkeys_dd561090)),
                    )
                }
        }
    }

    /**
     * Adds a passkey: the password (plus a 2FA code when on) buys the ceremony,
     * the device enrols a credential, and finish registers it. `context` must be
     * the Activity - Credential Manager raises its sheet over it.
     */
    fun addPasskey(
        context: android.content.Context,
        name: String,
        password: String,
        code: String,
        onDone: () -> Unit,
    ) {
        val current = _passkeys.value
        viewModelScope.launch {
            _passkeys.value = current.copy(busy = true, error = null)
            runCatching {
                val started = authRepository.startPasskeyRegistration(password, code)
                val options = Passkeys.optionsOf(started.challenge)
                    ?: throw IllegalStateException("The server sent an empty passkey request.")
                val response = Passkeys.create(context, options)
                authRepository.finishPasskeyRegistration(started.ceremonyToken, name, response)
            }
                .onSuccess { refreshPasskeys(); onDone() }
                .onFailure { _passkeys.value = current.copy(busy = false, error = passkeyMessage(it, AppStrings.get(appContext, R.string.catalog_that_didn_t_work_try_again_9ae914fa))) }
        }
    }

    fun renamePasskey(id: String, name: String, onDone: () -> Unit) {
        val current = _passkeys.value
        viewModelScope.launch {
            _passkeys.value = current.copy(busy = true, error = null)
            runCatching { authRepository.renamePasskey(id, name) }
                .onSuccess { refreshPasskeys(); onDone() }
                .onFailure {
                    _passkeys.value = current.copy(busy = false, error = passkeyMessage(it, AppStrings.get(appContext, R.string.catalog_could_not_rename_deb92092)))
                }
        }
    }

    fun removePasskey(
        id: String,
        password: String,
        code: String,
        onDone: () -> Unit,
    ) {
        val current = _passkeys.value
        viewModelScope.launch {
            _passkeys.value = current.copy(busy = true, error = null)
            runCatching { authRepository.deletePasskey(id, password, code) }
                .onSuccess { refreshPasskeys(); onDone() }
                .onFailure {
                    _passkeys.value =
                        current.copy(busy = false, error = passkeyMessage(it, AppStrings.get(appContext, R.string.catalog_could_not_remove_the_passkey_5245ad98)))
                }
        }
    }

    fun clearPasskeyError() { _passkeys.value = _passkeys.value.copy(error = null) }

    /**
     * Dismissing the system sheet is the common outcome and not a failure.
     *
     * Anything the server rejected explains itself in its own JSON body; without
     * digging that out, [HttpException.message] is only ever "HTTP 400 Bad
     * Request", which tells the person at the phone nothing they can act on.
     */
    private fun passkeyMessage(e: Throwable, fallback: String): String = when (e) {
        is Passkeys.Cancelled -> AppStrings.get(appContext, R.string.catalog_that_was_cancelled_b8e83864)
        is HttpException -> serverMessage(e) ?: when (e.code()) {
            401 -> AppStrings.get(appContext, R.string.catalog_incorrect_password_b68db3d0)
            429 -> AppStrings.get(appContext, R.string.catalog_too_many_attempts_wait_a_moment_and_585a4cea)
            else -> fallback
        }
        else -> e.message ?: fallback
    }

    /** The `error` field of AppError's JSON body, when the response carries one. */
    private fun serverMessage(e: HttpException): String? =
        runCatching { e.response()?.errorBody()?.string() }.getOrNull()
            ?.let { Regex("\"error\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(it) }
            ?.groupValues?.get(1)
            ?.replace("\\\"", "\"")
            ?.takeIf { it.isNotBlank() }

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
                            if (result.sessionsRevoked == 1)
                                AppStrings.get(appContext, R.string.catalog_password_changed_1_s_other_session_was_10fc8ee3, result.sessionsRevoked)
                            else
                                AppStrings.get(appContext, R.string.catalog_password_changed_1_s_other_sessions_were_64e22cd3, result.sessionsRevoked)
                        } else {
                            AppStrings.get(appContext, R.string.catalog_password_changed_47fa5289)
                        },
                    )
                    onDone()
                }
                .onFailure {
                    _credentials.value = CredentialsUi(error = it.message ?: AppStrings.get(appContext, R.string.catalog_could_not_change_password_ccfd53b0))
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
                    _credentials.value = CredentialsUi(error = it.message ?: AppStrings.get(appContext, R.string.catalog_could_not_delete_account_b721f0dc))
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
                            if (result.sessionsRevoked == 1)
                                AppStrings.get(appContext, R.string.catalog_locked_down_signed_out_1_s_other_d382c238, result.sessionsRevoked)
                            else
                                AppStrings.get(appContext, R.string.catalog_locked_down_signed_out_1_s_other_ecca9d9f, result.sessionsRevoked)
                        } else if (result.lockdown) {
                            AppStrings.get(appContext, R.string.catalog_locked_down_1dc1036e)
                        } else {
                            AppStrings.get(appContext, R.string.catalog_lockdown_lifted_f4f1b05c)
                        },
                    )
                    onDone()
                }
                .onFailure {
                    _lockdown.value = CredentialsUi(error = it.message ?: AppStrings.get(appContext, R.string.catalog_could_not_change_lockdown_fec83d39))
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
                .onFailure { _sessions.value = SessionsUi.Failed(it.message ?: AppStrings.get(appContext, R.string.catalog_could_not_load_devices_6cf850e0)) }
        }
    }

    fun revokeSession(jti: String) {
        viewModelScope.launch {
            runCatching { authRepository.revokeSession(jti) }
                .onSuccess { refreshSessions() }
                .onFailure { _sessions.value = SessionsUi.Failed(it.message ?: AppStrings.get(appContext, R.string.catalog_could_not_revoke_361c2e4b)) }
        }
    }

    fun revokeOtherSessions() {
        viewModelScope.launch {
            runCatching { authRepository.revokeOtherSessions() }
                .onSuccess { refreshSessions() }
                .onFailure { _sessions.value = SessionsUi.Failed(it.message ?: AppStrings.get(appContext, R.string.catalog_could_not_revoke_361c2e4b)) }
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
                    _wipe.value = CredentialsUi(
                        done = if (it.deleted == 1L) AppStrings.get(appContext, R.string.catalog_deleted_1_s_message_8ffc45b2, it.deleted)
                        else AppStrings.get(appContext, R.string.catalog_deleted_1_s_messages_9ec1f966, it.deleted),
                    )
                    onDone()
                }
                .onFailure {
                    _wipe.value = CredentialsUi(error = it.message ?: AppStrings.get(appContext, R.string.catalog_could_not_delete_messages_6af8eeaf))
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
                .onFailure { _standing.value = StandingUi.Failed(it.message ?: AppStrings.get(appContext, R.string.catalog_could_not_load_standing_51712f13)) }
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
                .onFailure { _leaveAll.value = LeaveAllUi.Failed(it.message ?: AppStrings.get(appContext, R.string.catalog_could_not_leave_servers_eb0a0647)) }
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
                    _credentials.value = CredentialsUi(done = AppStrings.get(appContext, R.string.catalog_email_changed_to_1_s_2755e486, it.email))
                    onDone()
                }
                .onFailure {
                    _credentials.value = CredentialsUi(error = it.message ?: AppStrings.get(appContext, R.string.catalog_could_not_change_email_0f20cf3a))
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
