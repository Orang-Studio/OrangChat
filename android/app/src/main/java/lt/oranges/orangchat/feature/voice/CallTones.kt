package lt.oranges.orangchat.feature.voice

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.sin

/**
 * Synthesised call audio: the outgoing ringback and the short join/leave/decline
 * cues. Port of the web client's lib/ringtone.ts, tone for tone, so a call
 * sounds the same on both clients.
 *
 * Synthesised rather than played from Android's [android.media.ToneGenerator]:
 * its supervisory tones are the *system's* call sounds, which sound nothing like
 * the web client's and cannot be reshaped.
 */
internal object CallTones {

    private const val SAMPLE_RATE = 44_100

    /** Seconds of fade at each end of a tone, so it does not click. */
    private const val FADE = 0.01

    /** One note: layered [freqs], starting [at] seconds in, lasting [dur]. */
    data class Note(val freqs: List<Double>, val at: Double, val dur: Double, val gain: Double)

    /** Classic ringback pair, one second on and three off - matches the web. */
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

    /** Mix [notes] into a mono 16-bit PCM buffer [totalSeconds] long. */
    private fun render(totalSeconds: Double, notes: List<Note>): ShortArray {
        val out = ShortArray((totalSeconds * SAMPLE_RATE).toInt())
        for (note in notes) {
            val start = (note.at * SAMPLE_RATE).toInt()
            val length = (note.dur * SAMPLE_RATE).toInt()
            // Layered tones share the note's gain rather than each taking it, so
            // a two-tone note is no louder than a one-tone note.
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

    /** Linear fade in and out; flat in between. */
    private fun envelope(t: Double, duration: Double): Double = when {
        duration <= FADE * 2 -> 1.0
        t < FADE -> t / FADE
        t > duration - FADE -> (duration - t) / FADE
        else -> 1.0
    }

    /**
     * An [AudioTrack] preloaded with [pcm]. [loop] repeats it forever, which is
     * how the ringback keeps going without a scheduler.
     */
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
