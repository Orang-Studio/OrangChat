package lt.oranges.orangchat.crypto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class SealedAttachmentRef(
    val fileId: String,
    val attachmentId: String,
    val key: String,
    val nonce: String,
    val filename: String,
    val contentType: String,
    val size: Long,
    val duration: Double? = null,
    val width: Int? = null,
    val height: Int? = null,
    val spoiler: Boolean? = null,
    val blur: String? = null,
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
