package lt.oranges.orangchat.feature.voice
import lt.oranges.orangchat.util.AppStrings
import lt.oranges.orangchat.R
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

enum class CallPhase { OUTGOING, ACTIVE }

enum class SessionKind { CALL, VOICE_CHANNEL }

private val REFUSALS = setOf("declined", "timeout", "busy")

data class CallNotice(val displayName: String, val reason: String) {
    val message: String
        get() = when (reason) {
            "declined" -> "$displayName declined the call"
            "timeout" -> "$displayName did not answer"
            "busy" -> "$displayName is on another call"
            else -> "$displayName left the call"
        }
}

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

data class CallVideoTrack(
    val identity: String,
    val name: String,
    val isLocal: Boolean,
    val track: VideoTrack,
)

data class CallAudioOutput(
    val id: String,
    val name: String,
)

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
    val incoming: StateFlow<DmCall?> = _incoming.asStateFlow()

    private val _current = MutableStateFlow<ActiveCall?>(null)
    val current: StateFlow<ActiveCall?> = _current.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _notice = MutableStateFlow<CallNotice?>(null)
    val notice: StateFlow<CallNotice?> = _notice.asStateFlow()

    private val _videoTracks = MutableStateFlow<List<CallVideoTrack>>(emptyList())
    val videoTracks: StateFlow<List<CallVideoTrack>> = _videoTracks.asStateFlow()

    private val _audioOutputs = MutableStateFlow<List<CallAudioOutput>>(emptyList())
    val audioOutputs: StateFlow<List<CallAudioOutput>> = _audioOutputs.asStateFlow()

    private val _selectedAudioOutputId = MutableStateFlow<String?>(null)
    val selectedAudioOutputId: StateFlow<String?> = _selectedAudioOutputId.asStateFlow()
    private var audioOutputDevices: Map<String, AudioDevice> = emptyMap()

    private val _remoteSpeakingIds = MutableStateFlow<Set<String>>(emptySet())
    private val _selfSpeaking = MutableStateFlow(false)

    val speakingIds: StateFlow<Set<String>> =
        combine(_remoteSpeakingIds, _selfSpeaking, _current) { remote, selfSpeaking, active ->
            val self = selfId ?: return@combine remote
            val others = remote - self
            if (selfSpeaking && active?.muted == false) others + self else others
        }.stateIn(scope, SharingStarted.Eagerly, emptySet())

    var room: Room? = null
        private set

    private var micSink: MicLevelSink? = null
    private var micTrack: LocalAudioTrack? = null

    private var rosterNames: Map<String, String> = emptyMap()

    private val selfId: String?
        get() = authRepository.currentUser?.id

    init {
        scope.launch {
            socketManager.events.collect { event ->
                when (event) {
                    is SocketEvent.DmCallRinging -> onRinging(event.call)
                    is SocketEvent.DmCallAccepted -> onAccepted(event.call)
                    is SocketEvent.DmCallFinished -> onFinished(event.ended)
                    is SocketEvent.SoundboardPlayed -> soundboardPlayer.play(event.url, event.volume)
                    is SocketEvent.ConnectionState ->
                        if (!event.connected) dismissIncoming()
                    else -> Unit
                }
            }
        }
    }


    fun startCall(channelId: String, video: Boolean = false, roster: List<User> = emptyList()) {
        startCallService(camera = video)
        rosterNames = roster.associate { it.id to it.displayName }
        scope.launch {
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
                _error.value = e.message ?: AppStrings.get(context, R.string.catalog_could_not_start_the_call_925e1fa3)
            }
        }
    }

    fun joinVoiceChannel(channelId: String, channelName: String, video: Boolean = false) {
        val active = _current.value
        if (active?.channelId == channelId) return
        scope.launch {
            _error.value = null
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
                _error.value = e.message ?: AppStrings.get(context, R.string.catalog_could_not_join_the_call_a95443d4)
            }
        }
    }

    fun declineCall() {
        val incoming = _incoming.value ?: return
        ringtonePlayer.stop()
        notificationHelper.cancelCall(incoming.channelId)
        _incoming.value = null
        scope.launch { runCatching { socketManager.declineCall(incoming.channelId) } }
    }

    fun playSound(soundId: String) {
        val active = _current.value ?: return
        scope.launch {
            runCatching { socketManager.playSound(active.channelId, soundId) }
                .onFailure { _error.value = it.message ?: AppStrings.get(context, R.string.catalog_could_not_play_that_sound_5f4f3c4c) }
        }
    }

    fun hangUp() {
        val active = _current.value ?: return
        ringtonePlayer.stop()
        val channelId = active.channelId
        if (active.kind == SessionKind.CALL) {
            if (active.phase == CallPhase.OUTGOING) socketManager.cancelCall(channelId)
            else socketManager.endCall(channelId)
        }
        _current.value = null
        scope.launch { disconnectMedia(channelId) }
    }

    fun toggleMute() {
        val active = _current.value ?: return
        val muted = !active.muted
        _current.value = active.copy(muted = muted)
        scope.launch {
            runCatching { room?.localParticipant?.setMicrophoneEnabled(!muted) }
            socketManager.updateVoice(active.channelId, muted = muted)
        }
    }

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
        startCallService(camera = video)
        _current.value = active.copy(video = video)
        scope.launch {
            runCatching { room?.localParticipant?.setCameraEnabled(video) }
                .onSuccess {
                    socketManager.updateVoice(active.channelId, video = video)
                    room?.let { refreshVideoTracks(it) }
                }
                .onFailure {
                    _current.value = _current.value?.copy(video = !video)
                }
        }
    }

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


    private fun onRinging(call: DmCall) {
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

        if (ringing.isEmpty() && participants.none { it != selfId }) {
            ringtonePlayer.stop()
            if (refused) {
                ringtonePlayer.playDecline()
                _notice.value = noticeFor(ended)
            }
            _current.value = null
            scope.launch { disconnectMedia(ended.channelId, silent = refused) }
            return
        }

        if (refused) {
            ringtonePlayer.playDecline()
            _notice.value = noticeFor(ended)
        }
        _current.value = active.copy(
            call = roster.copy(ringing = ringing, participants = participants),
        )
    }

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


    private suspend fun connectMedia(channelId: String, video: Boolean) {
        try {
            val creds = socketManager.joinVoice(channelId)
            val next = LiveKit.create(context)
            room = next
            observeAudioOutputs(next)
            scope.launch {
                next.events.collect { event ->
                    when (event) {
                        is RoomEvent.ParticipantConnected -> {
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
            ringtonePlayer.playJoin()
            val startMuted = tokenStore.joinMuted
            next.localParticipant.setMicrophoneEnabled(!startMuted)
            if (video) next.localParticipant.setCameraEnabled(true)
            refreshVideoTracks(next)
            syncMicLevelSink(next)
            _current.value = _current.value?.copy(connecting = false, muted = startMuted)
            if (video) socketManager.updateVoice(channelId, video = true)
            if (startMuted) socketManager.updateVoice(channelId, muted = true)
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
            _error.value = e.message ?: AppStrings.get(context, R.string.catalog_could_not_connect_to_the_call_a713db79)
        }
    }

    private fun disconnectMedia(channelId: String, silent: Boolean = false) {
        socketManager.leaveVoice(channelId)
        detachMicLevelSink()
        if (!silent && room != null) ringtonePlayer.playLeave()
        room?.disconnect()
        room = null
        _videoTracks.value = emptyList()
        clearAudioOutputs()
        _remoteSpeakingIds.value = emptySet()
        CallService.stop(context)
    }

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

    private fun audioOutputId(device: AudioDevice): String =
        "${device.javaClass.name}:${device.name}"

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
            .onFailure { Log.w(TAG, "could not observe mic level", it) }
    }

    private fun detachMicLevelSink() {
        micSink?.let { sink -> runCatching { micTrack?.removeSink(sink) } }
        micSink = null
        micTrack = null
        _selfSpeaking.value = false
    }

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

    private fun setRemoteVolume(volume: Double) {
        room?.remoteParticipants?.values?.forEach { participant ->
            participant.audioTrackPublications.forEach { (_, track) ->
                (track as? RemoteAudioTrack)?.setVolume(volume)
            }
        }
    }

    private fun startCallService(camera: Boolean) {
        runCatching { CallService.start(context, camera) }
            .onFailure { Log.w(TAG, "Could not start call foreground service", it) }
    }

    companion object {
        private const val TAG = "CallManager"
    }
}

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
        val next = now - lastLoudAt <= SPEAKING_HANGOVER_MS
        if (next != speaking) {
            speaking = next
            onChange(next)
        }
    }
}

private const val SPEAKING_RMS_THRESHOLD = 0.02
private const val SPEAKING_HANGOVER_MS = 400L
