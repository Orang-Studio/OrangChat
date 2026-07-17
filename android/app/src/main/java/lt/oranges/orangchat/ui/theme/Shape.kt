package lt.oranges.orangchat.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Tight, architectural corner scale ported from the CSS --radius-* tokens
 * (1rem = 16dp). Edges are crisp, not bubbly.
 */
object OrangRadius {
    val xs = 2.dp        // 0.125rem
    val sm = 3.dp        // 0.1875rem
    val md = 4.dp        // 0.25rem
    val lg = 5.dp        // 0.3125rem  (buttons, inputs — rounded-lg)
    val xl = 7.dp        // 0.4375rem
    val xl2 = 10.dp      // 0.625rem   (dialogs — rounded-2xl)
    val xl3 = 14.dp      // 0.875rem
    val squircle = 8.dp  // server icons — crisp rounded-square
}

val OrangShapes = Shapes(
    extraSmall = RoundedCornerShape(OrangRadius.sm),
    small = RoundedCornerShape(OrangRadius.md),
    medium = RoundedCornerShape(OrangRadius.lg),
    large = RoundedCornerShape(OrangRadius.xl2),
    extraLarge = RoundedCornerShape(OrangRadius.xl3),
)
