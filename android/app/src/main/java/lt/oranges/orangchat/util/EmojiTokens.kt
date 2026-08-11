package lt.oranges.orangchat.util

object EmojiTokens {
    private const val NAME = "[a-z0-9_-]{2,32}"
    private const val ID = "[a-z0-9]+"

    const val TOKEN_SOURCE = "<(a?):($NAME):($ID)>"

    const val SHORTCODE_SOURCE = ":($NAME):"

    private val TOKEN = Regex(TOKEN_SOURCE, RegexOption.IGNORE_CASE)
    private val SHORTCODE = Regex(SHORTCODE_SOURCE, RegexOption.IGNORE_CASE)

    data class Ref(val animated: Boolean, val name: String, val id: String)

    fun token(ref: Ref): String = "<${if (ref.animated) "a" else ""}:${ref.name}:${ref.id}>"

    sealed interface Segment {
        data class Text(val text: String) : Segment
        data class Emoji(val ref: Ref, val raw: String) : Segment
    }

    fun tokenize(content: String): List<Segment> {
        val out = mutableListOf<Segment>()
        var last = 0
        for (match in TOKEN.findAll(content)) {
            if (match.range.first > last) {
                out.add(Segment.Text(content.substring(last, match.range.first)))
            }
            out.add(
                Segment.Emoji(
                    Ref(
                        animated = match.groupValues[1].equals("a", ignoreCase = true),
                        name = match.groupValues[2],
                        id = match.groupValues[3],
                    ),
                    raw = match.value,
                ),
            )
            last = match.range.last + 1
        }
        if (last < content.length) out.add(Segment.Text(content.substring(last)))
        return out
    }

    fun resolveShortcodes(content: String, resolve: (String) -> Ref?): String =
        tokenize(content).joinToString("") { segment ->
            when (segment) {
                is Segment.Emoji -> segment.raw
                is Segment.Text -> SHORTCODE.replace(segment.text) { match ->
                    resolve(match.groupValues[1].lowercase())?.let(::token) ?: match.value
                }
            }
        }
}
