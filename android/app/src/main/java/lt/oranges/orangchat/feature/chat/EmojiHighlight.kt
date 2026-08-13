package lt.oranges.orangchat.feature.chat

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import lt.oranges.orangchat.util.EmojiRef
import lt.oranges.orangchat.util.EmojiSearch
import lt.oranges.orangchat.util.EmojiTokens

/**
 * The draft with every emoji token and known shortcode picked out. Drawn behind
 * a transparent text field, so it has to lay out with the same style.
 */
fun highlightEmoji(content: String, color: Color, custom: List<EmojiRef>): AnnotatedString {
    val names = custom.mapTo(mutableSetOf()) { it.name.lowercase() }
    val spans = EmojiTokens.spans(content) { it in names || EmojiSearch.emojiFor(it) != null }
    if (spans.isEmpty()) return AnnotatedString(content)
    return buildAnnotatedString {
        append(content)
        val style = SpanStyle(color = color, fontWeight = FontWeight.SemiBold)
        for (span in spans) addStyle(style, span.first, span.last + 1)
    }
}
