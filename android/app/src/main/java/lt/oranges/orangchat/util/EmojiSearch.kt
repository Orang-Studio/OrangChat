package lt.oranges.orangchat.util

data class UnicodeEmoji(
    val char: String,
    /** Primary shortcode, without the surrounding colons. */
    val name: String,
    val aliases: List<String>,
    val keywords: List<String>,
)

data class ShortcodeQuery(val start: Int, val query: String)

object EmojiSearch {

    val entries: List<UnicodeEmoji> by lazy {
        EMOJI_TABLE.map { row ->
            val parts = row.split("\t")
            val aliases = parts.getOrElse(1) { "" }.split(",")
            UnicodeEmoji(
                char = parts.getOrElse(0) { "" },
                name = aliases.first(),
                aliases = aliases,
                keywords = parts.getOrElse(2) { "" }.split(" "),
            )
        }
    }

    private val byShortcode: Map<String, UnicodeEmoji> by lazy {
        buildMap {
            for (entry in this@EmojiSearch.entries) {
                for (alias in entry.aliases) put(alias, entry)
            }
        }
    }

    private val byChar: Map<String, UnicodeEmoji> by lazy { entries.associateBy { it.char } }

    /** `sunflower` -> `🌻`. */
    fun emojiFor(name: String): String? = byShortcode[name.lowercase()]?.char

    /** `🌻` -> `sunflower`, for writing the short form back into a composer. */
    fun shortcodeFor(char: String): String? =
        (byChar[char] ?: byChar[char.replace("️", "")])?.name

    private val ACTIVE = Regex("(^|\\s):([a-z0-9_+-]{2,32})$", RegexOption.IGNORE_CASE)

    /** The `:xx` fragment being typed at the caret, if any. */
    fun activeShortcode(value: String, caret: Int): ShortcodeQuery? {
        val head = value.take(caret.coerceIn(0, value.length))
        val name = ACTIVE.find(head)?.groupValues?.get(2) ?: return null
        return ShortcodeQuery(head.length - name.length - 1, name.lowercase())
    }

    /**
     * Shortcode search for the composer's `:xx` panel. Whole-name matches
     * first, then names starting with the query, then anything mentioning it.
     */
    fun search(query: String, limit: Int = 24): List<UnicodeEmoji> {
        val q = query.lowercase()
        if (q.isEmpty()) return emptyList()
        val exact = mutableListOf<UnicodeEmoji>()
        val prefix = mutableListOf<UnicodeEmoji>()
        val loose = mutableListOf<UnicodeEmoji>()
        for (entry in entries) {
            when {
                entry.aliases.any { it == q } -> exact
                entry.aliases.any { it.startsWith(q) } -> prefix
                entry.aliases.any { it.contains(q) } ||
                    entry.keywords.any { it.startsWith(q) } -> loose
                else -> null
            }?.add(entry)
            if (exact.size >= limit) break
        }
        return (exact + prefix + loose).take(limit)
    }
}
