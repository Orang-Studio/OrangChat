package lt.oranges.orangchat.feature.chat

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import lt.oranges.orangchat.data.model.Message

object MediaPreviewHost {
    private val _messages = MutableStateFlow<List<Message>>(emptyList())

    val messages: StateFlow<List<Message>> = _messages.asStateFlow()

    private var react: ((Message, String) -> Unit)? = null

    fun bind(messages: List<Message>, onReact: (Message, String) -> Unit) {
        _messages.value = messages
        react = onReact
    }

    fun unbind() {
        _messages.value = emptyList()
        react = null
    }

    fun messageFor(messages: List<Message>, attachmentId: String): Message? =
        messages.firstOrNull { message -> message.attachments.any { it.id == attachmentId } }

    fun react(message: Message, emoji: String) {
        react?.invoke(message, emoji)
    }
}
