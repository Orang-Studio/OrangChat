package lt.oranges.orangchat.ui.components
import lt.oranges.orangchat.util.AppStrings
import androidx.compose.ui.platform.LocalContext
import lt.oranges.orangchat.R
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

@Composable
fun BotTag(modifier: Modifier = Modifier) {
        val context = LocalContext.current
    val c = LocalOrangColors.current
    Text(
        text = "BOT",
        color = c.primary,
        fontWeight = FontWeight.SemiBold,
        fontSize = 9.sp,
        modifier = modifier
            .semantics { contentDescription = AppStrings.get(context, R.string.catalog_bot_account_a78d875e) }
            .background(c.primary.copy(alpha = 0.15f), RoundedCornerShape(3.dp))
            .padding(horizontal = 4.dp, vertical = 1.dp),
    )
}
