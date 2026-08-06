package lt.oranges.orangchat.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import lt.oranges.orangchat.data.model.E2eeDevice
import lt.oranges.orangchat.data.remote.ApiService
import lt.oranges.orangchat.data.repository.AuthRepository
import lt.oranges.orangchat.data.repository.E2eeRepository

/**
 * Backs the Encryption settings screen. The device list is *verified* here
 * rather than displayed as received: showing a list the server handed over
 * without replaying its log would make the screen a place a fake device could
 * hide in plain sight.
 */
@HiltViewModel
class EncryptionViewModel @Inject constructor(
    private val api: ApiService,
    private val e2ee: E2eeRepository,
    private val auth: AuthRepository,
) : ViewModel() {

    enum class TransferRole { NEW, OLD }
    enum class TransferStep { IDLE, STARTING, QR, WAITING, SAS, TOTP, FINISHING, DONE }

    data class State(
        val deviceId: String? = null,
        val devices: List<E2eeDevice> = emptyList(),
        val head: Pair<Int, String>? = null,
        val myCode: String? = null,
        val error: String? = null,
        val transferRole: TransferRole? = null,
        val transferStep: TransferStep = TransferStep.IDLE,
        val transferQr: String? = null,
        val transferSas: String? = null,
        val transferError: String? = null,
        /**
         * True when this account has an authenticator app enrolled, so the
         * transfer takes a TOTP code; false means an emailed one-time code.
         */
        val hasTwoFactor: Boolean = true,
        /**
         * Non-null once an email code has been requested for this transfer;
         * the value is the token the code must be presented with.
         */
        val transferLoginToken: String? = null,
        val requestingEmailCode: Boolean = false,
        val revokingDeviceId: String? = null,
        val notice: String? = null,
        /**
         * This phone still holds an identity, but the account's own log no
         * longer authorizes it - another device revoked it. Until that is
         * cleared out this phone cannot do anything as an encryption device.
         */
        val revokedHere: Boolean = false,
        val resetting: Boolean = false,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private var newHandshake: E2eeRepository.NewDeviceHandshake? = null
    private var oldHandshake: E2eeRepository.OldDeviceHandshake? = null

    init {
        refresh()
    }

    fun refresh() = viewModelScope.launch {
        val local = e2ee.identity()
        _state.value = _state.value.copy(
            deviceId = local?.deviceId,
            myCode = e2ee.myContactQr(),
            hasTwoFactor = auth.currentUser?.twoFactorEnabled == true,
        )
        runCatching {
            val list = api.getMyE2eeDevices()
            // The verified set is the answer to "is this phone still a device?".
            // Reading revokedAt off the list instead would trust the server on
            // the one question the log exists to settle.
            e2ee.verifyList(list) to list
        }.onSuccess { (verified, list) ->
            _state.value = _state.value.copy(
                devices = list.devices,
                head = list.head?.let { it.seq to it.entryHash },
                revokedHere = local != null && local.deviceId !in verified.authorizedDeviceIds,
                error = null,
            )
        }.onFailure {
            _state.value = _state.value.copy(error = it.message)
        }
    }

    /**
     * Clears out an identity this account no longer authorizes and goes
     * straight into adding this phone again - the two are one action to the
     * person holding a phone that just stopped working.
     */
    fun setUpThisPhoneAgain() {
        _state.value = _state.value.copy(resetting = true, error = null, notice = null)
        viewModelScope.launch {
            runCatching { e2ee.forgetRevokedIdentity() }
                .onSuccess { forgotten ->
                    _state.value = _state.value.copy(resetting = false)
                    if (forgotten) {
                        // With no authorized device left there is nothing to
                        // transfer from, and this phone starts a new identity
                        // rather than waiting on a device that cannot come.
                        val userId = auth.currentUser?.id
                        val canTransfer = _state.value.devices.any { it.revokedAt == null }
                        if (!canTransfer && userId != null) {
                            runCatching { e2ee.enrol(userId) }
                                .onFailure {
                                    _state.value = _state.value.copy(error = it.message)
                                }
                            refresh()
                        } else {
                            refresh()
                            openAddThisDevice()
                        }
                    } else {
                        // The log says it is still authorized after all.
                        _state.value = _state.value.copy(revokedHere = false)
                        refresh()
                    }
                }
                .onFailure {
                    _state.value = _state.value.copy(
                        resetting = false,
                        error = it.message ?: "This phone could not be set up again.",
                    )
                }
        }
    }

    /** This phone has no identity: show its QR, then wait for the PC to scan. */
    fun addThisDevice() = startNewDevice(null)

    /** Explain the preferred PC-shows/phone-scans flow before offering fallback. */
    fun openAddThisDevice() {
        _state.value = _state.value.copy(
            transferRole = TransferRole.NEW,
            transferStep = TransferStep.IDLE,
            transferQr = null,
            transferSas = null,
            transferError = null,
            transferLoginToken = null,
            requestingEmailCode = false,
        )
    }

    /** Preferred desktop flow: this phone scanned the one-time code on the PC. */
    fun handleScannedTransfer(raw: String) {
        if (lt.oranges.orangchat.crypto.E2eeQr.isDeviceTransferInvite(raw)) {
            startNewDevice(raw)
        } else {
            addAnotherDevice(raw)
        }
    }

    private fun startNewDevice(invitation: String?) {
        val userId = auth.currentUser?.id
        if (userId == null) {
            _state.value = _state.value.copy(transferError = "Sign in before adding this device.")
            return
        }
        _state.value = _state.value.copy(
            transferRole = TransferRole.NEW,
            transferStep = TransferStep.STARTING,
            transferError = null,
        )
        viewModelScope.launch {
            runCatching {
                if (invitation == null) {
                    e2ee.beginDeviceTransfer(userId)
                } else {
                    e2ee.beginDeviceTransferFromInvitation(userId, invitation)
                }
            }
                .onSuccess { pending ->
                    _state.value = _state.value.copy(
                        transferStep = if (invitation == null) {
                            TransferStep.QR
                        } else {
                            TransferStep.WAITING
                        },
                        transferQr = pending.qr.takeIf { invitation == null },
                    )
                    runCatching { e2ee.awaitDeviceTransfer(pending) }
                        .onSuccess { handshake ->
                            newHandshake = handshake
                            _state.value = _state.value.copy(
                                transferStep = TransferStep.SAS,
                                transferSas = handshake.sas,
                            )
                        }
                        .onFailure(::transferFailed)
                }
                .onFailure(::transferFailed)
        }
    }

    /** Existing Android device: accept the new device's scanned/pasted QR. */
    fun addAnotherDevice(raw: String) {
        _state.value = _state.value.copy(
            transferRole = TransferRole.OLD,
            transferStep = TransferStep.STARTING,
            transferError = null,
        )
        viewModelScope.launch {
            runCatching { e2ee.adoptScannedDevice(raw) }
                .onSuccess { handshake ->
                    oldHandshake = handshake
                    _state.value = _state.value.copy(
                        transferStep = TransferStep.SAS,
                        transferSas = handshake.sas,
                    )
                }
                .onFailure(::transferFailed)
        }
    }

    fun openAddAnotherDevice() {
        _state.value = _state.value.copy(
            transferRole = TransferRole.OLD,
            transferStep = TransferStep.IDLE,
            transferError = null,
            transferLoginToken = null,
            requestingEmailCode = false,
        )
    }

    /** No authenticator app on this account: email a one-time code to use instead. */
    fun requestTransferEmailCode() {
        if (_state.value.requestingEmailCode) return
        _state.value = _state.value.copy(requestingEmailCode = true, transferError = null)
        viewModelScope.launch {
            runCatching { api.requestE2eeTransferEmailCode() }
                .onSuccess { _state.value = _state.value.copy(
                    transferLoginToken = it.loginToken,
                    requestingEmailCode = false,
                ) }
                .onFailure {
                    _state.value = _state.value.copy(
                        requestingEmailCode = false,
                        transferError = it.message ?: "The email code could not be sent.",
                    )
                }
        }
    }

    fun revokeDevice(deviceId: String) {
        if (deviceId == _state.value.deviceId) {
            _state.value = _state.value.copy(error = "This phone cannot revoke itself.")
            return
        }
        _state.value = _state.value.copy(
            revokingDeviceId = deviceId,
            error = null,
            notice = null,
        )
        viewModelScope.launch {
            runCatching { e2ee.revoke(deviceId) }
                .onSuccess {
                    _state.value = _state.value.copy(
                        revokingDeviceId = null,
                        notice = "The encryption device was revoked.",
                    )
                    refresh()
                }
                .onFailure {
                    _state.value = _state.value.copy(
                        revokingDeviceId = null,
                        error = it.message ?: "The device could not be revoked.",
                    )
                }
        }
    }

    /** Human confirmation is the security boundary; do not advance without it. */
    fun confirmSas() {
        when (_state.value.transferRole) {
            TransferRole.NEW -> {
                val handshake = newHandshake ?: return
                val userId = auth.currentUser?.id ?: return
                _state.value = _state.value.copy(
                    transferStep = TransferStep.FINISHING,
                    transferError = null,
                )
                viewModelScope.launch {
                    runCatching { e2ee.finishDeviceTransfer(userId, handshake) }
                        .onSuccess {
                            _state.value = _state.value.copy(transferStep = TransferStep.DONE)
                            refresh()
                        }
                        .onFailure(::transferFailed)
                }
            }
            TransferRole.OLD -> {
                _state.value = _state.value.copy(
                    transferStep = TransferStep.TOTP,
                    transferError = null,
                )
            }
            null -> Unit
        }
    }

    fun submitTotp(code: String) {
        val handshake = oldHandshake ?: return
        _state.value = _state.value.copy(
            transferStep = TransferStep.FINISHING,
            transferError = null,
        )
        viewModelScope.launch {
            runCatching {
                e2ee.finishAdoptingDevice(
                    handshake,
                    code.trim(),
                    _state.value.transferLoginToken,
                )
            }
                .onSuccess {
                    _state.value = _state.value.copy(transferStep = TransferStep.DONE)
                    refresh()
                }
                .onFailure(::transferFailed)
        }
    }

    fun cancelTransfer() {
        newHandshake = null
        oldHandshake = null
        _state.value = _state.value.copy(
            transferRole = null,
            transferStep = TransferStep.IDLE,
            transferQr = null,
            transferSas = null,
            transferError = null,
            transferLoginToken = null,
            requestingEmailCode = false,
        )
    }

    private fun transferFailed(error: Throwable) {
        _state.value = _state.value.copy(
            transferStep = TransferStep.IDLE,
            transferError = error.message ?: "The device transfer failed.",
        )
    }
}
