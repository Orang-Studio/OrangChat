package lt.oranges.orangchat.util

/**
 * Discord-style markdown, parsed into a structure Compose can render. Port of
 * the web client's lib/markdown.tsx — same subset, same precedence:
 *   **bold**  *italic* / _italic_  __underline__  ~~strike~~  `code`
 *   ```fenced code```  > blockquote  [label](url)  bare http(s) links
 *   @username and <@userId> mentions  @everyone / @here
 *
 * Parsing is kept free of Compose types so it stays unit-testable; MessageText
 * turns these nodes into an AnnotatedString.
 */

sealed interface MdNode {
    data class Text(val text: String) : MdNode
    data class Bold(val children: List<MdNode>) : MdNode
    data class Italic(val children: List<MdNode>) : MdNode
    data class Underline(val children: List<MdNode>) : MdNode
    data class Strike(val children: List<MdNode>) : MdNode
    data class Code(val text: String) : MdNode
    data class Link(val label: List<MdNode>, val url: String) : MdNode
    /** [name] is already resolved; [isSelf] marks a mention of the viewer. */
    data class Mention(val userId: String, val name: String, val isSelf: Boolean) : MdNode
    data class Everyone(val keyword: String) : MdNode
    /** A resolved `<:name:id>`. Unresolvable ones stay Text, Discord-style. */
    data class CustomEmoji(val id: String, val name: String, val url: String, val animated: Boolean) : MdNode
}

sealed interface MdBlock {
    data class Paragraph(val children: List<MdNode>) : MdBlock
    data class Quote(val children: List<MdNode>) : MdBlock
    data class CodeBlock(val lang: String, val body: String) : MdBlock
}

/** A mentionable user, keyed by handle: the stable id plus the label to show. */
data class MentionUser(val id: String, val name: String)

/** Just enough of a custom emoji to draw it; mirrors the shared `Emoji` type. */
data class EmojiRef(
    val id: String,
    val name: String,
    val url: String,
    val animated: Boolean = false,
)

/**
 * Names for mention resolution plus the viewer, so self-mentions stand out.
 *
 * Two encodings are live at once: composers write plain `@username` so the raw
 * text stays readable, while `<@id>` predates it and still sits in older
 * messages. [names] resolves the latter, [usernames] the former.
 */
data class MentionContext(
    val names: Map<String, String> = emptyMap(),
    /** username (lowercased) -> (userId, label), for resolving `@username`. */
    val usernames: Map<String, MentionUser> = emptyMap(),
    val selfId: String? = null,
    /** emojiId -> emoji, for resolving `<:name:id>` tokens. */
    val emojis: Map<String, EmojiRef> = emptyMap(),
)

private val FENCE = Regex("```([\\w+-]*)\\n?([\\s\\S]*?)```")
private val QUOTE_LINE = Regex("^>\\s?(.*)$")

// Ordered so greedier delimiters win (*** before **, ** before *).
private val INLINE_CODE = Regex("^`([^`\\n]+)`")
private val BOLD_ITALIC = Regex("^\\*\\*\\*([\\s\\S]+?)\\*\\*\\*")
private val BOLD = Regex("^\\*\\*([\\s\\S]+?)\\*\\*")
private val UNDERLINE = Regex("^__([\\s\\S]+?)__")
private val STRIKE = Regex("^~~([\\s\\S]+?)~~")
private val ITALIC_STAR = Regex("^\\*([^*\\n]+?)\\*")
private val ITALIC_UNDER = Regex("^_([^_\\n]+?)_")
private val LINK = Regex("^\\[([^\\]\\n]+)]\\((https?://[^\\s)]+)\\)")
private val AUTOLINK = Regex("^(https?://[^\\s<]+[^\\s<.,:;\"')\\]}])")
private val CUSTOM_EMOJI = Regex("^<(a?):([a-z0-9_-]{2,32}):([a-z0-9]+)>", RegexOption.IGNORE_CASE)
private val MENTION = Regex("^<@([a-zA-Z0-9]+)>")
private val EVERYONE = Regex("^@(everyone|here)\\b")
// Dot-separated segments rather than a greedy [a-z0-9_.] run, so "@alice." at
// the end of a sentence keeps its full stop as punctuation.
private val HANDLE = Regex("^@([a-zA-Z0-9_]+(?:\\.[a-zA-Z0-9_]+)*)")

/** True when the message pings the viewer: a self mention or an @everyone/@here. */
fun mentionsSelf(content: String, ctx: MentionContext): Boolean {
    if (ctx.selfId == null) return false
    return parseMarkdown(content, ctx).any { block ->
        when (block) {
            is MdBlock.Paragraph -> nodesMentionSelf(block.children)
            is MdBlock.Quote -> nodesMentionSelf(block.children)
            is MdBlock.CodeBlock -> false
        }
    }
}

private fun nodesMentionSelf(nodes: List<MdNode>): Boolean = nodes.any { node ->
    when (node) {
        is MdNode.Mention -> node.isSelf
        is MdNode.Everyone -> true
        is MdNode.Bold -> nodesMentionSelf(node.children)
        is MdNode.Italic -> nodesMentionSelf(node.children)
        is MdNode.Underline -> nodesMentionSelf(node.children)
        is MdNode.Strike -> nodesMentionSelf(node.children)
        is MdNode.Link -> nodesMentionSelf(node.label)
        else -> false
    }
}

fun parseMarkdown(source: String, ctx: MentionContext = MentionContext()): List<MdBlock> {
    val blocks = mutableListOf<MdBlock>()
    var last = 0

    fun pushText(chunk: String) {
        // Group consecutive "> " lines into one quote block.
        var quote: MutableList<String>? = null
        val text = mutableListOf<String>()

        fun flushQuote() {
            quote?.let { blocks += MdBlock.Quote(parseInline(it.joinToString("\n"), ctx)) }
            quote = null
        }
        fun flushText() {
            if (text.isNotEmpty()) {
                blocks += MdBlock.Paragraph(parseInline(text.joinToString("\n"), ctx))
                text.clear()
            }
        }

        for (line in chunk.split("\n")) {
            val q = QUOTE_LINE.find(line)
            if (q != null) {
                flushText()
                val run = quote ?: mutableListOf<String>().also { quote = it }
                run += q.groupValues[1]
            } else {
                flushQuote()
                text += line
            }
        }
        flushQuote()
        flushText()
    }

    for (match in FENCE.findAll(source)) {
        if (match.range.first > last) pushText(source.substring(last, match.range.first))
        blocks += MdBlock.CodeBlock(
            lang = match.groupValues[1],
            body = match.groupValues[2].removeSuffix("\n"),
        )
        last = match.range.last + 1
    }
    if (last < source.length) pushText(source.substring(last))
    return blocks
}

private fun parseInline(text: String, ctx: MentionContext): List<MdNode> {
    val out = mutableListOf<MdNode>()
    val buf = StringBuilder()
    var i = 0

    fun flush() {
        if (buf.isNotEmpty()) {
            out += MdNode.Text(buf.toString())
            buf.clear()
        }
    }

    while (i < text.length) {
        val slice = text.substring(i)
        val consumed = matchInline(slice, ctx)
        if (consumed != null) {
            flush()
            out += consumed.first
            i += consumed.second
        } else {
            buf.append(text[i])
            i++
        }
    }
    flush()
    return out
}

/** Returns the node plus how many chars it ate, or null if nothing matched. */
private fun matchInline(slice: String, ctx: MentionContext): Pair<MdNode, Int>? {
    INLINE_CODE.find(slice)?.let { return MdNode.Code(it.groupValues[1]) to it.value.length }
    BOLD_ITALIC.find(slice)?.let {
        return MdNode.Bold(listOf(MdNode.Italic(parseInline(it.groupValues[1], ctx)))) to it.value.length
    }
    BOLD.find(slice)?.let { return MdNode.Bold(parseInline(it.groupValues[1], ctx)) to it.value.length }
    UNDERLINE.find(slice)?.let { return MdNode.Underline(parseInline(it.groupValues[1], ctx)) to it.value.length }
    STRIKE.find(slice)?.let { return MdNode.Strike(parseInline(it.groupValues[1], ctx)) to it.value.length }
    ITALIC_STAR.find(slice)?.let { return MdNode.Italic(parseInline(it.groupValues[1], ctx)) to it.value.length }
    ITALIC_UNDER.find(slice)?.let { return MdNode.Italic(parseInline(it.groupValues[1], ctx)) to it.value.length }
    LINK.find(slice)?.let {
        return MdNode.Link(parseInline(it.groupValues[1], ctx), it.groupValues[2]) to it.value.length
    }
    AUTOLINK.find(slice)?.let {
        val url = it.groupValues[1]
        return MdNode.Link(listOf(MdNode.Text(url)), url) to it.value.length
    }
    CUSTOM_EMOJI.find(slice)?.let {
        val name = it.groupValues[2]
        val emoji = ctx.emojis[it.groupValues[3]]
        // Deleted, or from a server the viewer is not in. Discord shows the raw
        // name rather than a broken image, and so do we.
            ?: return MdNode.Text(":$name:") to it.value.length
        return MdNode.CustomEmoji(emoji.id, emoji.name, emoji.url, emoji.animated) to it.value.length
    }
    MENTION.find(slice)?.let {
        val id = it.groupValues[1]
        return MdNode.Mention(
            userId = id,
            name = ctx.names[id] ?: "unknown",
            isSelf = ctx.selfId == id,
        ) to it.value.length
    }
    EVERYONE.find(slice)?.let { return MdNode.Everyone(it.groupValues[1]) to it.value.length }
    HANDLE.find(slice)?.let {
        // Unresolved handles are ordinary words (and the tail of every email
        // address) - leave them as literal text so nothing lights up by accident.
        val user = ctx.usernames[it.groupValues[1].lowercase()]
            ?: return MdNode.Text(it.value) to it.value.length
        return MdNode.Mention(
            userId = user.id,
            name = user.name,
            isSelf = ctx.selfId == user.id,
        ) to it.value.length
    }
    return null
}
