package lt.oranges.orangchat.util

object Mentions {
    private val userMention = Regex("<@([a-zA-Z0-9_]+)>")

    private val handleMention = Regex("(^|[^a-zA-Z0-9])@([a-zA-Z0-9_]+(?:\\.[a-zA-Z0-9_]+)*)")

    fun mentionsUser(content: String, userId: String?, username: String? = null): Boolean {
        if (userId == null) return false
        if (content.contains("@everyone") || content.contains("@here")) return true
        if (userMention.findAll(content).any { it.groupValues[1] == userId }) return true
        if (username == null) return false
        return handleMention.findAll(content)
            .any { it.groupValues[2].equals(username, ignoreCase = true) }
    }

    fun render(content: String, resolveName: (String) -> String?): String {
        return userMention.replace(content) { m ->
            val name = resolveName(m.groupValues[1])
            if (name != null) "@$name" else m.value
        }
    }
}
