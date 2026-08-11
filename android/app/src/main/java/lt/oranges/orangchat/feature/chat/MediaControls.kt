package lt.oranges.orangchat.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

internal fun formatDuration(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0L)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

@Composable
internal fun Scrubber(
    positionMs: Long,
    durationMs: Long,
    enabled: Boolean,
    onSeek: (Long) -> Unit,
    accent: Color,
    track: Color,
    modifier: Modifier = Modifier,
    onScrubChange: (Long?) -> Unit = {},
) {
    var widthPx by remember { mutableIntStateOf(0) }
    var scrubFraction by remember { mutableStateOf<Float?>(null) }
    val thumb = 12.dp
    val thumbPx = with(LocalDensity.current) { thumb.toPx() }

    fun fractionAt(x: Float): Float {
        val usable = (widthPx - thumbPx).coerceAtLeast(1f)
        return ((x - thumbPx / 2f) / usable).coerceIn(0f, 1f)
    }

    val played = scrubFraction
        ?: if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(24.dp)
            .onSizeChanged { widthPx = it.width }
            .pointerInput(enabled, durationMs, widthPx) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    if (enabled) {
                        val f = fractionAt(down.position.x)
                        scrubFraction = f
                        onScrubChange((f * durationMs).toLong())
                    }
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        change.consume()
                        if (!change.pressed) {
                            if (enabled) onSeek((fractionAt(change.position.x) * durationMs).toLong())
                            scrubFraction = null
                            onScrubChange(null)
                            break
                        }
                        if (enabled) {
                            val f = fractionAt(change.position.x)
                            scrubFraction = f
                            onScrubChange((f * durationMs).toLong())
                        }
                    }
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(4.dp)
                .background(track, RoundedCornerShape(2.dp)),
        )
        Box(
            Modifier
                .fillMaxWidth(played)
                .height(4.dp)
                .background(accent, RoundedCornerShape(2.dp)),
        )
        if (enabled) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(),
                contentAlignment = Alignment.CenterStart,
            ) {
                Box(
                    Modifier
                        .padding(
                            start = with(LocalDensity.current) {
                                (played * (widthPx - thumbPx)).coerceAtLeast(0f).toDp()
                            },
                        )
                        .size(thumb)
                        .background(accent, CircleShape),
                )
            }
        }
    }
}

internal fun Modifier.tapToToggle(onTap: () -> Unit): Modifier = pointerInput(onTap) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        down.consume()
        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            change.consume()
            if (!change.pressed) {
                val p = change.position
                val inside = p.x >= 0 && p.y >= 0 && p.x <= size.width && p.y <= size.height
                if (inside) onTap()
                break
            }
        }
    }
}

@Composable
internal fun OverlayIconButton(
    onClick: () -> Unit,
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    size: Dp = 36.dp,
    iconSize: Dp = 20.dp,
) {
    Box(
        modifier = modifier
            .padding(4.dp)
            .size(size)
            .background(Color.Black.copy(alpha = 0.55f), CircleShape)
            .tapToToggle(onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = label, tint = Color.White, modifier = Modifier.size(iconSize))
    }
}
