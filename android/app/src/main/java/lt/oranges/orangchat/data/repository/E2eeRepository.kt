package lt.oranges.orangchat.data.repository

import android.os.Build
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import lt.oranges.orangchat.crypto.E2ee
import lt.oranges.orangchat.crypto.E2eeKeystore
import lt.oranges.orangchat.crypto.E2eePayloads
import lt.oranges.orangchat.crypto.GossipedHead
import lt.oranges.orangchat.crypto.MessagePayload
import lt.oranges.orangchat.data.model.E2eeChannelState
import lt.oranges.orangchat.data.model.E2eeDevice
import lt.oranges.orangchat.data.model.E2eeDeviceList
import lt.oranges.orangchat.data.model.E2eeEnvelopeInput
import lt.oranges.orangchat.data.model.E2eeGenesisRequest
import lt.oranges.orangchat.data.model.E2eeAddDeviceRequest
import lt.oranges.orangchat.data.model.E2eeBlobRequest
import lt.oranges.orangchat.data.model.E2eeLogEntryInput
import lt.oranges.orangchat.data.model.E2eeMintEpochRequest
import lt.oranges.orangchat.data.model.E2eeRevokeRequest
import lt.oranges.orangchat.data.model.E2eeTransferGrantRequest
import lt.oranges.orangchat.data.model.Message
import lt.oranges.orangchat.data.remote.ApiService
import org.json.JSONArray
import org.json.JSONObject

/**
 * The Android half of end-to-end encryption (docs/E2EE.md).
 *
 * Everything the web client does, this does too: the device log is replayed and
 * verified locally rather than trusted, conversation keys are wrapped only to
 * devices that survived that replay, and every message carries a per-sender
 * signature that is checked before the text is shown. A device the server
 * invented has no authorization signature from an existing device, and no amount
 * of server access can manufacture one.
 */
@Singleton
class E2eeRepository @Inject constructor(
    private val api: ApiService,
    private val keystore: E2eeKeystore,
) {
    class E2eeException(message: String) : Exception(message)

    /**
     * Strict mode holding a send until the peer has been checked (§6.5). The
     * outbox has to tell this apart from a real failure - the row stays queued
     * rather than being dropped - and it must not do so by matching on the
     * message text, which is user-facing copy that gets rewritten.
     */
    class VerificationRequiredException(message: String) : Exception(message)

    private val rotation = Mutex()

    data class PendingNewDevice(
        val transferId: String,
        val qr: String,
        val pairSecret: ByteArray,
        val ikSigPub: ByteArray,
        val ikDhPub: ByteArray,
    )

    data class NewDeviceHandshake(
        val pending: PendingNewDevice,
        val sharedSecret: ByteArray,
        val sas: String,
    )

    data class OldDeviceHandshake(
        val transferId: String,
        val pairSecret: ByteArray,
        val ikSigPub: ByteArray,
        val ikDhPub: ByteArray,
        val sharedSecret: ByteArray,
        val sas: String,
    )

    // ── Identity ──────────────────────────────────────────

    fun identity(): E2eeKeystore.LocalIdentity? = keystore.identity()

    /**
     * Gives this device an encryption identity if the account has none.
     *
     * Enrolment only ever succeeds on an account with no active device, so
     * installing the app a second time does not quietly become a second device -
     * that needs the transfer flow and a TOTP code (§4).
     */
    suspend fun enrol(userId: String): E2eeKeystore.LocalIdentity = withContext(Dispatchers.IO) {
        keystore.identity()?.let { if (it.userId == userId) return@withContext it }

        val list = api.getMyE2eeDevices()
        if (list.devices.any { it.revokedAt == null }) {
            throw E2eeException(
                "This account already has an encryption identity. Add this device from one that is already signed in.",
            )
        }

        keystore.generateIdentityKeys()

        val ikSigPub = keystore.signingPublic()?.encoded
            ?: throw E2eeException("This device could not create an encryption key.")
        val ikDhPub = keystore.agreementPublic()?.encoded
            ?: throw E2eeException("This device could not create an encryption key.")

        val bundle = E2ee.DeviceBundle(userId, ikSigPub, ikDhPub)
        val bundleSig = keystore.sign(E2ee.deviceBundleBytes(bundle))
        val identityGeneration = E2ee.toHex(E2ee.randomBytes(16))
        val payload = E2ee.genesisStatementBytes(bundle, identityGeneration)

        val device = api.enrolGenesisDevice(
            E2eeGenesisRequest(
                name = deviceName(),
                platform = "android",
                ikSigPub = E2ee.toBase64(ikSigPub),
                ikDhPub = E2ee.toBase64(ikDhPub),
                bundleSig = E2ee.toBase64(bundleSig),
                identityGeneration = identityGeneration,
                log = logEntry(payload, null),
            ),
        )

        keystore.rememberIdentity(userId, device.id, identityGeneration)
        selfMonitor(userId)
        keystore.identity()!!
    }

    private fun logEntry(payload: ByteArray, prevHash: ByteArray?): E2eeLogEntryInput {
        val entryHash = E2ee.logEntryHash(prevHash, payload)
        return E2eeLogEntryInput(
            payload = E2ee.toBase64(payload),
            prevHash = prevHash?.let(E2ee::toBase64),
            entryHash = E2ee.toBase64(entryHash),
            signature = E2ee.toBase64(keystore.sign(E2ee.logSignatureBytes(entryHash))),
        )
    }

    private fun deviceName(): String = "${Build.MANUFACTURER} ${Build.MODEL}".trim()
        .replaceFirstChar { it.uppercase() }

    /**
     * The step that closes first contact without asking a human anything: every
     * account audits its own log, so a genesis this device never created shows up
     * here as a hard failure rather than on the stranger being lied to.
     */
    suspend fun selfMonitor(userId: String): E2ee.VerifiedIdentity = withContext(Dispatchers.IO) {
        val list = api.getMyE2eeDevices()
        val verified = verifyList(list)
        val local = keystore.identity()

        if (local != null && local.userId == userId) {
            if (list.devices.none { it.id == local.deviceId }) {
                throw E2eeException(
                    "This device is missing from your own device log. Someone has rewritten it.",
                )
            }
            if (!verified.authorizedDeviceIds.contains(local.deviceId)) {
                throw E2eeException(
                    "This phone is no longer an authorized encryption device. Open Settings → Encryption to add it again.",
                )
            }
        }

        pin(userId, verified, list)

        // Checking in is what tells the server a key is still alive out here.
        // It is read by the key-erasure sweep, where a device that has not been
        // heard from is taken as one the account has genuinely lost - so a phone
        // that never reports in could have its keys erased out from under it.
        // Best-effort: a failure here must never break identity verification.
        local?.takeIf { it.userId == userId }?.let { identity ->
            runCatching { api.markE2eeDeviceSeen(identity.deviceId) }
        }

        verified
    }

    /**
     * Validates a device list against its own log rather than against the
     * server's word, and writes down the signing keys of everything that passed
     * so the notification path can check a signature later.
     */
    fun verifyList(list: E2eeDeviceList): E2ee.VerifiedIdentity {
        val identity = E2ee.verifyIdentity(
            list.userId,
            list.devices.map { device ->
                E2ee.DeviceRecord(
                    id = device.id,
                    ikSigPub = E2ee.fromBase64(device.ikSigPub),
                    ikDhPub = E2ee.fromBase64(device.ikDhPub),
                    bundleSig = E2ee.fromBase64(device.bundleSig),
                    authorizedBy = device.authorizedBy,
                    authorizationSig = device.authorizationSig?.let(E2ee::fromBase64),
                    revoked = device.revokedAt != null,
                )
            },
            list.log.map { entry ->
                E2ee.LogRecord(
                    seq = entry.seq,
                    kind = entry.kind,
                    payload = E2ee.fromBase64(entry.payload),
                    entryHash = E2ee.fromBase64(entry.entryHash),
                    prevHash = entry.prevHash?.let(E2ee::fromBase64),
                    signature = E2ee.fromBase64(entry.signature),
                )
            },
        )

        for (device in list.devices) {
            if (identity.authorizedDeviceIds.contains(device.id)) {
                keystore.rememberDeviceKey(device.id, device.userId, device.ikSigPub)
            }
        }
        return identity
    }

    /**
     * Refuses to move forward on a head that rewound, forked, or dropped an
     * entry this device already saw. A pinned head is a commitment to everything
     * before it, so a server showing a different history has to contradict
     * something already written down here.
     */
    private fun pin(userId: String, verified: E2ee.VerifiedIdentity, list: E2eeDeviceList) {
        val hashes = MutableList(verified.headSeq + 1) { "" }
        for (entry in list.log) if (entry.seq < hashes.size) hashes[entry.seq] = entry.entryHash

        val existing = keystore.pin(userId)
        if (existing != null) {
            if (existing.genesisCommitment != E2ee.toBase64(verified.genesisCommitment)) {
                throw E2eeException(
                    "This account's encryption identity changed. Verify them again before continuing.",
                )
            }
            if (existing.headSeq > verified.headSeq) {
                throw E2eeException("This account's device log went backwards.")
            }
            existing.entryHashes.forEachIndexed { seq, seen ->
                if (seen.isNotEmpty() && seq < hashes.size && hashes[seq] != seen) {
                    throw E2eeException("This account's device log has forked.")
                }
            }
        }

        keystore.savePin(
            E2eeKeystore.Pin(
                userId = userId,
                genesisCommitment = E2ee.toBase64(verified.genesisCommitment),
                verifiedAt = existing?.verifiedAt,
                headSeq = verified.headSeq,
                headHash = E2ee.toBase64(verified.headHash),
                entryHashes = hashes,
            ),
        )
    }

    /** Verifies a peer, pins their commitment, and returns who may hold a key. */
    suspend fun resolvePeer(userId: String): List<E2eeDevice> = withContext(Dispatchers.IO) {
        val list = api.getPeerE2eeDevices(userId)
        val verified = verifyList(list)
        pin(userId, verified, list)
        list.devices.filter { verified.authorizedDeviceIds.contains(it.id) }
    }

    /**
     * The way out of an identity change, and the only one. Losing every device
     * is a real thing that happens to real people, and before this there was no
     * path back from it: [pin] throws on the mismatch before the new commitment
     * can be written, so every contact of somebody who started over was left
     * unable to read or send, permanently.
     *
     * Dropping the pin first is what makes the re-verification possible - it is
     * the pin that rejects the new identity, so the account has to be treated as
     * a stranger again before it can be looked at. [verifyList] still runs in
     * full, so this forgives a *changed* identity, never a malformed one. A
     * failure puts the old commitment back rather than leaving the account
     * unpinned.
     */
    suspend fun acceptIdentityChange(userId: String): Unit = withContext(Dispatchers.IO) {
        val previous = keystore.pin(userId)
        keystore.deletePin(userId)
        try {
            resolvePeer(userId)
        } catch (e: Throwable) {
            previous?.let { keystore.savePin(it) }
            throw e
        }
        Unit
    }

    /** Marks a contact verified after their code has been confirmed in person. */
    fun markVerified(userId: String) {
        val existing = keystore.pin(userId) ?: throw E2eeException(
            "Fetch this contact's identity before verifying it.",
        )
        keystore.savePin(existing.copy(verifiedAt = java.time.Instant.now().toString()))
    }

    fun isVerified(userId: String): Boolean = keystore.pin(userId)?.verifiedAt != null

    fun setGlobalStrict(enabled: Boolean) = keystore.setGlobalStrict(enabled)

    fun setStrictFor(channelId: String, enabled: Boolean?) =
        keystore.setStrictFor(channelId, enabled)

    fun strictFor(channelId: String, channelType: String = "dm"): Boolean =
        keystore.strictFor(channelId, channelType)

    fun safetyNumberWith(peerUserId: String): String? {
        val local = keystore.identity() ?: return null
        val mine = keystore.pin(local.userId) ?: return null
        val theirs = keystore.pin(peerUserId) ?: return null
        return E2ee.safetyNumber(
            E2ee.fromBase64(mine.genesisCommitment),
            E2ee.fromBase64(theirs.genesisCommitment),
        )
    }

    suspend fun conversationSafetyNumber(
        peerUserIds: List<String>,
        group: Boolean,
    ): String? = withContext(Dispatchers.IO) {
        val local = keystore.identity() ?: return@withContext null
        for (userId in peerUserIds.distinct()) resolvePeer(userId)
        if (!group && peerUserIds.size == 1) {
            return@withContext safetyNumberWith(peerUserIds.single())
        }
        val commitments = (peerUserIds + local.userId)
            .distinct()
            .mapNotNull { keystore.pin(it)?.genesisCommitment?.let(E2ee::fromBase64) }
        if (commitments.size != (peerUserIds + local.userId).distinct().size) {
            null
        } else {
            E2ee.groupSafetyNumber(commitments)
        }
    }

    /**
     * This account's own verification code (§6.7). Public material only, and the
     * value it commits to is the genesis identity rather than a loose key.
     */
    fun myContactQr(): String? {
        val local = keystore.identity() ?: return null
        val mine = keystore.pin(local.userId) ?: return null
        return lt.oranges.orangchat.crypto.E2eeQr.encodeContactVerify(
            lt.oranges.orangchat.crypto.E2eeQr.ContactVerify(
                userId = local.userId,
                ikSigPub = local.ikSigPub,
                ikDhPub = local.ikDhPub,
                genesisCommitment = E2ee.fromBase64(mine.genesisCommitment),
            ),
        )
    }

    /**
     * The out-of-band half of verification. The commitment being compared came
     * off a camera, not off the network, so a server that substituted an identity
     * is caught here by a mismatch it cannot influence.
     */
    suspend fun acceptScannedContact(raw: String): String = withContext(Dispatchers.IO) {
        val scanned = lt.oranges.orangchat.crypto.E2eeQr.decodeContactVerify(raw)
        val local = keystore.identity()
        if (local != null && scanned.userId == local.userId) {
            throw E2eeException("That is your own code. Have the other person show theirs.")
        }

        resolvePeer(scanned.userId)
        val pinned = keystore.pin(scanned.userId)
            ?: throw E2eeException("Could not fetch that account's identity.")
        if (pinned.genesisCommitment != E2ee.toBase64(scanned.genesisCommitment)) {
            throw E2eeException(
                "This code does not match the identity the server gave for that account. Do not send anything until you know why.",
            )
        }
        markVerified(scanned.userId)
        scanned.userId
    }

    suspend fun revoke(deviceId: String) = withContext(Dispatchers.IO) {
        val local = keystore.identity() ?: throw E2eeException("This device has no encryption identity.")
        val list = api.getMyE2eeDevices()
        val verified = verifyList(list)
        if (local.deviceId !in verified.authorizedDeviceIds) {
            throw E2eeException(
                "This phone is not an authorized encryption device yet. Finish adding it before revoking another device.",
            )
        }
        if (deviceId !in verified.authorizedDeviceIds) {
            throw E2eeException("That device is already revoked or is not authorized.")
        }
        val revokedAt = java.time.Instant.now().toString()
        api.revokeE2eeDevice(
            E2eeRevokeRequest(
                deviceId = deviceId,
                signerDeviceId = local.deviceId,
                revokedAt = revokedAt,
                log = logEntry(
                    E2ee.revokeStatementBytes(local.userId, deviceId, revokedAt),
                    list.head?.entryHash?.let(E2ee::fromBase64),
                ),
            ),
        )
        Unit
    }

    /**
     * Lets this phone enrol again after another device revoked it.
     *
     * Revocation is one-way for the keys involved: the identity in the Keystore
     * is dead the moment it leaves the authorized set, and holding onto it is
     * what makes the Encryption screen believe this phone is still a device and
     * refuse to start a transfer. So the identity goes, and only the identity -
     * pins and epoch keys are what this phone knows about *other* people.
     *
     * The revocation is re-checked against a freshly verified log rather than
     * taken from the caller. Throwing away keys on the server's say-so alone
     * would hand it a way to knock any device out of an account; a real
     * revocation carries a signed entry from a device that was authorized.
     */
    suspend fun forgetRevokedIdentity(): Boolean = withContext(Dispatchers.IO) {
        val local = keystore.identity() ?: return@withContext false
        val list = api.getMyE2eeDevices()
        val verified = verifyList(list)
        if (local.deviceId in verified.authorizedDeviceIds) return@withContext false
        keystore.clearIdentity()
        true
    }

    // ── Nearby device transfer ────────────────────────────

    /**
     * Starts the receiving half of §4. The phone makes a distinct identity in
     * the Android Keystore, publishes only its self-signature, and shows the
     * pairing secret in a QR code. Conversation keys do not move until both
     * people confirm the six digits.
     */
    suspend fun beginDeviceTransfer(userId: String): PendingNewDevice =
        beginDeviceTransferInternal(userId, null)

    /** Desktop-first flow: the phone scanned the invitation shown on the PC. */
    suspend fun beginDeviceTransferFromInvitation(
        userId: String,
        raw: String,
    ): PendingNewDevice {
        val invitation = lt.oranges.orangchat.crypto.E2eeQr.decodeDeviceTransferInvite(raw)
        if (invitation.pairSecret.size != E2ee.PAIR_SECRET_BYTES) {
            throw E2eeException("This transfer invitation is malformed.")
        }
        return beginDeviceTransferInternal(userId, invitation)
    }

    private suspend fun beginDeviceTransferInternal(
        userId: String,
        invitation: lt.oranges.orangchat.crypto.E2eeQr.DeviceTransferInvite?,
    ): PendingNewDevice =
        withContext(Dispatchers.IO) {
            if (keystore.identity() != null) {
                throw E2eeException("This phone is already an encryption device.")
            }
            val list = api.getMyE2eeDevices()
            if (list.devices.none { it.revokedAt == null }) {
                throw E2eeException("Set up this phone as the first encryption device instead.")
            }

            keystore.generateIdentityKeys()
            val ikSigPub = keystore.signingPublic()?.encoded
                ?: throw E2eeException("This phone could not create its signing key.")
            val ikDhPub = keystore.agreementPublic()?.encoded
                ?: throw E2eeException("This phone could not create its agreement key.")
            val bundle = E2ee.DeviceBundle(userId, ikSigPub, ikDhPub)
            val bundleSig = keystore.sign(E2ee.deviceBundleBytes(bundle))
            val transferId = invitation?.transferId ?: api.startE2eeTransfer().transferId
            val pairSecret = invitation?.pairSecret ?: E2ee.randomBytes(E2ee.PAIR_SECRET_BYTES)

            val hello = JSONObject()
                .put("name", deviceName())
                .put("platform", "android")
                .put("bundleSig", E2ee.toBase64(bundleSig))
                .put("ikSigPub", E2ee.toBase64(ikSigPub))
                .put("ikDhPub", E2ee.toBase64(ikDhPub))
                .toString()
                .toByteArray(Charsets.UTF_8)
            putTransferBlob(transferId, "hello", hello)

            PendingNewDevice(
                transferId = transferId,
                qr = lt.oranges.orangchat.crypto.E2eeQr.encodeDeviceTransfer(
                    lt.oranges.orangchat.crypto.E2eeQr.DeviceTransfer(
                        transferId,
                        ikSigPub,
                        ikDhPub,
                        pairSecret,
                    ),
                ),
                pairSecret = pairSecret,
                ikSigPub = ikSigPub,
                ikDhPub = ikDhPub,
            )
        }

    /** Waits for the already-authorized PC/phone to scan the QR. */
    suspend fun awaitDeviceTransfer(pending: PendingNewDevice): NewDeviceHandshake =
        withContext(Dispatchers.IO) {
            val ephemeralPub = takeTransferBlob(pending.transferId, "handshake")
            val privateKey = keystore.agreementPrivate()
                ?: throw E2eeException("This phone no longer has its transfer key.")
            val shared = E2ee.agree(
                privateKey,
                E2ee.importAgreementPublicKey(ephemeralPub),
            )
            NewDeviceHandshake(
                pending = pending,
                sharedSecret = shared,
                sas = E2ee.pairSas(shared, pending.pairSecret),
            )
        }

    /**
     * Called only after the user taps that the SAS matches. Opens the history
     * archive, then waits until the old device's signed log entry appears.
     */
    suspend fun finishDeviceTransfer(userId: String, handshake: NewDeviceHandshake) =
        withContext(Dispatchers.IO) {
            val sealed = takeTransferBlob(handshake.pending.transferId, "bundle")
            val body = openTransferJson(
                E2ee.transferBundleKey(
                    handshake.sharedSecret,
                    handshake.pending.pairSecret,
                ),
                sealed,
            )
            val keys = body.optJSONArray("keys") ?: JSONArray()
            for (index in 0 until keys.length()) {
                val entry = keys.getJSONObject(index)
                keystore.saveEpochKey(
                    entry.getString("channelId"),
                    entry.getInt("epoch"),
                    E2ee.fromBase64(entry.getString("key")),
                )
            }

            val ownSigningKey = E2ee.toBase64(handshake.pending.ikSigPub)
            var enrolled: E2eeDevice? = null
            repeat(30) {
                if (enrolled == null) {
                    enrolled = runCatching { api.getMyE2eeDevices() }.getOrNull()
                        ?.devices
                        ?.firstOrNull { it.ikSigPub == ownSigningKey && it.revokedAt == null }
                    if (enrolled == null) delay(1_000)
                }
            }
            val device = enrolled ?: throw E2eeException(
                "The other device did not finish adding this phone. Start again.",
            )
            keystore.rememberIdentity(userId, device.id, "")
            selfMonitor(userId)
            runCatching { api.markE2eeDeviceSeen(device.id) }
        }

    /**
     * The sending half, used when this Android device is the one that already
     * holds history. The OS camera opens device-transfer deep links, while the
     * Encryption screen also accepts a pasted code.
     */
    suspend fun adoptScannedDevice(raw: String): OldDeviceHandshake =
        withContext(Dispatchers.IO) {
            val scanned = lt.oranges.orangchat.crypto.E2eeQr.decodeDeviceTransfer(raw)
            keystore.identity()
                ?: throw E2eeException("This phone has no encryption identity to copy from.")

            val generator = KeyPairGenerator.getInstance("EC")
            generator.initialize(ECGenParameterSpec("secp256r1"))
            val ephemeral: KeyPair = generator.generateKeyPair()
            val recipient = E2ee.importAgreementPublicKey(scanned.ikDhPub)
            val shared = E2ee.agree(ephemeral.private, recipient)
            putTransferBlob(scanned.transferId, "handshake", ephemeral.public.encoded)

            OldDeviceHandshake(
                transferId = scanned.transferId,
                pairSecret = scanned.pairSecret,
                ikSigPub = scanned.ikSigPub,
                ikDhPub = scanned.ikDhPub,
                sharedSecret = shared,
                sas = E2ee.pairSas(shared, scanned.pairSecret),
            )
        }

    /**
     * SAS has matched; require a fresh security code (TOTP when the account has
     * an authenticator, otherwise the emailed one-time code) and sign the new
     * device into the log.
     */
    suspend fun finishAdoptingDevice(
        handshake: OldDeviceHandshake,
        code: String,
        loginToken: String? = null,
    ) = withContext(Dispatchers.IO) {
            val local = keystore.identity()
                ?: throw E2eeException("This phone has no encryption identity to copy from.")
            val grant = api.requestE2eeTransferGrant(
                E2eeTransferGrantRequest(
                    transferId = handshake.transferId,
                    ikSigPub = E2ee.toBase64(handshake.ikSigPub),
                    ikDhPub = E2ee.toBase64(handshake.ikDhPub),
                    code = code,
                    loginToken = loginToken,
                ),
            ).grant

            val keys = JSONArray()
            for (entry in keystore.allEpochKeys()) {
                keys.put(
                    JSONObject()
                        .put("channelId", entry.channelId)
                        .put("epoch", entry.epoch)
                        .put("key", E2ee.toBase64(entry.key)),
                )
            }
            val bundle = JSONObject().put("keys", keys)
            val sealed = sealTransferJson(
                E2ee.transferBundleKey(handshake.sharedSecret, handshake.pairSecret),
                bundle,
            )
            putTransferBlob(handshake.transferId, "bundle", sealed)

            val hello = JSONObject(
                String(takeTransferBlob(handshake.transferId, "hello", attempts = 10), Charsets.UTF_8),
            )
            val list = api.getMyE2eeDevices()
            verifyList(list)
            val deviceBundle = E2ee.DeviceBundle(
                local.userId,
                handshake.ikSigPub,
                handshake.ikDhPub,
            )
            val statement = E2ee.addDeviceStatementBytes(deviceBundle, handshake.transferId)
            val authorizationSig = keystore.sign(statement)
            api.enrolAuthorizedDevice(
                E2eeAddDeviceRequest(
                    name = hello.optString("name").ifBlank { "New device" },
                    platform = hello.optString("platform").let {
                        if (it in setOf("web", "android", "desktop")) it else "web"
                    },
                    ikSigPub = E2ee.toBase64(handshake.ikSigPub),
                    ikDhPub = E2ee.toBase64(handshake.ikDhPub),
                    bundleSig = hello.getString("bundleSig"),
                    transferId = handshake.transferId,
                    grant = grant,
                    authorizedBy = local.deviceId,
                    authorizationSig = E2ee.toBase64(authorizationSig),
                    log = logEntry(
                        statement,
                        list.head?.entryHash?.let(E2ee::fromBase64),
                    ),
                ),
            )
        }

    private suspend fun putTransferBlob(transferId: String, slot: String, bytes: ByteArray) {
        val response = api.putE2eeTransferBlob(
            transferId,
            E2eeBlobRequest(E2ee.toBase64(bytes), slot),
        )
        if (!response.isSuccessful) {
            throw E2eeException("The secure transfer relay rejected this transfer.")
        }
    }

    private suspend fun takeTransferBlob(
        transferId: String,
        slot: String,
        attempts: Int = 45,
    ): ByteArray {
        repeat(attempts) {
            val blob = runCatching { api.takeE2eeTransferBlob(transferId, slot) }.getOrNull()
            if (blob != null) return E2ee.fromBase64(blob.blob)
            delay(2_000)
        }
        throw E2eeException("The transfer did not arrive. Start again on both devices.")
    }

    private fun sealTransferJson(
        key: javax.crypto.SecretKey,
        body: JSONObject,
    ): ByteArray {
        val nonce = E2ee.randomBytes(12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, nonce))
        return nonce + cipher.doFinal(body.toString().toByteArray(Charsets.UTF_8))
    }

    private fun openTransferJson(
        key: javax.crypto.SecretKey,
        sealed: ByteArray,
    ): JSONObject {
        if (sealed.size <= 12) throw E2eeException("The history bundle is malformed.")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, sealed.copyOfRange(0, 12)))
        return JSONObject(String(cipher.doFinal(sealed.copyOfRange(12, sealed.size)), Charsets.UTF_8))
    }

    // ── Epochs ────────────────────────────────────────────

    private val stateCache = mutableMapOf<String, Pair<Long, E2eeChannelState>>()

    /**
     * Sending touches the channel's state three times - to decide whether to
     * seal, to find the current epoch, and to collect whose heads to gossip.
     * Without this that is three round trips per message on a phone radio, so
     * the answer is held briefly. Short enough that a rotation lands quickly,
     * long enough that one send is one fetch.
     */
    suspend fun channelState(channelId: String, maxAgeMs: Long = 5_000): E2eeChannelState =
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            stateCache[channelId]?.let { (at, cached) ->
                if (now - at < maxAgeMs) return@withContext cached
            }
            api.getE2eeChannelState(channelId).also { stateCache[channelId] = now to it }
        }

    private fun forgetChannelState(channelId: String) {
        stateCache.remove(channelId)
    }

    /**
     * The gate that makes strict mode prevention rather than detection (§6.5).
     *
     * It has to run on the seal path as well as on rotation, which the web
     * client does and this one used to not: an established conversation already
     * has an epoch, so `currentEpoch` returns it without ever reaching `rotate`
     * and every message went out under a key wrapped to devices nobody had
     * checked. Turning the setting on looked like it worked and did nothing.
     */
    private fun assertMayEncryptTo(channelId: String, state: E2eeChannelState, selfId: String) {
        if (!keystore.strictFor(channelId, state.channelType)) return
        val unverified = state.memberDevices
            .map(E2eeDevice::userId)
            .distinct()
            .filter { it != selfId && !isVerified(it) }
        if (unverified.isNotEmpty()) {
            throw VerificationRequiredException(
                "Your message is waiting on this phone, locked, and has not been sent. You asked to check people before messaging them - tap the lock at the top of this conversation to scan their code or compare numbers.",
            )
        }
    }

    /**
     * Mints a fresh conversation key and wraps it to every verified device. The
     * epoch id is generated here rather than by the server, because every
     * wrapping is bound to it as the HKDF salt before the request is sent.
     */
    suspend fun rotate(channelId: String): Int = rotation.withLock {
        withContext(Dispatchers.IO) {
            val local = keystore.identity()
                ?: throw E2eeException("This device has no encryption identity yet.")
            val state = channelState(channelId, maxAgeMs = 0)

            assertMayEncryptTo(channelId, state, local.userId)

            val recipients = mutableListOf<E2eeDevice>()
            for (userId in state.memberDevices.map { it.userId }.distinct()) {
                if (userId == local.userId) {
                    val mine = api.getMyE2eeDevices()
                    val verified = verifyList(mine)
                    recipients += mine.devices.filter { verified.authorizedDeviceIds.contains(it.id) }
                } else {
                    recipients += resolvePeer(userId)
                }
            }
            if (recipients.isEmpty()) {
                throw E2eeException("No verified devices to encrypt to in this conversation.")
            }

            val conversationKey = E2ee.randomBytes(E2ee.CONVERSATION_KEY_BYTES)
            val epochId = E2ee.toHex(E2ee.randomBytes(16))
            val envelopes = recipients.map { device ->
                val wrapped = E2ee.wrapConversationKey(
                    conversationKey,
                    epochId,
                    device.id,
                    E2ee.fromBase64(device.ikDhPub),
                )
                E2eeEnvelopeInput(
                    deviceId = device.id,
                    ephemeralPub = E2ee.toBase64(wrapped.ephemeralPub),
                    wrapNonce = E2ee.toBase64(wrapped.wrapNonce),
                    wrapped = E2ee.toBase64(wrapped.wrapped),
                )
            }

            val epoch = api.mintE2eeEpoch(
                channelId,
                E2eeMintEpochRequest(id = epochId, createdBy = local.deviceId, envelopes = envelopes),
            )
            keystore.saveEpochKey(channelId, epoch.epoch, conversationKey)
            forgetChannelState(channelId)
            epoch.epoch
        }
    }

    /** Pulls down and caches every epoch key this device is entitled to. */
    suspend fun syncEpochKeys(channelId: String) = withContext(Dispatchers.IO) {
        val local = keystore.identity() ?: return@withContext
        val agreement = keystore.agreementPrivate() ?: return@withContext
        val keys = api.getE2eeEpochKeys(channelId, local.deviceId)
        for (key in keys.keys) {
            if (keystore.epochKey(channelId, key.epoch.epoch) != null) continue
            runCatching {
                E2ee.unwrapConversationKey(
                    agreement,
                    key.epoch.id,
                    local.deviceId,
                    E2ee.WrappedKey(
                        E2ee.fromBase64(key.envelope.ephemeralPub),
                        E2ee.fromBase64(key.envelope.wrapNonce),
                        E2ee.fromBase64(key.envelope.wrapped),
                    ),
                )
            }.onSuccess { keystore.saveEpochKey(channelId, key.epoch.epoch, it) }
            // An envelope this device cannot open belongs to an epoch minted
            // before it existed. That history is simply unreadable here rather
            // than an error to raise.
        }
    }

    private suspend fun conversationKeyFor(channelId: String, epoch: Int): ByteArray {
        keystore.epochKey(channelId, epoch)?.let { return it }
        syncEpochKeys(channelId)
        return keystore.epochKey(channelId, epoch)
            ?: throw E2eeException("This message was encrypted for a device you no longer have.")
    }

    private suspend fun currentEpoch(channelId: String): Int {
        val state = channelState(channelId)
        if (state.epochNumber == 0) return rotate(channelId)
        val ageMs = state.currentEpochCreatedAt
            ?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
            ?.let { System.currentTimeMillis() - it }
            ?: Long.MAX_VALUE
        if (
            state.rotationRequired ||
            state.currentEpochMessageCount >= 10_000 ||
            ageMs >= 30L * 24 * 60 * 60 * 1_000
        ) {
            return rotate(channelId)
        }
        keystore.epochKey(channelId, state.epochNumber)?.let { return state.epochNumber }
        syncEpochKeys(channelId)
        return if (keystore.epochKey(channelId, state.epochNumber) != null) {
            state.epochNumber
        } else {
            rotate(channelId)
        }
    }

    /** True once this conversation has ever been seen encrypted. */
    suspend fun isEncrypted(channelId: String): Boolean =
        runCatching { channelState(channelId).e2ee }.getOrDefault(false)

    /** True when this send must be encrypted, including the first capable send. */
    suspend fun shouldEncrypt(channelId: String): Boolean = runCatching {
        val state = channelState(channelId)
        state.e2ee ||
            (state.channelType in setOf("dm", "group_dm") && state.capable)
    }.getOrDefault(false)

    // ── Messages ──────────────────────────────────────────

    data class Sealed(val ciphertext: String, val encEpoch: Int, val encVersion: Int)

    suspend fun seal(
        channelId: String,
        text: String,
        replyToId: String? = null,
        sentAt: String = Instant.now().toString(),
        attachments: List<lt.oranges.orangchat.crypto.SealedAttachmentRef>? = null,
    ): Sealed = withContext(Dispatchers.IO) {
        val local = keystore.identity()
            ?: throw E2eeException("This device has no encryption identity yet.")

        val state = runCatching { channelState(channelId) }.getOrNull()
        // Before any key is derived, not after: a message sealed under a CK that
        // was already wrapped to an unchecked device has nothing left to prevent.
        if (state != null) assertMayEncryptTo(channelId, state, local.userId)

        val epoch = currentEpoch(channelId)
        val seq = keystore.reserveSeq(channelId, epoch)
        val key = conversationKeyFor(channelId, epoch)

        val heads = (state?.memberDevices?.map { it.userId }?.distinct() ?: emptyList())
            .plus(local.userId)
            .distinct()
            .mapNotNull { userId ->
                keystore.pin(userId)?.let { GossipedHead(userId, it.headSeq, it.headHash) }
            }

        val payload = MessagePayload(
            text = text,
            sentAt = sentAt,
            clientId = E2ee.toHex(E2ee.randomBytes(8)),
            replyTo = replyToId,
            attachments = attachments,
            heads = heads.ifEmpty { null },
        )

        val envelope = E2ee.sealMessage(
            key,
            E2ee.MessageContext(channelId, epoch, local.deviceId, local.userId, seq),
            E2eePayloads.encode(payload),
            keystore.signingPrivate() ?: throw E2eeException("This device has no signing key."),
        )

        Sealed(
            ciphertext = E2ee.toBase64(E2ee.encodeEnvelope(envelope)),
            encEpoch = epoch,
            encVersion = E2ee.VERSION,
        )
    }

    /**
     * Opens a message and, crucially, checks who wrote it. Everyone in the
     * conversation holds the same conversation key, so a valid GCM tag proves
     * only that *someone* here wrote it; the per-sender signature is what makes
     * authorship mean anything, and an unsigned message is a hard failure rather
     * than a degraded render.
     */
    suspend fun open(channelId: String, ciphertext: String, authorId: String): MessagePayload =
        withContext(Dispatchers.IO) {
            val envelope = E2ee.decodeEnvelope(E2ee.fromBase64(ciphertext))
            val key = conversationKeyFor(channelId, envelope.epoch)

            // A sender device we already verified is trusted from the keystore
            // alone, so a push can still check the signature while the phone is
            // offline or the app was cold-started (the notification path has no
            // server round-trip to spare). Fresh lookups still run for devices
            // never seen here, and re-verify on every online path.
            val local = keystore.identity()
            val remembered = keystore.deviceKey(envelope.senderDeviceId)
            val devices = if (remembered != null && remembered.first == envelope.senderUserId) {
                listOf(
                    E2eeDevice(
                        id = envelope.senderDeviceId,
                        userId = remembered.first,
                        name = "",
                        platform = "",
                        ikSigPub = E2ee.toBase64(remembered.second),
                        ikDhPub = "",
                        bundleSig = "",
                        createdAt = "",
                        lastSeenAt = "",
                    ),
                )
            } else if (local != null && envelope.senderUserId == local.userId) {
                // Our own devices get the same treatment as anyone else's: skipping
                // the replay here would let the server invent a device on *our*
                // account and attribute a message to it.
                val mine = api.getMyE2eeDevices()
                val verified = verifyList(mine)
                mine.devices.filter { verified.authorizedDeviceIds.contains(it.id) }
            } else {
                resolvePeer(envelope.senderUserId)
            }

            val sender = devices.firstOrNull { it.id == envelope.senderDeviceId }
                ?: throw E2eeException(
                    "This message came from a device that is not on the sender's account.",
                )
            if (sender.userId != authorId) {
                throw E2eeException("This message's signing device does not belong to its author.")
            }

            val plaintext = E2ee.openMessage(
                key,
                channelId,
                envelope,
                E2ee.fromBase64(sender.ikSigPub),
            )
            val payload = E2eePayloads.decode(plaintext)
            checkGossip(payload.heads)
            payload
        }

    /**
     * Cross-checks the sender's view of everyone's device log (§6.1). It never
     * blocks rendering - the message is already authenticated - but a
     * disagreement at a sequence this device already committed to means the
     * server is showing different histories to different people.
     */
    private fun checkGossip(heads: List<GossipedHead>?) {
        for (head in heads.orEmpty()) {
            val pinned = keystore.pin(head.userId) ?: continue
            if (E2eePayloads.checkGossipedHead(head, pinned.entryHashes) == "fork") {
                throw E2eeException(
                    "Someone in this conversation was shown a different device log for this account than this device was.",
                )
            }
        }
    }

    /**
     * Replaces an encrypted message's empty `content` with its plaintext, so
     * everything downstream keeps working on plain messages. A message that
     * cannot be read says so rather than rendering as an empty bubble.
     */
    suspend fun decrypt(message: Message): Message = withContext(Dispatchers.IO) {
        val ciphertext = message.ciphertext ?: return@withContext message
        // The cached *payload*, not just the cached text. An attachment's real
        // name, type and key exist only in here, so a cache hit that restored
        // text alone left every later render showing the server's placeholder
        // row - the file called "sealed" that no device could open.
        keystore.cachedPayload(message.id)?.let {
            keystore.backfillCachedMessageMetadata(message, it.sentAt)
            return@withContext apply(message, it)
        }
        keystore.cachedMessage(message.id)?.let {
            keystore.backfillCachedMessageMetadata(message)
            return@withContext message.copy(content = it)
        }
        try {
            val payload = open(message.channelId, ciphertext, message.author.id)
            keystore.cacheMessage(message, payload)
            apply(message, payload)
        } catch (e: Exception) {
            message.copy(content = unreadable(e))
        }
    }

    /**
     * Rebuilds a readable message from its payload. Attachments in an encrypted
     * conversation are described entirely in there - the server's row carries a
     * storage id, a byte count and the placeholder name "sealed" - so this has
     * to run on every path that produces one, cached or freshly opened.
     */
    private fun apply(message: Message, payload: MessagePayload): Message {
        payload.attachments.orEmpty().forEach(keystore::rememberSealedAttachment)
        val refs = payload.attachments.orEmpty().associateBy { it.attachmentId }
        return message.copy(
            content = payload.text,
            replyToId = payload.replyTo ?: message.replyToId,
            attachments = message.attachments
                .map { attachment ->
                    refs[attachment.id]?.let { ref ->
                        attachment.copy(
                            filename = ref.filename,
                            contentType = ref.contentType,
                            size = ref.size,
                            width = ref.width,
                            height = ref.height,
                            duration = ref.duration,
                            flagged = false,
                        )
                    } ?: attachment
                },
        )
    }

    /**
     * Report one exact message. For E2EE the server receives only the derived
     * key for this sender-device sequence, verifies the original signature and
     * GCM tag, and cannot use it to open any neighbouring message.
     */
    suspend fun reportMessage(message: Message, reason: String?): String =
        withContext(Dispatchers.IO) {
            val reportKey = message.ciphertext?.let { raw ->
                val envelope = E2ee.decodeEnvelope(E2ee.fromBase64(raw))
                val conversationKey = conversationKeyFor(message.channelId, envelope.epoch)
                E2ee.toBase64(
                    E2ee.deriveMessageKeyBytes(
                        conversationKey,
                        envelope.senderDeviceId,
                        envelope.seq,
                    ),
                )
            }
            api.reportMessage(
                message.id,
                lt.oranges.orangchat.data.remote.ReportMessageRequest(
                    reason = reason?.trim()?.takeIf(String::isNotEmpty),
                    messageKey = reportKey,
                ),
            ).status
        }

    suspend fun sealEdit(message: Message, text: String): Sealed {
        val previous = keystore.cachedPayload(message.id)
            ?: open(
                message.channelId,
                message.ciphertext
                    ?: throw E2eeException("This encrypted message has no envelope."),
                message.author.id,
            )
        return seal(
            channelId = message.channelId,
            text = text,
            replyToId = previous.replyTo ?: message.replyToId,
            sentAt = previous.sentAt.ifBlank { message.createdAt },
            attachments = previous.attachments,
        )
    }

    suspend fun decryptAll(messages: List<Message>): List<Message> =
        if (messages.none { it.ciphertext != null }) messages
        // One hop for the whole page: every message here touches the encrypted
        // preference store, and a page of them on the main thread is the
        // difference between a scroll and an ANR.
        else withContext(Dispatchers.IO) { messages.map { decrypt(it) } }

    private fun unreadable(error: Exception): String {
        val message = error.message.orEmpty()
        val tampered = message.contains("signature") ||
            message.contains("not on the sender's account") ||
            message.contains("does not belong to its author")
        return if (tampered) {
            "This message failed its authenticity check and was not shown."
        } else {
            "This message can't be read on this device."
        }
    }

    /** Local search over encrypted messages this device has actually opened. */
    suspend fun searchLocal(
        query: String,
        channelIds: Set<String>? = null,
        limit: Int = 100,
    ): List<E2eeKeystore.CachedMessage> = withContext(Dispatchers.IO) {
        keystore.searchCached(query, channelIds, limit)
    }

    /** Edits must evict the prior plaintext before opening the new envelope. */
    fun forgetCachedMessage(messageId: String) = keystore.forgetCachedMessage(messageId)

    fun saveQueuedMessage(
        id: String,
        channelId: String,
        content: String,
        replyToId: String?,
        attachmentIds: List<String>,
        sealedAttachments: List<lt.oranges.orangchat.crypto.SealedAttachmentRef>,
    ) = keystore.saveQueuedMessage(
        E2eeKeystore.QueuedMessage(
            id,
            channelId,
            content,
            replyToId,
            attachmentIds,
            sealedAttachments,
        ),
    )

    fun removeQueuedMessage(id: String) = keystore.removeQueuedMessage(id)

    fun queuedMessages(): List<E2eeKeystore.QueuedMessage> = keystore.queuedMessages()

    fun sealedAttachment(attachmentId: String) = keystore.sealedAttachment(attachmentId)

    fun signOut() = keystore.clear()
}
