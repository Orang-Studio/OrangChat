package lt.oranges.orangchat.feature.chat.voicemessage

import android.view.HapticFeedbackConstants
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import kotlin.time.Duration
import kotlin.time.TimeMark
import kotlin.time.TimeSource

enum class VoiceHaptic { Start, Lock, CrossCancel, Cancel, Send }

class VoiceRecorderState internal constructor(
    private val onStart: () -> Unit,
    private val onCancel: () -> Unit,
    private val onSend: (Duration) -> Unit,
    private val onTooShort: () -> Unit,
    private val onHaptic: (VoiceHaptic) -> Unit,
    private val minDuration: Duration,
    private val maxDuration: Duration,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) {
    private var phaseState: VoicePhase by mutableStateOf(VoicePhase.Idle)
    val phase: VoicePhase get() = phaseState

    private val samplesState = mutableStateListOf<Float>()

    val capturedSamples: List<Float> get() = samplesState

    private var elapsedState: Duration by mutableStateOf(Duration.ZERO)
    val elapsed: Duration get() = elapsedState

    private var lockProgressState by mutableFloatStateOf(0f)
    val lockProgress: Float get() = lockProgressState

    private var cancelProgressState by mutableFloatStateOf(0f)
    val cancelProgress: Float get() = cancelProgressState

    private var startMark: TimeMark? = null

    fun start() {
        if (phaseState.isRecording) return
        samplesState.clear()
        elapsedState = Duration.ZERO
        lockProgressState = 0f
        cancelProgressState = 0f
        startMark = timeSource.markNow()
        phaseState = VoicePhase.RecordingHeld
        onHaptic(VoiceHaptic.Start)
        onStart()
    }

    fun pushAmplitude(value: Float) {
        if (phaseState.isRecording) samplesState.add(value.coerceIn(0f, 1f))
    }

    fun tick() {
        val mark = startMark ?: return
        if (!phaseState.isRecording) return
        val now = mark.elapsedNow()
        elapsedState = now
        if (now >= maxDuration) triggerSend()
    }

    fun updateDrag(
        dragX: Float,
        dragY: Float,
        lockThresholdPx: Float,
        cancelThresholdPx: Float,
    ) {
        val previous = phaseState
        if (previous != VoicePhase.RecordingHeld && previous != VoicePhase.Cancelling) return
        lockProgressState = ((-dragY) / lockThresholdPx).coerceIn(0f, 1f)
        cancelProgressState = ((-dragX) / cancelThresholdPx).coerceIn(0f, 1f)
        val next = phaseFromDrag(previous, dragX, dragY, lockThresholdPx, cancelThresholdPx)
        if (next == previous) return
        phaseState = next
        when {
            next == VoicePhase.RecordingLocked -> onHaptic(VoiceHaptic.Lock)
            next == VoicePhase.Cancelling -> onHaptic(VoiceHaptic.CrossCancel)
        }
    }

    fun lock() {
        if (!phaseState.isRecording) return
        lockProgressState = 1f
        cancelProgressState = 0f
        phaseState = VoicePhase.RecordingLocked
        onHaptic(VoiceHaptic.Lock)
    }

    fun release() {
        val outcome = phaseOnRelease(
            phaseState,
            elapsedState.inWholeMilliseconds,
            minDuration.inWholeMilliseconds,
        )
        when (outcome) {
            ReleaseOutcome.Send -> triggerSend()
            ReleaseOutcome.Cancel -> triggerCancel()
            ReleaseOutcome.TooShort -> {
                val wasRecording = phaseState.isRecording
                resetToIdle()
                if (wasRecording) {
                    onCancel()
                    onTooShort()
                }
            }
            ReleaseOutcome.Locked -> Unit
        }
    }

    fun sendFromLock() {
        if (phaseState == VoicePhase.RecordingLocked) triggerSend()
    }

    fun cancelFromLock() {
        if (phaseState == VoicePhase.RecordingLocked) triggerCancel()
    }

    fun forceCancel() {
        if (phaseState.isRecording) triggerCancel()
    }

    private fun triggerSend() {
        val duration = elapsedState
        resetToIdle()
        onHaptic(VoiceHaptic.Send)
        onSend(duration)
    }

    private fun triggerCancel() {
        resetToIdle()
        onHaptic(VoiceHaptic.Cancel)
        onCancel()
    }

    private fun resetToIdle() {
        samplesState.clear()
        elapsedState = Duration.ZERO
        lockProgressState = 0f
        cancelProgressState = 0f
        startMark = null
        phaseState = VoicePhase.Idle
    }
}

@Composable
fun rememberVoiceRecorderState(
    onStart: () -> Unit = {},
    onCancel: () -> Unit = {},
    onSend: (Duration) -> Unit,
    onTooShort: () -> Unit = {},
    onHaptic: (VoiceHaptic) -> Unit = rememberVoiceHaptics(),
    minDuration: Duration = VoiceRecorderDefaults.MinDuration,
    maxDuration: Duration = VoiceRecorderDefaults.MaxDuration,
): VoiceRecorderState {
    val currentStart by rememberUpdatedState(onStart)
    val currentCancel by rememberUpdatedState(onCancel)
    val currentSend by rememberUpdatedState(onSend)
    val currentTooShort by rememberUpdatedState(onTooShort)
    val currentHaptic by rememberUpdatedState(onHaptic)
    return remember(minDuration, maxDuration) {
        VoiceRecorderState(
            onStart = { currentStart() },
            onCancel = { currentCancel() },
            onSend = { duration -> currentSend(duration) },
            onTooShort = { currentTooShort() },
            onHaptic = { haptic -> currentHaptic(haptic) },
            minDuration = minDuration,
            maxDuration = maxDuration,
        )
    }
}

@Composable
fun rememberVoiceHaptics(): (VoiceHaptic) -> Unit {
    val view = LocalView.current
    return remember(view) {
        { haptic ->
            val constant = when (haptic) {
                VoiceHaptic.Start -> HapticFeedbackConstants.LONG_PRESS
                VoiceHaptic.Lock -> HapticFeedbackConstants.CONFIRM
                VoiceHaptic.CrossCancel -> HapticFeedbackConstants.GESTURE_START
                VoiceHaptic.Cancel -> HapticFeedbackConstants.REJECT
                VoiceHaptic.Send -> HapticFeedbackConstants.CONFIRM
            }
            view.performHapticFeedback(constant)
        }
    }
}
