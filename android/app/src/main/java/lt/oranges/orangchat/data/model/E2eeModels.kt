package lt.oranges.orangchat.data.model

import kotlinx.serialization.Serializable

/**
 * Wire DTOs for end-to-end encryption, mirroring the `E2ee*` types in
 * `packages/shared/src/types.ts` and the Rust DTOs in `dto.rs`.
 *
 * Everything here is public material or server-opaque ciphertext. The private
 * keys these describe never appear on the wire in any direction, which is the
 * property the whole design exists to preserve.
 */

@Serializable
data class E2eeDevice(
    val id: String,
    val userId: String,
    val name: String,
    val platform: String,
    val ikSigPub: String,
    val ikDhPub: String,
    val bundleSig: String,
    val authorizedBy: String? = null,
    val authorizationSig: String? = null,
    val createdAt: String,
    val lastSeenAt: String,
    val revokedAt: String? = null,
)

@Serializable
data class E2eeLogEntry(
    val seq: Int,
    val kind: String,
    val payload: String,
    val entryHash: String,
    val prevHash: String? = null,
    val signature: String,
    val createdAt: String,
)

@Serializable
data class E2eeLogHead(val seq: Int, val entryHash: String)

@Serializable
data class E2eeDeviceList(
    val userId: String,
    val devices: List<E2eeDevice> = emptyList(),
    val log: List<E2eeLogEntry> = emptyList(),
    val head: E2eeLogHead? = null,
)

@Serializable
data class E2eeEpoch(
    val id: String,
    val channelId: String,
    val epoch: Int,
    val createdAt: String,
    val createdBy: String,
)

@Serializable
data class E2eeEnvelope(
    val ephemeralPub: String,
    val wrapNonce: String,
    val wrapped: String,
)

@Serializable
data class E2eeEpochKey(val epoch: E2eeEpoch, val envelope: E2eeEnvelope)

@Serializable
data class E2eeEpochKeys(val keys: List<E2eeEpochKey> = emptyList())

@Serializable
data class E2eeChannelState(
    val channelId: String,
    val channelType: String = "dm",
    val e2ee: Boolean = false,
    val epochNumber: Int = 0,
    val capable: Boolean = false,
    val rotationRequired: Boolean = false,
    val currentEpochCreatedAt: String? = null,
    val currentEpochMessageCount: Long = 0,
    val memberDevices: List<E2eeDevice> = emptyList(),
)

@Serializable
data class E2eeLogEntryInput(
    val payload: String,
    val prevHash: String? = null,
    val entryHash: String,
    val signature: String,
)

@Serializable
data class E2eeGenesisRequest(
    val name: String,
    val platform: String,
    val ikSigPub: String,
    val ikDhPub: String,
    val bundleSig: String,
    val identityGeneration: String,
    val log: E2eeLogEntryInput,
)

@Serializable
data class E2eeAddDeviceRequest(
    val name: String,
    val platform: String,
    val ikSigPub: String,
    val ikDhPub: String,
    val bundleSig: String,
    val transferId: String,
    val grant: String,
    val authorizedBy: String,
    val authorizationSig: String,
    val log: E2eeLogEntryInput,
)

@Serializable
data class E2eeRevokeRequest(
    val deviceId: String,
    val signerDeviceId: String,
    val revokedAt: String,
    val log: E2eeLogEntryInput,
)

@Serializable
data class E2eeEnvelopeInput(
    val deviceId: String,
    val ephemeralPub: String,
    val wrapNonce: String,
    val wrapped: String,
)

@Serializable
data class E2eeMintEpochRequest(
    val id: String,
    val createdBy: String,
    val envelopes: List<E2eeEnvelopeInput>,
)

@Serializable
data class E2eeTransferId(val transferId: String)

@Serializable
data class E2eeTransferGrant(
    val grant: String,
    val transferId: String,
    val expiresIn: Long = 0,
)

@Serializable
data class E2eeTransferGrantRequest(
    val transferId: String,
    val ikSigPub: String,
    val ikDhPub: String,
    val code: String,
    /** Set when the account has no authenticator: token from requestE2eeTransferEmailCode. */
    val loginToken: String? = null,
)

@Serializable
data class E2eeTransferEmailCode(val loginToken: String)

@Serializable
data class E2eeBlobRequest(val blob: String, val slot: String)

@Serializable
data class E2eeBlob(val blob: String)
