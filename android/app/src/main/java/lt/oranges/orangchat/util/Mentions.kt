package lt.oranges.orangchat.util

/**
 * Mentions are encoded in message content either as a plain `@username` handle
 * or as a legacy `<@userId>` token, plus the broadcast tokens `@everyone` /
 * `@here`. Composers write handles so the raw text stays readable; the id form
 * predates that and still sits in older messages, so both are understood here.
 * Mirrors the web client's parsing.
 */
object Mentions {
    private val userMention = Regex("<@([a-zA-Z0-9_]+)>")

    // Dot-separated segments rather than a greedy [a-z0-9_.] run, so "@alice."
    // at the end of a sentence keeps its full stop as punctuation. The leading
    // guard keeps the host of an email address from reading as a handle.
    private val handleMention = Regex("(^|[^a-zA-Z0-9])@([a-zA-Z0-9_]+(?:\\.[a-zA-Z0-9_]+)*)")

    fun mentionsUser(content: String, userId: String?, username: String? = null): Boolean {
        if (userId == null) return false
        if (content.contains("@everyone") || content.contains("@here")) return true
        if (userMention.findAll(content).any { it.groupValues[1] == userId }) return true
        if (username == null) return false
        return handleMention.findAll(content)
            .any { it.groupValues[2].equals(username, ignoreCase = true) }
    }

    /**
     * Replace raw mention tokens with readable text for display/notifications.
     * Handles are already readable, so only the id form needs rewriting; an
     * unresolvable id is left verbatim rather than shown as a broken token.
     */
    fun render(content: String, resolveName: (String) -> String?): String {
        return userMention.replace(content) { m ->
            val name = resolveName(m.groupValues[1])
            if (name != null) "@$name" else m.value
        }
    }
}
