package lt.oranges.orangchat.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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

private const val MORPH_MS = 180

private fun targetCircleCx(status: PresenceStatus) = if (status == PresenceStatus.IDLE) 4.6f else 6f
private fun targetCircleCy(status: PresenceStatus) = if (status == PresenceStatus.IDLE) 4.6f else 6f
private fun targetCircleR(status: PresenceStatus) = when (status) {
    PresenceStatus.IDLE -> 4.4f
    PresenceStatus.OFFLINE -> 2.8f
    else -> 0f
}

private fun targetHoleHeight(status: PresenceStatus) = if (status == PresenceStatus.ONLINE) 0f else 9.2f
private fun targetHoleY(status: PresenceStatus) = if (status == PresenceStatus.ONLINE) 6f else 1.4f

@Composable
fun StatusIcon(
    status: PresenceStatus,
    modifier: Modifier = Modifier,
    size: Dp = 12.dp,
    mobile: Boolean = false,
    contentDescription: String? = null,
) {
    val color = statusColor(status)
    val animSpec = tween<Float>(durationMillis = MORPH_MS)
    val circleCx by animateFloatAsState(targetCircleCx(status), animSpec)
    val circleCy by animateFloatAsState(targetCircleCy(status), animSpec)
    val circleR by animateFloatAsState(targetCircleR(status), animSpec)
    val barAlpha by animateFloatAsState(if (status == PresenceStatus.DND) 1f else 0f, animSpec)
    val holeHeight by animateFloatAsState(targetHoleHeight(status), animSpec)
    val holeY by animateFloatAsState(targetHoleY(status), animSpec)

    Canvas(
        modifier = modifier
            .size(size)
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
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
            drawRoundRect(
                color = Color.Black,
                topLeft = Offset(4.3f * u, holeY * u),
                size = Size(3.4f * u, holeHeight * u),
                cornerRadius = CornerRadius(0.7f * u),
                blendMode = BlendMode.Clear,
            )
            drawRoundRect(
                color = Color.Black.copy(alpha = barAlpha),
                topLeft = Offset(2.1f * u, 4.9f * u),
                size = Size(7.8f * u, 2.2f * u),
                cornerRadius = CornerRadius(1.1f * u),
                blendMode = BlendMode.DstOut,
            )
            return@Canvas
        }
        drawCircle(color = color, radius = 6f * u, center = Offset(6f * u, 6f * u))
        drawCircle(
            color = Color.Black,
            radius = circleR * u,
            center = Offset(circleCx * u, circleCy * u),
            blendMode = BlendMode.Clear,
        )
        drawRoundRect(
            color = Color.Black.copy(alpha = barAlpha),
            topLeft = Offset(1.4f * u, 4.9f * u),
            size = Size(9.2f * u, 2.2f * u),
            cornerRadius = CornerRadius(1.1f * u),
            blendMode = BlendMode.DstOut,
        )
    }
}
