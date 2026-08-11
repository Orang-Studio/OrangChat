package lt.oranges.orangchat.feature.chat.voicemessage

import kotlin.math.max
import kotlin.math.min

enum class VoicePhase {
    Idle,
    RecordingHeld,
    RecordingLocked,
    Cancelling;

    val isRecording: Boolean
        get() = this == RecordingHeld || this == RecordingLocked || this == Cancelling
}

internal enum class WaveformMode {
    Live,

    Static,
}

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
    return VoicePhase.RecordingHeld
}

internal enum class ReleaseOutcome {
    Send,

    Cancel,

    Locked,

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
