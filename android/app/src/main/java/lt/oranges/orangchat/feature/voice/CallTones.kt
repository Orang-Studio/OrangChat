package lt.oranges.orangchat.feature.voice

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.sin

internal object CallTones {

    private const val SAMPLE_RATE = 44_100

    private const val FADE = 0.01

    data class Note(val freqs: List<Double>, val at: Double, val dur: Double, val gain: Double)

    fun ringbackCycle(): ShortArray = render(
        totalSeconds = 4.0,
        notes = listOf(Note(listOf(440.0, 480.0), at = 0.0, dur = 1.0, gain = 0.35)),
    )

    fun joinCue(): ShortArray = render(
        totalSeconds = 0.24,
        notes = listOf(
            Note(listOf(587.33), at = 0.0, dur = 0.09, gain = 0.5),
            Note(listOf(880.0), at = 0.09, dur = 0.13, gain = 0.5),
        ),
    )

    fun leaveCue(): ShortArray = render(
        totalSeconds = 0.24,
        notes = listOf(
            Note(listOf(880.0), at = 0.0, dur = 0.09, gain = 0.5),
            Note(listOf(587.33), at = 0.09, dur = 0.13, gain = 0.5),
        ),
    )

    fun declineCue(): ShortArray = render(
        totalSeconds = 0.42,
        notes = listOf(
            Note(listOf(392.0), at = 0.0, dur = 0.12, gain = 0.45),
            Note(listOf(392.0), at = 0.18, dur = 0.22, gain = 0.45),
        ),
    )

    private fun render(totalSeconds: Double, notes: List<Note>): ShortArray {
        val out = ShortArray((totalSeconds * SAMPLE_RATE).toInt())
        for (note in notes) {
            val start = (note.at * SAMPLE_RATE).toInt()
            val length = (note.dur * SAMPLE_RATE).toInt()
            val perTone = note.gain / note.freqs.size
            for (i in 0 until length) {
                val index = start + i
                if (index >= out.size) break
                val t = i.toDouble() / SAMPLE_RATE
                var sample = 0.0
                for (freq in note.freqs) sample += sin(2.0 * PI * freq * t) * perTone
                sample *= envelope(t, note.dur)
                val mixed = out[index] + (sample * Short.MAX_VALUE)
                out[index] = mixed.coerceIn(Short.MIN_VALUE.toDouble(), Short.MAX_VALUE.toDouble())
                    .toInt()
                    .toShort()
            }
        }
        return out
    }

    private fun envelope(t: Double, duration: Double): Double = when {
        duration <= FADE * 2 -> 1.0
        t < FADE -> t / FADE
        t > duration - FADE -> (duration - t) / FADE
        else -> 1.0
    }

    fun track(pcm: ShortArray, usage: Int, loop: Boolean): AudioTrack {
        val bytes = pcm.size * 2
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(usage)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(bytes)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        track.write(pcm, 0, pcm.size)
        if (loop) track.setLoopPoints(0, pcm.size, -1)
        return track
    }
}
