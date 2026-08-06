package lt.oranges.orangchat.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import coil.compose.AsyncImage
import lt.oranges.orangchat.ui.theme.OrangTheme
import lt.oranges.orangchat.util.absoluteUrl

/**
 * Profile badge catalog, mirroring packages/shared/src/badges.ts and
 * services::badge. The slug is the contract; everything shown here is local, so
 * a renamed label never needs a server change. Each badge is a piece of artwork
 * served from the web app's /badges/ directory.
 */
enum class Badge(
    val slug: String,
    val label: String,
    val description: String,
) {
    BETA("beta", "Beta", "Here since the beta."),
    FOUNDER("founder", "Founder", "Was here at the very beginning."),
    DEVELOPER("developer", "Developer", "Builds and maintains OrangChat."),
    BUGHUNTER("bughunter", "Bug Hunter", "Tracked down bugs in OrangChat."),
    CONTRIBUTOR("contributor", "Contributor", "Contributed to OrangChat."),
    BONFIRE("bonfire", "Bonfire", "Was there for the bonfire."),
    BOT("bot", "Bot", "An automated account.");

    /** Artwork URL, served from the web app's static assets. */
    val imageUrl: String? get() = absoluteUrl("/badges/$slug.png")

    companion object {
        /** Drops unknown slugs and returns the rest in catalog order. */
        fun resolve(slugs: List<String>): List<Badge> =
            entries.filter { it.slug in slugs }
    }
}

private class BadgePopupPositionProvider(private val gapPx: Int) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val centeredX = anchorBounds.left + (anchorBounds.width - popupContentSize.width) / 2
        val x = centeredX.coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
        val above = anchorBounds.top - popupContentSize.height - gapPx
        val y = if (above >= 0) above else anchorBounds.bottom + gapPx
        return IntOffset(x, y)
    }
}

/**
 * Artwork badges for a profile card. Tapping one shows its name above it;
 * tapping it again, tapping outside, or pressing Back dismisses the label.
 * Renders nothing when there are no known badges.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProfileBadges(badges: List<String>, modifier: Modifier = Modifier) {
    val resolved = Badge.resolve(badges)
    if (resolved.isEmpty()) return
    val c = OrangTheme.colors
    var selectedBadge by remember(resolved) { mutableStateOf<Badge?>(null) }
    val gapPx = with(LocalDensity.current) { 6.dp.roundToPx() }
    val popupPosition = remember(gapPx) { BadgePopupPositionProvider(gapPx) }

    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        resolved.forEach { badge ->
            Box {
                AsyncImage(
                    model = badge.imageUrl,
                    contentDescription = "${badge.label}: ${badge.description}",
                    modifier = Modifier
                        .size(20.dp)
                        .clickable(role = Role.Button) {
                            selectedBadge = badge.takeUnless { selectedBadge == badge }
                        },
                )

                if (selectedBadge == badge) {
                    Popup(
                        popupPositionProvider = popupPosition,
                        onDismissRequest = { selectedBadge = null },
                        properties = PopupProperties(
                            focusable = true,
                            dismissOnBackPress = true,
                            dismissOnClickOutside = true,
                        ),
                    ) {
                        val shape = RoundedCornerShape(8.dp)
                        Box(
                            modifier = Modifier
                                .background(c.surface4, shape)
                                .border(1.dp, c.border, shape)
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                        ) {
                            Text(text = badge.label, color = c.ink, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}
