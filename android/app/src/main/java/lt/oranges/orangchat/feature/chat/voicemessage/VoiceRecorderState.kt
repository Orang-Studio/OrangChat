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
 */
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

/** A phase change worth feeling. */
enum class VoiceHaptic { Start, Lock, CrossCancel, Cancel, Send }

/**
 * Owns the gesture state machine and the live payload - elapsed time plus the
 * amplitude samples driving the waveform.
 *
 * It never opens a microphone. Capture is wired in through the callbacks:
 * [onStart] opens [lt.oranges.orangchat.feature.chat.VoiceMessageRecorder],
 * [onSend] stops it and hands the file over, [onCancel] throws it away, and the
 * caller pumps readings in through [pushAmplitude].
 */
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

    /** Amplitude readings since this recording began, oldest first. */
    val capturedSamples: List<Float> get() = samplesState

    private var elapsedState: Duration by mutableStateOf(Duration.ZERO)
    val elapsed: Duration get() = elapsedState

    /**
     * How far the finger has travelled toward each threshold, `0f..1f`. The bar
     * tracks these rather than only reacting once a threshold trips, so the
     * gesture shows its progress while it is still reversible.
     */
    private var lockProgressState by mutableFloatStateOf(0f)
    val lockProgress: Float get() = lockProgressState

    private var cancelProgressState by mutableFloatStateOf(0f)
    val cancelProgress: Float get() = cancelProgressState

    private var startMark: TimeMark? = null

    /** Begins a recording. A no-op if one is already running. */
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

    /** Feeds the waveform one reading (`0f..1f`). Ignored when idle. */
    fun pushAmplitude(value: Float) {
        if (phaseState.isRecording) samplesState.add(value.coerceIn(0f, 1f))
    }

    /**
     * Advances the elapsed counter from the monotonic clock. Driven by a ticker
     * while recording; finishes the clip on its own once [maxDuration] is up.
     */
    fun tick() {
        val mark = startMark ?: return
        if (!phaseState.isRecording) return
        val now = mark.elapsedNow()
        elapsedState = now
        if (now >= maxDuration) triggerSend()
    }

    /** Reports how far the finger has moved from where it went down. */
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

    /**
     * Pins the recording into the locked phase with the finger long gone. Used
     * when a recording begins from a permission dialog: the launcher has the
     * user's attention, so the clip should behave like a locked one from its
     * first second rather than hover as a held one nobody is holding.
     */
    fun lock() {
        if (!phaseState.isRecording) return
        lockProgressState = 1f
        cancelProgressState = 0f
        phaseState = VoicePhase.RecordingLocked
        onHaptic(VoiceHaptic.Lock)
    }

    /** The finger came up. Resolves to send, cancel, too-short, or stay locked. */
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
                // The mic was open, so there is a file to clean up, and the user
                // gets told what the gesture wanted instead of silence.
                if (wasRecording) {
                    onCancel()
                    onTooShort()
                }
            }
            ReleaseOutcome.Locked -> Unit
        }
    }

    /** Send tapped while locked. */
    fun sendFromLock() {
        if (phaseState == VoicePhase.RecordingLocked) triggerSend()
    }

    /** Delete tapped while locked. */
    fun cancelFromLock() {
        if (phaseState == VoicePhase.RecordingLocked) triggerCancel()
    }

    /** Drops whatever is in flight - the app is going away, or a call came in. */
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

    // Idle is restored before any callback fires, so an observer that re-reads
    // `phase` from inside one sees the resting state rather than a stuck phase.
    private fun resetToIdle() {
        samplesState.clear()
        elapsedState = Duration.ZERO
        lockProgressState = 0f
        cancelProgressState = 0f
        startMark = null
        phaseState = VoicePhase.Idle
    }
}

/**
 * Creates and remembers a [VoiceRecorderState].
 *
 * The callbacks are read through [rememberUpdatedState], so a lambda that closes
 * over the open channel keeps working after the user switches channels - upstream
 * captured them once and went stale.
 */
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

/**
 * Maps the abstract transitions onto the platform's own feedback. Going through
 * the host view means an app-wide opt-out of haptics is respected for free.
 */
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
