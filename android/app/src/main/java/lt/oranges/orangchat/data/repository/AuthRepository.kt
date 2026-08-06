package lt.oranges.orangchat.data.repository

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import lt.oranges.orangchat.data.local.TokenStore
import lt.oranges.orangchat.data.local.OfflineCache
import lt.oranges.orangchat.data.model.AuthResult
import lt.oranges.orangchat.data.model.SelfUser
import lt.oranges.orangchat.data.remote.ApiService
import lt.oranges.orangchat.data.remote.AccountStanding
import lt.oranges.orangchat.data.remote.BackupCodesResult
import lt.oranges.orangchat.data.remote.ChangeEmailRequest
import lt.oranges.orangchat.data.remote.ChangeEmailResult
import lt.oranges.orangchat.data.remote.ChangePasswordRequest
import lt.oranges.orangchat.data.remote.ChangePasswordResult
import lt.oranges.orangchat.data.remote.DeleteAccountRequest
import lt.oranges.orangchat.data.remote.DeleteAccountResult
import lt.oranges.orangchat.data.remote.DeleteAllMessagesRequest
import lt.oranges.orangchat.data.remote.DeleteAllMessagesResult
import lt.oranges.orangchat.data.remote.EmailTwoFactorRequest
import lt.oranges.orangchat.data.remote.ResendEmailTwoFactorRequest
import lt.oranges.orangchat.data.remote.LockdownRequest
import lt.oranges.orangchat.data.remote.LockdownResult
import lt.oranges.orangchat.data.remote.QrTokenRequest
import lt.oranges.orangchat.data.remote.LoginRequest
import lt.oranges.orangchat.data.remote.RevokeResult
import lt.oranges.orangchat.data.remote.SessionsResult
import lt.oranges.orangchat.data.remote.SignupRequest
import lt.oranges.orangchat.data.remote.TwoFactorCodeRequest
import lt.oranges.orangchat.data.model.LoginChallenge
import lt.oranges.orangchat.data.remote.Passkey
import lt.oranges.orangchat.data.remote.PasskeyChallenge
import lt.oranges.orangchat.data.remote.PasskeyFinishRequest
import lt.oranges.orangchat.data.remote.PasskeyListResult
import lt.oranges.orangchat.data.remote.PasskeyNameRequest
import lt.oranges.orangchat.data.remote.PasskeyRegisterFinishRequest
import lt.oranges.orangchat.data.remote.TwoFactorDisableRequest
import lt.oranges.orangchat.data.remote.TwoFactorEnableResult
import lt.oranges.orangchat.data.remote.TwoFactorPasswordRequest
import lt.oranges.orangchat.data.remote.TwoFactorSetup
import lt.oranges.orangchat.data.remote.TwoFactorStatus
import lt.oranges.orangchat.data.remote.UpdateMeRequest
import lt.oranges.orangchat.data.remote.UploadResponse
import lt.oranges.orangchat.realtime.SocketManager
import lt.oranges.orangchat.notifications.PushTokenRegistrar
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import okhttp3.MultipartBody
import retrofit2.HttpException
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
    private val json: Json,
    private val offlineCache: OfflineCache,
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
            return
        } catch (_: Exception) {
            // Fall through to one refresh attempt; the access token may simply
            // have expired while the app was closed.
        }

        try {
            applyAuth(api.refresh())
        } catch (e: Exception) {
            // Only the server saying no ends a session. A dropped link, a DNS
            // failure or a 5xx means the account is fine and unreachable, so the
            // last known profile is restored and the socket reconnects behind it.
            // Signing out here is what made a lost wifi look like a logout.
            if (isSessionRejected(e)) {
                cachedUser()?.id?.let { offlineCache.clear(it) }
                tokenStore.clear()
                _session.value = SessionState.Unauthenticated
            } else {
                val cached = cachedUser()
                if (cached != null) onAuthenticated(cached)
                else _session.value = SessionState.Unauthenticated
            }
        }
    }

    /**
     * True only for a definitive rejection from the server, never for reachability.
     *
     * The status code on its own does not establish that, because anything
     * between the phone and us can answer with one: captive portals, corporate
     * proxies and CDNs under strain all serve 401/403 HTML pages, and believing
     * one of those signs the user out of a perfectly good account. A real
     * rejection carries our own JSON error body (AppError's IntoResponse), and
     * `/auth/refresh` only ever raises Unauthorized - a 403 on this route came
     * from something in the middle, so it counts as unreachable.
     */
    private fun isSessionRejected(e: Exception): Boolean {
        if (e !is HttpException || e.code() != 401) return false
        val body = e.response()?.errorBody() ?: return false
        if (body.contentType()?.subtype?.contains("json", ignoreCase = true) != true) return false
        return runCatching {
            json.parseToJsonElement(body.string()).jsonObject.containsKey("error")
        }.getOrDefault(false)
    }

    private fun cachedUser(): SelfUser? =
        tokenStore.cachedUser?.let {
            runCatching { json.decodeFromString(SelfUser.serializer(), it) }.getOrNull()
        }

    /**
     * First half of a login. A correct password buys one second factor - a
     * passkey, an authenticator code, or a mailed one - so this usually returns
     * the challenge that finishes the sign-in rather than flipping [session].
     *
     * The exception is an authenticator code sent with the password: that rung
     * ends the login, so the session is opened here and the caller sees a
     * challenge carrying [LoginChallenge.user]. [lostAuthenticator] steps past
     * that rung down to the mailed code, for a phone left at home.
     */
    suspend fun login(
        email: String,
        password: String,
        totpCode: String? = null,
        skipPasskey: Boolean = false,
        lostAuthenticator: Boolean = false,
    ): LoginChallenge =
        api.login(
            LoginRequest(
                email = email.trim(),
                password = password,
                totpCode = totpCode?.trim()?.ifBlank { null },
                skipPasskey = true.takeIf { skipPasskey },
                lostAuthenticator = true.takeIf { lostAuthenticator },
            ),
        ).also { challenge ->
            val user = challenge.user
            val tokens = challenge.tokens
            if (user != null && tokens != null) applyAuth(AuthResult(user, tokens))
        }

    // ── Passkeys ────────────────────────────────────────

    /** Opens a sign-in ceremony for a device that hasn't said who it is. */
    suspend fun startPasskeySignIn(): PasskeyChallenge = api.startPasskeySignIn()

    /**
     * Closes a ceremony - either shape - and opens the session. This is the whole
     * login: the authenticator already proved the device, the person and the
     * origin, so nothing weaker is asked for on top.
     */
    suspend fun finishPasskeySignIn(ceremonyToken: String, response: JsonElement) {
        applyAuth(api.finishPasskeySignIn(PasskeyFinishRequest(ceremonyToken, response)))
    }

    suspend fun passkeys(): PasskeyListResult = api.getPasskeys()

    suspend fun startPasskeyRegistration(password: String?, code: String): PasskeyChallenge =
        api.startPasskeyRegistration(TwoFactorDisableRequest(password?.ifBlank { null }, code.trim()))

    suspend fun finishPasskeyRegistration(
        ceremonyToken: String,
        name: String,
        response: JsonElement,
    ): Passkey = api.finishPasskeyRegistration(
        PasskeyRegisterFinishRequest(ceremonyToken, name.trim(), response),
    ).passkey

    suspend fun renamePasskey(id: String, name: String): Passkey =
        api.renamePasskey(id, PasskeyNameRequest(name.trim())).passkey

    suspend fun deletePasskey(id: String, password: String?, code: String) {
        api.deletePasskey(id, TwoFactorDisableRequest(password?.ifBlank { null }, code.trim()))
    }

    /** Second half: the mailed code is what actually opens the session. */
    suspend fun verifyEmailCode(loginToken: String, code: String) {
        applyAuth(api.verifyEmailTwoFactor(EmailTwoFactorRequest(loginToken, code.trim())))
    }

    suspend fun resendEmailCode(loginToken: String) {
        api.resendEmailTwoFactor(ResendEmailTwoFactorRequest(loginToken))
    }

    /** Creates the account; it stays unusable until the emailed link is opened. */
    suspend fun signup(email: String, username: String, password: String, displayName: String?) {
        api.signup(
            SignupRequest(
                email = email.trim(),
                username = username.trim(),
                password = password,
                displayName = displayName?.trim()?.ifBlank { null },
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
        e2eeStrict: Boolean? = null,
    ): SelfUser = updateMe(
        UpdateMeRequest(
            dmPrivacy = dmPrivacy,
            friendRequestPrivacy = friendRequestPrivacy,
            typingIndicators = typingIndicators,
            e2eeStrict = e2eeStrict,
        ),
    )

    suspend fun uploadImage(part: MultipartBody.Part, kind: String): UploadResponse =
        api.uploadImage(part, kind)

    /** The running backend's health payload, including its version. */
    suspend fun health() = api.health()

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

    suspend fun accountStanding(): AccountStanding = api.getAccountStanding()

    // ── Devices ─────────────────────────────────────────
    suspend fun sessions(): SessionsResult = api.getSessions()

    suspend fun revokeSession(jti: String): RevokeResult = api.revokeSession(jti)

    suspend fun revokeOtherSessions(): RevokeResult = api.revokeOtherSessions()

    /** QR sign-in: tell the server this phone scanned a web code. */
    suspend fun qrScan(token: String) = api.qrScan(QrTokenRequest(token))

    /** QR sign-in: approve the code, opening a web session for this account. */
    suspend fun qrApprove(token: String) = api.qrApprove(QrTokenRequest(token))

    /** Freezes/unfreezes the account; the password is only needed to lift it. */
    suspend fun setLockdown(on: Boolean, password: String?): LockdownResult {
        val result = api.setLockdown(LockdownRequest(on, password?.ifBlank { null }))
        currentUser?.let {
            _session.value = SessionState.Authenticated(it.copy(lockdown = result.lockdown))
        }
        return result
    }

    // ── Credentials ─────────────────────────────────────
    /**
     * The server revokes every refresh token on success, including this device's.
     * The access token stays valid until it expires, so the session survives long
     * enough to show the result and the next refresh is what signs us out.
     */
    suspend fun changePassword(password: String?, newPassword: String, code: String): ChangePasswordResult {
        val result = api.changePassword(
            ChangePasswordRequest(password?.ifBlank { null }, newPassword, code.trim()),
        )
        currentUser?.let { _session.value = SessionState.Authenticated(it.copy(hasPassword = true)) }
        return result
    }

    suspend fun changeEmail(password: String?, email: String, code: String): ChangeEmailResult {
        val result = api.changeEmail(
            ChangeEmailRequest(password?.ifBlank { null }, email.trim(), code.trim()),
        )
        currentUser?.let { _session.value = SessionState.Authenticated(it.copy(email = result.email)) }
        return result
    }

    /** Irreversible: wipes the user's messages everywhere, including left servers. */
    suspend fun deleteAllMessages(password: String?, code: String): DeleteAllMessagesResult =
        api.deleteAllMessages(DeleteAllMessagesRequest(password?.ifBlank { null }, code.trim()))

    /** Irreversible. On success the local session is torn down like a sign-out. */
    suspend fun deleteAccount(password: String?, username: String, code: String): DeleteAccountResult {
        val userId = currentUser?.id
        val result = api.deleteAccount(
            DeleteAccountRequest(password?.ifBlank { null }, username.trim(), code.trim()),
        )
        userId?.let { offlineCache.clear(it) }
        tokenStore.clear()
        socketManager.disconnect()
        _session.value = SessionState.Unauthenticated
        return result
    }

    suspend fun logout() {
        val userId = currentUser?.id
        try {
            api.logout()
        } catch (_: Exception) {
            // Best-effort; clear locally regardless.
        }
        userId?.let { offlineCache.clear(it) }
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
        // Kept for the offline cold start in restoreSession.
        tokenStore.cachedUser =
            runCatching { json.encodeToString(SelfUser.serializer(), user) }.getOrNull()
        socketManager.connect()
        pushTokenRegistrar.registerCurrentToken()
    }
}
