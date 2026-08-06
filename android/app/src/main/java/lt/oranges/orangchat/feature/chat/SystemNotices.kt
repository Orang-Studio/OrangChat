package lt.oranges.orangchat.feature.chat

/**
 * Things that happen *to* a conversation rather than in it: the verification
 * requirement going on or off, a fresh key, a new background.
 *
 * There is no system-message channel to say any of this on, so each notice
 * travels as an ordinary encrypted, signed message - as unforgeable as anything
 * else in the conversation, and readable by exactly the people who can already
 * read it. Clients then draw a recognised sentence centred rather than as a
 * bubble.
 *
 * That only works while every client agrees on the text to the character, so
 * these strings are a wire contract shared with the web client
 * (`SYSTEM_NOTICES` in `packages/shared/src/e2ee.ts`) and are append-only:
 * editing one turns every notice already sent back into a plain message.
 */
enum class SystemNotice(val text: String) {
    StrictDisabled("Turned off the requirement to verify before messaging in this conversation."),
    StrictEnabled("Turned on the requirement to verify before messaging in this conversation."),
    KeyReset("Started a new encryption key for this conversation."),
    BackgroundChanged("Changed the chat background."),
    BackgroundRemoved("Removed the chat background."),
    ;

    /**
     * The notice as a sentence about whoever sent it. Who turned the requirement
     * off is the whole point of sending that one, so the name leads.
     */
    fun describe(name: String): String = when (this) {
        StrictDisabled ->
            "$name turned off the requirement to verify before messaging in this conversation."
        StrictEnabled ->
            "$name turned on the requirement to verify before messaging in this conversation."
        KeyReset -> "$name started a new encryption key for this conversation."
        BackgroundChanged -> "$name changed the chat background."
        BackgroundRemoved -> "$name removed the chat background."
    }

    companion object {
        /**
         * Which notice a message is, if any. Nothing is authenticated here beyond
         * the text: anyone could type the same sentence, and a notice is
         * attributed to whoever sent it, so a forged one only ever says something
         * about its own author.
         */
        fun of(content: String): SystemNotice? {
            val text = content.trim()
            return entries.firstOrNull { it.text == text }
        }
    }
}
