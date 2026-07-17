package lt.oranges.orangchat.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import coil.compose.AsyncImage
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import lt.oranges.orangchat.ui.theme.OrangRadius
import lt.oranges.orangchat.ui.theme.OrangTheme
import lt.oranges.orangchat.util.EmojiRef
import lt.oranges.orangchat.util.MdBlock
import lt.oranges.orangchat.util.MdNode
import lt.oranges.orangchat.util.MentionContext
import lt.oranges.orangchat.util.absoluteUrl
import lt.oranges.orangchat.util.parseMarkdown

/**
 * Renders Discord-subset markdown. Compose counterpart of the web client's
 * <RichText>: same parser grammar (util/Markdown.kt), styled with theme tokens.
 */
@Composable
fun MessageText(
    content: String,
    modifier: Modifier = Modifier,
    mentionNames: Map<String, String> = emptyMap(),
    selfId: String? = null,
    emojis: Map<String, EmojiRef> = emptyMap(),
    fontSize: androidx.compose.ui.unit.TextUnit = 14.sp,
) {
    val c = OrangTheme.colors
    val blocks = if (isDirectMediaMessage(content)) emptyList()
    else parseMarkdown(content, MentionContext(mentionNames, selfId, emojis))

    val inlineContent = rememberEmojiInlineContent(blocks, fontSize)

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        blocks.forEach { block ->
            when (block) {
                is MdBlock.CodeBlock -> Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(c.surface1, RoundedCornerShape(OrangRadius.md))
                        .padding(10.dp),
                ) {
                    // Long lines scroll rather than wrap, as in the web <pre>.
                    Text(
                        text = block.body,
                        color = c.ink,
                        fontFamily = FontFamily.Monospace,
                        fontSize = fontSize * 0.85f,
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                    )
                }

                is MdBlock.Quote -> Row(modifier = Modifier.fillMaxWidth()) {
                    Box(
                        modifier = Modifier
                            .width(3.dp)
                            .background(c.borderStrong, RoundedCornerShape(OrangRadius.xs)),
                    ) { Text("") }
                    Text(
                        text = block.children.toAnnotated(c, fontSize),
                        color = c.inkSecondary,
                        fontSize = fontSize,
                        inlineContent = inlineContent,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }

                is MdBlock.Paragraph -> Text(
                    text = block.children.toAnnotated(c, fontSize),
                    color = c.ink,
                    fontSize = fontSize,
                    inlineContent = inlineContent,
                )
            }
        }
        LinkEmbeds(content = content)
    }
}

private const val EMOJI_INLINE_PREFIX = "emoji:"

/**
 * Sized in sp rather than dp so custom emoji track the accessibility font-scale
 * preference along with the text they sit in.
 */
private fun emojiSize(fontSize: androidx.compose.ui.unit.TextUnit) = fontSize * 2.3f

private fun collectEmojis(blocks: List<MdBlock>): List<MdNode.CustomEmoji> {
    val out = mutableListOf<MdNode.CustomEmoji>()
    fun walk(nodes: List<MdNode>) {
        nodes.forEach { node ->
            when (node) {
                is MdNode.CustomEmoji -> out += node
                is MdNode.Bold -> walk(node.children)
                is MdNode.Italic -> walk(node.children)
                is MdNode.Underline -> walk(node.children)
                is MdNode.Strike -> walk(node.children)
                is MdNode.Link -> walk(node.label)
                else -> Unit
            }
        }
    }
    blocks.forEach { block ->
        when (block) {
            is MdBlock.Paragraph -> walk(block.children)
            is MdBlock.Quote -> walk(block.children)
            is MdBlock.CodeBlock -> Unit
        }
    }
    return out
}

@Composable
private fun rememberEmojiInlineContent(
    blocks: List<MdBlock>,
    fontSize: androidx.compose.ui.unit.TextUnit,
): Map<String, InlineTextContent> {
    val found = collectEmojis(blocks)
    if (found.isEmpty()) return emptyMap()
    val size = emojiSize(fontSize)
    return found.distinctBy { it.id }.associate { emoji ->
        EMOJI_INLINE_PREFIX + emoji.id to InlineTextContent(
            placeholder = Placeholder(
                width = size,
                height = size,
                placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter,
            ),
        ) {
            AsyncImage(
                model = absoluteUrl(emoji.url),
                contentDescription = ":${emoji.name}:",
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

private fun List<MdNode>.toAnnotated(
    c: lt.oranges.orangchat.ui.theme.OrangColors,
    fontSize: androidx.compose.ui.unit.TextUnit,
): AnnotatedString = buildAnnotatedString { appendNodes(this@toAnnotated, c, fontSize) }

private fun androidx.compose.ui.text.AnnotatedString.Builder.appendNodes(
    nodes: List<MdNode>,
    c: lt.oranges.orangchat.ui.theme.OrangColors,
    fontSize: androidx.compose.ui.unit.TextUnit,
) {
    nodes.forEach { node ->
        when (node) {
            is MdNode.Text -> append(node.text)

            is MdNode.Bold -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                appendNodes(node.children, c, fontSize)
            }

            is MdNode.Italic -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                appendNodes(node.children, c, fontSize)
            }

            is MdNode.Underline -> withStyle(SpanStyle(textDecoration = TextDecoration.Underline)) {
                appendNodes(node.children, c, fontSize)
            }

            is MdNode.Strike -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
                appendNodes(node.children, c, fontSize)
            }

            is MdNode.Code -> withStyle(
                SpanStyle(
                    fontFamily = FontFamily.Monospace,
                    background = c.surface1,
                    fontSize = fontSize * 0.85f,
                ),
            ) { append(node.text) }

            is MdNode.Link -> withLink(
                LinkAnnotation.Url(
                    url = node.url,
                    styles = TextLinkStyles(
                        style = SpanStyle(color = c.primary, textDecoration = TextDecoration.Underline),
                    ),
                ),
            ) { appendNodes(node.label, c, fontSize) }

            is MdNode.Mention -> withStyle(
                SpanStyle(
                    color = c.primary,
                    background = if (node.isSelf) c.primary.copy(alpha = 0.30f) else c.primarySoft,
                    fontWeight = FontWeight.Medium,
                ),
            ) { append("@${node.name}") }

            // @everyone always reads as aimed at you, so it uses the self style.
            is MdNode.Everyone -> withStyle(
                SpanStyle(
                    color = c.primary,
                    background = c.primary.copy(alpha = 0.30f),
                    fontWeight = FontWeight.Medium,
                ),
            ) { append("@${node.keyword}") }

            // The alternate text is what copy and TalkBack get.
            is MdNode.CustomEmoji -> appendInlineContent(
                EMOJI_INLINE_PREFIX + node.id,
                ":${node.name}:",
            )
        }
    }
}
