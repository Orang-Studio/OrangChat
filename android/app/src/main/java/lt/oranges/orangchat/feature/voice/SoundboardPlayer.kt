package lt.oranges.orangchat.feature.voice

import android.media.AudioAttributes
import android.media.MediaPlayer
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import lt.oranges.orangchat.util.absoluteUrl
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Plays soundboard clips over the voice call rather than through LiveKit: the
 * server broadcasts the url to everyone in the room and each client plays it
 * locally, so the clip never competes with the microphone for a publish slot.
 *
 * Clips are capped at 3 seconds server-side, so overlapping players are bounded
 * and each one is released on completion.
 */
@Singleton
class SoundboardPlayer @Inject constructor(
    @ApplicationContext private val context: android.content.Context,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val active = mutableSetOf<MediaPlayer>()

    fun play(url: String, volume: Double) {
        scope.launch {
            runCatching {
                val player = MediaPlayer().apply {
                    // USAGE_MEDIA rather than VOICE_COMMUNICATION: the clip is
                    // content, and routing it as call audio would duck it into
                    // the earpiece while a call is up.
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build(),
                    )
                    setDataSource(absoluteUrl(url))
                    val gain = volume.coerceIn(0.0, 1.0).toFloat()
                    setVolume(gain, gain)
                    setOnCompletionListener { release(it) }
                    setOnErrorListener { mp, _, _ -> release(mp); true }
                    prepare()
                }
                synchronized(active) { active += player }
                player.start()
            }
        }
    }

    private fun release(player: MediaPlayer) {
        synchronized(active) { active -= player }
        runCatching { player.release() }
    }

    fun stopAll() {
        synchronized(active) { active.toList() }.forEach { player ->
            runCatching { player.stop() }
            release(player)
        }
    }
}
