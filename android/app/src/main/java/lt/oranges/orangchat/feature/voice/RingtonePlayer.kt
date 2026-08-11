package lt.oranges.orangchat.feature.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioTrack
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import lt.oranges.orangchat.data.local.TokenStore
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RingtonePlayer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val tokenStore: TokenStore,
) {
    private var ringtone: Ringtone? = null
    private var ringback: AudioTrack? = null

    private fun ringtoneUri(): Uri? =
        tokenStore.ringtoneUri?.let(Uri::parse)
            ?: RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_RINGTONE)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)

    private fun defaultUri(): Uri? =
        RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_RINGTONE)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)

    @Synchronized
    fun startIncoming() {
        stop()
        if (play(ringtoneUri())) return
        val fallback = defaultUri()
        if (tokenStore.ringtoneUri != null && fallback != null) {
            Log.w(TAG, "custom ringtone unusable; falling back to the device default")
            play(fallback)
        }
    }

    private fun play(uri: Uri?): Boolean {
        if (uri == null) return false
        return runCatching {
            val tone = RingtoneManager.getRingtone(context, uri) ?: return false
            tone.audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) tone.isLooping = true
            tone.play()
            ringtone = tone
            tone.isPlaying
        }.getOrDefault(false)
    }

    @Synchronized
    fun startOutgoing() {
        stop()
        runCatching {
            ringback = CallTones.track(
                CallTones.ringbackCycle(),
                AudioAttributes.USAGE_VOICE_COMMUNICATION,
                loop = true,
            ).apply { play() }
        }
    }

    fun playJoin() = cue(CallTones.joinCue())

    fun playLeave() = cue(CallTones.leaveCue())

    fun playDecline() = cue(CallTones.declineCue())

    private fun cue(pcm: ShortArray) {
        runCatching {
            val track = CallTones.track(pcm, AudioAttributes.USAGE_VOICE_COMMUNICATION, loop = false)
            track.setNotificationMarkerPosition(pcm.size)
            track.setPlaybackPositionUpdateListener(
                object : AudioTrack.OnPlaybackPositionUpdateListener {
                    override fun onMarkerReached(t: AudioTrack?) {
                        runCatching { t?.release() }
                    }

                    override fun onPeriodicNotification(t: AudioTrack?) = Unit
                },
            )
            track.play()
        }
    }

    @Synchronized
    fun stop() {
        runCatching { ringtone?.stop() }
        ringtone = null
        runCatching {
            ringback?.pause()
            ringback?.flush()
            ringback?.release()
        }
        ringback = null
    }

    @Synchronized
    fun preview() {
        startIncoming()
    }

    private companion object {
        const val TAG = "RingtonePlayer"
    }
}
