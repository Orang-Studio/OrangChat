package lt.oranges.orangchat.feature.voice
import lt.oranges.orangchat.util.AppStrings
import androidx.compose.ui.platform.LocalContext
import lt.oranges.orangchat.R
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import lt.oranges.orangchat.ui.components.Text
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.HeadsetOff
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.livekit.android.compose.ui.VideoTrackView
import io.livekit.android.room.Room
import lt.oranges.orangchat.data.model.User
import lt.oranges.orangchat.data.model.VoiceState
import lt.oranges.orangchat.ui.components.Avatar
import lt.oranges.orangchat.ui.theme.OrangRadius
import lt.oranges.orangchat.ui.theme.OrangTheme

/**
 * Dedicated DM call screen. It shows everybody in the conversation, separating
 * connected people from those still ringing, and highlights active speakers.
 */
@Composable
fun DmCallScreen(
    state: ActiveCall,
    title: String,
    users: List<User>,
    tracks: List<CallVideoTrack>,
    speakingIds: Set<String>,
    voiceStates: Map<String, VoiceState>,
    selfId: String,
    room: Room?,
    onToggleMute: () -> Unit,
    onToggleDeafen: () -> Unit,
    onToggleCamera: () -> Unit,
    onFlipCamera: () -> Unit,
    audioOutputs: List<CallAudioOutput>,
    selectedAudioOutputId: String?,
    onSelectAudioOutput: (String) -> Unit,
    onHangUp: () -> Unit,
    onMinimize: () -> Unit,
    onSoundboard: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
        val context = LocalContext.current
    val c = OrangTheme.colors
    val roster = state.call
    val connected = roster?.participants.orEmpty().toSet()
    val ringing = roster?.ringing.orEmpty().toSet()
    val trackById = tracks.associateBy { it.identity }

    /** Whose camera is blown up, if any. */
    var focusedId by remember { mutableStateOf<String?>(null) }
    val focusedTrack = focusedId?.let { trackById[it] }
    // Their camera going off (or them leaving) takes the fullscreen view with
    // it, rather than stranding a black rectangle over the call.
    LaunchedEffect(focusedTrack) { if (focusedId != null && focusedTrack == null) focusedId = null }

    // Back closes the expanded camera first - minimizing the whole call out from
    // under it would be the wrong thing to undo.
    BackHandler { if (focusedId != null) focusedId = null else onMinimize() }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(c.surface1)
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (state.phase == CallPhase.OUTGOING) "CALLING" else AppStrings.get(context, R.string.catalog_call_connected_78e88c19),
                    color = if (state.phase == CallPhase.OUTGOING) c.warning else c.success,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp,
                )
                Text(title, color = c.ink, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text(
                    text = "${connected.size} in call · ${ringing.size} ringing",
                    color = c.inkMuted,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 2.dp, bottom = 14.dp),
                )
            }
            IconButton(onClick = onMinimize) {
                Icon(
                    Icons.Default.KeyboardArrowDown,
                    contentDescription = AppStrings.get(context, R.string.catalog_minimize_call_6cb0f0f8),
                    tint = c.inkSecondary,
                )
            }
        }

        LazyVerticalGrid(
            columns = GridCells.Adaptive(150.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.weight(1f),
        ) {
            items(users, key = { it.id }) { user ->
                // Our own switches are authoritative here: the roster echo of
                // them arrives a round-trip late, and lagging our own button is
                // more jarring than lagging someone else's.
                val voice = voiceStates[user.id]
                val isSelf = user.id == selfId
                ParticipantTile(
                    user = user,
                    track = trackById[user.id],
                    room = room,
                    onExpand = { focusedId = user.id },
                    speaking = user.id in speakingIds,
                    muted = if (isSelf) state.muted else voice?.muted == true,
                    deafened = if (isSelf) state.deafened else voice?.deafened == true,
                    status = when (user.id) {
                        in connected -> ParticipantStatus.CONNECTED
                        in ringing -> ParticipantStatus.RINGING
                        else -> ParticipantStatus.WAITING
                    },
                )
            }
        }

        CallBar(
            state = state,
            onToggleMute = onToggleMute,
            onToggleDeafen = onToggleDeafen,
            onToggleCamera = onToggleCamera,
            onFlipCamera = onFlipCamera,
            audioOutputs = audioOutputs,
            selectedAudioOutputId = selectedAudioOutputId,
            onSelectAudioOutput = onSelectAudioOutput,
            onHangUp = onHangUp,
            onSoundboard = onSoundboard,
            modifier = Modifier.padding(top = 12.dp),
        )
    }

    if (focusedTrack != null && room != null) {
        FocusedVideo(focusedTrack, room) { focusedId = null }
    }
}

private enum class ParticipantStatus { CONNECTED, RINGING, WAITING }

/**
 * Draw everything inside desaturated. Compose has no grayscale modifier, and a
 * ColorFilter on the leaf would miss the video surface, so the whole subtree is
 * composited into an offscreen layer and the matrix applied to that.
 */
private fun Modifier.grayscale(): Modifier = this.then(
    Modifier.drawWithCache {
        val paint = Paint().apply {
            colorFilter = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })
        }
        onDrawWithContent {
            drawIntoCanvas { canvas ->
                canvas.saveLayer(Rect(Offset.Zero, size), paint)
                drawContent()
                canvas.restore()
            }
        }
    },
)

@Composable
private fun ParticipantTile(
    user: User,
    track: CallVideoTrack?,
    room: Room?,
    speaking: Boolean,
    muted: Boolean,
    deafened: Boolean,
    status: ParticipantStatus,
    onExpand: () -> Unit = {},
) {
        val context = LocalContext.current
    val c = OrangTheme.colors
    val shape = RoundedCornerShape(OrangRadius.xl2)
    val statusText = when {
        speaking -> "Speaking"
        status == ParticipantStatus.CONNECTED -> AppStrings.get(context, R.string.catalog_in_call_07d16f43)
        status == ParticipantStatus.RINGING -> AppStrings.get(context, R.string.catalog_ringing_417814b1)
        else -> AppStrings.get(context, R.string.catalog_not_in_call_32010878)
    }
    val statusColor = when {
        speaking || status == ParticipantStatus.CONNECTED -> c.success
        status == ParticipantStatus.RINGING -> c.warning
        else -> c.inkMuted
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1.15f)
            .then(
                if (speaking) Modifier.shadow(
                    elevation = 16.dp,
                    shape = shape,
                    ambientColor = c.success,
                    spotColor = c.success,
                ) else Modifier,
            )
            .clip(shape)
            .background(c.surface2)
            .border(if (speaking) 3.dp else 1.dp, if (speaking) c.success else c.border, shape)
            // Only a camera is worth opening; an avatar looks the same at any size.
            .then(if (track != null) Modifier.clickable(onClick = onExpand) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        // Anyone not actually in the call yet is drained of colour and dimmed, so
        // a glance at the stage says who is present without reading the pills.
        val pending = status != ParticipantStatus.CONNECTED
        Box(
            modifier = Modifier.fillMaxSize().then(if (pending) Modifier.grayscale() else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            if (track != null && room != null) {
                VideoTrackView(
                    track.track,
                    Modifier.fillMaxSize(),
                    room,
                    track.isLocal,
                )
            } else {
                Avatar(user = user, size = 84.dp, status = user.status)
            }
        }
        // A wash rather than alpha: it darkens the tile without also fading the
        // name and status text layered over it.
        if (pending) {
            Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)))
        }

        // Muted and deafened are independent - you can be deafened and still
        // talk - so neither icon stands in for the other.
        if (muted || deafened) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (muted) VoiceStateBadge(Icons.Default.MicOff, "Muted")
                if (deafened) VoiceStateBadge(Icons.Default.HeadsetOff, "Deafened")
            }
        }

        Row(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.68f))
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = user.displayName,
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Text(statusText, color = statusColor, fontSize = 11.sp)
        }
    }
}

/** One mic-off / headset-off pill, legible over a camera tile or an avatar. */
@Composable
private fun VoiceStateBadge(icon: ImageVector, label: String) {
    Box(
        modifier = Modifier
            .size(26.dp)
            .background(Color.Black.copy(alpha = 0.68f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = OrangTheme.colors.danger,
            modifier = Modifier.size(15.dp),
        )
    }
}

/**
 * One camera filling the screen, over everything else.
 *
 * A LiveKit track renders into as many views as you like, so this is a second
 * view of the same track rather than a handover - the grid tile underneath keeps
 * playing and is still there on the way back.
 */
@Composable
private fun FocusedVideo(track: CallVideoTrack, room: Room, onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center,
        ) {
            VideoTrackView(track.track, Modifier.fillMaxSize(), room, track.isLocal)
            Text(
                text = track.name,
                color = Color.White,
                fontSize = 13.sp,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(24.dp)
                    .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(OrangRadius.xl))
                    .padding(horizontal = 12.dp, vertical = 5.dp),
            )
        }
    }
}

/** Small video grid retained for server voice channels. */
@Composable
fun CallStage(
    tracks: List<CallVideoTrack>,
    room: Room?,
    modifier: Modifier = Modifier,
) {
    if (tracks.isEmpty() || room == null) return

    var focusedKey by remember { mutableStateOf<String?>(null) }
    val focusedTrack = tracks.firstOrNull { "${it.identity}:${it.isLocal}" == focusedKey }
    LaunchedEffect(focusedTrack) { if (focusedKey != null && focusedTrack == null) focusedKey = null }

    LazyVerticalGrid(
        columns = GridCells.Fixed(if (tracks.size == 1) 1 else 2),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier.fillMaxWidth().padding(horizontal = 8.dp),
    ) {
        items(tracks, key = { "${it.identity}:${it.isLocal}" }) { tile ->
            Box(
                modifier = Modifier
                    .aspectRatio(4f / 3f)
                    .clip(RoundedCornerShape(OrangRadius.xl2))
                    .background(Color.Black)
                    .clickable { focusedKey = "${tile.identity}:${tile.isLocal}" },
            ) {
                VideoTrackView(
                    tile.track,
                    Modifier.fillMaxSize(),
                    room,
                    tile.isLocal,
                )
                Text(
                    text = tile.name,
                    color = Color.White,
                    fontSize = 11.sp,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(4.dp)
                        .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(OrangRadius.sm))
                        .padding(horizontal = 5.dp, vertical = 2.dp),
                )
            }
        }
    }

    if (focusedTrack != null) {
        FocusedVideo(focusedTrack, room) { focusedKey = null }
    }
}
