package lt.oranges.orangchat.feature.chat

import android.content.Context
import androidx.annotation.OptIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory

@OptIn(UnstableApi::class)
object MediaPlayback {
    var currentId by mutableStateOf<String?>(null)
        private set
    var isPlaying by mutableStateOf(false)
        private set
    var muted by mutableStateOf(false)
        private set
    var buffering by mutableStateOf(false)
        private set
    var positionMs by mutableLongStateOf(0L)
        private set

    var durationMs by mutableLongStateOf(0L)
        private set

    var videoAspect by mutableFloatStateOf(0f)
        private set

    private var player: ExoPlayer? = null
    private var onError: (() -> Unit)? = null
    private var lifecycleHooked = false

    val ready: Boolean get() = player != null && durationMs > 0

    fun playerFor(id: String): ExoPlayer? = player?.takeIf { currentId == id }

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(playing: Boolean) {
            isPlaying = playing
            if (playing) syncPosition()
        }

        override fun onPlaybackStateChanged(state: Int) {
            buffering = state == Player.STATE_BUFFERING
            player?.let { p ->
                durationMs = p.duration.takeIf { it != C.TIME_UNSET }?.coerceAtLeast(0L) ?: 0L
            }
            if (state == Player.STATE_ENDED) positionMs = 0L
        }

        override fun onVideoSizeChanged(size: VideoSize) {
            videoAspect = if (size.width > 0 && size.height > 0) {
                size.width * size.pixelWidthHeightRatio / size.height
            } else {
                0f
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            val sink = onError
            release()
            sink?.invoke()
        }
    }

    fun open(context: Context, id: String, url: String, onError: () -> Unit): ExoPlayer {
        player?.let {
            if (currentId == id) {
                this.onError = onError
                return it
            }
        }
        hookLifecycle()
        val p = player ?: build(context)

        currentId = id
        this.onError = onError
        p.volume = 1f
        isPlaying = false
        muted = false
        buffering = true
        positionMs = 0L
        durationMs = 0L
        videoAspect = 0f

        p.playWhenReady = false
        p.setMediaItem(MediaItem.fromUri(url))
        p.prepare()
        return p
    }

    private fun build(context: Context): ExoPlayer {
        val p = ExoPlayer.Builder(context.applicationContext)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(MediaCache.dataSourceFactory(context)),
            )
            .build()
        p.setAudioAttributes(
            AudioAttributes.Builder()
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .setUsage(C.USAGE_MEDIA)
                .build(),
true,
        )
        p.addListener(listener)
        player = p
        return p
    }

    fun toggle(context: Context, id: String, url: String, onError: () -> Unit) {
        val p = open(context, id, url, onError)
        if (p.playbackState == Player.STATE_ENDED) {
            p.seekTo(0)
            p.playWhenReady = true
            return
        }
        p.playWhenReady = !p.playWhenReady
    }

    fun toggleMute() {
        val p = player ?: return
        muted = !muted
        p.volume = if (muted) 0f else 1f
    }

    fun seekTo(ms: Long) {
        val p = player ?: return
        val clamped = ms.coerceIn(0L, if (durationMs > 0) durationMs else ms)
        p.seekTo(clamped)
        positionMs = clamped
    }

    fun syncPosition() {
        val p = player ?: return
        positionMs = p.currentPosition.coerceAtLeast(0L)
    }

    fun pause() {
        player?.playWhenReady = false
    }

    private fun hookLifecycle() {
        if (lifecycleHooked) return
        lifecycleHooked = true
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) = pause()
        })
    }

    fun release() {
        player?.let { p ->
            p.removeListener(listener)
            p.release()
        }
        player = null
        onError = null
        currentId = null
        isPlaying = false
        muted = false
        buffering = false
        positionMs = 0L
        durationMs = 0L
        videoAspect = 0f
    }
}
