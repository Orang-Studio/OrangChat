package lt.oranges.orangchat.util

/**
 * Single source of truth for the custom-emoji grammar, mirroring
 * `packages/shared/src/emoji.ts`.
 *
 * A resolved emoji is a `<:name:id>` token (`<a:name:id>` when animated); a
 * hand-typed `:name:` shortcode is turned into a token on send. Rendering and
 * the send-time normalizer both tokenize through here, so a token is always
 * consumed whole and its inner `:name:` is never re-read as a shortcode and
 * re-wrapped into `<<:name:id>id>`.
 */
object EmojiTokens {
    /** name: 2-32 of `[a-z0-9_-]`; id: a run of `[a-z0-9]`. */
    private const val NAME = "[a-z0-9_-]{2,32}"
    private const val ID = "[a-z0-9]+"

    /** A complete token. Groups: 1 = animated flag, 2 = name, 3 = id. */
    const val TOKEN_SOURCE = "<(a?):($NAME):($ID)>"

    /** A bare, hand-typed shortcode. Group 1 = name. */
    const val SHORTCODE_SOURCE = ":($NAME):"

    private val TOKEN = Regex(TOKEN_SOURCE, RegexOption.IGNORE_CASE)
    private val SHORTCODE = Regex(SHORTCODE_SOURCE, RegexOption.IGNORE_CASE)

    /** Enough of an emoji to build its token. */
    data class Ref(val animated: Boolean, val name: String, val id: String)

    /** The canonical token string for an emoji. The one place tokens are built. */
    fun token(ref: Ref): String = "<${if (ref.animated) "a" else ""}:${ref.name}:${ref.id}>"

    sealed interface Segment {
        data class Text(val text: String) : Segment
        data class Emoji(val ref: Ref, val raw: String) : Segment
    }

    /** Split content into text runs and whole emoji tokens. */
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

    /**
     * Resolve typed `:name:` shortcodes to tokens, leaving existing tokens
     * untouched. `resolve` maps a lowercased name to its emoji, or null to leave
     * the shortcode as literal text (unknown names stay text).
     */
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
