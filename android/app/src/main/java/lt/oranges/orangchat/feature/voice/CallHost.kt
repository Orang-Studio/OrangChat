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

    val voiceParticipants = voiceParticipantStore.participants

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
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        add(Manifest.permission.BLUETOOTH_CONNECT)
    }
}.toTypedArray()

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

@Composable
fun CallHost(modifier: Modifier = Modifier) {
    val vm: CallViewModel = hiltViewModel()
    val incoming by vm.incoming.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()

    val requestAndAccept = rememberCallPermissionGate(
        onGranted = { video -> vm.accept(video) },
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
