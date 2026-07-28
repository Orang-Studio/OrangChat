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

/**
 * Call audio. Inbound calls ring with the user's chosen tone from settings, or
 * the device ringtone if they have not picked one. Everything the app generates
 * itself - the outgoing ringback and the join/leave/decline cues - is
 * synthesised by [CallTones] to match the web client, deliberately in place of
 * Android's own ToneGenerator call sounds.
 *
 * Deliberately independent of the notification permission - a call is happening
 * now and must be audible to be answerable, and the full-screen popup is the
 * fallback if audio is unavailable.
 */
@Singleton
class RingtonePlayer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val tokenStore: TokenStore,
) {
    private var ringtone: Ringtone? = null
    private var ringback: AudioTrack? = null

    /** The user's pick, else the device default. */
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
        // A custom tone can rot - the file gets deleted, or the persisted URI
        // permission is lost on restore. Never let that mean a silent call.
        if (play(ringtoneUri())) return
        val fallback = defaultUri()
        if (tokenStore.ringtoneUri != null && fallback != null) {
            Log.w(TAG, "custom ringtone unusable; falling back to the device default")
            play(fallback)
        }
    }

    /** Returns true when the tone actually started. */
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

    /**
     * Ringback while our own outbound call waits to be picked up.
     *
     * Synthesised rather than ToneGenerator's TONE_SUP_RINGTONE: that is
     * Android's own call sound, and hearing it layered under ours was the whole
     * complaint. This matches the web client's ringback instead.
     */
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

    /** Someone joined the call. Fire-and-forget; never cancelled. */
    fun playJoin() = cue(CallTones.joinCue())

    /** Someone left the call. */
    fun playLeave() = cue(CallTones.leaveCue())

    /** Our callee refused, as distinct from a plain hang-up. */
    fun playDecline() = cue(CallTones.declineCue())

    /**
     * One-shot cue. Released on its own completion rather than tracked, so a cue
     * that lands as the call ends still finishes rather than being cut off.
     */
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

    /** Play the current ringtone briefly so the user can hear their pick. */
    @Synchronized
    fun preview() {
        startIncoming()
    }

    private companion object {
        const val TAG = "RingtonePlayer"
    }
}
