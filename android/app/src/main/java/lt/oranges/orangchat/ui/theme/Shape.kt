package lt.oranges.orangchat.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

object OrangRadius {
    val xs = 2.dp
    val sm = 3.dp
    val md = 4.dp
    val lg = 5.dp
    val xl = 7.dp
    val xl2 = 10.dp
    val xl3 = 14.dp
    val squircle = 8.dp
}

val OrangShapes = Shapes(
    extraSmall = RoundedCornerShape(OrangRadius.sm),
    small = RoundedCornerShape(OrangRadius.md),
    medium = RoundedCornerShape(OrangRadius.lg),
    large = RoundedCornerShape(OrangRadius.xl2),
    extraLarge = RoundedCornerShape(OrangRadius.xl3),
)
