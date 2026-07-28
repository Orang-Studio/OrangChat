package lt.oranges.orangchat.feature.voice

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.lifecycle.HiltViewModel
import lt.oranges.orangchat.data.model.User
import javax.inject.Inject

/** Thin Compose-facing wrapper over the singleton [CallManager]. */
@HiltViewModel
class CallViewModel @Inject constructor(
    private val callManager: CallManager,
    private val voiceParticipantStore: VoiceParticipantStore,
) : ViewModel() {
    val incoming = callManager.incoming
    val current = callManager.current
    val error = callManager.error
    val notice = callManager.notice
    val videoTracks = callManager.videoTracks
    val speakingIds = callManager.speakingIds
    val audioOutputs = callManager.audioOutputs
    val selectedAudioOutputId = callManager.selectedAudioOutputId

    /** channelId -> who is in that voice channel. */
    val voiceParticipants = voiceParticipantStore.participants

    /** The live LiveKit room, needed by VideoTrackView to render tiles. */
    val room get() = callManager.room

    fun seedVoiceChannels(channelIds: List<String>) = voiceParticipantStore.seedAll(channelIds)

    fun playSound(soundId: String) = callManager.playSound(soundId)

    fun joinVoiceChannel(channelId: String, channelName: String, video: Boolean = false) =
        callManager.joinVoiceChannel(channelId, channelName, video)

    fun startCall(channelId: String, video: Boolean, roster: List<User> = emptyList()) =
        callManager.startCall(channelId, video, roster)
    fun accept(video: Boolean) = callManager.acceptCall(video)
    fun decline() = callManager.declineCall()
    fun hangUp() = callManager.hangUp()
    fun toggleMute() = callManager.toggleMute()
    fun toggleDeafen() = callManager.toggleDeafen()
    fun toggleCamera() = callManager.toggleCamera()
    fun flipCamera() = callManager.flipCamera()
    fun selectAudioOutput(id: String) = callManager.selectAudioOutput(id)
    fun dismissError() = callManager.dismissError()
    fun dismissNotice() = callManager.dismissNotice()
}

private fun callPermissions(video: Boolean): Array<String> = buildList {
    add(Manifest.permission.RECORD_AUDIO)
    if (video) add(Manifest.permission.CAMERA)
    // Optional: denial never blocks the call, but Bluetooth routes cannot be
    // discovered on Android 12+ without it.
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        add(Manifest.permission.BLUETOOTH_CONNECT)
    }
}.toTypedArray()

/**
 * Returns a `launch(video)` function that secures mic (and camera) access before
 * running [onGranted]. RECORD_AUDIO is declared in the manifest but was never
 * requested at runtime until calls existed, so this is the first ask.
 */
@Composable
fun rememberCallPermissionGate(
    onGranted: (video: Boolean) -> Unit,
    onMicDenied: () -> Unit = {},
): (Boolean) -> Unit {
    val context = LocalContext.current
    var wantedVideo by remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        if (grants[Manifest.permission.RECORD_AUDIO] == true) {
            // Camera refused on a video call still leaves a usable voice call.
            onGranted(wantedVideo && grants[Manifest.permission.CAMERA] == true)
        } else {
            onMicDenied()
        }
    }

    return { video ->
        wantedVideo = video
        val needed = callPermissions(video)
        val granted = needed.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
        if (granted) onGranted(video) else launcher.launch(needed)
    }
}

/**
 * App-wide call surface: the ringing popup. Mounted in MainActivity above the
 * whole nav tree so it shows over any screen, and over the lockscreen via the
 * full-screen intent that NotificationHelper raises alongside it.
 */
@Composable
fun CallHost(modifier: Modifier = Modifier) {
    val vm: CallViewModel = hiltViewModel()
    val incoming by vm.incoming.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()

    val requestAndAccept = rememberCallPermissionGate(
        onGranted = { video -> vm.accept(video) },
        // Without a microphone there is no call to answer.
        onMicDenied = { vm.decline() },
    )

    incoming?.let { call ->
        IncomingCallOverlay(
            call = call,
            onAccept = { video -> requestAndAccept(video) },
            onDecline = { vm.decline() },
            modifier = modifier,
        )
    }

    // "Already on a call", "Everyone else is busy" - otherwise the ack error is
    // swallowed and tapping call just appears to do nothing. An outright failure
    // to start is the more actionable of the two, so it wins the banner slot.
    val banner = error ?: notice?.message
    val dismiss = if (error != null) vm::dismissError else vm::dismissNotice
    banner?.let { message ->
        LaunchedEffect(message) {
            delay(6_000)
            dismiss()
        }
        CallErrorBanner(message = message, onDismiss = dismiss)
    }
}
