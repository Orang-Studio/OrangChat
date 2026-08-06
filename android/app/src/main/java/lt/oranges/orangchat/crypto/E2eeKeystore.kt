package lt.oranges.orangchat.crypto

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.PublicKey
import java.security.spec.ECGenParameterSpec
import org.json.JSONArray
import org.json.JSONObject
import lt.oranges.orangchat.data.model.Message

/**
 * Where this device's private keys live (docs/E2EE.md §3).
 *
 * The identity keys are generated inside the Android Keystore - StrongBox when
 * the hardware has it - and are **non-extractable**: their bytes never exist in
 * app memory, so code that can use them while the app is running is a bad day,
 * while code that could copy them would own the account's future traffic on
 * every device that trusts it. P-256 rather than X25519 exactly because it can
 * be non-extractable on both platforms.
 *
 * Conversation keys are unavoidably in memory to do bulk decryption. At rest
 * they live in EncryptedSharedPreferences, whose master key is itself Keystore
 * backed, so a copied preferences file is inert off this device.
 */
class E2eeKeystore(context: Context) {

    private val appContext: Context = context.applicationContext

    // Both are built on first use rather than on construction: opening the
    // Keystore and unwrapping the preference keyset costs real milliseconds, and
    // holding a keystore reference must not be something a caller has to think
    // about before doing it.
    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            appContext,
            "orangchat_e2ee",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    private val keyStore: KeyStore by lazy {
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    }

    data class LocalIdentity(
        val userId: String,
        val deviceId: String,
        val identityGeneration: String,
        val ikSigPub: ByteArray,
        val ikDhPub: ByteArray,
    )

    // ── Identity ──────────────────────────────────────────

    fun identity(): LocalIdentity? {
        val userId = prefs.getString(KEY_USER, null) ?: return null
        val deviceId = prefs.getString(KEY_DEVICE, null) ?: return null
        val sigPub = signingPublic() ?: return null
        val dhPub = agreementPublic() ?: return null
        return LocalIdentity(
            userId = userId,
            deviceId = deviceId,
            identityGeneration = prefs.getString(KEY_GENERATION, "").orEmpty(),
            ikSigPub = sigPub.encoded,
            ikDhPub = dhPub.encoded,
        )
    }

    fun rememberIdentity(userId: String, deviceId: String, identityGeneration: String) {
        prefs.edit()
            .putString(KEY_USER, userId)
            .putString(KEY_DEVICE, deviceId)
            .putString(KEY_GENERATION, identityGeneration)
            .apply()
    }

    fun hasKeys(): Boolean = keyStore.containsAlias(ALIAS_SIG) && keyStore.containsAlias(ALIAS_DH)

    /**
     * Mints this device's identity. Both pairs are generated in the Keystore and
     * never leave it; `setIsStrongBoxBacked` is attempted first and falls back,
     * because a device without StrongBox must still get a TEE-backed key rather
     * than no key at all.
     */
    fun generateIdentityKeys() {
        generate(ALIAS_SIG, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
        generate(ALIAS_DH, KeyProperties.PURPOSE_AGREE_KEY)
    }

    private fun generate(alias: String, purposes: Int) {
        if (keyStore.containsAlias(alias)) return

        fun spec(strongBox: Boolean) = KeyGenParameterSpec.Builder(alias, purposes)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .apply {
                if (purposes and KeyProperties.PURPOSE_SIGN != 0) {
                    setDigests(KeyProperties.DIGEST_SHA256)
                }
                if (strongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    setIsStrongBoxBacked(true)
                }
            }
            .build()

        val generator = KeyPairGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_EC,
            ANDROID_KEYSTORE,
        )
        try {
            generator.initialize(spec(true))
            generator.generateKeyPair()
        } catch (_: Exception) {
            generator.initialize(spec(false))
            generator.generateKeyPair()
        }
    }

    fun signingPrivate(): PrivateKey? = keyStore.getKey(ALIAS_SIG, null) as? PrivateKey

    fun signingPublic(): PublicKey? = keyStore.getCertificate(ALIAS_SIG)?.publicKey

    fun agreementPrivate(): PrivateKey? = keyStore.getKey(ALIAS_DH, null) as? PrivateKey

    fun agreementPublic(): PublicKey? = keyStore.getCertificate(ALIAS_DH)?.publicKey

    fun sign(message: ByteArray): ByteArray {
        val key = signingPrivate() ?: error("e2ee: this device has no signing key")
        return E2ee.sign(key, message)
    }

    // ── Conversation keys ─────────────────────────────────

    fun saveEpochKey(channelId: String, epoch: Int, key: ByteArray) {
        prefs.edit().putString(epochPrefKey(channelId, epoch), E2ee.toBase64(key)).apply()
    }

    fun epochKey(channelId: String, epoch: Int): ByteArray? =
        prefs.getString(epochPrefKey(channelId, epoch), null)?.let(E2ee::fromBase64)

    fun knownEpochs(channelId: String): List<Int> =
        prefs.all.keys
            .mapNotNull { key ->
                key.removePrefix("$PREFIX_EPOCH$channelId:")
                    .takeIf { key.startsWith("$PREFIX_EPOCH$channelId:") }
                    ?.toIntOrNull()
            }
            .sorted()

    data class EpochKey(
        val channelId: String,
        val epoch: Int,
        val key: ByteArray,
    )

    /**
     * The history archive copied during a device transfer. Identity private keys
     * are deliberately absent: the receiving device has already made its own.
     */
    fun allEpochKeys(): List<EpochKey> =
        prefs.all.entries.mapNotNull { (prefKey, value) ->
            if (!prefKey.startsWith(PREFIX_EPOCH) || value !is String) return@mapNotNull null
            val encoded = prefKey.removePrefix(PREFIX_EPOCH)
            val separator = encoded.lastIndexOf(':')
            if (separator <= 0) return@mapNotNull null
            val epoch = encoded.substring(separator + 1).toIntOrNull() ?: return@mapNotNull null
            EpochKey(
                channelId = encoded.substring(0, separator),
                epoch = epoch,
                key = runCatching { E2ee.fromBase64(value) }.getOrNull() ?: return@mapNotNull null,
            )
        }.sortedWith(compareBy(EpochKey::channelId, EpochKey::epoch))

    /**
     * Hands out the next per-message sequence number for an epoch. The whole
     * safety argument for the fixed message nonce rests on this never repeating
     * for one (conversation key, device), so a client that cannot prove
     * monotonicity must mint a new epoch rather than guess.
     */
    @Synchronized
    fun reserveSeq(channelId: String, epoch: Int): Int {
        val key = "$PREFIX_SEQ$channelId:$epoch"
        val seq = prefs.getInt(key, 0)
        prefs.edit().putInt(key, seq + 1).commit()
        return seq
    }

    // ── Pins and gossip ───────────────────────────────────

    data class Pin(
        val userId: String,
        val genesisCommitment: String,
        val verifiedAt: String?,
        val headSeq: Int,
        val headHash: String,
        val entryHashes: List<String>,
    )

    fun pin(userId: String): Pin? {
        val raw = prefs.getString("$PREFIX_PIN$userId", null) ?: return null
        val json = JSONObject(raw)
        val hashes = json.optJSONArray("entryHashes") ?: JSONArray()
        return Pin(
            userId = userId,
            genesisCommitment = json.getString("genesisCommitment"),
            verifiedAt = json.optString("verifiedAt").takeIf { it.isNotEmpty() },
            headSeq = json.optInt("headSeq"),
            headHash = json.optString("headHash"),
            entryHashes = (0 until hashes.length()).map { hashes.getString(it) },
        )
    }

    fun savePin(pin: Pin) {
        val json = JSONObject()
            .put("genesisCommitment", pin.genesisCommitment)
            .put("verifiedAt", pin.verifiedAt ?: "")
            .put("headSeq", pin.headSeq)
            .put("headHash", pin.headHash)
            .put("entryHashes", JSONArray(pin.entryHashes))
        prefs.edit().putString("$PREFIX_PIN${pin.userId}", json.toString()).apply()
    }

    /**
     * Puts an account back to first-contact state. Only for accepting an
     * identity change out loud - a pin is a commitment, and discarding one
     * quietly is indistinguishable from losing the memory that catches a
     * server out.
     */
    fun deletePin(userId: String) {
        prefs.edit().remove("$PREFIX_PIN$userId").apply()
    }

    fun setGlobalStrict(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_GLOBAL_STRICT, enabled).apply()
    }

    fun globalStrict(): Boolean = prefs.getBoolean(KEY_GLOBAL_STRICT, false)

    /** Per-conversation policy; absent follows the account-wide default. */
    fun setStrictFor(channelId: String, enabled: Boolean?) {
        val edit = prefs.edit()
        if (enabled == null) edit.remove("$PREFIX_STRICT$channelId")
        else edit.putBoolean("$PREFIX_STRICT$channelId", enabled)
        edit.apply()
    }

    fun strictFor(channelId: String, channelType: String): Boolean {
        if (channelType != "dm") return false
        val key = "$PREFIX_STRICT$channelId"
        return if (prefs.contains(key)) prefs.getBoolean(key, false) else globalStrict()
    }

    // ── Device signing keys, for the notification path ────

    /**
     * Signing keys of devices whose whole chain has already been replayed and
     * accepted. The notification path reads them so a push can still check the
     * sender's signature (§2) without being able to re-run the verification.
     */
    fun rememberDeviceKey(deviceId: String, userId: String, ikSigPub: String) {
        prefs.edit().putString("$PREFIX_DEVICE$deviceId", "$userId:$ikSigPub").apply()
    }

    fun deviceKey(deviceId: String): Pair<String, ByteArray>? {
        val raw = prefs.getString("$PREFIX_DEVICE$deviceId", null) ?: return null
        val at = raw.indexOf(':')
        if (at <= 0) return null
        return raw.substring(0, at) to E2ee.fromBase64(raw.substring(at + 1))
    }

    // ── Local message cache ───────────────────────────────

    fun cacheMessage(message: Message, payload: MessagePayload) {
        prefs.edit()
            .putString(
                "$PREFIX_MESSAGE${message.id}",
                JSONObject()
                    .put("c", message.channelId)
                    .put("a", message.author.id)
                    .put("d", payload.sentAt.ifBlank { message.createdAt })
                    .put("t", payload.text)
                    .put("p", E2ee.toBase64(E2eePayloads.encode(payload)))
                    .toString(),
            )
            .apply()
    }

    fun cachedMessage(messageId: String): String? =
        prefs.getString("$PREFIX_MESSAGE$messageId", null)
            ?.let { runCatching { JSONObject(it).getString("t") }.getOrNull() }

    fun cachedPayload(messageId: String): MessagePayload? =
        prefs.getString("$PREFIX_MESSAGE$messageId", null)
            ?.let { runCatching { JSONObject(it).optString("p") }.getOrNull() }
            ?.takeIf(String::isNotEmpty)
            ?.let { runCatching { E2eePayloads.decode(E2ee.fromBase64(it)) }.getOrNull() }

    /** Add searchable metadata to records written before the Android index had it. */
    fun backfillCachedMessageMetadata(message: Message, sentAt: String? = null) {
        val key = "$PREFIX_MESSAGE${message.id}"
        val raw = prefs.getString(key, null) ?: return
        val body = runCatching { JSONObject(raw) }.getOrNull() ?: return
        if (body.optString("a").isNotEmpty() && body.optString("d").isNotEmpty()) return
        body.put("a", message.author.id)
        body.put("d", sentAt?.takeIf(String::isNotBlank) ?: message.createdAt)
        prefs.edit().putString(key, body.toString()).apply()
    }

    fun rememberSealedAttachment(ref: SealedAttachmentRef) {
        val edit = prefs.edit().putString(
            "$PREFIX_ATTACHMENT${ref.attachmentId}",
            E2eePayloads.encodeAttachments(listOf(ref)),
        )
        ref.thumb?.let { thumb ->
            edit.putString(
                "$PREFIX_ATTACHMENT${thumb.attachmentId}",
                E2eePayloads.encodeAttachments(
                    listOf(
                        ref.copy(
                            fileId = thumb.fileId,
                            attachmentId = thumb.attachmentId,
                            key = thumb.key,
                            nonce = thumb.nonce,
                            contentType = thumb.contentType,
                            size = thumb.size,
                            width = null,
                            height = null,
                            spoiler = null,
                            blur = null,
                            thumb = null,
                        ),
                    ),
                ),
            )
            edit.putBoolean("$PREFIX_THUMB${thumb.attachmentId}", true)
        }
        edit.apply()
    }

    fun sealedAttachment(attachmentId: String): SealedAttachmentRef? =
        prefs.getString("$PREFIX_ATTACHMENT$attachmentId", null)
            ?.let { runCatching { E2eePayloads.decodeAttachments(it).firstOrNull() }.getOrNull() }

    fun isSealedThumbnail(attachmentId: String): Boolean =
        prefs.getBoolean("$PREFIX_THUMB$attachmentId", false)

    data class CachedMessage(
        val id: String,
        val channelId: String,
        val authorId: String,
        val createdAt: String,
        val text: String,
    )

    /** Local search over encrypted messages this device has actually opened. */
    fun searchCached(
        query: String,
        channelIds: Set<String>? = null,
        limit: Int = 100,
    ): List<CachedMessage> {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) return emptyList()
        return prefs.all.entries.mapNotNull { (key, value) ->
            if (!key.startsWith(PREFIX_MESSAGE) || value !is String) return@mapNotNull null
            val json = runCatching { JSONObject(value) }.getOrNull() ?: return@mapNotNull null
            val text = json.optString("t")
            val cachedChannelId = json.optString("c")
            if (channelIds != null && cachedChannelId !in channelIds) return@mapNotNull null
            if (!text.lowercase().contains(needle)) return@mapNotNull null
            CachedMessage(
                id = key.removePrefix(PREFIX_MESSAGE),
                channelId = cachedChannelId,
                // Records written by older releases did not carry these two
                // fields. They remain searchable and the UI resolves whatever
                // metadata it can from its conversation/member directory.
                authorId = json.optString("a"),
                createdAt = json.optString("d"),
                text = text,
            )
        }.sortedByDescending(CachedMessage::createdAt).take(limit)
    }

    fun forgetCachedMessage(messageId: String) {
        prefs.edit().remove("$PREFIX_MESSAGE$messageId").apply()
    }

    data class QueuedMessage(
        val id: String,
        val channelId: String,
        val content: String,
        val replyToId: String?,
        val attachmentIds: List<String>,
        val sealedAttachments: List<SealedAttachmentRef>,
    )

    fun saveQueuedMessage(message: QueuedMessage) {
        prefs.edit().putString(
            "$PREFIX_QUEUED${message.id}",
            JSONObject()
                .put("channelId", message.channelId)
                .put("content", message.content)
                .put("replyToId", message.replyToId ?: "")
                .put("attachmentIds", JSONArray(message.attachmentIds))
                .put("sealedAttachments", E2eePayloads.encodeAttachments(message.sealedAttachments))
                .toString(),
        ).apply()
    }

    fun removeQueuedMessage(id: String) {
        prefs.edit().remove("$PREFIX_QUEUED$id").apply()
    }

    fun queuedMessages(): List<QueuedMessage> =
        prefs.all.entries.mapNotNull { (key, value) ->
            if (!key.startsWith(PREFIX_QUEUED) || value !is String) return@mapNotNull null
            val body = runCatching { JSONObject(value) }.getOrNull() ?: return@mapNotNull null
            val ids = body.optJSONArray("attachmentIds") ?: JSONArray()
            QueuedMessage(
                id = key.removePrefix(PREFIX_QUEUED),
                channelId = body.getString("channelId"),
                content = body.getString("content"),
                replyToId = body.optString("replyToId").ifBlank { null },
                attachmentIds = (0 until ids.length()).map(ids::getString),
                sealedAttachments = body.optString("sealedAttachments")
                    .takeIf(String::isNotEmpty)
                    ?.let { runCatching { E2eePayloads.decodeAttachments(it) }.getOrNull() }
                    .orEmpty(),
            )
        }

    /**
     * Forgets only this device's own identity, so a revoked phone can enrol
     * again. What it learned about other people - the contacts checked in
     * person, the pinned logs - was never in question and is left alone.
     */
    fun clearIdentity() {
        prefs.edit()
            .remove(KEY_USER)
            .remove(KEY_DEVICE)
            .remove(KEY_GENERATION)
            .apply()
        runCatching { keyStore.deleteEntry(ALIAS_SIG) }
        runCatching { keyStore.deleteEntry(ALIAS_DH) }
    }

    /** Signing out must not leave a readable history behind for the next account. */
    fun clear() {
        prefs.edit().clear().apply()
        runCatching { keyStore.deleteEntry(ALIAS_SIG) }
        runCatching { keyStore.deleteEntry(ALIAS_DH) }
    }

    private fun epochPrefKey(channelId: String, epoch: Int) = "$PREFIX_EPOCH$channelId:$epoch"

    companion object {
        @Volatile
        private var instance: E2eeKeystore? = null

        /**
         * The one instance for the process, shared with the Hilt binding. A
         * keystore built per caller means the Keystore and the preference keyset
         * are opened again for every one of them; doing that from composition,
         * once per attachment on screen, is how a chat full of encrypted files
         * became an ANR.
         */
        fun get(context: Context): E2eeKeystore =
            instance ?: synchronized(this) {
                instance ?: E2eeKeystore(context.applicationContext).also { instance = it }
            }

        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val ALIAS_SIG = "orangchat.e2ee.ik-sig"
        private const val ALIAS_DH = "orangchat.e2ee.ik-dh"
        private const val KEY_USER = "identity.userId"
        private const val KEY_DEVICE = "identity.deviceId"
        private const val KEY_GENERATION = "identity.generation"
        private const val PREFIX_EPOCH = "epoch:"
        private const val PREFIX_SEQ = "seq:"
        private const val PREFIX_PIN = "pin:"
        private const val PREFIX_DEVICE = "device:"
        private const val PREFIX_MESSAGE = "msg:"
        private const val PREFIX_QUEUED = "queued:"
        private const val PREFIX_ATTACHMENT = "attachment:"
        private const val PREFIX_THUMB = "attachment-thumb:"
        private const val PREFIX_STRICT = "policy.strict.channel:"
        private const val KEY_GLOBAL_STRICT = "policy.strict.global"
    }
}
