package lt.oranges.orangchat.feature.transfer

import lt.oranges.orangchat.crypto.E2eeQr
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class TransferQrScannerTest {
    private val invitation = E2eeQr.encodeDeviceTransferInvite(
        E2eeQr.DeviceTransferInvite(
            transferId = "a".repeat(32),
            pairSecret = ByteArray(32) { it.toByte() },
        ),
    )
    private val device = E2eeQr.encodeDeviceTransfer(
        E2eeQr.DeviceTransfer(
            transferId = "b".repeat(32),
            ikSigPub = byteArrayOf(1),
            ikDhPub = byteArrayOf(2),
            pairSecret = ByteArray(32),
        ),
    )

    @Test
    fun `new phone scanner accepts only a desktop invitation`() {
        assertNull(validateTransferCode(invitation, expectInvitation = true))
        assertNotNull(validateTransferCode(device, expectInvitation = true))
    }

    @Test
    fun `authorized device scanner accepts only a full device code`() {
        assertNull(validateTransferCode(device, expectInvitation = false))
        assertNotNull(validateTransferCode(invitation, expectInvitation = false))
    }

    @Test
    fun `scanner rejects unrelated and malformed codes`() {
        assertNotNull(validateTransferCode("https://example.com", expectInvitation = true))
        assertNotNull(
            validateTransferCode(
                "orangchat://device-transfer?v=1&m=invite&t=short&p=AA",
                expectInvitation = true,
            ),
        )
    }

    @Test
    fun `contact scanner accepts only contact verification codes`() {
        val contact = E2eeQr.encodeContactVerify(
            E2eeQr.ContactVerify(
                userId = "user-1",
                ikSigPub = byteArrayOf(1),
                ikDhPub = byteArrayOf(2),
                genesisCommitment = ByteArray(32) { it.toByte() },
            ),
        )
        assertNull(validateContactCode(contact))
        assertNotNull(validateContactCode(invitation))
        assertNotNull(validateContactCode("https://example.com"))
    }
}
