package lt.oranges.orangchat.crypto

import java.net.URLDecoder
import java.net.URLEncoder

object E2eeQr {
    data class DeviceTransfer(
        val transferId: String,
        val ikSigPub: ByteArray,
        val ikDhPub: ByteArray,
        val pairSecret: ByteArray,
    )

    data class DeviceTransferInvite(
        val transferId: String,
        val pairSecret: ByteArray,
    )

    data class ContactVerify(
        val userId: String,
        val ikSigPub: ByteArray,
        val ikDhPub: ByteArray,
        val genesisCommitment: ByteArray,
    )

    class WrongKindException(message: String) : Exception(message)

    private fun encodeQuery(params: List<Pair<String, String>>): String =
        params.joinToString("&") { (key, value) ->
            "$key=${URLEncoder.encode(value, "UTF-8")}"
        }

    fun encodeDeviceTransfer(payload: DeviceTransfer): String = buildString {
        append("orangchat://${E2ee.QrKind.DEVICE_TRANSFER}?")
        append(
            encodeQuery(
                listOf(
                    "v" to E2ee.VERSION.toString(),
                    "m" to "device",
                    "t" to payload.transferId,
                    "s" to E2ee.toBase64(payload.ikSigPub),
                    "d" to E2ee.toBase64(payload.ikDhPub),
                    "p" to E2ee.toBase64(payload.pairSecret),
                ),
            ),
        )
    }

    fun encodeDeviceTransferInvite(payload: DeviceTransferInvite): String = buildString {
        append("orangchat://${E2ee.QrKind.DEVICE_TRANSFER}?")
        append(
            encodeQuery(
                listOf(
                    "v" to E2ee.VERSION.toString(),
                    "m" to "invite",
                    "t" to payload.transferId,
                    "p" to E2ee.toBase64(payload.pairSecret),
                ),
            ),
        )
    }

    fun encodeContactVerify(payload: ContactVerify): String = buildString {
        append("orangchat://${E2ee.QrKind.CONTACT_VERIFY}?")
        append(
            encodeQuery(
                listOf(
                    "v" to E2ee.VERSION.toString(),
                    "u" to payload.userId,
                    "s" to E2ee.toBase64(payload.ikSigPub),
                    "d" to E2ee.toBase64(payload.ikDhPub),
                    "g" to E2ee.toBase64(payload.genesisCommitment),
                ),
            ),
        )
    }

    private val PREFIX = Regex("^orangchat://([a-z-]+)\\?")

    fun kindOf(raw: String): String? {
        val kind = PREFIX.find(raw.trim())?.groupValues?.getOrNull(1) ?: return null
        return kind.takeIf {
            it == E2ee.QrKind.SIGN_IN ||
                it == E2ee.QrKind.DEVICE_TRANSFER ||
                it == E2ee.QrKind.CONTACT_VERIFY
        }
    }

    private fun paramsOf(raw: String, expected: String): Map<String, String> {
        val kind = kindOf(raw) ?: throw WrongKindException("e2ee: not an OrangChat code")
        if (kind != expected) {
            throw WrongKindException("e2ee: this is a $kind code, not a $expected code")
        }
        val query = raw.trim().substringAfter('?')
        val params = query.split('&').mapNotNull { pair ->
            val at = pair.indexOf('=')
            if (at <= 0) null
            else pair.substring(0, at) to URLDecoder.decode(pair.substring(at + 1), "UTF-8")
        }.toMap()
        if (params["v"] != E2ee.VERSION.toString()) {
            throw WrongKindException("e2ee: this code was made by a different app version")
        }
        return params
    }

    private fun required(params: Map<String, String>, key: String): String =
        params[key]?.takeIf { it.isNotEmpty() }
            ?: throw WrongKindException("e2ee: code is missing $key")

    private fun transferId(params: Map<String, String>): String =
        required(params, "t").takeIf { it.matches(Regex("^[0-9a-f]{32}$")) }
            ?: throw WrongKindException("e2ee: code has an invalid transfer id")

    fun decodeDeviceTransfer(raw: String): DeviceTransfer {
        val params = paramsOf(raw, E2ee.QrKind.DEVICE_TRANSFER)
        if (params["m"] == "invite") {
            throw WrongKindException(
                "This invitation must be scanned by the phone being added.",
            )
        }
        return DeviceTransfer(
            transferId = transferId(params),
            ikSigPub = E2ee.fromBase64(required(params, "s")),
            ikDhPub = E2ee.fromBase64(required(params, "d")),
            pairSecret = E2ee.fromBase64(required(params, "p")),
        )
    }

    fun decodeDeviceTransferInvite(raw: String): DeviceTransferInvite {
        val params = paramsOf(raw, E2ee.QrKind.DEVICE_TRANSFER)
        if (params["m"] != "invite") {
            throw WrongKindException(
                "This device code must be scanned by an already-authorized device.",
            )
        }
        return DeviceTransferInvite(
            transferId = transferId(params),
            pairSecret = E2ee.fromBase64(required(params, "p")),
        )
    }

    fun isDeviceTransferInvite(raw: String): Boolean =
        runCatching {
            paramsOf(raw, E2ee.QrKind.DEVICE_TRANSFER)["m"] == "invite"
        }.getOrDefault(false)

    fun decodeContactVerify(raw: String): ContactVerify {
        val params = paramsOf(raw, E2ee.QrKind.CONTACT_VERIFY)
        return ContactVerify(
            userId = required(params, "u"),
            ikSigPub = E2ee.fromBase64(required(params, "s")),
            ikDhPub = E2ee.fromBase64(required(params, "d")),
            genesisCommitment = E2ee.fromBase64(required(params, "g")),
        )
    }
}
