package lt.oranges.orangchat.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import lt.oranges.orangchat.ui.theme.LocalOrangColors

/**
 * The `BOT` label shown beside an automated account's name.
 *
 * A drawn label rather than text appended to the display name: the flag comes
 * from the account itself, so nobody can put "BOT" in a nickname and pass as
 * one. The content description spells it out, since the visible text is an
 * all-caps abbreviation a screen reader would otherwise read letter by letter.
 */
@Composable
fun BotTag(modifier: Modifier = Modifier) {
    val c = LocalOrangColors.current
    Text(
        text = "BOT",
        color = c.primary,
        fontWeight = FontWeight.SemiBold,
        fontSize = 9.sp,
        modifier = modifier
            .semantics { contentDescription = "Bot account" }
            .background(c.primary.copy(alpha = 0.15f), RoundedCornerShape(3.dp))
            .padding(horizontal = 4.dp, vertical = 1.dp),
    )
}
