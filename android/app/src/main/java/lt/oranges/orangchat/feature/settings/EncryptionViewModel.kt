package lt.oranges.orangchat.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.content.Context
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import lt.oranges.orangchat.data.model.E2eeDevice
import lt.oranges.orangchat.data.remote.ApiService
import lt.oranges.orangchat.data.repository.AuthRepository
import lt.oranges.orangchat.data.repository.E2eeRepository
import lt.oranges.orangchat.util.AppStrings
import lt.oranges.orangchat.R

@HiltViewModel
class EncryptionViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
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
        val hasTwoFactor: Boolean = true,
        val transferLoginToken: String? = null,
        val requestingEmailCode: Boolean = false,
        val revokingDeviceId: String? = null,
        val notice: String? = null,
        val revokedHere: Boolean = false,
        val resetting: Boolean = false,
        val erasing: Boolean = false,
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

    fun setUpThisPhoneAgain() {
        _state.value = _state.value.copy(resetting = true, error = null, notice = null)
        viewModelScope.launch {
            runCatching { e2ee.forgetRevokedIdentity() }
                .onSuccess { forgotten ->
                    _state.value = _state.value.copy(resetting = false)
                    if (forgotten) {
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
                        _state.value = _state.value.copy(revokedHere = false)
                        refresh()
                    }
                }
                .onFailure {
                    _state.value = _state.value.copy(
                        resetting = false,
                        error = it.message ?: AppStrings.get(context, R.string.catalog_this_phone_could_not_be_set_up_ee589c92),
                    )
                }
        }
    }

    fun addThisDevice() = startNewDevice(null)

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
            _state.value = _state.value.copy(transferError = AppStrings.get(context, R.string.catalog_sign_in_before_adding_this_device_8327420f))
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
                        transferError = it.message ?: AppStrings.get(context, R.string.catalog_the_email_code_could_not_be_sent_7bee875a),
                    )
                }
        }
    }

    fun revokeDevice(deviceId: String) {
        if (deviceId == _state.value.deviceId) {
            _state.value = _state.value.copy(error = AppStrings.get(context, R.string.catalog_this_phone_cannot_revoke_itself_4f680a33))
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
                        notice = AppStrings.get(context, R.string.catalog_the_encryption_device_was_revoked_5d4569c7),
                    )
                    refresh()
                }
                .onFailure {
                    _state.value = _state.value.copy(
                        revokingDeviceId = null,
                        error = it.message ?: AppStrings.get(context, R.string.catalog_the_device_could_not_be_revoked_8f71eb6b),
                    )
                }
        }
    }

    fun eraseKeysNow() {
        _state.value = _state.value.copy(erasing = true, error = null, notice = null)
        viewModelScope.launch {
            runCatching { e2ee.eraseKeysNow() }
                .onSuccess {
                    val userId = auth.currentUser?.id
                    if (userId != null) {
                        runCatching { e2ee.enrol(userId) }
                            .onFailure { _state.value = _state.value.copy(error = it.message) }
                    }
                    _state.value = _state.value.copy(
                        erasing = false,
                        notice = AppStrings.get(context, R.string.catalog_your_old_keys_are_gone_this_phone_be322585),
                    )
                    refresh()
                }
                .onFailure {
                    _state.value = _state.value.copy(
                        erasing = false,
                        error = it.message ?: AppStrings.get(context, R.string.catalog_the_keys_could_not_be_erased_fc80d5dd),
                    )
                }
        }
    }

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
            transferError = error.message ?: AppStrings.get(context, R.string.catalog_the_device_transfer_failed_7a70c4fe),
        )
    }
}
