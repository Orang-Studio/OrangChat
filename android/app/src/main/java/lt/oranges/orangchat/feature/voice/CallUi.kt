package lt.oranges.orangchat.feature.voice

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Headset
import androidx.compose.material.icons.filled.HeadsetOff
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import lt.oranges.orangchat.ui.components.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import lt.oranges.orangchat.data.model.DmCall
import lt.oranges.orangchat.ui.components.Avatar
import lt.oranges.orangchat.ui.components.MenuItem
import lt.oranges.orangchat.ui.components.OrangDropdownMenu
import lt.oranges.orangchat.ui.theme.OrangRadius
import lt.oranges.orangchat.ui.theme.OrangTheme

/**
 * Full-screen ringing popup. Rendered above everything (see MainActivity) so a
 * call reaches the user whatever screen they are on.
 */
@Composable
fun IncomingCallOverlay(
    call: DmCall,
    onAccept: (video: Boolean) -> Unit,
    onDecline: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = OrangTheme.colors
    val kind = if (call.video) "video call" else "voice call"

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.85f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.padding(32.dp),
        ) {
            Text(
                text = "Incoming $kind",
                color = c.inkSecondary,
                fontSize = 14.sp,
            )
            Avatar(call.caller, size = 96.dp)
            Text(
                text = call.caller.displayName,
                color = c.ink,
                fontWeight = FontWeight.Bold,
                fontSize = 24.sp,
            )
            if (call.isGroup) {
                Text("started a $kind in this group", color = c.inkMuted, fontSize = 13.sp)
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(40.dp),
                modifier = Modifier.padding(top = 24.dp),
            ) {
                CallActionButton(
                    icon = Icons.Default.CallEnd,
                    label = "Decline",
                    background = c.danger,
                    onClick = onDecline,
                )
                CallActionButton(
                    icon = if (call.video) Icons.Default.Videocam else Icons.Default.Call,
                    label = "Accept",
                    background = c.success,
                    onClick = { onAccept(call.video) },
                )
            }
            // Answering a video call with the camera off is a normal choice.
            if (call.video) {
                Text(
                    text = "Answer without camera",
                    color = c.inkSecondary,
                    fontSize = 13.sp,
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .clickable { onAccept(false) },
                )
            }
        }
    }
}

@Composable
private fun CallActionButton(
    icon: ImageVector,
    label: String,
    background: Color,
    onClick: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .background(background, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            IconButton(onClick = onClick) {
                Icon(icon, contentDescription = label, tint = Color.White)
            }
        }
        Text(label, color = OrangTheme.colors.inkSecondary, fontSize = 12.sp,
            modifier = Modifier.padding(top = 8.dp))
    }
}

/** Why a call could not start. Auto-dismisses; a stale call error is noise. */
@Composable
fun CallErrorBanner(
    message: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = OrangTheme.colors
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .background(c.surface3, RoundedCornerShape(OrangRadius.xl2))
                .clickable(onClick = onDismiss)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(Icons.Default.CallEnd, contentDescription = null, tint = c.danger)
            Text(message, color = c.ink, fontSize = 13.sp)
        }
    }
}

/**
 * The in-call strip: status plus mute / deafen / camera / hang-up. Mirrors the
 * web client's VoicePanel.
 */
@Composable
fun CallBar(
    state: ActiveCall,
    onToggleMute: () -> Unit,
    onToggleDeafen: () -> Unit,
    onToggleCamera: () -> Unit,
    onFlipCamera: () -> Unit,
    audioOutputs: List<CallAudioOutput>,
    selectedAudioOutputId: String?,
    onSelectAudioOutput: (String) -> Unit,
    onHangUp: () -> Unit,
    onOpen: (() -> Unit)? = null,
    /** Null in DMs — the soundboard belongs to a server. */
    onSoundboard: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val c = OrangTheme.colors
    var outputMenuOpen by remember { mutableStateOf(false) }
    val selectedOutput = audioOutputs.firstOrNull { it.id == selectedAudioOutputId }
    val status = when {
        state.phase == CallPhase.OUTGOING -> "Calling…"
        state.connecting -> "Connecting…"
        state.kind == SessionKind.VOICE_CHANNEL -> "Voice connected"
        else -> "Call connected"
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(c.surface3, RoundedCornerShape(OrangRadius.xl2))
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    status,
                    color = if (state.connecting || state.phase == CallPhase.OUTGOING) c.warning else c.success,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                )
                Text(
                    state.label,
                    color = c.inkMuted,
                    fontSize = 12.sp,
                )
            }
            if (onOpen != null) {
                IconButton(onClick = onOpen, modifier = Modifier.size(40.dp)) {
                    Icon(
                        Icons.Default.KeyboardArrowUp,
                        contentDescription = "Open call",
                        tint = c.inkMuted,
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            IconButton(onClick = onToggleMute, modifier = Modifier.size(40.dp)) {
                Icon(
                    if (state.muted) Icons.Default.MicOff else Icons.Default.Mic,
                    contentDescription = if (state.muted) "Unmute" else "Mute",
                    tint = if (state.muted) c.danger else c.inkMuted,
                )
            }
            IconButton(onClick = onToggleDeafen, modifier = Modifier.size(40.dp)) {
                Icon(
                    if (state.deafened) Icons.Default.HeadsetOff else Icons.Default.Headset,
                    contentDescription = if (state.deafened) "Undeafen" else "Deafen",
                    tint = if (state.deafened) c.danger else c.inkMuted,
                )
            }
            Box {
                IconButton(
                    onClick = { outputMenuOpen = true },
                    enabled = audioOutputs.isNotEmpty(),
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.VolumeUp,
                        contentDescription = selectedOutput?.let { "Audio output: ${it.name}" }
                            ?: "Change audio output",
                        tint = if (selectedOutput != null) c.primary else c.inkMuted,
                    )
                }
                OrangDropdownMenu(
                    expanded = outputMenuOpen,
                    onDismiss = { outputMenuOpen = false },
                    items = audioOutputs.map { output ->
                        MenuItem(
                            label = output.name,
                            icon = if (output.id == selectedAudioOutputId) Icons.Default.Check
                            else Icons.AutoMirrored.Filled.VolumeUp,
                            onClick = { onSelectAudioOutput(output.id) },
                        )
                    },
                )
            }
            IconButton(onClick = onToggleCamera, modifier = Modifier.size(40.dp)) {
                Icon(
                    if (state.video) Icons.Default.Videocam else Icons.Default.VideocamOff,
                    contentDescription = if (state.video) "Turn camera off" else "Turn camera on",
                    tint = if (state.video) c.primary else c.inkMuted,
                )
            }
            if (state.video) {
                IconButton(onClick = onFlipCamera, modifier = Modifier.size(40.dp)) {
                    Icon(
                        Icons.Default.Cameraswitch,
                        contentDescription = "Flip camera",
                        tint = c.inkMuted,
                    )
                }
            }
            if (onSoundboard != null) {
                IconButton(onClick = onSoundboard, modifier = Modifier.size(40.dp)) {
                    Icon(
                        Icons.Default.MusicNote,
                        contentDescription = "Soundboard",
                        tint = c.inkMuted,
                    )
                }
            }
            IconButton(onClick = onHangUp, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Default.CallEnd, contentDescription = "Hang up", tint = c.danger)
            }
        }
    }
}
