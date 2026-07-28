package lt.oranges.orangchat.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
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

/** Artwork badges for a profile card. Renders nothing when there are none. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProfileBadges(badges: List<String>, modifier: Modifier = Modifier) {
    val resolved = Badge.resolve(badges)
    if (resolved.isEmpty()) return

    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        resolved.forEach { badge ->
            AsyncImage(
                model = badge.imageUrl,
                contentDescription = "${badge.label}: ${badge.description}",
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
