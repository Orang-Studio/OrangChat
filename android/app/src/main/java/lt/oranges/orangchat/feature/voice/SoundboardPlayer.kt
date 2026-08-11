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
