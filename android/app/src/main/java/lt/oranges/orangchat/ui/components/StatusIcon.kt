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

/**
 * Port of components/StatusIcon.tsx. Presence as a shape, not just a colour: a
 * full disc for online, a crescent for idle, a barred disc for do-not-disturb,
 * a hollow ring for offline. At 12dp the green dot and the amber dot are the
 * same dot for anyone with a red/green deficiency.
 *
 * The cut-outs are punched out with [BlendMode.Clear] against an offscreen
 * layer rather than painted in the backdrop colour, so whatever sits behind the
 * badge shows through them on any surface.
 */
@Composable
fun StatusIcon(
    status: PresenceStatus,
    modifier: Modifier = Modifier,
    size: Dp = 12.dp,
    contentDescription: String? = null,
) {
    val color = statusColor(status)
    Canvas(
        modifier = modifier
            .size(size)
            // Online is a plain disc; every avatar in a long list paying for an
            // offscreen layer to punch a hole it doesn't have is not worth it.
            .then(
                if (status == PresenceStatus.ONLINE) Modifier
                else Modifier.graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            )
            .then(
                if (contentDescription == null) Modifier
                else Modifier.semantics { this.contentDescription = contentDescription }
            ),
    ) {
        // The geometry below is expressed against the same 12-unit viewBox the
        // web component uses, so the two stay pixel-identical as they change.
        val u = this.size.minDimension / 12f
        drawCircle(color = color, radius = 6f * u, center = Offset(6f * u, 6f * u))
        when (status) {
            PresenceStatus.ONLINE -> Unit
            PresenceStatus.IDLE -> drawCircle(
                color = Color.Black,
                radius = 5.2f * u,
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
