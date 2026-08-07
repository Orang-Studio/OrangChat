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
 * Adapted from github.com/NadeemIqbal/voice-message @ 671085b (v0.3.1),
 * a Compose Multiplatform library. Vendored rather than depended on: upstream
 * builds against Kotlin 2.3 / Compose Multiplatform 1.10, and this app is on
 * Kotlin 2.0.21, which cannot read Kotlin 2.3 metadata. The code itself only
 * ever touches plain AndroidX Compose, so it compiles here unchanged.
 *
 * Local changes: added lock/cancel drag progress so the UI can track the finger
 * rather than only snap at the thresholds, added a "too short" outcome the host
 * can surface as a hint, and dropped the playback bubble (AudioCard already
 * renders sent voice messages).
 */
package lt.oranges.orangchat.feature.chat.voicemessage

import kotlin.math.max
import kotlin.math.min

/**
 * The four states of the hold-to-record gesture.
 *
 * - [Idle]: nothing is happening. Also the resting state after a send or a
 *   cancel - the callbacks are the delivery contract, there is no terminal
 *   "sent" phase to get stuck in.
 * - [RecordingHeld]: the mic is pressed and recording; the slide hints and the
 *   live waveform are up.
 * - [RecordingLocked]: the finger crossed the lock threshold, so recording
 *   carries on hands-free behind a delete/send pair.
 * - [Cancelling]: the finger is past the cancel threshold; releasing now throws
 *   the clip away, and sliding back returns to [RecordingHeld].
 */
enum class VoicePhase {
    Idle,
    RecordingHeld,
    RecordingLocked,
    Cancelling;

    val isRecording: Boolean
        get() = this == RecordingHeld || this == RecordingLocked || this == Cancelling
}

/** How [downsampleAmplitudes] treats input longer than the bar count. */
internal enum class WaveformMode {
    /** Keep only the newest bars, so the waveform scrolls in from the right. */
    Live,

    /** Compress the whole history with max-of-bucket. */
    Static,
}

/**
 * Reduces raw amplitude readings (each `0f..1f`) to exactly [targetCount] bars.
 *
 * Max-of-bucket rather than mean-of-bucket: quiet runs still read as quiet, but
 * a peak survives instead of being averaged into the noise around it. Short
 * input is padded on the left with zeros so a fresh recording grows in from the
 * right edge instead of stretching to fill.
 */
internal fun downsampleAmplitudes(
    samples: List<Float>,
    targetCount: Int,
    mode: WaveformMode = WaveformMode.Static,
): List<Float> {
    require(targetCount > 0) { "targetCount must be > 0, was $targetCount" }
    if (samples.isEmpty()) return List(targetCount) { 0f }
    if (samples.size <= targetCount) {
        val pad = targetCount - samples.size
        return List(targetCount) { i -> if (i < pad) 0f else samples[i - pad].coerceIn(0f, 1f) }
    }
    return when (mode) {
        WaveformMode.Live -> {
            val start = samples.size - targetCount
            List(targetCount) { i -> samples[start + i].coerceIn(0f, 1f) }
        }
        WaveformMode.Static -> {
            val bucketSize = samples.size.toDouble() / targetCount
            List(targetCount) { i ->
                val from = (i * bucketSize).toInt()
                val to = min(samples.size, ((i + 1) * bucketSize).toInt())
                var peak = 0f
                for (j in from until max(to, from + 1)) {
                    val s = samples.getOrNull(j) ?: 0f
                    if (s > peak) peak = s
                }
                peak.coerceIn(0f, 1f)
            }
        }
    }
}

/**
 * The phase a drag offset resolves to, given where we already are.
 *
 * [dragX] is rightward-positive and [dragY] downward-positive - the standard
 * Compose pointer convention. The mic sits at the right of the composer, so
 * locking is upward ([dragY] past `-lockThresholdPx`) and cancelling is leftward
 * ([dragX] past `-cancelThresholdPx`). Flipping [dragX] for RTL is the caller's
 * job; this stays pure.
 */
internal fun phaseFromDrag(
    current: VoicePhase,
    dragX: Float,
    dragY: Float,
    lockThresholdPx: Float,
    cancelThresholdPx: Float,
): VoicePhase {
    if (current != VoicePhase.RecordingHeld && current != VoicePhase.Cancelling) return current
    if (dragY <= -lockThresholdPx) return VoicePhase.RecordingLocked
    if (dragX <= -cancelThresholdPx) return VoicePhase.Cancelling
    // Past the threshold once, but drifted back under it - resume holding.
    return VoicePhase.RecordingHeld
}

/** What the finger coming up means, split out so the caller never reads the new phase to find out. */
internal enum class ReleaseOutcome {
    /** Deliver the clip. */
    Send,

    /** Discard it - the user slid to cancel. */
    Cancel,

    /** Nothing to do; the recording carries on hands-free. */
    Locked,

    /** Discard it - the press was too brief to be audible. */
    TooShort,
}

internal fun phaseOnRelease(
    current: VoicePhase,
    elapsedMillis: Long,
    minDurationMillis: Long,
): ReleaseOutcome = when (current) {
    VoicePhase.RecordingHeld ->
        if (elapsedMillis >= minDurationMillis) ReleaseOutcome.Send else ReleaseOutcome.TooShort
    VoicePhase.Cancelling -> ReleaseOutcome.Cancel
    VoicePhase.RecordingLocked -> ReleaseOutcome.Locked
    VoicePhase.Idle -> ReleaseOutcome.TooShort
}
