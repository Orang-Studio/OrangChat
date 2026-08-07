package lt.oranges.orangchat.feature.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import lt.oranges.orangchat.data.model.ChannelType
import lt.oranges.orangchat.data.remote.UpdateServerRequest
import lt.oranges.orangchat.data.model.SelfUser
import lt.oranges.orangchat.data.model.ServerMember
import lt.oranges.orangchat.data.model.User
import lt.oranges.orangchat.feature.dms.HomePane
import lt.oranges.orangchat.feature.dms.NewGroupScreen
import lt.oranges.orangchat.feature.friends.FriendsScreen
import lt.oranges.orangchat.feature.invite.AddServerDialog
import lt.oranges.orangchat.feature.members.MembersScreen
import lt.oranges.orangchat.feature.voice.SoundboardSheet
import lt.oranges.orangchat.feature.roles.RolesScreen
import lt.oranges.orangchat.feature.profile.ProfileDialog
import lt.oranges.orangchat.feature.profile.ProfileRelation
import lt.oranges.orangchat.feature.invite.DeepLinkInviteDialog
import lt.oranges.orangchat.feature.qrlogin.QrLoginConfirmDialog
import lt.oranges.orangchat.feature.verify.VerifyContactDialog
import lt.oranges.orangchat.feature.settings.ScannedDeviceTransferDialog
import lt.oranges.orangchat.feature.search.SearchScreen
import lt.oranges.orangchat.feature.settings.SettingsScreen
import lt.oranges.orangchat.feature.settings.SettingsViewModel
import lt.oranges.orangchat.feature.settings.ThemeViewModel
import lt.oranges.orangchat.feature.voice.CallBar
import lt.oranges.orangchat.feature.voice.CallStage
import lt.oranges.orangchat.feature.voice.DmCallScreen
import lt.oranges.orangchat.feature.voice.SessionKind
import lt.oranges.orangchat.feature.voice.CallViewModel
import lt.oranges.orangchat.feature.voice.rememberCallPermissionGate
import lt.oranges.orangchat.notifications.hasNotificationPermission
import lt.oranges.orangchat.ui.theme.OrangTheme

private enum class Overlay { NONE, FRIENDS, NEW_GROUP, SETTINGS, SEARCH, SERVER_SETTINGS, ROLES, MEMBERS, AUDIT_LOG }

/**
 * The authenticated shell: a server rail plus a swapping content pane
 * (DM/home list, channel list, chat, friends, settings). Mobile-first - one
 * content pane at a time with back navigation, Discord-style rail on the left.
 * Once a chat is open it takes the full width and the rail plus its list move
 * into a swipe-from-the-left drawer, mirroring the web client.
 */
@Composable
fun HomeScreen(
    appViewModel: AppViewModel,
    self: SelfUser,
    /** Opened once on first composition; a bubble's conversation. */
    initialChannelId: String? = null,
    themeViewModel: ThemeViewModel = hiltViewModel(),
    callViewModel: CallViewModel = hiltViewModel(),
    settingsViewModel: SettingsViewModel = hiltViewModel(),
) {
    val servers by appViewModel.servers.collectAsStateWithLifecycle()
    val detail by appViewModel.serverDetail.collectAsStateWithLifecycle()
    val currentChannelId by appViewModel.currentChannelId.collectAsStateWithLifecycle()
    val messages by appViewModel.messages.collectAsStateWithLifecycle()
    val pendingMessageIds by appViewModel.pendingMessageIds.collectAsStateWithLifecycle()
    val failedMessageIds by appViewModel.failedMessageIds.collectAsStateWithLifecycle()
    val typing by appViewModel.typing.collectAsStateWithLifecycle()
    val presence by appViewModel.presence.collectAsStateWithLifecycle()
    val presenceDevices by appViewModel.presenceDevices.collectAsStateWithLifecycle()
    val presenceActivities by appViewModel.presenceActivities.collectAsStateWithLifecycle()
    val dms by appViewModel.dms.collectAsStateWithLifecycle()
    val friends by appViewModel.friends.collectAsStateWithLifecycle()
    val incoming by appViewModel.incomingRequests.collectAsStateWithLifecycle()
    val outgoing by appViewModel.outgoingRequests.collectAsStateWithLifecycle()
    val themePref by themeViewModel.preference.collectAsStateWithLifecycle()
    val devicePrefs by settingsViewModel.prefs.collectAsStateWithLifecycle()
    val connected by appViewModel.connected.collectAsStateWithLifecycle()
    val pendingConversation by appViewModel.pendingConversation.collectAsStateWithLifecycle()
    val pendingInvite by appViewModel.pendingInvite.collectAsStateWithLifecycle()
    val pendingQrLogin by appViewModel.pendingQrLogin.collectAsStateWithLifecycle()
    val pendingVerify by appViewModel.pendingVerify.collectAsStateWithLifecycle()
    val pendingTransfer by appViewModel.pendingTransfer.collectAsStateWithLifecycle()

    // The authenticated user object is a login-time snapshot. Presence events
    // arrive separately, so fold their latest values into every self-facing UI.
    val liveSelf = self.copy(
        status = presence[self.id] ?: self.status,
        devices = presenceDevices[self.id]?.toList() ?: self.devices,
        activities = presenceActivities[self.id] ?: self.activities,
    )

    // Who the conversation long-press menu may offer "Remove friend" for.
    val friendIds = remember(friends) { friends.map { it.user.id }.toSet() }

    // On Android 13+ posting a notification needs permission, and the system
    // dialog is a one-shot: once it is dismissed it never comes back, so a
    // reflexive "Deny" from someone who had no idea what was being asked is
    // permanent. Put the question in the app's own words first and spend that
    // single system prompt only on a yes.
    //
    // Saying no here is taken for an answer - nothing nags afterwards. The way
    // back in is Settings > Privacy > Notifications, which offers to open the
    // system screen whenever the permission is missing.
    val context = LocalContext.current
    var notifRationale by remember { mutableStateOf(false) }
    val notifPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* result handled by hasNotificationPermission at post time */ }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !hasNotificationPermission(context) &&
            !settingsViewModel.notificationPermissionAsked
        ) {
            notifRationale = true
        }
    }
    if (notifRationale) {
        val dismissRationale = {
            // Recorded on either answer, so "Not now" is not re-asked on every
            // launch - the Privacy screen is where a change of mind belongs.
            settingsViewModel.markNotificationPermissionAsked()
            notifRationale = false
        }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = dismissRationale,
            title = { androidx.compose.material3.Text("Get notified about messages?") },
            text = {
                androidx.compose.material3.Text(
                    "OrangChat can let you know when someone messages or calls you " +
                        "while the app is closed. Without this, messages only arrive " +
                        "while you have OrangChat open.",
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    dismissRationale()
                    notifPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                }) {
                    androidx.compose.material3.Text("Allow")
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = dismissRationale) {
                    androidx.compose.material3.Text("Not now")
                }
            },
        )
    }

    val activeCall by callViewModel.current.collectAsStateWithLifecycle()
    val videoTracks by callViewModel.videoTracks.collectAsStateWithLifecycle()
    val speakingIds by callViewModel.speakingIds.collectAsStateWithLifecycle()
    val audioOutputs by callViewModel.audioOutputs.collectAsStateWithLifecycle()
    val selectedAudioOutputId by callViewModel.selectedAudioOutputId.collectAsStateWithLifecycle()
    val unreads by appViewModel.unreads.collectAsStateWithLifecycle()
    val voiceParticipants by callViewModel.voiceParticipants.collectAsStateWithLifecycle()
    val emojis by appViewModel.emojis.collectAsStateWithLifecycle()
    val sounds by appViewModel.sounds.collectAsStateWithLifecycle()
    val loadingOlder by appViewModel.loadingOlder.collectAsStateWithLifecycle()
    val channelsAtStart by appViewModel.channelsAtStart.collectAsStateWithLifecycle()
    val conversationEncryption by appViewModel.conversationEncryption.collectAsStateWithLifecycle()
    val e2eeError by appViewModel.e2eeError.collectAsStateWithLifecycle()
    val error by appViewModel.error.collectAsStateWithLifecycle()
    val auditLog by appViewModel.auditLog.collectAsStateWithLifecycle()
    val auditLogLoading by appViewModel.auditLogLoading.collectAsStateWithLifecycle()
    val serverIconUploading by appViewModel.serverIconUploading.collectAsStateWithLifecycle()

    // Seed each voice channel's roster when a server opens; voice:state keeps
    // them current from then on.
    LaunchedEffect(detail?.server?.id) {
        detail?.channels
            ?.filter { it.type == ChannelType.VOICE }
            ?.map { it.id }
            ?.let { if (it.isNotEmpty()) callViewModel.seedVoiceChannels(it) }
    }
    // Starting a call or joining a voice channel both need the mic (and the
    // camera for video) before anything happens.
    var pendingCallChannel by remember { mutableStateOf<String?>(null) }
    var pendingVoiceChannel by remember { mutableStateOf<Pair<String, String>?>(null) }
    val callGate = rememberCallPermissionGate(
        onGranted = { video ->
            pendingCallChannel?.let { channelId ->
                // Answering a call that is already ringing at us goes through
                // accept rather than start: starting would put us in the room
                // with the phone still ringing at both ends.
                if (callViewModel.incoming.value?.channelId == channelId) {
                    callViewModel.accept(video)
                } else {
                    // The roster only puts a name to whoever declines; an unknown
                    // conversation still calls fine.
                    val roster = dms.firstOrNull { it.id == channelId }?.participants.orEmpty()
                    callViewModel.startCall(channelId, video, roster)
                }
            }
            pendingVoiceChannel?.let { (id, name) -> callViewModel.joinVoiceChannel(id, name, video) }
            pendingCallChannel = null
            pendingVoiceChannel = null
        },
        onMicDenied = { pendingCallChannel = null; pendingVoiceChannel = null },
    )
    val requestAndStartCall: (String, Boolean) -> Unit = { channelId, video ->
        pendingCallChannel = channelId
        pendingVoiceChannel = null
        callGate(video)
    }
    val requestAndJoinVoice: (String, String) -> Unit = { channelId, channelName ->
        pendingVoiceChannel = channelId to channelName
        pendingCallChannel = null
        callGate(devicePrefs.joinWithVideo)
    }
    val cameraGate = rememberCallPermissionGate(
        onGranted = { video ->
            if (video && activeCall?.video == false) callViewModel.toggleCamera()
        },
    )

    var homeSelected by remember { mutableStateOf(true) }
    var openChat by remember { mutableStateOf(false) }
    var overlay by remember { mutableStateOf(Overlay.NONE) }
    /** Null searches the whole selected area; otherwise search only this chat. */
    var searchChannelId by remember { mutableStateOf<String?>(null) }
    var showCreateServer by remember { mutableStateOf(false) }
    var showCreateChannel by remember { mutableStateOf(false) }
    var callExpanded by remember { mutableStateOf(false) }
    var soundboardOpen by remember { mutableStateOf(false) }
    /** Whose profile card is open, if any. */
    var profileUser by remember { mutableStateOf<User?>(null) }
    /** When set, the NEW_GROUP overlay grows this group instead of creating one. */
    var groupAddTargetId by remember { mutableStateOf<String?>(null) }
    /** A search hit the chat should land on once its channel is open. */
    var pendingJumpMessageId by remember { mutableStateOf<String?>(null) }

    // A newly started/answered DM call opens its stage. Minimizing it does not
    // touch the call session; the persistent bar below can reopen it anytime.
    LaunchedEffect(activeCall?.channelId, activeCall?.kind) {
        callExpanded = activeCall?.kind == SessionKind.CALL
    }

    // An open chat takes over the whole width - the rail would only steal room
    // from the conversation, so it moves into a swipe-from-the-left drawer,
    // matching the web client's mobile layout.
    val chatOpen = openChat && currentChannelId != null
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val closeDrawer: () -> Unit = { scope.launch { drawerState.close() }; Unit }

    // Show each error once and clear it, so the same message can be raised
    // again later - leaving it set would make a repeat of the same failure
    // look like nothing happened.
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(error) {
        val message = error ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        appViewModel.clearError()
    }

    // Leaving the chat puts the rail back on screen, so a drawer left open
    // would otherwise show it twice.
    LaunchedEffect(chatOpen) { if (!chatOpen) drawerState.close() }

    // A notification tap, a conversation shortcut or a bubble names a channel
    // and expects to land in it. Overlays and the drawer come down with it -
    // arriving behind a settings screen would read as the tap having done
    // nothing at all.
    val openConversation: suspend (String) -> Unit = { channelId ->
        appViewModel.openConversation(channelId) { isDm ->
            homeSelected = isDm
            overlay = Overlay.NONE
            searchChannelId = null
            openChat = true
        }
        drawerState.close()
    }
    LaunchedEffect(pendingConversation) {
        val channelId = pendingConversation ?: return@LaunchedEffect
        appViewModel.clearPendingConversation()
        openConversation(channelId)
    }
    // A bubble is only ever its own conversation, so it is opened directly
    // rather than through the store the rest of the app shares.
    LaunchedEffect(initialChannelId) {
        initialChannelId?.let { openConversation(it) }
    }

    // The drawer slides in over an expanded call stage. Once it settles open the
    // stage is fully hidden, so drop it then - closing the drawer should come
    // back to the conversation, and the call bar can reopen the stage anytime.
    LaunchedEffect(drawerState.isOpen) { if (drawerState.isOpen) callExpanded = false }

    BackHandler(enabled = drawerState.isOpen || overlay != Overlay.NONE || chatOpen) {
        when {
            // Ahead of the drawer: opening an overlay always closes the drawer,
            // so a still-Open drawer here is a close mid-animation. Settings
            // takes the drawer off screen outright - deferring to it would eat
            // the back press and strand the user.
            overlay != Overlay.NONE -> {
                overlay = Overlay.NONE
                groupAddTargetId = null
                searchChannelId = null
            }
            drawerState.isOpen -> closeDrawer()
            else -> {
                openChat = false
                appViewModel.clearActiveChannel()
            }
        }
    }

    val rail: @Composable () -> Unit = {
        ServerRail(
            servers = servers,
            selectedServerId = detail?.server?.id,
            homeSelected = homeSelected,
            unreads = unreads,
            onHome = {
                homeSelected = true
                openChat = false
                overlay = Overlay.NONE
                appViewModel.clearActiveChannel()
                closeDrawer()
            },
            onSelectServer = { id ->
                homeSelected = false
                openChat = false
                overlay = Overlay.NONE
                appViewModel.selectServer(id)
                closeDrawer()
            },
            onAddServer = { showCreateServer = true; closeDrawer() },
        )
    }

    val sidebar: @Composable () -> Unit = {
        when {
            homeSelected -> HomePane(
                self = liveSelf,
                conversations = dms,
                presence = presence,
                presenceActivities = presenceActivities,
                friendIds = friendIds,
                unreads = unreads,
                onOpenFriends = { overlay = Overlay.FRIENDS; closeDrawer() },
                onOpenConversation = { convo ->
                    appViewModel.selectChannel(convo.id)
                    openChat = true
                    closeDrawer()
                },
                onOpenSettings = { overlay = Overlay.SETTINGS; closeDrawer() },
                onSearch = {
                    searchChannelId = null
                    overlay = Overlay.SEARCH
                    closeDrawer()
                },
                onNewGroup = { groupAddTargetId = null; overlay = Overlay.NEW_GROUP; closeDrawer() },
                onMarkRead = appViewModel::markChannelRead,
                onOpenProfile = { profileUser = it },
                // Starting from the list has no call UI of its own to lean on,
                // so it goes through the same permission gate as the chat header.
                onStartCall = { convo -> requestAndStartCall(convo.id, false) },
                onRemoveFriend = appViewModel::removeFriend,
                onLeaveConversation = { convo -> appViewModel.leaveConversation(convo.id) },
            )

            detail != null -> ChannelListPane(
                detail = detail!!,
                self = liveSelf,
                currentChannelId = currentChannelId,
                unreads = unreads,
                voiceParticipants = voiceParticipants,
                memberNames = detail!!.members.associate {
                    it.userId to (it.nickname ?: it.user.displayName)
                },
                onSelectChannel = { channel ->
                    when (channel.type) {
                        // A voice channel is joined, not opened as a chat.
                        ChannelType.VOICE -> {
                            requestAndJoinVoice(channel.id, channel.name ?: "voice")
                            closeDrawer()
                        }
                        ChannelType.CATEGORY -> Unit
                        else -> {
                            appViewModel.selectChannel(channel.id)
                            openChat = true
                            closeDrawer()
                        }
                    }
                },
                onMarkRead = { channel -> appViewModel.markChannelRead(channel.id) },
                onAddChannel = { showCreateChannel = true; closeDrawer() },
                onSearch = {
                    searchChannelId = null
                    overlay = Overlay.SEARCH
                    closeDrawer()
                },
                onServerSettings = { overlay = Overlay.SERVER_SETTINGS; closeDrawer() },
                onOpenUserSettings = { overlay = Overlay.SETTINGS; closeDrawer() },
            )
        }
    }

    // Pinned under the content so call controls stay reachable from any screen
    // while a call is up.
    val callDock: @Composable () -> Unit = {
        activeCall?.takeIf {
            it.kind == SessionKind.VOICE_CHANNEL ||
                (it.kind == SessionKind.CALL && !callExpanded)
        }?.let { call ->
            if (call.kind == SessionKind.VOICE_CHANNEL) {
                CallStage(
                    tracks = videoTracks,
                    room = callViewModel.room,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }
            CallBar(
                state = call,
                onToggleMute = callViewModel::toggleMute,
                onToggleDeafen = callViewModel::toggleDeafen,
                onToggleCamera = {
                    if (call.video) callViewModel.toggleCamera() else cameraGate(true)
                },
                onFlipCamera = callViewModel::flipCamera,
                audioOutputs = audioOutputs,
                selectedAudioOutputId = selectedAudioOutputId,
                onSelectAudioOutput = callViewModel::selectAudioOutput,
                onHangUp = callViewModel::hangUp,
                onOpen = if (call.kind == SessionKind.CALL) {
                    { callExpanded = true }
                } else {
                    null
                },
                // Only server voice channels have a soundboard; the server
                // rejects a DM channel anyway.
                onSoundboard = if (call.kind == SessionKind.VOICE_CHANNEL) {
                    { soundboardOpen = true }
                } else {
                    null
                },
                modifier = Modifier.padding(8.dp),
            )
        }
    }

    if (soundboardOpen) {
        SoundboardSheet(
            sounds = sounds,
            onDismiss = { soundboardOpen = false },
            onPlay = { sound -> callViewModel.playSound(sound.id) },
        )
    }

    // Settings is a takeover: it owns the whole window, rail and sidebar
    // included. An expanded call still outranks it - the call stage is the one
    // thing a user needs to get back to immediately.
    val settingsTakeover = overlay == Overlay.SETTINGS &&
        !(activeCall?.kind == SessionKind.CALL && callExpanded)

    if (settingsTakeover) {
        Column(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                SettingsScreen(
                    self = liveSelf,
                    themePreference = themePref,
                    onThemeChange = themeViewModel::setPreference,
                    onStatusChange = appViewModel::updateStatus,
                    onBack = { overlay = Overlay.NONE },
                    onLogout = appViewModel::logout,
                    connected = connected,
                )
            }
            callDock()
        }
    } else {
        ModalNavigationDrawer(
            drawerState = drawerState,
            // Only when the chat is full-width; otherwise the rail is already there.
            gesturesEnabled = chatOpen && overlay == Overlay.NONE,
            drawerContent = {
                ModalDrawerSheet(
                    drawerShape = RectangleShape,
                    drawerContainerColor = OrangTheme.colors.surface1,
                    modifier = Modifier.fillMaxWidth(0.86f),
                ) {
                    Row(modifier = Modifier.fillMaxSize()) {
                        rail()
                        Box(modifier = Modifier.weight(1f).fillMaxSize()) { sidebar() }
                    }
                }
            },
        ) {
            Row(modifier = Modifier.fillMaxSize()) {
                if (!chatOpen) rail()

                Column(modifier = Modifier.weight(1f).fillMaxSize()) {
                    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        when {
                            activeCall?.kind == SessionKind.CALL && callExpanded -> {
                                val call = activeCall!!
                                val conversation = dms.firstOrNull { it.id == call.channelId }
                                val callUsers = conversation?.participants
                                    ?: listOfNotNull(self.asUser(), call.call?.caller).distinctBy { it.id }
                                val callTitle = conversation?.let { cv ->
                                    cv.name ?: cv.participants
                                        .filter { it.id != self.id }
                                        .joinToString(", ") { it.displayName }
                                } ?: call.label
                                DmCallScreen(
                                    state = call,
                                    title = callTitle,
                                    users = callUsers,
                                    tracks = videoTracks,
                                    speakingIds = speakingIds,
                                    voiceStates = voiceParticipants[call.channelId].orEmpty(),
                                    selfId = self.id,
                                    room = callViewModel.room,
                                    onToggleMute = callViewModel::toggleMute,
                                    onToggleDeafen = callViewModel::toggleDeafen,
                                    onToggleCamera = {
                                        if (call.video) callViewModel.toggleCamera() else cameraGate(true)
                                    },
                                    onFlipCamera = callViewModel::flipCamera,
                                    audioOutputs = audioOutputs,
                                    selectedAudioOutputId = selectedAudioOutputId,
                                    onSelectAudioOutput = callViewModel::selectAudioOutput,
                                    onHangUp = callViewModel::hangUp,
                                    onMinimize = { callExpanded = false },
                                )
                            }

                            overlay == Overlay.ROLES && detail != null -> RolesScreen(
                                detail = detail!!,
                                selfId = self.id,
                                onBack = { overlay = Overlay.SERVER_SETTINGS },
                                onCreate = { name -> appViewModel.createRole(detail!!.server.id, name) },
                                onSave = { roleId, patch -> appViewModel.updateRole(detail!!.server.id, roleId, patch) },
                                onDelete = { roleId -> appViewModel.deleteRole(detail!!.server.id, roleId) },
                            )

                            overlay == Overlay.MEMBERS && detail != null -> MembersScreen(
                                detail = detail!!,
                                selfId = self.id,
                                presence = presence,
                                presenceDevices = presenceDevices,
                                presenceActivities = presenceActivities,
                                onBack = { overlay = Overlay.SERVER_SETTINGS },
                                onSetNickname = { userId, nick ->
                                    appViewModel.setNickname(detail!!.server.id, userId, nick)
                                },
                                onAssignRole = { userId, roleId ->
                                    appViewModel.assignRole(detail!!.server.id, userId, roleId)
                                },
                                onUnassignRole = { userId, roleId ->
                                    appViewModel.unassignRole(detail!!.server.id, userId, roleId)
                                },
                                onTimeout = { userId, seconds ->
                                    appViewModel.timeoutMember(detail!!.server.id, userId, seconds)
                                },
                                onLiftTimeout = { userId -> appViewModel.liftTimeout(detail!!.server.id, userId) },
                                onKick = { userId -> appViewModel.kickMember(detail!!.server.id, userId) },
                                onBan = { userId -> appViewModel.banMember(detail!!.server.id, userId) },
                            )

                            overlay == Overlay.AUDIT_LOG && detail != null -> AuditLogScreen(
                                entries = auditLog,
                                loading = auditLogLoading,
                                onBack = { overlay = Overlay.SERVER_SETTINGS },
                                onLoad = { appViewModel.loadAuditLog(detail!!.server.id) },
                            )

                            overlay == Overlay.SERVER_SETTINGS && detail != null -> ServerSettingsScreen(
                                detail = detail!!,
                                selfId = self.id,
                                onBack = { overlay = Overlay.NONE },
                                onRename = { name -> appViewModel.renameServer(detail!!.server.id, name) },
                                onSaveDescription = { text ->
                                    appViewModel.updateServerSettings(
                                        detail!!.server.id,
                                        UpdateServerRequest(description = text),
                                    )
                                },
                                iconUploading = serverIconUploading,
                                onUploadIcon = { uri ->
                                    appViewModel.uploadServerIcon(detail!!.server.id, uri)
                                },
                                onRemoveIcon = { appViewModel.removeServerIcon(detail!!.server.id) },
                                onOpenRoles = { overlay = Overlay.ROLES },
                                onOpenMembers = { overlay = Overlay.MEMBERS },
                                onOpenAuditLog = { overlay = Overlay.AUDIT_LOG },
                                onCreateInvite = { onCode -> appViewModel.createInvite(detail!!.server.id, onCode) },
                                onLeave = {
                                    appViewModel.leaveServer(detail!!.server.id) {
                                        overlay = Overlay.NONE
                                        homeSelected = true
                                        openChat = false
                                    }
                                },
                                onDelete = {
                                    appViewModel.deleteServer(detail!!.server.id) {
                                        overlay = Overlay.NONE
                                        homeSelected = true
                                        openChat = false
                                    }
                                },
                            )

                            overlay == Overlay.SEARCH -> {
                                val localDmSearch = homeSelected
                                val searchableChannels = searchChannelId?.let { setOf(it) } ?: if (localDmSearch) {
                                    dms.mapTo(mutableSetOf()) { it.id }
                                } else {
                                    detail?.channels
                                        ?.filter { it.type == ChannelType.TEXT }
                                        ?.mapTo(mutableSetOf()) { it.id }
                                        .orEmpty()
                                }
                                val searchChannelNames = if (localDmSearch) {
                                    dms.associate { convo ->
                                        convo.id to (
                                            convo.name
                                                ?: convo.participants
                                                    .filter { it.id != self.id }
                                                    .joinToString(", ") { it.displayName }
                                                    .ifBlank { "Direct Message" }
                                            )
                                    }
                                } else {
                                    detail?.channels
                                        ?.associate { it.id to (it.name ?: "channel") }
                                        .orEmpty()
                                }
                                val searchAuthors = (
                                    listOf(self.asUser()) +
                                        dms.flatMap { it.participants } +
                                        detail?.members.orEmpty().map { it.user }
                                    ).associateBy { it.id }
                                SearchScreen(
                                    serverId = if (localDmSearch) null else detail?.server?.id,
                                    channelIds = searchableChannels,
                                    channelNames = searchChannelNames,
                                    authors = searchAuthors,
                                    onBack = {
                                        overlay = Overlay.NONE
                                        searchChannelId = null
                                    },
                                    onJumpToMessage = { channelId, messageId ->
                                        overlay = Overlay.NONE
                                        searchChannelId = null
                                        pendingJumpMessageId = messageId
                                        appViewModel.selectChannel(channelId)
                                        openChat = true
                                    },
                                )
                            }

                            overlay == Overlay.FRIENDS -> FriendsScreen(
                                friends = friends,
                                incoming = incoming,
                                outgoing = outgoing,
                                presence = presence,
                                presenceDevices = presenceDevices,
                                presenceActivities = presenceActivities,
                                onBack = { overlay = Overlay.NONE },
                                onAdd = { appViewModel.sendFriendRequest(it) },
                                onAccept = appViewModel::acceptRequest,
                                onDecline = appViewModel::declineRequest,
                                onRemove = appViewModel::removeFriend,
                                onMessage = { userId ->
                                    appViewModel.openDmWith(userId) { overlay = Overlay.NONE; homeSelected = true; openChat = true }
                                },
                            )

                            overlay == Overlay.NEW_GROUP -> {
                                val addTarget = groupAddTargetId?.let { id -> dms.firstOrNull { it.id == id } }
                                NewGroupScreen(
                                    friends = friends,
                                    presence = presence,
                                    addMode = addTarget != null,
                                    excludeUserIds = addTarget?.participants?.map { it.id }?.toSet() ?: emptySet(),
                                    // 15-person cap: the seats a grow can still fill.
                                    maxSelection = addTarget?.let {
                                        (15 - it.participants.size).coerceAtLeast(0)
                                    } ?: 14,
                                    onBack = { overlay = Overlay.NONE; groupAddTargetId = null },
                                    onConfirm = { userIds ->
                                        val target = addTarget
                                        if (target != null) {
                                            appViewModel.addGroupParticipants(target.id, userIds) {
                                                overlay = Overlay.NONE
                                                groupAddTargetId = null
                                            }
                                        } else {
                                            appViewModel.createGroupDm(userIds) {
                                                overlay = Overlay.NONE
                                                homeSelected = true
                                                openChat = true
                                            }
                                        }
                                    },
                                )
                            }

                            openChat && currentChannelId != null -> {
                                val channelId = currentChannelId!!
                                val chan = detail?.channels?.firstOrNull { it.id == channelId }
                                val convo = dms.firstOrNull { it.id == channelId }
                                val title = chan?.name
                                    ?: convo?.let { cv -> cv.name ?: cv.participants.filter { it.id != self.id }.joinToString(", ") { it.displayName } }
                                    ?: "Chat"
                                LaunchedEffect(channelId, convo?.participants) {
                                    if (convo != null) {
                                        appViewModel.loadConversationEncryption(
                                            channelId,
                                            convo.participants.filter { it.id != self.id }.map { it.id },
                                            convo.type == ChannelType.GROUP_DM,
                                        )
                                    }
                                }
                                lt.oranges.orangchat.feature.chat.ChatPane(
                                    title = title,
                                    topic = chan?.topic,
                                    channelId = channelId,
                                    messages = messages[channelId].orEmpty(),
                                    pendingMessageIds = pendingMessageIds,
                                    failedMessageIds = failedMessageIds,
                                    onRetryMessage = appViewModel::retryFailedMessage,
                                    onDiscardMessage = appViewModel::discardFailedMessage,
                                    selfId = self.id,
                                    // A DM's mentionable people are its
                                    // participants, not the members of whatever
                                    // server happens to be selected behind it.
                                    members = convo?.participants?.map { u ->
                                        ServerMember(
                                            id = u.id,
                                            serverId = "",
                                            userId = u.id,
                                            user = u,
                                        )
                                    } ?: detail?.members.orEmpty(),
                                    presence = presence,
                                    typingUserIds = (typing[channelId].orEmpty() - self.id),
                                    onBack = { openChat = false; appViewModel.clearActiveChannel() },
                                    onSend = { content, replyTo, attachmentIds, sealedAttachments ->
                                        appViewModel.sendMessage(
                                            channelId,
                                            content,
                                            replyTo,
                                            attachmentIds,
                                            sealedAttachments,
                                        )
                                    },
                                     onEdit = { id, content, done ->
                                         appViewModel.editMessage(channelId, id, content, done)
                                     },
                                    onDelete = { id -> appViewModel.deleteMessage(channelId, id) },
                                    onReport = { message, reason, done ->
                                        appViewModel.reportMessage(message, reason, done)
                                    },
                                    onReact = { message, emoji -> appViewModel.toggleReaction(channelId, message, emoji) },
                                    onTyping = { appViewModel.startTyping(channelId) },
                                    onSearch = {
                                        searchChannelId = channelId
                                        overlay = Overlay.SEARCH
                                    },
                                    onLoadOlder = { appViewModel.loadOlderMessages(channelId) },
                                    loadingOlder = channelId in loadingOlder,
                                    hasOlder = channelId !in channelsAtStart,
                                    jumpToMessageId = pendingJumpMessageId,
                                    onJumpHandled = { pendingJumpMessageId = null },
                                    compact = devicePrefs.compactMessages,
                                    reducedMotion = devicePrefs.reducedMotion,
                                    // Only DMs and group DMs can be called.
                                    onStartCall = if (convo != null) {
                                        { video -> requestAndStartCall(channelId, video) }
                                    } else {
                                        null
                                    },
                                    // Only group DMs can grow; the button opens the friend picker.
                                    onAddPeople = if (convo?.type == ChannelType.GROUP_DM) {
                                        { groupAddTargetId = channelId; overlay = Overlay.NEW_GROUP }
                                    } else {
                                        null
                                    },
                                    onCall = activeCall?.channelId == channelId,
                                    headerUser = convo?.participants?.firstOrNull { it.id != self.id },
                                    headerActivities = convo?.participants
                                        ?.firstOrNull { it.id != self.id }
                                        ?.let { presenceActivities[it.id] ?: it.activities }
                                        .orEmpty(),
                                    onOpenProfile = { profileUser = it },
                                    emojis = emojis,
                                    encryptionInfo = conversationEncryption[channelId],
                                    onResetEncryption = {
                                        appViewModel.resetConversationEncryption(channelId)
                                    },
                                    onSetStrict = if (convo?.type == ChannelType.DM) {
                                        { enabled ->
                                            appViewModel.setConversationStrict(channelId, enabled)
                                        }
                                    } else {
                                        null
                                    },
                                    backgroundUrl = convo?.backgroundUrl,
                                    onSetBackground = if (convo != null) {
                                        { uri -> appViewModel.setDmBackground(channelId, uri) }
                                    } else {
                                        null
                                    },
                                    onRemoveBackground = if (convo != null) {
                                        { appViewModel.clearDmBackground(channelId) }
                                    } else {
                                        null
                                    },
                                    // Groups get the same comparison, but §6.3
                                    // keeps their number informational: a match
                                    // confirms the membership and pins nothing.
                                    onCompareSafetyNumber = if (convo != null) {
                                        { typed, done ->
                                            appViewModel.compareSafetyNumber(
                                                channelId,
                                                convo.participants
                                                    .filter { it.id != self.id }
                                                    .map { it.id },
                                                convo.type == ChannelType.GROUP_DM,
                                                typed,
                                                done,
                                            )
                                        }
                                    } else {
                                        null
                                    },
                                    onVerifyContact = if (convo?.type == ChannelType.DM) {
                                        { raw, expectedUserId, done ->
                                            appViewModel.verifyScannedContactFor(
                                                raw,
                                                expectedUserId,
                                            ) { ok, error ->
                                                if (ok) {
                                                    appViewModel.loadConversationEncryption(
                                                        channelId,
                                                        convo.participants
                                                            .filter { it.id != self.id }
                                                            .map { it.id },
                                                        false,
                                                    )
                                                }
                                                done(ok, error)
                                            }
                                        }
                                    } else {
                                        null
                                    },
                                )
                            }

                            else -> sidebar()
                        }
                    }

                    callDock()
                }
            }
        }
    }

    // Every failed action in the AppViewModel writes `error`, and until now
    // nothing read it - a refresh, an upload or a join could fail in total
    // silence. One host sits over the whole shell (settings takeover included,
    // since that is rendered from here too) and speaks each one once.
    //
    // MainActivity already pads the whole tree by WindowInsets.safeDrawing, so
    // this deliberately adds no inset padding of its own.
    Box(modifier = Modifier.fillMaxSize()) {
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp),
        ) { data ->
            Snackbar(
                snackbarData = data,
                containerColor = OrangTheme.colors.surface3,
                contentColor = OrangTheme.colors.ink,
            )
        }
    }

    profileUser?.let { target ->
        // Relationship decides which action the card offers. An outgoing request
        // reads as PENDING; an incoming one is left as STRANGER so the button
        // stays "Add friend", which the server resolves into an accept.
        val relation = when {
            target.id == self.id -> ProfileRelation.SELF
            friends.any { it.user.id == target.id } -> ProfileRelation.FRIEND
            outgoing.any { it.user.id == target.id } -> ProfileRelation.PENDING
            else -> ProfileRelation.STRANGER
        }
        val liveTarget = target.copy(
            status = presence[target.id] ?: target.status,
            devices = presenceDevices[target.id]?.toList() ?: target.devices,
            activities = presenceActivities[target.id] ?: target.activities,
        )
        ProfileDialog(
            user = liveTarget,
            relation = relation,
            presence = presence[target.id],
            onDismiss = { profileUser = null },
            onMessage = {
                profileUser = null
                appViewModel.openDmWith(target.id) {
                    overlay = Overlay.NONE
                    homeSelected = true
                    openChat = true
                }
            },
            onAddFriend = {
                appViewModel.sendFriendRequest(target.username)
                profileUser = null
            },
            onRemoveFriend = {
                appViewModel.removeFriend(target.id)
                profileUser = null
            },
        )
    }

    if (showCreateServer) {
        AddServerDialog(
            onDismiss = { showCreateServer = false },
            onCreate = { name ->
                appViewModel.createServer(name); showCreateServer = false; homeSelected = false
            },
            onJoined = { server ->
                appViewModel.serverJoined(server.id); showCreateServer = false; homeSelected = false
            },
        )
    }

    // An invite link tapped outside the app. It waits in the store through the
    // whole sign-in flow if need be, so this is the first moment it can be shown.
    pendingInvite?.let { code ->
        DeepLinkInviteDialog(
            code = code,
            onDismiss = { appViewModel.clearPendingInvite() },
            onJoined = { server ->
                appViewModel.clearPendingInvite()
                appViewModel.serverJoined(server.id)
                homeSelected = false
            },
        )
    }

    // A web sign-in QR scanned into the app. Only reachable here, in the signed-in
    // shell, since approving a web session needs this phone's own account.
    pendingQrLogin?.let { token ->
        QrLoginConfirmDialog(token = token, appViewModel = appViewModel)
    }

    // Somebody's contact code, scanned with the phone's camera. Pinning their
    // identity only means anything once this phone is signed in, so like the
    // sign-in code above it is only ever raised here.
    pendingVerify?.let { code ->
        VerifyContactDialog(raw = code, appViewModel = appViewModel)
    }
    pendingTransfer?.let { code ->
        ScannedDeviceTransferDialog(
            raw = code,
            onDismiss = appViewModel::clearPendingTransfer,
        )
    }
    e2eeError?.let { error ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = {},
            title = { androidx.compose.material3.Text("Encryption security alert") },
            text = {
                androidx.compose.material3.Text(
                    "$error\n\nMessaging is blocked until this identity or device-log change is resolved.",
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = appViewModel::clearE2eeError) {
                    androidx.compose.material3.Text("I understand")
                }
            },
        )
    }
    if (showCreateChannel && detail != null) {
        CreateEntityDialog(
            title = "Create a channel",
            label = "Channel name",
            onDismiss = { showCreateChannel = false },
            onConfirm = { name -> appViewModel.createChannel(detail!!.server.id, name, "text"); showCreateChannel = false },
        )
    }
}
