/*
 * Copyright 2026 Nadeem Iqbal
 * Copyright 2026 OrangChat
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Adapted from github.com/NadeemIqbal/voice-message @ 671085b (v0.3.1).
 * See VoicePhase.kt for why this is vendored rather than depended on.
 *
 * Local changes: OrangChat design tokens instead of MaterialTheme, the app's own
 * Material icons instead of upstream's hand-drawn glyphs, a lock target that
 * fills as the finger rises, and a strip that slides with the cancel gesture.
 */
package lt.oranges.orangchat.feature.chat.voicemessage

import androidx.compose.ui.platform.LocalContext
import lt.oranges.orangchat.R
import lt.oranges.orangchat.util.AppStrings
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import lt.oranges.orangchat.ui.components.Text
import lt.oranges.orangchat.ui.theme.OrangTheme
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

object VoiceRecorderDefaults {
    /** Most bars the live strip will draw; narrow strips simply fit fewer. */
    const val BarCount: Int = 48
    val BarWidth: Dp = 3.dp
    val BarSpacing: Dp = 2.dp
    val BarMinHeight: Dp = 3.dp

    /**
     * Upward travel that locks the recording. Short, because it is a flick with
     * a thumb that is already committed to the gesture.
     */
    val LockThreshold: Dp = 64.dp

    /**
     * Leftward travel that arms cancel. Longer than the lock: throwing the clip
     * away should take a deliberate move, and there is a whole composer's width
     * to do it in.
     */
    val CancelThreshold: Dp = 88.dp

    /**
     * How long the mic has to be held before recording actually starts. Well
     * under the platform long-press timeout, which at 500ms makes hold-to-talk
     * feel broken, but long enough that a stray tap never opens the microphone.
     */
    val PressToStart: Duration = 200.milliseconds

    /** Releases shorter than this are a tap, not a hold, and are inaudible anyway. */
    val MinDuration: Duration = 600.milliseconds

    /** Upper bound on one clip; it finishes and sends itself at the cap. */
    val MaxDuration: Duration = 10.minutes
}

data class VoiceRecorderColors(
    val mic: Color,
    val micActive: Color,
    val waveform: Color,
    val timer: Color,
    val hint: Color,
    val danger: Color,
    val lock: Color,
    val lockTrack: Color,
    val onAccent: Color,
)

@Composable
fun orangVoiceRecorderColors(): VoiceRecorderColors {
    val c = OrangTheme.colors
    return VoiceRecorderColors(
        mic = c.inkMuted,
        micActive = c.primary,
        waveform = c.primary,
        timer = c.ink,
        hint = c.inkMuted,
        danger = c.danger,
        lock = c.primary,
        lockTrack = c.surface4,
        onAccent = c.inkOnPrimary,
    )
}

/** Rendered length of the clip so far: `m:ss`. */
internal fun formatVoiceDuration(duration: Duration): String {
    val totalSeconds = duration.inWholeSeconds.coerceAtLeast(0)
    return "${totalSeconds / 60}:${(totalSeconds % 60).toString().padStart(2, '0')}"
}

/**
 * The strip that replaces the text field while recording: a pulsing dot, the
 * running time, the live waveform, and whichever hint applies.
 *
 * The whole thing slides left with the cancel gesture and bleeds toward danger
 * as the threshold approaches, so the swipe shows its own progress instead of
 * silently snapping at the end.
 */
@Composable
fun VoiceRecordingStrip(
    state: VoiceRecorderState,
    modifier: Modifier = Modifier,
    colors: VoiceRecorderColors = orangVoiceRecorderColors(),
) {
        val context = LocalContext.current
    val cancelling = state.phase == VoicePhase.Cancelling
    val locked = state.phase == VoicePhase.RecordingLocked
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl

    val pulse by rememberInfiniteTransition(label = "voice-dot").animateFloat(
        initialValue = 1f,
        targetValue = 0.25f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 700), RepeatMode.Reverse),
        label = "voice-dot-alpha",
    )
    // Tracks the finger while held; springs back to rest once the gesture ends.
    val slide by animateFloatAsState(
        targetValue = if (locked) 0f else state.cancelProgress,
        animationSpec = tween(durationMillis = if (locked) 180 else 60),
        label = "voice-cancel-slide",
    )
    val danger = if (cancelling) 1f else slide
    val accent = lerp(colors.waveform, colors.danger, danger)
    val hintColor = lerp(colors.hint, colors.danger, danger)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .offset { IntOffset(x = (-slide * 24.dp.toPx() * if (isRtl) -1f else 1f).roundToInt(), y = 0) },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .alpha(pulse)
                .background(colors.danger, CircleShape),
        )
        Text(
            formatVoiceDuration(state.elapsed),
            color = colors.timer,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier
                .width(44.dp)
                .semantics { liveRegion = LiveRegionMode.Polite },
        )
        VoiceWaveform(
            samples = state.capturedSamples,
            barColor = accent,
            modifier = Modifier
                .weight(1f)
                .height(24.dp),
            live = true,
        )
        if (locked) {
            Text("Locked", color = colors.hint, fontSize = 12.sp)
        } else {
            Text(
                text = when {
                    cancelling -> AppStrings.get(context, R.string.catalog_release_to_cancel_29bd6c29)
                    isRtl -> AppStrings.get(context, R.string.catalog_slide_to_cancel_8f64a698)
                    else -> AppStrings.get(context, R.string.catalog_slide_to_cancel_9c411f6d)
                },
                color = hintColor,
                fontSize = 12.sp,
                fontWeight = if (cancelling) FontWeight.SemiBold else FontWeight.Normal,
            )
        }
    }
}

/**
 * The hold-to-record mic.
 *
 * Two things here are load-bearing and easy to undo by accident. First, this
 * composable has to keep one stable slot across idle, held and cancelling: if it
 * moved between `when` branches Compose would dispose and re-mount it mid-press,
 * tearing down the pointer loop and resolving the gesture as a too-short tap one
 * frame after it started. Second, the drag is accumulated from
 * [positionChange] deltas rather than read off `change.position`, which is
 * node-local and therefore lies the moment anything in the row changes width.
 *
 * @param canStart consulted once the press has been held long enough, immediately
 *   before the mic would open. Returning false abandons the press silently, which
 *   is what a host that just put a permission dialog on screen wants.
 * @param onTapTooShort the press ended before it became a hold. Distinct from a
 *   blocked start: here the user meant to do something and nothing happened.
 */
@Composable
fun VoiceMicButton(
    state: VoiceRecorderState,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: VoiceRecorderColors = orangVoiceRecorderColors(),
    lockThreshold: Dp = VoiceRecorderDefaults.LockThreshold,
    cancelThreshold: Dp = VoiceRecorderDefaults.CancelThreshold,
    canStart: () -> Boolean = { true },
    onTapTooShort: () -> Unit = {},
) {
        val context = LocalContext.current
    val density = LocalDensity.current
    val lockThresholdPx = with(density) { lockThreshold.toPx() }
    val cancelThresholdPx = with(density) { cancelThreshold.toPx() }
    val pressToStartMs = VoiceRecorderDefaults.PressToStart.inWholeMilliseconds
    // In RTL the mic sits at the left edge, so cancelling is physically rightward;
    // the state machine only ever knows "leftward", so the sign is flipped here.
    val dragSign = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1f else 1f

    val recording = state.phase.isRecording
    val cancelling = state.phase == VoicePhase.Cancelling
    val background by animateColorAsState(
        targetValue = when {
            cancelling -> colors.danger.copy(alpha = 0.20f)
            recording -> colors.micActive.copy(alpha = 0.20f)
            else -> Color.Transparent
        },
        label = "voice-mic-bg",
    )
    val tint by animateColorAsState(
        targetValue = when {
            cancelling -> colors.danger
            recording -> colors.micActive
            else -> colors.mic
        },
        label = "voice-mic-tint",
    )
    // A mic that swells with your voice is the clearest possible signal that it
    // is actually listening.
    val level = state.capturedSamples.lastOrNull() ?: 0f
    val swell by animateFloatAsState(
        targetValue = if (recording) 1f + level * 0.25f else 1f,
        animationSpec = tween(durationMillis = 90),
        label = "voice-mic-swell",
    )
    val phaseDescription = when (state.phase) {
        VoicePhase.RecordingHeld -> "Recording"
        VoicePhase.RecordingLocked -> AppStrings.get(context, R.string.catalog_recording_locked_32fe3a05)
        VoicePhase.Cancelling -> AppStrings.get(context, R.string.catalog_release_to_cancel_29bd6c29)
        VoicePhase.Idle -> "Idle"
    }

    Box(
        modifier = modifier
            .size(44.dp)
            .semantics {
                contentDescription = AppStrings.get(context, R.string.catalog_hold_to_record_a_voice_message_06b1b5f5)
                role = Role.Button
                stateDescription = phaseDescription
            }
            .then(
                if (!enabled) Modifier else Modifier.pointerInput(
                    state,
                    lockThresholdPx,
                    cancelThresholdPx,
                    dragSign,
                ) {
                    // The press timer is owned here rather than delegated to
                    // detectDragGesturesAfterLongPress, which cancels itself on
                    // the slightest movement - fatal for a gesture whose whole
                    // point is to be dragged. Starting from the launched
                    // coroutine keeps awaitPointerEvent suspended throughout, so
                    // the pointer scope is never torn down between the press
                    // landing and the drag beginning.
                    coroutineScope {
                        val scope = this
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            var dragX = 0f
                            var dragY = 0f
                            var started = false
                            var blocked = false
                            val startJob = scope.launch {
                                delay(pressToStartMs)
                                if (canStart()) {
                                    started = true
                                    state.start()
                                } else {
                                    blocked = true
                                }
                            }
                            try {
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                    if (!change.pressed) break
                                    if (started) {
                                        val delta = change.positionChange()
                                        dragX += delta.x
                                        dragY += delta.y
                                        state.updateDrag(
                                            dragX * dragSign,
                                            dragY,
                                            lockThresholdPx,
                                            cancelThresholdPx,
                                        )
                                        change.consume()
                                    }
                                }
                            } finally {
                                startJob.cancel()
                                when {
                                    started -> state.release()
                                    blocked -> Unit
                                    else -> onTapTooShort()
                                }
                            }
                        }
                    }
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Default.Mic,
            contentDescription = null,
            tint = tint,
            modifier = Modifier
                .size((22 * swell).dp)
                .alpha(if (enabled) 1f else 0.4f),
        )
        if (state.phase == VoicePhase.RecordingHeld || cancelling) {
            VoiceLockTarget(
                progress = state.lockProgress,
                colors = colors,
                modifier = Modifier.align(Alignment.TopCenter),
            )
        }
    }
}

/**
 * The slide-up-to-lock target, floating above the mic. It rises and fills as the
 * finger climbs, so the gesture is discoverable without a caption explaining it.
 */
@Composable
private fun VoiceLockTarget(
    progress: Float,
    colors: VoiceRecorderColors,
    modifier: Modifier = Modifier,
) {
        val context = LocalContext.current
    val lift by animateFloatAsState(
        targetValue = progress,
        animationSpec = tween(durationMillis = 60),
        label = "voice-lock-lift",
    )
    val fill = lerp(colors.lockTrack, colors.lock, lift)
    Box(
        modifier = modifier
            .offset { IntOffset(x = 0, y = (-40.dp.toPx() - lift * 8.dp.toPx()).roundToInt()) }
            .size(width = 28.dp, height = 44.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(fill.copy(alpha = 0.28f + lift * 0.72f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                Icons.Default.Lock,
                contentDescription = AppStrings.get(context, R.string.catalog_slide_up_to_lock_recording_52127830),
                tint = if (lift > 0.5f) colors.onAccent else colors.lock,
                modifier = Modifier.size(14.dp),
            )
            Icon(
                Icons.Default.KeyboardArrowUp,
                contentDescription = null,
                tint = if (lift > 0.5f) colors.onAccent else colors.lock,
                modifier = Modifier
                    .size(14.dp)
                    .alpha(1f - lift),
            )
        }
    }
}

/** The delete button that stands in for the mic once a recording is locked. */
@Composable
fun VoiceDeleteButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    colors: VoiceRecorderColors = orangVoiceRecorderColors(),
) {
        val context = LocalContext.current
    Box(
        modifier = modifier
            .size(44.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .semantics {
                contentDescription = AppStrings.get(context, R.string.catalog_delete_recording_8b58ce23)
                role = Role.Button
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Default.Delete,
            contentDescription = null,
            tint = colors.danger,
            modifier = Modifier.size(22.dp),
        )
    }
}

/**
 * Vertical pill bars, one per pooled amplitude, scrolling in from the right.
 *
 * Bars keep a fixed width and the leftovers become padding, so a wide strip
 * draws more bars rather than fatter ones.
 */
@Composable
fun VoiceWaveform(
    samples: List<Float>,
    barColor: Color,
    modifier: Modifier = Modifier,
    barCount: Int = VoiceRecorderDefaults.BarCount,
    barWidth: Dp = VoiceRecorderDefaults.BarWidth,
    barSpacing: Dp = VoiceRecorderDefaults.BarSpacing,
    minBarHeight: Dp = VoiceRecorderDefaults.BarMinHeight,
    live: Boolean = false,
) {
    val density = LocalDensity.current
    val barWidthPx = with(density) { barWidth.toPx() }.coerceAtLeast(1f)
    val barSpacingPx = with(density) { barSpacing.toPx() }.coerceAtLeast(0f)
    val minBarHeightPx = with(density) { minBarHeight.toPx() }
    val mode = if (live) WaveformMode.Live else WaveformMode.Static

    Canvas(modifier = modifier) {
        val canvasWidth = size.width
        val canvasHeight = size.height
        if (canvasWidth <= 0f || canvasHeight <= 0f) return@Canvas

        val pitch = barWidthPx + barSpacingPx
        val maxFit = ((canvasWidth + barSpacingPx) / pitch).toInt().coerceAtLeast(1)
        val effectiveBarCount = maxFit.coerceAtMost(barCount)

        val bars = downsampleAmplitudes(samples, effectiveBarCount, mode)
        val totalRowWidth = effectiveBarCount * barWidthPx + (effectiveBarCount - 1) * barSpacingPx
        val leftPadding = ((canvasWidth - totalRowWidth) / 2f).coerceAtLeast(0f)
        val cornerRadius = CornerRadius(barWidthPx / 2f, barWidthPx / 2f)
        val minHeight = minBarHeightPx.coerceAtMost(canvasHeight)

        for (i in 0 until effectiveBarCount) {
            val barHeight = max(minHeight, bars.getOrElse(i) { 0f } * canvasHeight)
            drawRoundRect(
                color = barColor,
                topLeft = Offset(leftPadding + i * pitch, (canvasHeight - barHeight) / 2f),
                size = Size(barWidthPx, barHeight),
                cornerRadius = cornerRadius,
            )
        }
    }
}
