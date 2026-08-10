package lt.oranges.orangchat.feature.chat

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import lt.oranges.orangchat.data.model.Message

/**
 * Things that happen *to* a conversation rather than in it: the verification
 * requirement going on or off, a fresh key, a new background, a call.
 *
 * These used to travel as ordinary messages whose exact text every client
 * matched, which meant anyone could forge one by typing the sentence, and the
 * notice was attributed to whoever sent it. Now the server writes the kind into
 * `Message.systemNotice`, a field no client can set, and only for an action the
 * server carried out itself. The author stays the person whose action it was, so
 * the notice can still say who did it.
 *
 * Kinds are a wire contract shared with the web client (`SYSTEM_NOTICE_KINDS` in
 * `packages/shared/src/e2ee.ts`) and are append-only: a stored kind is what
 * history renders from. A kind this build has never heard of falls back to the
 * sentence the server wrote, which already names the actor.
 */
enum class SystemNotice(val kind: String) {
    StrictDisabled("strictDisabled"),
    StrictEnabled("strictEnabled"),
    KeyReset("keyReset"),
    BackgroundChanged("backgroundChanged"),
    BackgroundRemoved("backgroundRemoved"),
    IconChanged("iconChanged"),
    IconRemoved("iconRemoved"),

    /** A call: one card, rewritten as the call runs, rather than a sentence. */
    Call("call"),
    ;

    /**
     * The notice as a sentence about whoever it is about. Who turned the
     * requirement off is the whole point of saying it, so the name leads. Null
     * for the kinds that are a card.
     */
    fun describe(name: String): String? = when (this) {
        StrictDisabled ->
            "$name turned off the requirement to verify before messaging in this conversation."
        StrictEnabled ->
            "$name turned on the requirement to verify before messaging in this conversation."
        KeyReset -> "$name started a new encryption key for this conversation."
        BackgroundChanged -> "$name changed the chat background."
        BackgroundRemoved -> "$name removed the chat background."
        IconChanged -> "$name changed the group icon."
        IconRemoved -> "$name removed the group icon."
        Call -> null
    }

    companion object {
        /** The notice this message is, if it is one this build knows. */
        fun of(message: Message): SystemNotice? =
            message.systemNotice?.let { kind -> entries.firstOrNull { it.kind == kind } }
    }
}

/** Any message the server authored, including kinds this build cannot re-word. */
fun Message.isSystemNotice(): Boolean = systemNotice != null

/**
 * The `systemData` of a `call` notice.
 *
 * [joined] is everyone who was ever connected, not who is on it now, which is
 * what makes a missed call recognisable afterwards. While [endedAt] is null the
 * call is live and the card is a way into it.
 */
@Serializable
data class CallNotice(
    val callerId: String = "",
    val video: Boolean = false,
    val startedAt: String,
    val endedAt: String? = null,
    /** Everyone who has been connected at some point during the call. */
    val joined: List<String> = emptyList(),
    /** Whose device is still ringing. Empty once the call is over. */
    val ringing: List<String> = emptyList(),
    /** How long anyone was actually talking. Null when nobody answered. */
    val durationSec: Long? = null,
    /** Ended with nobody but the caller ever connecting. */
    val missed: Boolean = false,
) {
    val live: Boolean get() = endedAt == null
}

private val noticeJson = Json { ignoreUnknownKeys = true }

/** `systemData` read as a call, when that is what this message is. */
fun Message.callNotice(): CallNotice? {
    if (systemNotice != SystemNotice.Call.kind) return null
    val data = systemData ?: return null
    return runCatching { noticeJson.decodeFromJsonElement(CallNotice.serializer(), data) }
        .getOrNull()
}

/** "4:05" / "1:02:33" - a call length, read the way a clock is. */
fun formatCallDuration(seconds: Long): String {
    val total = seconds.coerceAtLeast(0)
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val secs = total % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, secs)
    else "%d:%02d".format(minutes, secs)
}
