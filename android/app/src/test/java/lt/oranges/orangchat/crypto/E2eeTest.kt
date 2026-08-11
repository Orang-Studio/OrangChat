package lt.oranges.orangchat.crypto

import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class E2eeTest {

    private fun signingPair(): KeyPair = KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec("secp256r1"))
    }.generateKeyPair()

    private fun agreementPair(): KeyPair = signingPair()

    private val ctx = E2ee.MessageContext(
        channelId = "chan1",
        epoch = 3,
        senderDeviceId = "devA",
        senderUserId = "userA",
        seq = 7,
    )


    @Test
    fun `length-prefixes every field`() {
        assertEquals("00000002686900000000", E2ee.toHex(E2ee.encodeFields("hi", ByteArray(0))))
    }

    @Test
    fun `cannot be confused by shifting a field boundary`() {
        assertNotEquals(
            E2ee.toHex(E2ee.encodeFields("ab", "c")),
            E2ee.toHex(E2ee.encodeFields("a", "bc")),
        )
    }

    @Test
    fun `encodes numbers as 8 big-endian bytes`() {
        assertEquals("000000080000000000000001", E2ee.toHex(E2ee.encodeFields(1)))
    }

    @Test
    fun `rejects values it cannot encode unambiguously`() {
        try {
            E2ee.encodeFields(-1L)
            fail("expected a negative field to be rejected")
        } catch (_: IllegalArgumentException) {
        }
    }


    @Test
    fun `pins the one-message report key across Android and web`() {
        val conversationKey = ByteArray(32) { it.toByte() }
        assertEquals(
            "1774769631917b635b4cf1e539494c4e995e9c8e6dd104957494366dddf0571e",
            E2ee.toHex(E2ee.deriveMessageKeyBytes(conversationKey, "device-1", 7)),
        )
        assertFalse(
            E2ee.deriveMessageKeyBytes(conversationKey, "device-1", 7)
                .contentEquals(E2ee.deriveMessageKeyBytes(conversationKey, "device-1", 8)),
        )
    }

    @Test
    fun `pins the statement encodings`() {
        val bundle = E2ee.DeviceBundle("u", byteArrayOf(1), byteArrayOf(2))

        assertEquals(
            "0000001a6f72616e67636861742f6465766963652d62756e646c652f7631" +
                "000000017500000001010000000102",
            E2ee.toHex(E2ee.deviceBundleBytes(bundle)),
        )
        assertEquals(
            "000000146f72616e67636861742f67656e657369732f7631" +
                "0000000175000000010100000001020000000367656e",
            E2ee.toHex(E2ee.genesisStatementBytes(bundle, "gen")),
        )
        assertEquals(
            "000000136f72616e67636861742f7265766f6b652f76310000000175000000016400000018" +
                "323032362d30312d30315430303a30303a30302e3030305a",
            E2ee.toHex(E2ee.revokeStatementBytes("u", "d", "2026-01-01T00:00:00.000Z")),
        )
        assertEquals(
            "000000176f72616e67636861742f65726173652d6b6579732f763100000001750000000164" +
                "00000018323032362d30312d30315430303a30303a30302e3030305a",
            E2ee.toHex(E2ee.eraseKeysStatementBytes("u", "d", "2026-01-01T00:00:00.000Z")),
        )
    }

    @Test
    fun `cannot spend a revocation as an erasure`() {
        assertNotEquals(
            E2ee.toHex(E2ee.revokeStatementBytes("u", "d", "now")),
            E2ee.toHex(E2ee.eraseKeysStatementBytes("u", "d", "now")),
        )
    }

    @Test
    fun `gives statements over identical material different bytes`() {
        val bundle = E2ee.DeviceBundle("u", byteArrayOf(1), byteArrayOf(2))
        val all = setOf(
            E2ee.toHex(E2ee.deviceBundleBytes(bundle)),
            E2ee.toHex(E2ee.genesisStatementBytes(bundle, "gen")),
            E2ee.toHex(E2ee.addDeviceStatementBytes(bundle, "gen")),
        )
        assertEquals(3, all.size)
    }

    @Test
    fun `pins the message nonce to 12 zero bytes`() {
        assertEquals(12, E2ee.MESSAGE_NONCE.size)
        assertTrue(E2ee.MESSAGE_NONCE.all { it.toInt() == 0 })
    }


    @Test
    fun `signatures are 64 raw bytes, not DER`() {
        val pair = signingPair()
        val signature = E2ee.sign(pair.private, "hello".toByteArray())
        assertEquals(64, signature.size)
        assertTrue(E2ee.verify(pair.public.encoded, "hello".toByteArray(), signature))
    }

    @Test
    fun `raw and DER signatures round-trip`() {
        val pair = signingPair()
        val raw = E2ee.sign(pair.private, "payload".toByteArray())
        assertArrayEquals(raw, E2ee.derToRawSignature(E2ee.rawToDerSignature(raw)))
    }


    @Test
    fun `hkdf matches RFC 5869 test case 1`() {
        val ikm = ByteArray(22) { 0x0b }
        val salt = byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12)
        val info = byteArrayOf(
            0xf0.toByte(), 0xf1.toByte(), 0xf2.toByte(), 0xf3.toByte(), 0xf4.toByte(),
            0xf5.toByte(), 0xf6.toByte(), 0xf7.toByte(), 0xf8.toByte(), 0xf9.toByte(),
        )
        assertEquals(
            "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865",
            E2ee.toHex(E2ee.hkdf(ikm, salt, info, 42)),
        )
    }


    @Test
    fun `round-trips a message`() {
        val key = E2ee.randomBytes(E2ee.CONVERSATION_KEY_BYTES)
        val pair = signingPair()
        val envelope = E2ee.sealMessage(key, ctx, "hello".toByteArray(), pair.private)
        val plaintext = E2ee.openMessage(key, ctx.channelId, envelope, pair.public.encoded)
        assertEquals("hello", String(plaintext))
    }

    @Test
    fun `binds the ciphertext to its channel`() {
        val key = E2ee.randomBytes(E2ee.CONVERSATION_KEY_BYTES)
        val pair = signingPair()
        val envelope = E2ee.sealMessage(key, ctx, "hi".toByteArray(), pair.private)
        try {
            E2ee.openMessage(key, "other-channel", envelope, pair.public.encoded)
            fail("expected a different channel to fail")
        } catch (_: Exception) {
        }
    }

    @Test
    fun `refuses a message another member re-signed as someone else`() {
        val key = E2ee.randomBytes(E2ee.CONVERSATION_KEY_BYTES)
        val honest = signingPair()
        val impostor = signingPair()
        val envelope = E2ee.sealMessage(key, ctx, "hi".toByteArray(), impostor.private)
        try {
            E2ee.openMessage(key, ctx.channelId, envelope, honest.public.encoded)
            fail("expected a foreign signature to fail")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun `envelopes round-trip and reject trailing bytes`() {
        val envelope = E2ee.MessageEnvelope(
            E2ee.VERSION,
            42,
            9,
            "device-1",
            "user-1",
            E2ee.randomBytes(64),
            E2ee.randomBytes(64),
        )
        val encoded = E2ee.encodeEnvelope(envelope)
        val decoded = E2ee.decodeEnvelope(encoded)
        assertEquals(42, decoded.epoch)
        assertEquals("device-1", decoded.senderDeviceId)
        assertArrayEquals(envelope.ciphertext, decoded.ciphertext)

        try {
            E2ee.decodeEnvelope(encoded + byteArrayOf(0))
            fail("expected trailing bytes to be rejected")
        } catch (_: IllegalArgumentException) {
        }
    }


    @Test
    fun `round-trips a conversation key to one device`() {
        val conversationKey = E2ee.randomBytes(E2ee.CONVERSATION_KEY_BYTES)
        val recipient = agreementPair()
        val wrapped = E2ee.wrapConversationKey(
            conversationKey,
            "epoch-1",
            "device-1",
            recipient.public.encoded,
        )
        val unwrapped = E2ee.unwrapConversationKey(
            recipient.private,
            "epoch-1",
            "device-1",
            wrapped,
        )
        assertArrayEquals(conversationKey, unwrapped)
    }

    @Test
    fun `will not open under another device id or epoch`() {
        val conversationKey = E2ee.randomBytes(E2ee.CONVERSATION_KEY_BYTES)
        val recipient = agreementPair()
        val wrapped = E2ee.wrapConversationKey(
            conversationKey,
            "epoch-1",
            "device-1",
            recipient.public.encoded,
        )
        for (attempt in listOf("device-2" to "epoch-1", "device-1" to "epoch-2")) {
            try {
                E2ee.unwrapConversationKey(
                    recipient.private,
                    attempt.second,
                    attempt.first,
                    wrapped,
                )
                fail("expected ${attempt.first}/${attempt.second} to fail")
            } catch (_: Exception) {
            }
        }
    }


    private fun entry(seq: Int, payload: String, prev: ByteArray?): E2ee.LogRecord {
        val bytes = payload.toByteArray()
        return E2ee.LogRecord(
            seq,
            "genesis",
            bytes,
            E2ee.logEntryHash(prev, bytes),
            prev,
            ByteArray(0),
        )
    }

    @Test
    fun `accepts an intact chain and catches a rewritten one`() {
        val first = entry(0, "genesis", null)
        val second = entry(1, "add", first.entryHash)
        assertNull(E2ee.verifyLogChain(listOf(first, second)))

        val tampered = second.copy(payload = "different".toByteArray())
        assertEquals("hash-mismatch at 1", E2ee.verifyLogChain(listOf(first, tampered)))

        val third = entry(2, "revoke", second.entryHash)
        assertEquals("seq-gap at 1", E2ee.verifyLogChain(listOf(first, third)))
        assertEquals("empty", E2ee.verifyLogChain(emptyList()))
    }


    private class Account(
        val sig: KeyPair,
        val device: E2ee.DeviceRecord,
        val log: List<E2ee.LogRecord>,
    )

    private fun buildAccount(userId: String): Account {
        val sig = signingPair()
        val dh = agreementPair()
        val bundle = E2ee.DeviceBundle(userId, sig.public.encoded, dh.public.encoded)
        val bundleSig = E2ee.sign(sig.private, E2ee.deviceBundleBytes(bundle))

        val payload = E2ee.genesisStatementBytes(bundle, "generation-1")
        val entryHash = E2ee.logEntryHash(null, payload)
        val signature = E2ee.sign(sig.private, E2ee.logSignatureBytes(entryHash))

        return Account(
            sig,
            E2ee.DeviceRecord(
                id = "device-genesis",
                ikSigPub = sig.public.encoded,
                ikDhPub = dh.public.encoded,
                bundleSig = bundleSig,
                authorizedBy = null,
                authorizationSig = null,
                revoked = false,
            ),
            listOf(E2ee.LogRecord(0, "genesis", payload, entryHash, null, signature)),
        )
    }

    @Test
    fun `accepts a genuine genesis device`() {
        val account = buildAccount("user-1")
        val identity = E2ee.verifyIdentity("user-1", listOf(account.device), account.log)
        assertEquals("device-genesis", identity.genesisDeviceId)
        assertEquals(listOf("device-genesis"), identity.authorizedDeviceIds)
    }

    @Test
    fun `rejects a device the server invented`() {
        val account = buildAccount("user-1")
        val sig = signingPair()
        val dh = agreementPair()
        val bundle = E2ee.DeviceBundle("user-1", sig.public.encoded, dh.public.encoded)

        val injected = E2ee.DeviceRecord(
            id = "device-injected",
            ikSigPub = sig.public.encoded,
            ikDhPub = dh.public.encoded,
            bundleSig = E2ee.sign(sig.private, E2ee.deviceBundleBytes(bundle)),
            authorizedBy = "device-genesis",
            authorizationSig = E2ee.sign(
                sig.private,
                E2ee.addDeviceStatementBytes(bundle, "x".repeat(32)),
            ),
            revoked = false,
        )

        try {
            E2ee.verifyIdentity("user-1", listOf(account.device, injected), account.log)
            fail("expected an invented device to be rejected")
        } catch (e: E2ee.IdentityException) {
            assertTrue(e.message!!.startsWith("unintroduced-device"))
        }
    }

    @Test
    fun `rejects a bundle signed for a different account`() {
        val account = buildAccount("user-1")
        try {
            E2ee.verifyIdentity("user-2", listOf(account.device), account.log)
            fail("expected a foreign bundle to be rejected")
        } catch (e: E2ee.IdentityException) {
            assertTrue(e.message!!.startsWith("bad-bundle-signature"))
        }
    }


    @Test
    fun `safety numbers are order-independent and 12 groups of 5 digits`() {
        val a = E2ee.randomBytes(32)
        val b = E2ee.randomBytes(32)
        val value = E2ee.safetyNumber(a, b)
        assertEquals(E2ee.safetyNumber(b, a), value)
        assertEquals(12, value.split(" ").size)
        assertTrue(value.replace(" ", "").matches(Regex("\\d{60}")))
    }

    @Test
    fun `typed safety numbers ignore spacing and punctuation`() {
        val value = E2ee.safetyNumber(E2ee.randomBytes(32), E2ee.randomBytes(32))
        val digits = value.replace(" ", "")
        assertTrue(E2ee.safetyNumbersMatch(digits, value))
        assertTrue(E2ee.safetyNumbersMatch("  ${digits.chunked(4).joinToString("-")}\n", value))
    }

    @Test
    fun `a partly typed safety number is never called a mismatch`() {
        val value = E2ee.safetyNumber(E2ee.randomBytes(32), E2ee.randomBytes(32))
        val digits = value.replace(" ", "")
        assertNull(E2ee.normalizeSafetyNumber(digits.dropLast(1)))
        assertFalse(E2ee.safetyNumbersMatch(digits.dropLast(1), value))
        assertFalse(E2ee.safetyNumbersMatch(digits + "0", value))
        assertFalse(E2ee.safetyNumbersMatch("", value))
    }

    @Test
    fun `typed safety numbers reject another conversation and a transposed digit`() {
        val mine = E2ee.randomBytes(32)
        val ours = E2ee.safetyNumber(mine, E2ee.randomBytes(32))
        assertFalse(E2ee.safetyNumbersMatch(E2ee.safetyNumber(mine, E2ee.randomBytes(32)), ours))

        val digits = ours.replace(" ", "").toCharArray()
        val at = (1 until digits.size).first { digits[it] != digits[it - 1] }
        digits[at - 1] = digits[at].also { digits[at] = digits[at - 1] }
        assertFalse(E2ee.safetyNumbersMatch(String(digits), ours))
    }

    @Test
    fun `group safety numbers do not depend on member order`() {
        val members = listOf(E2ee.randomBytes(32), E2ee.randomBytes(32), E2ee.randomBytes(32))
        assertEquals(
            E2ee.groupSafetyNumber(members),
            E2ee.groupSafetyNumber(members.reversed()),
        )
        assertNotEquals(
            E2ee.groupSafetyNumber(members),
            E2ee.groupSafetyNumber(members + E2ee.randomBytes(32)),
        )
    }

    @Test
    fun `pairing SAS is six digits`() {
        assertTrue(
            E2ee.pairSas(E2ee.randomBytes(32), E2ee.randomBytes(32)).matches(Regex("\\d{6}")),
        )
    }


    @Test
    fun `round-trips a transfer code`() {
        val payload = E2eeQr.DeviceTransfer(
            transferId = "a".repeat(32),
            ikSigPub = E2ee.randomBytes(8),
            ikDhPub = E2ee.randomBytes(8),
            pairSecret = E2ee.randomBytes(32),
        )
        val decoded = E2eeQr.decodeDeviceTransfer(E2eeQr.encodeDeviceTransfer(payload))
        assertEquals(payload.transferId, decoded.transferId)
        assertArrayEquals(payload.pairSecret, decoded.pairSecret)
    }

    @Test
    fun `round-trips a desktop-first transfer invitation`() {
        val payload = E2eeQr.DeviceTransferInvite(
            transferId = "b".repeat(32),
            pairSecret = E2ee.randomBytes(32),
        )
        val encoded = E2eeQr.encodeDeviceTransferInvite(payload)
        val decoded = E2eeQr.decodeDeviceTransferInvite(encoded)
        assertEquals(payload.transferId, decoded.transferId)
        assertArrayEquals(payload.pairSecret, decoded.pairSecret)
        assertTrue(E2eeQr.isDeviceTransferInvite(encoded))
        try {
            E2eeQr.decodeDeviceTransfer(encoded)
            fail("expected the invitation to require the new device")
        } catch (e: E2eeQr.WrongKindException) {
            assertTrue(e.message!!.contains("phone being added"))
        }
    }

    @Test
    fun `pins the desktop invitation wire format for web`() {
        assertEquals(
            "orangchat://device-transfer?v=1&m=invite&t=${"a".repeat(32)}" +
                "&p=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8%3D",
            E2eeQr.encodeDeviceTransferInvite(
                E2eeQr.DeviceTransferInvite(
                    transferId = "a".repeat(32),
                    pairSecret = ByteArray(32) { it.toByte() },
                ),
            ),
        )
    }

    @Test
    fun `round-trips a verification code`() {
        val payload = E2eeQr.ContactVerify(
            userId = "user-1",
            ikSigPub = E2ee.randomBytes(8),
            ikDhPub = E2ee.randomBytes(8),
            genesisCommitment = E2ee.randomBytes(32),
        )
        val decoded = E2eeQr.decodeContactVerify(E2eeQr.encodeContactVerify(payload))
        assertEquals("user-1", decoded.userId)
        assertArrayEquals(payload.genesisCommitment, decoded.genesisCommitment)
    }

    @Test
    fun `refuses a code of the wrong kind by name`() {
        val transfer = E2eeQr.encodeDeviceTransfer(
            E2eeQr.DeviceTransfer("a".repeat(32), byteArrayOf(1), byteArrayOf(2), ByteArray(32)),
        )
        val verify = E2eeQr.encodeContactVerify(
            E2eeQr.ContactVerify("user-1", byteArrayOf(1), byteArrayOf(2), ByteArray(32)),
        )

        try {
            E2eeQr.decodeContactVerify(transfer)
            fail("expected a transfer code to be refused")
        } catch (e: E2eeQr.WrongKindException) {
            assertTrue(e.message!!.contains("device-transfer code, not a verify code"))
        }

        try {
            E2eeQr.decodeDeviceTransfer(verify)
            fail("expected a verification code to be refused")
        } catch (e: E2eeQr.WrongKindException) {
            assertTrue(e.message!!.contains("verify code, not a device-transfer code"))
        }

        for (attempt in listOf<() -> Unit>(
            { E2eeQr.decodeContactVerify("orangchat://login?token=abc") },
            { E2eeQr.decodeDeviceTransfer("orangchat://login?token=abc") },
        )) {
            try {
                attempt()
                fail("expected the sign-in code to be refused")
            } catch (e: E2eeQr.WrongKindException) {
                assertTrue(e.message!!.contains("login code"))
            }
        }

        try {
            E2eeQr.decodeContactVerify("https://example.com/?v=1")
            fail("expected a non-OrangChat code to be refused")
        } catch (e: E2eeQr.WrongKindException) {
            assertTrue(e.message!!.contains("not an OrangChat code"))
        }
    }


    @Test
    fun `round-trips a payload and reads pre-payload text as text`() {
        val payload = MessagePayload(
            text = "hello",
            sentAt = "2026-07-26T10:00:00.000Z",
            clientId = "local-1",
            replyTo = "msg-9",
            heads = listOf(GossipedHead("u", 2, "aGVhZA==")),
        )
        val decoded = E2eePayloads.decode(E2eePayloads.encode(payload))
        assertEquals("hello", decoded.text)
        assertEquals("local-1", decoded.clientId)
        assertEquals("msg-9", decoded.replyTo)
        assertEquals(1, decoded.heads!!.size)

        val legacy = E2eePayloads.decode("just text".toByteArray())
        assertEquals("just text", legacy.text)
        assertEquals("", legacy.clientId)
        assertEquals("{not json", E2eePayloads.decode("{not json".toByteArray()).text)
    }

    @Test
    fun `head gossip agrees, forks and reports ahead`() {
        val known = listOf("aGVhZDA=", "aGVhZDE=", "aGVhZDI=")
        assertEquals(
            "agrees",
            E2eePayloads.checkGossipedHead(GossipedHead("u", 1, "aGVhZDE="), known),
        )
        assertEquals(
            "fork",
            E2eePayloads.checkGossipedHead(GossipedHead("u", 1, "b3RoZXI="), known),
        )
        assertEquals(
            "ahead",
            E2eePayloads.checkGossipedHead(GossipedHead("u", 7, "aGVhZDc="), known),
        )
    }

    // ── Attachments ───────────────────────────────────────

    @Test
    fun `round-trips file bytes and binds them to their file id`() {
        val plaintext = E2ee.randomBytes(1024)
        val sealed = E2ee.sealFile(plaintext)
        assertArrayEquals(
            plaintext,
            E2ee.openFile(sealed.key, sealed.nonce, sealed.fileId, sealed.bytes),
        )
        try {
            E2ee.openFile(sealed.key, sealed.nonce, "a-different-file", sealed.bytes)
            fail("expected a different file id to fail")
        } catch (_: Exception) {
            // expected
        }
    }

    @Test
    fun `never reuses a key or a nonce between files`() {
        val a = E2ee.sealFile(E2ee.randomBytes(16))
        val b = E2ee.sealFile(E2ee.randomBytes(16))
        assertFalse(E2ee.bytesEqual(a.key, b.key))
        assertFalse(E2ee.bytesEqual(a.nonce, b.nonce))
        assertNotEquals(a.fileId, b.fileId)
    }
}
