package lt.oranges.orangchat.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import lt.oranges.orangchat.data.model.PresenceStatus

@Composable
fun StatusIcon(
    status: PresenceStatus,
    modifier: Modifier = Modifier,
    size: Dp = 12.dp,
    mobile: Boolean = false,
    contentDescription: String? = null,
) {
    val color = statusColor(status)
    Canvas(
        modifier = modifier
            .size(size)
            .then(
                if (status == PresenceStatus.ONLINE) Modifier
                else Modifier.graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            )
            .then(
                if (contentDescription == null) Modifier
                else Modifier.semantics { this.contentDescription = contentDescription }
            ),
    ) {
        val u = this.size.minDimension / 12f
        if (mobile) {
            drawRoundRect(
                color = color,
                topLeft = Offset(3.1f * u, 0.2f * u),
                size = Size(5.8f * u, 11.6f * u),
                cornerRadius = CornerRadius(1.5f * u),
            )
            if (status != PresenceStatus.ONLINE) {
                drawRoundRect(
                    color = Color.Black,
                    topLeft = Offset(4.3f * u, 1.4f * u),
                    size = Size(3.4f * u, 9.2f * u),
                    cornerRadius = CornerRadius(0.7f * u),
                    blendMode = BlendMode.Clear,
                )
            }
            if (status == PresenceStatus.DND) {
                drawRoundRect(
                    color = color,
                    topLeft = Offset(2.1f * u, 4.9f * u),
                    size = Size(7.8f * u, 2.2f * u),
                    cornerRadius = CornerRadius(1.1f * u),
                )
            }
            return@Canvas
        }
        drawCircle(color = color, radius = 6f * u, center = Offset(6f * u, 6f * u))
        when (status) {
            PresenceStatus.ONLINE -> Unit
            PresenceStatus.IDLE -> drawCircle(
                color = Color.Black,
                radius = 4.4f * u,
                center = Offset(4.6f * u, 4.6f * u),
                blendMode = BlendMode.Clear,
            )
            PresenceStatus.DND -> drawRoundRect(
                color = Color.Black,
                topLeft = Offset(1.4f * u, 4.9f * u),
                size = Size(9.2f * u, 2.2f * u),
                cornerRadius = CornerRadius(1.1f * u),
                blendMode = BlendMode.Clear,
            )
            PresenceStatus.OFFLINE -> drawCircle(
                color = Color.Black,
                radius = 2.8f * u,
                center = Offset(6f * u, 6f * u),
                blendMode = BlendMode.Clear,
            )
        }
    }
}
