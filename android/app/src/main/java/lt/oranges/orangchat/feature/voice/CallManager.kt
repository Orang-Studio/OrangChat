package lt.oranges.orangchat.feature.voice

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import com.twilio.audioswitch.AudioDevice
import dagger.hilt.android.qualifiers.ApplicationContext
import io.livekit.android.LiveKit
import io.livekit.android.audio.AudioSwitchHandler
import io.livekit.android.room.Room
import io.livekit.android.events.RoomEvent
import io.livekit.android.events.collect
import io.livekit.android.room.track.LocalAudioTrack
import io.livekit.android.room.track.LocalVideoTrack
import io.livekit.android.room.track.RemoteAudioTrack
import io.livekit.android.room.track.VideoTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import livekit.org.webrtc.AudioTrackSink
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt
import lt.oranges.orangchat.data.model.DmCall
import lt.oranges.orangchat.data.model.DmCallEnded
import lt.oranges.orangchat.data.model.User
import lt.oranges.orangchat.data.repository.AuthRepository
import lt.oranges.orangchat.notifications.NotificationHelper
import lt.oranges.orangchat.realtime.SocketEvent
import lt.oranges.orangchat.realtime.SocketManager
import javax.inject.Inject
import javax.inject.Singleton

/** `OUTGOING` = still ringing out; `ACTIVE` = someone else is on the call. */
enum class CallPhase { OUTGOING, ACTIVE }

/** A DM call rings and can be declined; a voice channel is just walked into. */
enum class SessionKind { CALL, VOICE_CHANNEL }

/**
 * Ways of not answering. All of them are an answer to the call rather than a
 * lull in it, so the ringback stops and the caller is told which one happened.
 */
private val REFUSALS = setOf("declined", "timeout", "busy")

/** Why an outgoing call ended without connecting, for the caller's banner. */
data class CallNotice(val displayName: String, val reason: String) {
    /** Reads as a sentence in the banner. */
    val message: String
        get() = when (reason) {
            "declined" -> "$displayName declined the call"
            "timeout" -> "$displayName did not answer"
            "busy" -> "$displayName is on another call"
            else -> "$displayName left the call"
        }
}

/**
 * Whatever media session we are in — a DM/group call or a server voice channel.
 * Both ride the same LiveKit room, so they share one state; [call] is null for a
 * voice channel, which has no ringing and no roster to track.
 */
data class ActiveCall(
    val channelId: String,
    val label: String,
    val phase: CallPhase,
    val kind: SessionKind = SessionKind.CALL,
    val call: DmCall? = null,
    val muted: Boolean = false,
    val deafened: Boolean = false,
    val video: Boolean = false,
    val connecting: Boolean = true,
)

/** A camera track to render. [isLocal] tiles are mirrored and muted. */
data class CallVideoTrack(
    val identity: String,
    val name: String,
    val isLocal: Boolean,
    val track: VideoTrack,
)

/** One route LiveKit can currently send call audio through. */
data class CallAudioOutput(
    val id: String,
    val name: String,
)

/**
 * App-wide owner of call signalling and the LiveKit room, mirroring the web
 * client's callStore + voice store. A @Singleton rather than a ViewModel so a
 * call survives screen changes and can be driven from a notification action.
 *
 * Signalling and media are separate: the dm:call:* events decide who rings,
 * then `voice:join` mints the LiveKit token for the room `voice_<channelId>`.
 */
@Singleton
class CallManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val socketManager: SocketManager,
    private val authRepository: AuthRepository,
    private val notificationHelper: NotificationHelper,
    private val ringtonePlayer: RingtonePlayer,
    private val tokenStore: lt.oranges.orangchat.data.local.TokenStore,
    private val voiceParticipantStore: VoiceParticipantStore,
    private val soundboardPlayer: SoundboardPlayer,
) {
    private val scope = CoroutineScope(SupervisorJob())

    private val _incoming = MutableStateFlow<DmCall?>(null)
    /** A call ringing at us that we have not answered yet. */
    val incoming: StateFlow<DmCall?> = _incoming.asStateFlow()

    private val _current = MutableStateFlow<ActiveCall?>(null)
    /** The call we started, joined, or answered. */
    val current: StateFlow<ActiveCall?> = _current.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _notice = MutableStateFlow<CallNotice?>(null)
    /** How an outgoing call ended without connecting: declined, missed, busy. */
    val notice: StateFlow<CallNotice?> = _notice.asStateFlow()

    private val _videoTracks = MutableStateFlow<List<CallVideoTrack>>(emptyList())
    /** Camera tracks currently published on the call, local first. */
    val videoTracks: StateFlow<List<CallVideoTrack>> = _videoTracks.asStateFlow()

    private val _audioOutputs = MutableStateFlow<List<CallAudioOutput>>(emptyList())
    /** Speaker, earpiece and any currently connected wired/Bluetooth routes. */
    val audioOutputs: StateFlow<List<CallAudioOutput>> = _audioOutputs.asStateFlow()

    private val _selectedAudioOutputId = MutableStateFlow<String?>(null)
    val selectedAudioOutputId: StateFlow<String?> = _selectedAudioOutputId.asStateFlow()
    private var audioOutputDevices: Map<String, AudioDevice> = emptyMap()

    private val _remoteSpeakingIds = MutableStateFlow<Set<String>>(emptySet())
    private val _selfSpeaking = MutableStateFlow(false)

    /**
     * Identities currently speaking. Other people come from the SFU's
     * active-speaker list, but our own tile is driven straight off the local mic
     * (see [MicLevelSink]): the round-trip lags our own voice by a few hundred
     * ms, which reads as broken when it is your own face glowing late. Muting
     * drops us immediately, without waiting for the mic to fall silent.
     */
    val speakingIds: StateFlow<Set<String>> =
        combine(_remoteSpeakingIds, _selfSpeaking, _current) { remote, selfSpeaking, active ->
            val self = selfId ?: return@combine remote
            val others = remote - self
            if (selfSpeaking && active?.muted == false) others + self else others
        }.stateIn(scope, SharingStarted.Eagerly, emptySet())

    /** LiveKit room for the call in progress. Null when not on one. */
    var room: Room? = null
        private set

    private var micSink: MicLevelSink? = null
    private var micTrack: LocalAudioTrack? = null

    /** userId -> display name for the call we started, for [CallNotice]. */
    private var rosterNames: Map<String, String> = emptyMap()

    /**
     * Our own user id, needed to tell "someone else left" from "we were removed".
     * Read off the session rather than set from outside, so it cannot go stale.
     */
    private val selfId: String?
        get() = authRepository.currentUser?.id

    init {
        scope.launch {
            socketManager.events.collect { event ->
                when (event) {
                    is SocketEvent.DmCallRinging -> onRinging(event.call)
                    is SocketEvent.DmCallAccepted -> onAccepted(event.call)
                    is SocketEvent.DmCallFinished -> onFinished(event.ended)
                    // The server only sends this to the voice room we are in, so
                    // there is no channel check to make here.
                    is SocketEvent.SoundboardPlayed -> soundboardPlayer.play(event.url, event.volume)
                    is SocketEvent.ConnectionState ->
                        // A ring we missed while offline can no longer be answered.
                        if (!event.connected) dismissIncoming()
                    else -> Unit
                }
            }
        }
    }

    // ── User actions ────────────────────────────────────

    /**
     * Ring everyone else in a DM / group DM, then join the room ourselves.
     * [roster] is the conversation's participants, kept only to put a name to
     * whoever declines — signalling itself carries ids.
     */
    fun startCall(channelId: String, video: Boolean = false, roster: List<User> = emptyList()) {
        // Promote while the permission-backed UI action is still foreground;
        // waiting for socket + LiveKit handshakes is too late on Android 14+.
        startCallService(camera = video)
        rosterNames = roster.associate { it.id to it.displayName }
        scope.launch {
            // A fresh call supersedes whatever the last one ended as.
            _error.value = null
            _notice.value = null
            try {
                val call = socketManager.startCall(channelId, video)
                val phase = if (call.participants.size > 1) CallPhase.ACTIVE else CallPhase.OUTGOING
                _current.value = ActiveCall(
                    channelId = channelId,
                    label = call.caller.displayName,
                    phase = phase,
                    call = call,
                    video = video,
                )
                if (phase == CallPhase.OUTGOING) ringtonePlayer.startOutgoing()
                connectMedia(channelId, video)
            } catch (e: Exception) {
                Log.w(TAG, "startCall failed", e)
                ringtonePlayer.stop()
                _current.value = null
                CallService.stop(context)
                _error.value = e.message ?: "Could not start the call"
            }
        }
    }

    /**
     * Walk into a server voice channel. No ringing and no roster — just media,
     * so it skips the dm:call:* handshake entirely and goes straight to
     * voice:join.
     */
    fun joinVoiceChannel(channelId: String, channelName: String, video: Boolean = false) {
        val active = _current.value
        if (active?.channelId == channelId) return
        scope.launch {
            _error.value = null
            // Only one media session at a time; leaving first frees the mic.
            if (active != null) {
                if (active.kind == SessionKind.CALL) socketManager.endCall(active.channelId)
                disconnectMedia(active.channelId)
            }
            startCallService(camera = video)
            _current.value = ActiveCall(
                channelId = channelId,
                label = channelName,
                phase = CallPhase.ACTIVE,
                kind = SessionKind.VOICE_CHANNEL,
                video = video,
            )
            connectMedia(channelId, video = video)
        }
    }

    /** Answer the call ringing at us. */
    fun acceptCall(video: Boolean = false) {
        val incoming = _incoming.value ?: return
        ringtonePlayer.stop()
        notificationHelper.cancelCall(incoming.channelId)
        _incoming.value = null
        startCallService(camera = video)
        scope.launch {
            _error.value = null
            try {
                val call = socketManager.acceptCall(incoming.channelId)
                _current.value = ActiveCall(
                    channelId = incoming.channelId,
                    label = call.caller.displayName,
                    phase = CallPhase.ACTIVE,
                    call = call,
                    video = video,
                )
                connectMedia(incoming.channelId, video)
            } catch (e: Exception) {
                Log.w(TAG, "acceptCall failed", e)
                _current.value = null
                CallService.stop(context)
                _error.value = e.message ?: "Could not join the call"
            }
        }
    }

    /** Refuse the call ringing at us. */
    fun declineCall() {
        val incoming = _incoming.value ?: return
        ringtonePlayer.stop()
        notificationHelper.cancelCall(incoming.channelId)
        _incoming.value = null
        scope.launch { runCatching { socketManager.declineCall(incoming.channelId) } }
    }

    /** Hang up — cancels if it never connected, otherwise leaves. */
    /**
     * The clip is not played locally here: the server echoes it back to the
     * whole voice room, us included, so playing on send would double it.
     */
    fun playSound(soundId: String) {
        val active = _current.value ?: return
        scope.launch {
            runCatching { socketManager.playSound(active.channelId, soundId) }
                .onFailure { _error.value = it.message ?: "Could not play that sound" }
        }
    }

    fun hangUp() {
        val active = _current.value ?: return
        ringtonePlayer.stop()
        val channelId = active.channelId
        // A voice channel has no call to cancel or end — you just walk out.
        if (active.kind == SessionKind.CALL) {
            if (active.phase == CallPhase.OUTGOING) socketManager.cancelCall(channelId)
            else socketManager.endCall(channelId)
        }
        _current.value = null
        scope.launch { disconnectMedia(channelId) }
    }

    /** Mute is independent of deafen, so you can talk while deafened. */
    fun toggleMute() {
        val active = _current.value ?: return
        val muted = !active.muted
        _current.value = active.copy(muted = muted)
        scope.launch {
            runCatching { room?.localParticipant?.setMicrophoneEnabled(!muted) }
            socketManager.updateVoice(active.channelId, muted = muted)
        }
    }

    /**
     * Deafening mutes too, as a starting point. Unmuting while still deafened is
     * allowed: deafen governs only what we hear, never whether we can be heard.
     */
    fun toggleDeafen() {
        val active = _current.value ?: return
        val deafened = !active.deafened
        val muted = if (deafened) true else active.muted
        _current.value = active.copy(deafened = deafened, muted = muted)
        scope.launch {
            runCatching {
                setRemoteVolume(if (deafened) 0.0 else 1.0)
                room?.localParticipant?.setMicrophoneEnabled(!muted)
            }
            socketManager.updateVoice(active.channelId, muted = muted, deafened = deafened)
        }
    }

    fun toggleCamera() {
        val active = _current.value ?: return
        val video = !active.video
        // Updating an already-running FGS to camera must also happen while the
        // app is foreground and immediately after the camera permission grant.
        startCallService(camera = video)
        _current.value = active.copy(video = video)
        scope.launch {
            runCatching { room?.localParticipant?.setCameraEnabled(video) }
                .onSuccess {
                    socketManager.updateVoice(active.channelId, video = video)
                    // Our own publication does not reliably raise a room event,
                    // so pick the new tile up directly.
                    room?.let { refreshVideoTracks(it) }
                }
                .onFailure {
                    // Permission refused or no camera: put the flag back.
                    _current.value = _current.value?.copy(video = !video)
                }
        }
    }

    /** Switch the published local camera between the front and rear lenses. */
    fun flipCamera() {
        if (_current.value?.video != true) return
        val track = room?.localParticipant?.videoTrackPublications
            ?.firstNotNullOfOrNull { (_, track) -> track as? LocalVideoTrack }
            ?: return
        runCatching { track.switchCamera() }
            .onFailure {
                Log.w(TAG, "camera switch failed", it)
                _error.value = "Could not switch camera"
            }
    }

    /** Select one of the routes most recently reported by LiveKit. */
    fun selectAudioOutput(id: String) {
        val handler = room?.audioHandler as? AudioSwitchHandler ?: return
        val device = audioOutputDevices[id] ?: return
        runCatching {
            handler.selectDevice(device)
            updateAudioOutputs(handler.availableAudioDevices, handler.selectedAudioDevice)
        }.onFailure {
            Log.w(TAG, "audio output switch failed", it)
            _error.value = "Could not change audio output"
        }
    }

    fun dismissError() { _error.value = null }

    // ── Server events ───────────────────────────────────

    private fun onRinging(call: DmCall) {
        // Already on a call: ignore rather than stack popups.
        if (_current.value != null) return
        _incoming.value = call
        ringtonePlayer.startIncoming()
        notificationHelper.notifyIncomingCall(call)
    }

    private fun onAccepted(call: DmCall) {
        val active = _current.value ?: return
        if (active.channelId != call.channelId) return
        ringtonePlayer.stop()
        _current.value = active.copy(call = call, phase = CallPhase.ACTIVE)
    }

    private fun onFinished(ended: DmCallEnded) {
        val aboutUs = ended.userId == selfId

        _incoming.value?.let { inc ->
            if (inc.channelId == ended.channelId && (ended.callOver || aboutUs)) {
                ringtonePlayer.stop()
                notificationHelper.cancelCall(ended.channelId)
                _incoming.value = null
            }
        }

        val active = _current.value ?: return
        // Only DM calls end this way; a voice channel is unaffected.
        if (active.kind != SessionKind.CALL || active.channelId != ended.channelId) return
        if (ended.callOver || aboutUs) {
            ringtonePlayer.stop()
            _current.value = null
            scope.launch { disconnectMedia(ended.channelId) }
            return
        }

        val roster = active.call ?: return
        val ringing = roster.ringing.filterNot { it == ended.userId }
        val participants = roster.participants.filterNot { it == ended.userId }
        val refused = ended.reason in REFUSALS

        // The server keeps a call alive while we alone are in it, but a call with
        // nobody left to answer and nobody to talk to is over from our side.
        // Without this the caller rings out forever at a callee who said no.
        if (ringing.isEmpty() && participants.none { it != selfId }) {
            ringtonePlayer.stop()
            if (refused) {
                ringtonePlayer.playDecline()
                _notice.value = noticeFor(ended)
            }
            _current.value = null
            // A refusal already sounded; hanging up behind it must not also thud.
            scope.launch { disconnectMedia(ended.channelId, silent = refused) }
            return
        }

        // The call goes on without them. Someone merely leaving is the LiveKit
        // layer's cue to play; a refusal never reached LiveKit, so it is ours.
        if (refused) {
            ringtonePlayer.playDecline()
            _notice.value = noticeFor(ended)
        }
        _current.value = active.copy(
            call = roster.copy(ringing = ringing, participants = participants),
        )
    }

    /** The caller's roster is the only place a callee's name is known. */
    private fun noticeFor(ended: DmCallEnded): CallNotice =
        CallNotice(rosterNames[ended.userId] ?: "They", ended.reason)

    fun dismissNotice() {
        _notice.value = null
    }

    private fun dismissIncoming() {
        if (_incoming.value == null) return
        ringtonePlayer.stop()
        _incoming.value?.let { notificationHelper.cancelCall(it.channelId) }
        _incoming.value = null
    }

    // ── Media ───────────────────────────────────────────

    private suspend fun connectMedia(channelId: String, video: Boolean) {
        try {
            val creds = socketManager.joinVoice(channelId)
            val next = LiveKit.create(context)
            room = next
            observeAudioOutputs(next)
            // Rebuild the tile list from room state on any track change: cheaper
            // to reason about than patching a list per event, and it cannot drift.
            scope.launch {
                next.events.collect { event ->
                    when (event) {
                        is RoomEvent.ParticipantConnected -> {
                            // Cue off real media connectivity, and here rather
                            // than in signalling, so DM calls and voice channels
                            // sound the same.
                            ringtonePlayer.playJoin()
                            refreshVideoTracks(next)
                            syncMicLevelSink(next)
                        }
                        is RoomEvent.ParticipantDisconnected -> {
                            ringtonePlayer.playLeave()
                            refreshVideoTracks(next)
                            syncMicLevelSink(next)
                        }
                        is RoomEvent.TrackSubscribed,
                        is RoomEvent.TrackUnsubscribed,
                        is RoomEvent.TrackPublished,
                        is RoomEvent.TrackUnpublished,
                        is RoomEvent.LocalTrackSubscribed,
                        is RoomEvent.TrackMuted,
                        is RoomEvent.TrackUnmuted,
                        -> {
                            refreshVideoTracks(next)
                            // The mic track comes and goes with mute; follow it.
                            syncMicLevelSink(next)
                        }
                        is RoomEvent.ActiveSpeakersChanged -> {
                            _remoteSpeakingIds.value = event.speakers
                                .mapNotNull { it.identity?.value }
                                .toSet()
                        }
                        else -> Unit
                    }
                }
            }
            next.connect(creds.url, creds.token)
            // Our own arrival. ParticipantConnected only fires for other people,
            // and the confirmation that we are actually in is worth hearing.
            ringtonePlayer.playJoin()
            // "Join muted" is a device pref; deafen still forces mute regardless.
            val startMuted = tokenStore.joinMuted
            next.localParticipant.setMicrophoneEnabled(!startMuted)
            if (video) next.localParticipant.setCameraEnabled(true)
            refreshVideoTracks(next)
            syncMicLevelSink(next)
            _current.value = _current.value?.copy(connecting = false, muted = startMuted)
            if (video) socketManager.updateVoice(channelId, video = true)
            if (startMuted) socketManager.updateVoice(channelId, muted = true)
            // Whoever was already here muted before we arrived: their voice:state
            // fired while we were not listening. A DM call has no channel list to
            // seed it the way voice channels get seeded.
            voiceParticipantStore.seed(channelId)
        } catch (e: Exception) {
            Log.w(TAG, "media connect failed", e)
            socketManager.leaveVoice(channelId)
            socketManager.endCall(channelId)
            detachMicLevelSink()
            room?.disconnect()
            room = null
            _current.value = null
            _videoTracks.value = emptyList()
            clearAudioOutputs()
            _remoteSpeakingIds.value = emptySet()
            CallService.stop(context)
            _error.value = e.message ?: "Could not connect to the call"
        }
    }

    /**
     * Tear the media session down. [silent] suppresses the leave cue for callers
     * already playing a more specific one — a decline should not also sound like
     * a departure.
     */
    private fun disconnectMedia(channelId: String, silent: Boolean = false) {
        socketManager.leaveVoice(channelId)
        detachMicLevelSink()
        // Only cue if we were really connected; a failed join never "left".
        if (!silent && room != null) ringtonePlayer.playLeave()
        room?.disconnect()
        room = null
        _videoTracks.value = emptyList()
        clearAudioOutputs()
        _remoteSpeakingIds.value = emptySet()
        CallService.stop(context)
    }

    /** Keep Compose in sync as headsets connect, disconnect, or become active. */
    private fun observeAudioOutputs(room: Room) {
        val handler = room.audioHandler as? AudioSwitchHandler
        if (handler == null) {
            clearAudioOutputs()
            return
        }
        handler.audioDeviceChangeListener = { devices, selected ->
            updateAudioOutputs(devices, selected)
        }
        updateAudioOutputs(handler.availableAudioDevices, handler.selectedAudioDevice)
    }

    private fun updateAudioOutputs(devices: List<AudioDevice>, selected: AudioDevice?) {
        audioOutputDevices = devices.associateBy(::audioOutputId)
        _audioOutputs.value = devices.map { CallAudioOutput(audioOutputId(it), it.name) }
        _selectedAudioOutputId.value = selected?.let(::audioOutputId)
    }

    private fun clearAudioOutputs() {
        audioOutputDevices = emptyMap()
        _audioOutputs.value = emptyList()
        _selectedAudioOutputId.value = null
    }

    /** Type disambiguates routes whose display names happen to be identical. */
    private fun audioOutputId(device: AudioDevice): String =
        "${device.javaClass.name}:${device.name}"

    /** Point the level sink at whatever the local mic track is now, if any. */
    private fun syncMicLevelSink(room: Room) {
        val track = room.localParticipant.audioTrackPublications
            .firstNotNullOfOrNull { (_, track) -> track as? LocalAudioTrack }
        if (track === micTrack) return
        detachMicLevelSink()
        if (track == null) return
        val sink = MicLevelSink { speaking -> _selfSpeaking.value = speaking }
        runCatching { track.addSink(sink) }
            .onSuccess {
                micTrack = track
                micSink = sink
            }
            // Losing our own glow is not worth dropping the call over.
            .onFailure { Log.w(TAG, "could not observe mic level", it) }
    }

    private fun detachMicLevelSink() {
        micSink?.let { sink -> runCatching { micTrack?.removeSink(sink) } }
        micSink = null
        micTrack = null
        _selfSpeaking.value = false
    }

    /**
     * Snapshot every *live* camera track, ours first.
     *
     * A muted publication is skipped, and that is the whole reason camera-off
     * works: `setCameraEnabled(false)` mutes the track rather than unpublishing
     * it (only screenshare is unpublished on disable), so the publication and
     * its last frame outlive the camera being switched off. `muted` is what
     * "camera off" actually looks like, on our tile and on theirs alike.
     */
    private fun refreshVideoTracks(room: Room) {
        val tiles = mutableListOf<CallVideoTrack>()
        room.localParticipant.videoTrackPublications.forEach { (publication, track) ->
            if (publication.muted) return@forEach
            (track as? VideoTrack)?.let {
                tiles += CallVideoTrack(
                    identity = room.localParticipant.identity?.value ?: "self",
                    name = "You",
                    isLocal = true,
                    track = it,
                )
            }
        }
        room.remoteParticipants.values.forEach { participant ->
            participant.videoTrackPublications.forEach { (publication, track) ->
                if (publication.muted || !publication.subscribed) return@forEach
                (track as? VideoTrack)?.let {
                    val id = participant.identity?.value ?: return@let
                    tiles += CallVideoTrack(
                        identity = id,
                        name = participant.name?.takeIf(String::isNotBlank) ?: id,
                        isLocal = false,
                        track = it,
                    )
                }
            }
        }
        _videoTracks.value = tiles
    }

    /** Silence (0.0) or restore (1.0) everyone else — this is what deafen means. */
    private fun setRemoteVolume(volume: Double) {
        room?.remoteParticipants?.values?.forEach { participant ->
            participant.audioTrackPublications.forEach { (_, track) ->
                (track as? RemoteAudioTrack)?.setVolume(volume)
            }
        }
    }

    /** A platform start restriction should degrade background persistence, not
     * take the whole app down while the foreground call can still continue. */
    private fun startCallService(camera: Boolean) {
        runCatching { CallService.start(context, camera) }
            .onFailure { Log.w(TAG, "Could not start call foreground service", it) }
    }

    companion object {
        private const val TAG = "CallManager"
    }
}

/**
 * Reads the mic level off the PCM we capture, before it reaches the server, and
 * reports speaking / not speaking. [onChange] runs on the audio capture thread
 * and fires only on a flip, so it stays cheap at one call per 10 ms buffer.
 */
private class MicLevelSink(private val onChange: (Boolean) -> Unit) : AudioTrackSink {
    private var lastLoudAt = 0L
    private var speaking = false

    override fun onData(
        audioData: ByteBuffer,
        bitsPerSample: Int,
        sampleRate: Int,
        numberOfChannels: Int,
        numberOfFrames: Int,
        absoluteCaptureTimestampMs: Long,
    ) {
        if (bitsPerSample != 16) return
        // duplicate(): the buffer is reused by WebRTC, so leave its position be.
        val samples = audioData.duplicate().order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        if (!samples.hasRemaining()) return
        var sum = 0.0
        var count = 0
        while (samples.hasRemaining()) {
            val sample = samples.get().toDouble()
            sum += sample * sample
            count++
        }
        val rms = sqrt(sum / count) / Short.MAX_VALUE

        val now = SystemClock.elapsedRealtime()
        if (rms >= SPEAKING_RMS_THRESHOLD) lastLoudAt = now
        // Hold the glow through the gaps between syllables, or it strobes.
        val next = now - lastLoudAt <= SPEAKING_HANGOVER_MS
        if (next != speaking) {
            speaking = next
            onChange(next)
        }
    }
}

/** Chosen by ear: quiet enough to catch normal speech, above room tone. */
private const val SPEAKING_RMS_THRESHOLD = 0.02
private const val SPEAKING_HANGOVER_MS = 400L
