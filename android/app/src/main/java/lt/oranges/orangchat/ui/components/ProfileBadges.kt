package lt.oranges.orangchat.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Profile badge catalog, mirroring packages/shared/src/badges.ts and
 * services::badge. The slug is the contract; everything shown here is local, so
 * a renamed label never needs a server change.
 */
enum class Badge(
    val slug: String,
    val label: String,
    val description: String,
    val color: Color,
    val icon: ImageVector,
) {
    EARLY_DEVELOPER(
        "early_developer",
        "Early Developer",
        "Built on OrangChat before it opened up.",
        Color(0xFFFF6A1A),
        Icons.Default.Code,
    ),
    EARLY_MEMBER(
        "early_member",
        "Early Member",
        "Joined OrangChat in its first days.",
        Color(0xFF5B8DEF),
        Icons.Default.AutoAwesome,
    ),
    BONFIRE(
        "bonfire",
        "Bonfire",
        "Was there for the bonfire.",
        Color(0xFFE2574C),
        Icons.Default.LocalFireDepartment,
    );

    companion object {
        /** Drops unknown slugs and returns the rest in catalog order. */
        fun resolve(slugs: List<String>): List<Badge> =
            entries.filter { it.slug in slugs }
    }
}

/** Badge pills for a profile card. Renders nothing when there are none. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProfileBadges(badges: List<String>, modifier: Modifier = Modifier) {
    val resolved = Badge.resolve(badges)
    if (resolved.isEmpty()) return

    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        resolved.forEach { badge ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .background(badge.color.copy(alpha = 0.12f), RoundedCornerShape(6.dp))
                    .border(1.dp, badge.color.copy(alpha = 0.35f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            ) {
                Icon(
                    imageVector = badge.icon,
                    contentDescription = null,
                    tint = badge.color,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = badge.label,
                    color = badge.color,
                    fontWeight = FontWeight.Medium,
                    fontSize = 11.sp,
                )
            }
        }
    }
}
