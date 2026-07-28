package lt.oranges.orangchat.crypto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What actually gets encrypted, mirroring `MessagePayload` in
 * `packages/shared/src/e2ee.ts`.
 *
 * The server assigns the message id after the client has already sealed, so it
 * cannot be bound into the AAD. The payload therefore carries its own timestamp,
 * reply target and client id, and clients render *those* rather than the
 * server's copy. The server can still lie about ordering; it cannot lie about
 * content or authorship.
 */
@Serializable
data class SealedAttachmentRef(
    val fileId: String,
    val attachmentId: String,
    val key: String,
    val nonce: String,
    val filename: String,
    val contentType: String,
    val size: Long,
    val width: Int? = null,
    val height: Int? = null,
    val spoiler: Boolean? = null,
    val thumb: Thumb? = null,
) {
    @Serializable
    data class Thumb(
        val fileId: String,
        val attachmentId: String,
        val key: String,
        val nonce: String,
        val contentType: String,
        val size: Long,
    )
}

/** A log head as one participant saw it, cross-checked on receipt (§6.1). */
@Serializable
data class GossipedHead(val userId: String, val seq: Int, val entryHash: String)

@Serializable
data class MessagePayload(
    @SerialName("v") val version: Int = E2ee.PAYLOAD_VERSION,
    val text: String,
    val sentAt: String = "",
    val clientId: String = "",
    val replyTo: String? = null,
    val attachments: List<SealedAttachmentRef>? = null,
    val heads: List<GossipedHead>? = null,
)

object E2eePayloads {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    fun encode(payload: MessagePayload): ByteArray =
        json.encodeToString(MessagePayload.serializer(), payload.copy(version = E2ee.PAYLOAD_VERSION))
            .toByteArray(Charsets.UTF_8)

    fun encodeAttachments(attachments: List<SealedAttachmentRef>): String =
        json.encodeToString(
            kotlinx.serialization.builtins.ListSerializer(SealedAttachmentRef.serializer()),
            attachments,
        )

    fun decodeAttachments(value: String): List<SealedAttachmentRef> =
        json.decodeFromString(
            kotlinx.serialization.builtins.ListSerializer(SealedAttachmentRef.serializer()),
            value,
        )

    /**
     * Messages sealed before the payload format existed are bare UTF-8 text.
     * Reading them as text rather than failing is the difference between old
     * history rendering and old history looking corrupted.
     */
    fun decode(bytes: ByteArray): MessagePayload {
        val text = String(bytes, Charsets.UTF_8)
        if (!text.startsWith("{")) return MessagePayload(text = text)
        return try {
            val parsed = json.decodeFromString(MessagePayload.serializer(), text)
            if (parsed.version > E2ee.PAYLOAD_VERSION) {
                throw IllegalStateException(
                    "e2ee: this message needs a newer version of OrangChat (payload v${parsed.version})",
                )
            }
            parsed
        } catch (e: IllegalStateException) {
            throw e
        } catch (_: Exception) {
            MessagePayload(text = text)
        }
    }

    /**
     * Checks one gossiped head against the entry hashes this device replayed for
     * that account. "ahead" is not yet a problem - it means the server has not
     * shown us those entries - but a disagreement at a sequence we already
     * committed to is an equivocating server.
     */
    fun checkGossipedHead(head: GossipedHead, known: List<String>): String = when {
        head.seq >= known.size -> "ahead"
        known[head.seq] == head.entryHash -> "agrees"
        else -> "fork"
    }
}
