package lt.oranges.orangchat.feature.chat

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import lt.oranges.orangchat.data.model.Message

/**
 * What the open chat knows, made reachable from [MediaPreviewActivity].
 *
 * The preview is a separate activity and is handed one attachment, which is
 * enough to show the bytes and nothing else - not who sent them, not what they
 * said with them, not the reactions. Rather than serialising a message snapshot
 * into the intent (which would be frozen the moment it was sent, so a reaction
 * made from the viewer would never appear there), the chat pane leaves its list
 * here while it is on screen and the viewer looks the message up by attachment.
 *
 * The pane stays composed underneath - a stopped activity keeps its composition
 * - so the flow keeps updating and the bar reacts along with the row behind it.
 */
object MediaPreviewHost {
    private val _messages = MutableStateFlow<List<Message>>(emptyList())

    /** The messages currently rendered by the chat behind the viewer. */
    val messages: StateFlow<List<Message>> = _messages.asStateFlow()

    private var react: ((Message, String) -> Unit)? = null

    /** Called by the chat pane as its list changes. */
    fun bind(messages: List<Message>, onReact: (Message, String) -> Unit) {
        _messages.value = messages
        react = onReact
    }

    /** Dropped when the pane leaves the screen, so nothing outlives it. */
    fun unbind() {
        _messages.value = emptyList()
        react = null
    }

    /** The message a full-screen file arrived on, or null if it is not from one. */
    fun messageFor(messages: List<Message>, attachmentId: String): Message? =
        messages.firstOrNull { message -> message.attachments.any { it.id == attachmentId } }

    /** Toggle a reaction on a message shown in the viewer. */
    fun react(message: Message, emoji: String) {
        react?.invoke(message, emoji)
    }
}
