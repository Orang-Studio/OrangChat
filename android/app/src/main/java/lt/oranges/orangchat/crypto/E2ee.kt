package lt.oranges.orangchat.crypto

import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.experimental.xor

object E2ee {
    const val VERSION = 1
    const val PAYLOAD_VERSION = 1
    const val ENVELOPE_MAGIC = "OCE1"

    const val CONVERSATION_KEY_BYTES = 32
    const val WRAP_NONCE_BYTES = 12
    const val PAIR_SECRET_BYTES = 32

    val MESSAGE_NONCE = ByteArray(12)

    object Domain {
        const val CK_WRAP = "orangchat/ck-wrap/v1"
        const val MESSAGE_KEY = "orangchat/msg/v1"
        const val MESSAGE_SIG = "orangchat/msg-sig/v1"
        const val DEVICE_BUNDLE = "orangchat/device-bundle/v1"
        const val GENESIS = "orangchat/genesis/v1"
        const val ADD_DEVICE = "orangchat/add-device/v1"
        const val REVOKE = "orangchat/revoke/v1"
        const val ERASE_KEYS = "orangchat/erase-keys/v1"
        const val LOG_ENTRY = "orangchat/device-log/v1"
        const val SAFETY_NUMBER = "orangchat/safety-number/v1"
        const val GROUP_SAFETY_NUMBER = "orangchat/group-safety-number/v1"
        const val PAIR_SAS = "orangchat/pair-sas/v1"
        const val TRANSFER_BUNDLE = "orangchat/transfer-bundle/v1"
        const val ATTACHMENT = "orangchat/attachment/v1"
    }

    object QrKind {
        const val SIGN_IN = "login"
        const val DEVICE_TRANSFER = "device-transfer"
        const val CONTACT_VERIFY = "verify"
    }


    private fun field(out: ByteArrayOutputStream, bytes: ByteArray) {
        out.write(ByteBuffer.allocate(4).putInt(bytes.size).array())
        out.write(bytes)
    }

    fun encodeFields(vararg fields: Any): ByteArray {
        val out = ByteArrayOutputStream()
        for (value in fields) {
            when (value) {
                is String -> field(out, value.toByteArray(Charsets.UTF_8))
                is ByteArray -> field(out, value)
                is Int -> field(out, ByteBuffer.allocate(8).putLong(value.toLong()).array())
                is Long -> {
                    require(value >= 0) { "e2ee: $value is not encodable as a field" }
                    field(out, ByteBuffer.allocate(8).putLong(value).array())
                }
                else -> throw IllegalArgumentException("e2ee: cannot encode ${value::class}")
            }
        }
        return out.toByteArray()
    }

    fun sha256(input: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(input)

    private fun sha512(input: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-512").digest(input)

    fun toHex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    fun toBase64(bytes: ByteArray): String = java.util.Base64.getEncoder().encodeToString(bytes)

    fun fromBase64(value: String): ByteArray = java.util.Base64.getDecoder().decode(value)

    fun bytesEqual(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var diff = 0
        for (i in a.indices) diff = diff or ((a[i] xor b[i]).toInt() and 0xff)
        return diff == 0
    }

    private val secureRandom = java.security.SecureRandom()

    fun randomBytes(length: Int): ByteArray = ByteArray(length).also(secureRandom::nextBytes)


    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(if (key.isEmpty()) ByteArray(32) else key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val prk = hmacSha256(salt, ikm)
        val out = ByteArrayOutputStream()
        var previous = ByteArray(0)
        var counter = 1
        while (out.size() < length) {
            val input = previous + info + byteArrayOf(counter.toByte())
            previous = hmacSha256(prk, input)
            out.write(previous)
            counter += 1
        }
        return out.toByteArray().copyOf(length)
    }


    fun derToRawSignature(der: ByteArray): ByteArray {
        require(der.size >= 8 && der[0].toInt() == 0x30) { "e2ee: not a DER signature" }
        var at = if (der[1].toInt() and 0xff < 0x80) 2 else 3
        require(der[at].toInt() == 0x02) { "e2ee: malformed DER signature" }
        val rLen = der[at + 1].toInt() and 0xff
        val r = BigInteger(1, der.copyOfRange(at + 2, at + 2 + rLen))
        at += 2 + rLen
        require(der[at].toInt() == 0x02) { "e2ee: malformed DER signature" }
        val sLen = der[at + 1].toInt() and 0xff
        val s = BigInteger(1, der.copyOfRange(at + 2, at + 2 + sLen))
        return fixedWidth(r) + fixedWidth(s)
    }

    private fun fixedWidth(value: BigInteger): ByteArray {
        val bytes = value.toByteArray()
        val out = ByteArray(32)
        val source = if (bytes.size > 32) bytes.copyOfRange(bytes.size - 32, bytes.size) else bytes
        System.arraycopy(source, 0, out, 32 - source.size, source.size)
        return out
    }

    fun rawToDerSignature(raw: ByteArray): ByteArray {
        require(raw.size == 64) { "e2ee: signature must be 64 bytes" }
        val r = encodeDerInteger(BigInteger(1, raw.copyOfRange(0, 32)))
        val s = encodeDerInteger(BigInteger(1, raw.copyOfRange(32, 64)))
        val body = r + s
        val out = ByteArrayOutputStream()
        out.write(0x30)
        if (body.size < 0x80) {
            out.write(body.size)
        } else {
            out.write(0x81)
            out.write(body.size)
        }
        out.write(body)
        return out.toByteArray()
    }

    private fun encodeDerInteger(value: BigInteger): ByteArray {
        val bytes = value.toByteArray()
        return byteArrayOf(0x02, bytes.size.toByte()) + bytes
    }

    fun sign(key: PrivateKey, message: ByteArray): ByteArray {
        val signer = Signature.getInstance("SHA256withECDSA")
        signer.initSign(key)
        signer.update(message)
        return derToRawSignature(signer.sign())
    }

    fun verify(spkiPub: ByteArray, message: ByteArray, signature: ByteArray): Boolean = try {
        val key = importSigningPublicKey(spkiPub)
        val verifier = Signature.getInstance("SHA256withECDSA")
        verifier.initVerify(key)
        verifier.update(message)
        verifier.verify(rawToDerSignature(signature))
    } catch (_: Exception) {
        false
    }

    fun importSigningPublicKey(spki: ByteArray): PublicKey =
        KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(spki))

    fun importAgreementPublicKey(spki: ByteArray): PublicKey =
        KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(spki))


    data class MessageContext(
        val channelId: String,
        val epoch: Int,
        val senderDeviceId: String,
        val senderUserId: String,
        val seq: Int,
    )

    data class MessageEnvelope(
        val version: Int,
        val epoch: Int,
        val seq: Int,
        val senderDeviceId: String,
        val senderUserId: String,
        val ciphertext: ByteArray,
        val signature: ByteArray,
    )

    fun messageAad(ctx: MessageContext): ByteArray = encodeFields(
        Domain.MESSAGE_KEY,
        ctx.channelId,
        ctx.epoch,
        ctx.senderDeviceId,
        ctx.senderUserId,
        ctx.seq,
    )

    fun messageKeyInfo(senderDeviceId: String, seq: Int): ByteArray =
        encodeFields(Domain.MESSAGE_KEY, senderDeviceId, seq)

    fun messageSignaturePayload(
        ctx: MessageContext,
        ciphertext: ByteArray,
        nonce: ByteArray,
        aad: ByteArray,
    ): ByteArray = encodeFields(
        Domain.MESSAGE_SIG,
        ctx.channelId,
        ctx.epoch,
        ctx.senderDeviceId,
        ctx.senderUserId,
        ctx.seq,
        sha256(ciphertext + nonce + aad),
    )

    fun deriveMessageKey(
        conversationKey: ByteArray,
        senderDeviceId: String,
        seq: Int,
    ): SecretKeySpec = SecretKeySpec(
        deriveMessageKeyBytes(conversationKey, senderDeviceId, seq),
        "AES",
    )

    fun deriveMessageKeyBytes(
        conversationKey: ByteArray,
        senderDeviceId: String,
        seq: Int,
    ): ByteArray = hkdf(
        conversationKey,
        ByteArray(0),
        messageKeyInfo(senderDeviceId, seq),
        32,
    )

    fun sealMessage(
        conversationKey: ByteArray,
        ctx: MessageContext,
        plaintext: ByteArray,
        signingKey: PrivateKey,
    ): MessageEnvelope {
        val aad = messageAad(ctx)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            deriveMessageKey(conversationKey, ctx.senderDeviceId, ctx.seq),
            GCMParameterSpec(128, MESSAGE_NONCE),
        )
        cipher.updateAAD(aad)
        val ciphertext = cipher.doFinal(plaintext)
        val signature = sign(
            signingKey,
            messageSignaturePayload(ctx, ciphertext, MESSAGE_NONCE, aad),
        )
        return MessageEnvelope(
            VERSION,
            ctx.epoch,
            ctx.seq,
            ctx.senderDeviceId,
            ctx.senderUserId,
            ciphertext,
            signature,
        )
    }

    fun openMessage(
        conversationKey: ByteArray,
        channelId: String,
        envelope: MessageEnvelope,
        senderIkSigPub: ByteArray,
    ): ByteArray {
        val ctx = MessageContext(
            channelId,
            envelope.epoch,
            envelope.senderDeviceId,
            envelope.senderUserId,
            envelope.seq,
        )
        val aad = messageAad(ctx)
        val payload = messageSignaturePayload(ctx, envelope.ciphertext, MESSAGE_NONCE, aad)
        require(verify(senderIkSigPub, payload, envelope.signature)) {
            "e2ee: message signature does not verify"
        }

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            deriveMessageKey(conversationKey, envelope.senderDeviceId, envelope.seq),
            GCMParameterSpec(128, MESSAGE_NONCE),
        )
        cipher.updateAAD(aad)
        return cipher.doFinal(envelope.ciphertext)
    }

    fun encodeEnvelope(envelope: MessageEnvelope): ByteArray {
        require(envelope.version == VERSION) {
            "e2ee: cannot encode envelope version ${envelope.version}"
        }
        return ENVELOPE_MAGIC.toByteArray(Charsets.UTF_8) +
            byteArrayOf(envelope.version.toByte()) +
            encodeFields(
                envelope.epoch,
                envelope.seq,
                envelope.senderDeviceId,
                envelope.senderUserId,
                envelope.signature,
                envelope.ciphertext,
            )
    }

    fun decodeEnvelope(bytes: ByteArray): MessageEnvelope {
        require(bytes.size >= 5 && String(bytes, 0, 4, Charsets.UTF_8) == ENVELOPE_MAGIC) {
            "e2ee: not a message envelope"
        }
        val version = bytes[4].toInt()
        require(version == VERSION) { "e2ee: unsupported envelope version $version" }

        var at = 5
        fun next(): ByteArray {
            require(at + 4 <= bytes.size) { "e2ee: truncated envelope" }
            val length = ByteBuffer.wrap(bytes, at, 4).int
            at += 4
            require(at + length <= bytes.size) { "e2ee: truncated envelope" }
            val slice = bytes.copyOfRange(at, at + length)
            at += length
            return slice
        }

        fun nextInt(): Int {
            val raw = next()
            require(raw.size == 8) { "e2ee: malformed integer in envelope" }
            val value = ByteBuffer.wrap(raw).long
            require(value in 0..Int.MAX_VALUE) { "e2ee: integer out of range" }
            return value.toInt()
        }

        val epoch = nextInt()
        val seq = nextInt()
        val senderDeviceId = String(next(), Charsets.UTF_8)
        val senderUserId = String(next(), Charsets.UTF_8)
        val signature = next()
        val ciphertext = next()
        require(at == bytes.size) { "e2ee: trailing bytes in envelope" }

        return MessageEnvelope(
            version,
            epoch,
            seq,
            senderDeviceId,
            senderUserId,
            ciphertext,
            signature,
        )
    }


    data class WrappedKey(
        val ephemeralPub: ByteArray,
        val wrapNonce: ByteArray,
        val wrapped: ByteArray,
    )

    fun wrapAad(epochId: String, deviceId: String): ByteArray =
        encodeFields(Domain.CK_WRAP, epochId, deviceId)

    private fun wrapKeyFor(sharedSecret: ByteArray, epochId: String): SecretKeySpec =
        SecretKeySpec(
            hkdf(
                sharedSecret,
                epochId.toByteArray(Charsets.UTF_8),
                Domain.CK_WRAP.toByteArray(Charsets.UTF_8),
                32,
            ),
            "AES",
        )

    fun agree(privateKey: PrivateKey, peerPublic: PublicKey): ByteArray {
        val agreement = KeyAgreement.getInstance("ECDH")
        agreement.init(privateKey)
        agreement.doPhase(peerPublic, true)
        return agreement.generateSecret()
    }

    fun unwrapConversationKey(
        ourIkDhPriv: PrivateKey,
        epochId: String,
        deviceId: String,
        envelope: WrappedKey,
    ): ByteArray {
        val shared = agree(ourIkDhPriv, importAgreementPublicKey(envelope.ephemeralPub))
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            wrapKeyFor(shared, epochId),
            GCMParameterSpec(128, envelope.wrapNonce),
        )
        cipher.updateAAD(wrapAad(epochId, deviceId))
        return cipher.doFinal(envelope.wrapped)
    }

    fun wrapConversationKey(
        conversationKey: ByteArray,
        epochId: String,
        deviceId: String,
        recipientIkDhPub: ByteArray,
    ): WrappedKey {
        val generator = java.security.KeyPairGenerator.getInstance("EC")
        generator.initialize(java.security.spec.ECGenParameterSpec("secp256r1"))
        val ephemeral = generator.generateKeyPair()

        val shared = agree(ephemeral.private, importAgreementPublicKey(recipientIkDhPub))
        val wrapNonce = randomBytes(WRAP_NONCE_BYTES)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            wrapKeyFor(shared, epochId),
            GCMParameterSpec(128, wrapNonce),
        )
        cipher.updateAAD(wrapAad(epochId, deviceId))
        return WrappedKey(ephemeral.public.encoded, wrapNonce, cipher.doFinal(conversationKey))
    }


    data class DeviceBundle(val userId: String, val ikSigPub: ByteArray, val ikDhPub: ByteArray)

    fun deviceBundleBytes(bundle: DeviceBundle): ByteArray =
        encodeFields(Domain.DEVICE_BUNDLE, bundle.userId, bundle.ikSigPub, bundle.ikDhPub)

    fun genesisStatementBytes(bundle: DeviceBundle, identityGeneration: String): ByteArray =
        encodeFields(
            Domain.GENESIS,
            bundle.userId,
            bundle.ikSigPub,
            bundle.ikDhPub,
            identityGeneration,
        )

    fun genesisCommitment(bundle: DeviceBundle, identityGeneration: String): ByteArray =
        sha256(genesisStatementBytes(bundle, identityGeneration))

    fun addDeviceStatementBytes(bundle: DeviceBundle, transferId: String): ByteArray =
        encodeFields(Domain.ADD_DEVICE, bundle.userId, bundle.ikSigPub, bundle.ikDhPub, transferId)

    fun revokeStatementBytes(userId: String, deviceId: String, revokedAt: String): ByteArray =
        encodeFields(Domain.REVOKE, userId, deviceId, revokedAt)

    fun eraseKeysStatementBytes(userId: String, deviceId: String, issuedAt: String): ByteArray =
        encodeFields(Domain.ERASE_KEYS, userId, deviceId, issuedAt)

    fun logEntryHash(prevHash: ByteArray?, payload: ByteArray): ByteArray =
        sha256(encodeFields(Domain.LOG_ENTRY, prevHash ?: ByteArray(0), payload))

    fun logSignatureBytes(entryHash: ByteArray): ByteArray =
        encodeFields(Domain.LOG_ENTRY, entryHash)


    data class DeviceRecord(
        val id: String,
        val ikSigPub: ByteArray,
        val ikDhPub: ByteArray,
        val bundleSig: ByteArray,
        val authorizedBy: String?,
        val authorizationSig: ByteArray?,
        val revoked: Boolean,
    )

    data class LogRecord(
        val seq: Int,
        val kind: String,
        val payload: ByteArray,
        val entryHash: ByteArray,
        val prevHash: ByteArray?,
        val signature: ByteArray,
    )

    data class VerifiedIdentity(
        val genesisDeviceId: String,
        val genesisCommitment: ByteArray,
        val authorizedDeviceIds: List<String>,
        val headSeq: Int,
        val headHash: ByteArray,
    )

    class IdentityException(message: String) : Exception(message)

    private fun startsWith(haystack: ByteArray, prefix: ByteArray): Boolean =
        haystack.size >= prefix.size &&
            bytesEqual(haystack.copyOfRange(0, prefix.size), prefix)

    fun verifyLogChain(entries: List<LogRecord>): String? {
        if (entries.isEmpty()) return "empty"
        var prev: ByteArray? = null
        for ((i, entry) in entries.withIndex()) {
            if (entry.seq != i) return "seq-gap at $i"
            if (i == 0 && entry.prevHash != null) return "genesis-has-prev"
            if (i > 0 && (entry.prevHash == null || !bytesEqual(entry.prevHash, prev!!))) {
                return "prev-mismatch at $i"
            }
            if (!bytesEqual(logEntryHash(entry.prevHash, entry.payload), entry.entryHash)) {
                return "hash-mismatch at $i"
            }
            prev = entry.entryHash
        }
        return null
    }

    fun verifyIdentity(
        userId: String,
        devices: List<DeviceRecord>,
        log: List<LogRecord>,
    ): VerifiedIdentity {
        verifyLogChain(log)?.let { throw IdentityException("chain: $it") }

        val byId = devices.associateBy { it.id }
        val genesisDevices = devices.filter { it.authorizedBy == null }
        if (genesisDevices.isEmpty()) throw IdentityException("no-genesis")
        if (genesisDevices.size > 1) throw IdentityException("multiple-genesis")

        for (device in devices) {
            val bundle = DeviceBundle(userId, device.ikSigPub, device.ikDhPub)
            if (!verify(device.ikSigPub, deviceBundleBytes(bundle), device.bundleSig)) {
                throw IdentityException("bad-bundle-signature: ${device.id}")
            }
        }

        val authorized = linkedSetOf<String>()
        val introduced = mutableSetOf<String>()
        var commitment: ByteArray? = null

        fun matchByPrefix(payload: ByteArray, domain: String): DeviceRecord? = devices.firstOrNull {
            startsWith(payload, encodeFields(domain, userId, it.ikSigPub, it.ikDhPub))
        }

        for (entry in log) {
            when (entry.kind) {
                "genesis" -> {
                    val device = matchByPrefix(entry.payload, Domain.GENESIS)
                    if (device == null || device.id != genesisDevices[0].id) {
                        throw IdentityException("unknown-statement at ${entry.seq}")
                    }
                    if (!verify(
                            device.ikSigPub,
                            logSignatureBytes(entry.entryHash),
                            entry.signature,
                        )
                    ) {
                        throw IdentityException("bad-entry-signature at ${entry.seq}")
                    }
                    commitment = sha256(entry.payload)
                    authorized.add(device.id)
                    introduced.add(device.id)
                }

                "add-device" -> {
                    val device = matchByPrefix(entry.payload, Domain.ADD_DEVICE)
                    if (device?.authorizedBy == null) {
                        throw IdentityException("unknown-statement at ${entry.seq}")
                    }
                    val authorizer = byId[device.authorizedBy]
                    if (authorizer == null || !authorized.contains(authorizer.id)) {
                        throw IdentityException("unauthorized-signer at ${entry.seq}")
                    }
                    val authorizationSig = device.authorizationSig
                    if (authorizationSig == null ||
                        !verify(authorizer.ikSigPub, entry.payload, authorizationSig)
                    ) {
                        throw IdentityException("bad-authorization: ${device.id}")
                    }
                    if (!verify(
                            authorizer.ikSigPub,
                            logSignatureBytes(entry.entryHash),
                            entry.signature,
                        )
                    ) {
                        throw IdentityException("bad-entry-signature at ${entry.seq}")
                    }
                    authorized.add(device.id)
                    introduced.add(device.id)
                }

                else -> {
                    val target = devices.firstOrNull {
                        startsWith(entry.payload, encodeFields(Domain.REVOKE, userId, it.id))
                    } ?: throw IdentityException("unknown-statement at ${entry.seq}")

                    val signedByAuthorized = devices.any { candidate ->
                        authorized.contains(candidate.id) &&
                            verify(
                                candidate.ikSigPub,
                                logSignatureBytes(entry.entryHash),
                                entry.signature,
                            )
                    }
                    if (!signedByAuthorized) {
                        throw IdentityException("bad-entry-signature at ${entry.seq}")
                    }
                    authorized.remove(target.id)
                }
            }
        }

        for (device in devices) {
            if (!device.revoked && !introduced.contains(device.id)) {
                throw IdentityException("unintroduced-device: ${device.id}")
            }
        }

        val last = log.last()
        return VerifiedIdentity(
            genesisDevices[0].id,
            commitment!!,
            authorized.toList(),
            last.seq,
            last.entryHash,
        )
    }


    private fun digitGroups(digest: ByteArray, groups: Int): String =
        (0 until groups).joinToString(" ") { i ->
            var value = 0L
            for (byte in digest.copyOfRange(i * 5, i * 5 + 5)) {
                value = value * 256 + (byte.toInt() and 0xff)
            }
            (value % 100000).toString().padStart(5, '0')
        }

    fun safetyNumber(commitmentA: ByteArray, commitmentB: ByteArray): String {
        val (low, high) = if (toHex(commitmentA) <= toHex(commitmentB)) {
            commitmentA to commitmentB
        } else {
            commitmentB to commitmentA
        }
        return digitGroups(sha512(encodeFields(Domain.SAFETY_NUMBER, low, high)), 12)
    }

    fun groupSafetyNumber(commitments: List<ByteArray>): String {
        val sorted = commitments.sortedBy { toHex(it) }
        val fields = mutableListOf<Any>(Domain.GROUP_SAFETY_NUMBER)
        fields.addAll(sorted)
        return digitGroups(sha512(encodeFields(*fields.toTypedArray())), 12)
    }

    const val SAFETY_NUMBER_DIGITS = 60

    fun normalizeSafetyNumber(input: String): String? {
        val digits = input.filter { it.isDigit() }
        if (digits.length != SAFETY_NUMBER_DIGITS) return null
        return digits.chunked(5).joinToString(" ")
    }

    fun safetyNumbersMatch(typed: String, expected: String): Boolean {
        val left = normalizeSafetyNumber(typed) ?: return false
        return left == normalizeSafetyNumber(expected)
    }

    fun pairSas(sharedSecret: ByteArray, pairSecret: ByteArray): String {
        val digest = sha256(encodeFields(Domain.PAIR_SAS, sharedSecret, pairSecret))
        var value = 0L
        for (i in 0 until 4) value = value * 256 + (digest[i].toInt() and 0xff)
        return (value % 1000000).toString().padStart(6, '0')
    }

    fun transferBundleKey(sharedSecret: ByteArray, pairSecret: ByteArray): SecretKeySpec =
        SecretKeySpec(
            hkdf(
                sharedSecret + pairSecret,
                ByteArray(0),
                Domain.TRANSFER_BUNDLE.toByteArray(Charsets.UTF_8),
                32,
            ),
            "AES",
        )


    fun attachmentAad(fileId: String): ByteArray = encodeFields(Domain.ATTACHMENT, fileId)

    data class SealedFile(
        val fileId: String,
        val key: ByteArray,
        val nonce: ByteArray,
        val bytes: ByteArray,
    )

    fun sealFile(plaintext: ByteArray): SealedFile {
        val fileId = toHex(randomBytes(16))
        val raw = randomBytes(32)
        val nonce = randomBytes(WRAP_NONCE_BYTES)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(raw, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(attachmentAad(fileId))
        return SealedFile(fileId, raw, nonce, cipher.doFinal(plaintext))
    }

    fun openFile(
        rawKey: ByteArray,
        nonce: ByteArray,
        fileId: String,
        ciphertext: ByteArray,
    ): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(rawKey, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(attachmentAad(fileId))
        return cipher.doFinal(ciphertext)
    }
}
