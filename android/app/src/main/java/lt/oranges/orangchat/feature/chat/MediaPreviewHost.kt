package lt.oranges.orangchat.feature.chat

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import lt.oranges.orangchat.data.model.Message
import lt.oranges.orangchat.util.EmojiRef

object MediaPreviewHost {
    private val _messages = MutableStateFlow<List<Message>>(emptyList())

    val messages: StateFlow<List<Message>> = _messages.asStateFlow()

    private val _emojis = MutableStateFlow<Map<String, EmojiRef>>(emptyMap())

    val emojis: StateFlow<Map<String, EmojiRef>> = _emojis.asStateFlow()

    private val _emojiGroups = MutableStateFlow<List<CustomEmojiGroup>>(emptyList())

    val emojiGroups: StateFlow<List<CustomEmojiGroup>> = _emojiGroups.asStateFlow()

    private var react: ((Message, String) -> Unit)? = null

    fun bind(
        messages: List<Message>,
        emojis: Map<String, EmojiRef>,
        emojiGroups: List<CustomEmojiGroup>,
        onReact: (Message, String) -> Unit,
    ) {
        _messages.value = messages
        _emojis.value = emojis
        _emojiGroups.value = emojiGroups
        react = onReact
    }

    fun unbind() {
        _messages.value = emptyList()
        _emojis.value = emptyMap()
        _emojiGroups.value = emptyList()
        react = null
    }

    fun messageFor(messages: List<Message>, attachmentId: String): Message? =
        messages.firstOrNull { message -> message.attachments.any { it.id == attachmentId } }

    fun react(message: Message, emoji: String) {
        react?.invoke(message, emoji)
    }
}
