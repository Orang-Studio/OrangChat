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

/**
 * The app's single media player, shared by audio and video attachments.
 *
 * One player for the whole app means clips can't talk over each other, a
 * channel with a dozen attachments opens one stream rather than a dozen, and
 * playback survives its row being recycled out of the list. Handing the same
 * player between the inline surface and the full-screen one is what makes
 * expanding seamless - the surface changes, the playback does not.
 *
 * Main-thread only.
 */
@OptIn(UnstableApi::class)
object MediaPlayback {
    /** Attachment id the player is loaded with, if any. */
    var currentId by mutableStateOf<String?>(null)
        private set
    var isPlaying by mutableStateOf(false)
        private set
    var buffering by mutableStateOf(false)
        private set
    var positionMs by mutableLongStateOf(0L)
        private set

    /** 0 until known; a live stream never reports one. */
    var durationMs by mutableLongStateOf(0L)
        private set

    /** Pixel aspect included. 0 for audio, and for video until the first frame. */
    var videoAspect by mutableFloatStateOf(0f)
        private set

    private var player: ExoPlayer? = null
    private var onError: (() -> Unit)? = null
    private var lifecycleHooked = false

    /** True once seeking and pausing mean anything. */
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

    /**
     * Load [id] if it isn't already loaded and return the player driving it, so
     * a video can bind its surface. Does not start playback.
     *
     * Switching clips re-uses the one player rather than releasing and building
     * another: the outgoing video's surface unbinds a frame or two after the
     * switch and needs a live player to unbind from.
     */
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
        isPlaying = false
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
        val p = ExoPlayer.Builder(context.applicationContext).build()
        // handleAudioFocus: don't play over a call, or the user's own music.
        p.setAudioAttributes(
            AudioAttributes.Builder()
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .setUsage(C.USAGE_MEDIA)
                .build(),
            /* handleAudioFocus = */ true,
        )
        p.addListener(listener)
        player = p
        return p
    }

    fun toggle(context: Context, id: String, url: String, onError: () -> Unit) {
        val p = open(context, id, url, onError)
        if (p.playbackState == Player.STATE_ENDED) p.seekTo(0)
        // Not isPlaying: that stays false while buffering, so a pause tapped
        // mid-buffer would read as "not playing" and start it again.
        p.playWhenReady = !p.playWhenReady
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

    /** Stop at a resumable point; unlike [release] the clip keeps its position. */
    fun pause() {
        player?.playWhenReady = false
    }

    /**
     * Until this grows a media session, leaving is a pause: audio playing on in
     * the background would have no notification or lock-screen control to stop
     * it. The observer is process-scoped and outlives any one clip, hence the
     * once-only guard.
     */
    private fun hookLifecycle() {
        if (lifecycleHooked) return
        lifecycleHooked = true
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) = pause()
        })
    }

    /** Tear the player down and forget it; safe to call at any point. */
    fun release() {
        player?.let { p ->
            p.removeListener(listener)
            p.release()
        }
        player = null
        onError = null
        currentId = null
        isPlaying = false
        buffering = false
        positionMs = 0L
        durationMs = 0L
        videoAspect = 0f
    }
}
