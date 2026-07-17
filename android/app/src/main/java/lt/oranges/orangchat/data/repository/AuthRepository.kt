package lt.oranges.orangchat.data.repository

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import lt.oranges.orangchat.data.local.TokenStore
import lt.oranges.orangchat.data.model.AuthResult
import lt.oranges.orangchat.data.model.SelfUser
import lt.oranges.orangchat.data.remote.ApiService
import lt.oranges.orangchat.data.remote.BackupCodesResult
import lt.oranges.orangchat.data.remote.LoginRequest
import lt.oranges.orangchat.data.remote.SignupRequest
import lt.oranges.orangchat.data.remote.TwoFactorCodeRequest
import lt.oranges.orangchat.data.remote.TwoFactorDisableRequest
import lt.oranges.orangchat.data.remote.TwoFactorEnableResult
import lt.oranges.orangchat.data.remote.TwoFactorPasswordRequest
import lt.oranges.orangchat.data.remote.TwoFactorSetup
import lt.oranges.orangchat.data.remote.TwoFactorStatus
import lt.oranges.orangchat.data.remote.UpdateMeRequest
import lt.oranges.orangchat.data.remote.UploadResponse
import lt.oranges.orangchat.realtime.SocketManager
import lt.oranges.orangchat.notifications.PushTokenRegistrar
import okhttp3.MultipartBody
import javax.inject.Inject
import javax.inject.Singleton

sealed interface SessionState {
    data object Loading : SessionState
    data object Unauthenticated : SessionState
    data class Authenticated(val user: SelfUser) : SessionState
}

@Singleton
class AuthRepository @Inject constructor(
    private val api: ApiService,
    private val tokenStore: TokenStore,
    private val socketManager: SocketManager,
    private val pushTokenRegistrar: PushTokenRegistrar,
) {
    private val _session = MutableStateFlow<SessionState>(SessionState.Loading)
    val session: StateFlow<SessionState> = _session.asStateFlow()

    val currentUser: SelfUser?
        get() = (_session.value as? SessionState.Authenticated)?.user

    /** On cold start: if we have a refresh cookie, /auth/refresh mints a token,
     *  then /auth/me hydrates the profile. Falls back to Unauthenticated. */
    suspend fun restoreSession() {
        _session.value = SessionState.Loading
        try {
            if (tokenStore.accessToken == null) {
                val refreshed = api.refresh()
                applyAuth(refreshed)
            } else {
                val me = api.getMe()
                onAuthenticated(me)
            }
        } catch (_: Exception) {
            try {
                val refreshed = api.refresh()
                applyAuth(refreshed)
            } catch (_: Exception) {
                _session.value = SessionState.Unauthenticated
            }
        }
    }

    suspend fun login(email: String, password: String, totpCode: String? = null) {
        applyAuth(api.login(LoginRequest(email.trim(), password, totpCode?.trim()?.ifBlank { null })))
    }

    suspend fun signup(email: String, username: String, password: String, displayName: String?) {
        applyAuth(
            api.signup(
                SignupRequest(
                    email = email.trim(),
                    username = username.trim(),
                    password = password,
                    displayName = displayName?.trim()?.ifBlank { null },
                ),
            ),
        )
    }

    suspend fun updateMe(patch: UpdateMeRequest): SelfUser {
        val updated = api.patchMe(patch)
        _session.value = SessionState.Authenticated(updated)
        return updated
    }

    /**
     * Patch profile fields. Every parameter is null-by-default meaning "leave
     * alone"; pass "" to clear a field such as the avatar.
     */
    suspend fun updateProfile(
        username: String? = null,
        displayName: String? = null,
        avatarUrl: String? = null,
        bio: String? = null,
        bannerUrl: String? = null,
        accentColor: Int? = null,
        pronouns: String? = null,
        profileCss: String? = null,
    ): SelfUser = updateMe(
        UpdateMeRequest(
            username = username,
            displayName = displayName,
            avatarUrl = avatarUrl,
            bio = bio,
            bannerUrl = bannerUrl,
            accentColor = accentColor,
            pronouns = pronouns,
            profileCss = profileCss,
        ),
    )

    suspend fun updatePrivacy(
        dmPrivacy: String? = null,
        friendRequestPrivacy: String? = null,
        typingIndicators: Boolean? = null,
    ): SelfUser = updateMe(
        UpdateMeRequest(
            dmPrivacy = dmPrivacy,
            friendRequestPrivacy = friendRequestPrivacy,
            typingIndicators = typingIndicators,
        ),
    )

    suspend fun uploadImage(part: MultipartBody.Part, kind: String): UploadResponse =
        api.uploadImage(part, kind)

    // ── Two-factor auth ─────────────────────────────────
    suspend fun twoFactorStatus(): TwoFactorStatus = api.getTwoFactorStatus()

    suspend fun setupTwoFactor(password: String?): TwoFactorSetup =
        api.setupTwoFactor(TwoFactorPasswordRequest(password?.ifBlank { null }))

    suspend fun enableTwoFactor(code: String): TwoFactorEnableResult {
        val result = api.enableTwoFactor(TwoFactorCodeRequest(code.trim()))
        // Reflect the new state in the cached self so settings update at once.
        currentUser?.let { _session.value = SessionState.Authenticated(it.copy(twoFactorEnabled = true)) }
        return result
    }

    suspend fun disableTwoFactor(password: String?, code: String): TwoFactorStatus {
        val result = api.disableTwoFactor(TwoFactorDisableRequest(password?.ifBlank { null }, code.trim()))
        currentUser?.let { _session.value = SessionState.Authenticated(it.copy(twoFactorEnabled = false)) }
        return result
    }

    suspend fun regenerateBackupCodes(password: String?, code: String): BackupCodesResult =
        api.regenerateBackupCodes(TwoFactorDisableRequest(password?.ifBlank { null }, code.trim()))

    suspend fun logout() {
        try {
            api.logout()
        } catch (_: Exception) {
            // Best-effort; clear locally regardless.
        }
        tokenStore.clear()
        socketManager.disconnect()
        _session.value = SessionState.Unauthenticated
    }

    private fun applyAuth(result: AuthResult) {
        tokenStore.setAccessToken(result.tokens.accessToken)
        onAuthenticated(result.user)
    }

    private fun onAuthenticated(user: SelfUser) {
        _session.value = SessionState.Authenticated(user)
        socketManager.connect()
        pushTokenRegistrar.registerCurrentToken()
    }
}
