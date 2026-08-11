package lt.oranges.orangchat.feature.transfer

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import lt.oranges.orangchat.crypto.E2ee
import lt.oranges.orangchat.crypto.E2eeQr

@Singleton
class PendingTransferStore @Inject constructor() {
    private val _code = MutableStateFlow<String?>(null)
    val code: StateFlow<String?> = _code.asStateFlow()

    fun offer(raw: String) {
        if (E2eeQr.kindOf(raw) != E2ee.QrKind.DEVICE_TRANSFER) return
        runCatching {
            if (E2eeQr.isDeviceTransferInvite(raw)) {
                E2eeQr.decodeDeviceTransferInvite(raw)
            } else {
                E2eeQr.decodeDeviceTransfer(raw)
            }
        }.onSuccess { _code.value = raw }
    }

    fun consume() {
        _code.value = null
    }
}
