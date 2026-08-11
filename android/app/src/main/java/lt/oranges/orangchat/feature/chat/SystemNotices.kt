package lt.oranges.orangchat.feature.chat

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import lt.oranges.orangchat.data.model.Message

enum class SystemNotice(val kind: String) {
    StrictDisabled("strictDisabled"),
    StrictEnabled("strictEnabled"),
    KeyReset("keyReset"),
    BackgroundChanged("backgroundChanged"),
    BackgroundRemoved("backgroundRemoved"),
    IconChanged("iconChanged"),
    IconRemoved("iconRemoved"),

    Call("call"),
    ;

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
        fun of(message: Message): SystemNotice? =
            message.systemNotice?.let { kind -> entries.firstOrNull { it.kind == kind } }
    }
}

fun Message.isSystemNotice(): Boolean = systemNotice != null

@Serializable
data class CallNotice(
    val callerId: String = "",
    val video: Boolean = false,
    val startedAt: String,
    val endedAt: String? = null,
    val joined: List<String> = emptyList(),
    val ringing: List<String> = emptyList(),
    val durationSec: Long? = null,
    val missed: Boolean = false,
) {
    val live: Boolean get() = endedAt == null
}

private val noticeJson = Json { ignoreUnknownKeys = true }

fun Message.callNotice(): CallNotice? {
    if (systemNotice != SystemNotice.Call.kind) return null
    val data = systemData ?: return null
    return runCatching { noticeJson.decodeFromJsonElement(CallNotice.serializer(), data) }
        .getOrNull()
}

fun formatCallDuration(seconds: Long): String {
    val total = seconds.coerceAtLeast(0)
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val secs = total % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, secs)
    else "%d:%02d".format(minutes, secs)
}
