package lt.oranges.orangchat.util

/**
 * Mentions are encoded in message content as `<@userId>` tokens, plus the
 * broadcast tokens `@everyone` / `@here`. Mirrors the web client's parsing.
 */
object Mentions {
    private val userMention = Regex("<@([a-zA-Z0-9_]+)>")

    fun mentionsUser(content: String, userId: String?): Boolean {
        if (userId == null) return false
        if (content.contains("@everyone") || content.contains("@here")) return true
        return userMention.findAll(content).any { it.groupValues[1] == userId }
    }

    /** Replace raw mention tokens with readable text for display/notifications. */
    fun render(content: String, resolveName: (String) -> String?): String {
        return userMention.replace(content) { m ->
            val name = resolveName(m.groupValues[1])
            if (name != null) "@$name" else m.value
        }
    }
}
