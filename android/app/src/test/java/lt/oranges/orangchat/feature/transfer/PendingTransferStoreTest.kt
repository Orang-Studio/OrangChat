package lt.oranges.orangchat.feature.transfer

import lt.oranges.orangchat.crypto.E2eeQr
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PendingTransferStoreTest {
    @Test
    fun `accepts the desktop invitation scanned by the new phone`() {
        val raw = E2eeQr.encodeDeviceTransferInvite(
            E2eeQr.DeviceTransferInvite(
                transferId = "a".repeat(32),
                pairSecret = ByteArray(32) { it.toByte() },
            ),
        )
        val store = PendingTransferStore()

        store.offer(raw)

        assertEquals(raw, store.code.value)
    }

    @Test
    fun `still accepts the full new-device code`() {
        val raw = E2eeQr.encodeDeviceTransfer(
            E2eeQr.DeviceTransfer(
                transferId = "b".repeat(32),
                ikSigPub = byteArrayOf(1),
                ikDhPub = byteArrayOf(2),
                pairSecret = ByteArray(32),
            ),
        )
        val store = PendingTransferStore()

        store.offer(raw)

        assertEquals(raw, store.code.value)
    }

    @Test
    fun `rejects other QR kinds and malformed transfer payloads`() {
        val store = PendingTransferStore()
        store.offer("orangchat://login?token=abc")
        store.offer("orangchat://device-transfer?v=1&m=invite&t=short")

        assertNull(store.code.value)
    }
}
