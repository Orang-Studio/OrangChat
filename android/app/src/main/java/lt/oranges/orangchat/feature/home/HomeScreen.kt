package lt.oranges.orangchat.feature.home
import lt.oranges.orangchat.util.AppStrings
import lt.oranges.orangchat.R
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
import android.content.Intent
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import lt.oranges.orangchat.data.model.Channel
import lt.oranges.orangchat.data.model.ChannelType
import lt.oranges.orangchat.data.model.Hierarchy
import lt.oranges.orangchat.data.model.Permissions
import lt.oranges.orangchat.data.model.PresenceStatus
import lt.oranges.orangchat.data.model.hasPermission
import lt.oranges.orangchat.data.remote.UpdateServerRequest
import lt.oranges.orangchat.data.model.SelfUser
import lt.oranges.orangchat.data.model.ServerMember
import lt.oranges.orangchat.data.model.User
import lt.oranges.orangchat.feature.chat.ChannelDetailsScreen
import lt.oranges.orangchat.feature.dms.HomePane
import lt.oranges.orangchat.feature.dms.NewGroupScreen
import lt.oranges.orangchat.feature.friends.FriendsScreen
import lt.oranges.orangchat.feature.invite.AddServerDialog
import lt.oranges.orangchat.feature.members.MembersScreen
import lt.oranges.orangchat.feature.unread.unreadCountExcluding
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
import lt.oranges.orangchat.notifications.isActiveMute
import lt.oranges.orangchat.util.InviteLink
import lt.oranges.orangchat.ui.theme.OrangTheme

private enum class Overlay { NONE, FRIENDS, NEW_GROUP, SETTINGS, SEARCH, SERVER_SETTINGS, ROLES, MEMBERS, AUDIT_LOG, CHANNEL_DETAILS }

@Composable
fun HomeScreen(
    appViewModel: AppViewModel,
    self: SelfUser,
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
    val online by appViewModel.online.collectAsStateWithLifecycle()
    val pendingConversation by appViewModel.pendingConversation.collectAsStateWithLifecycle()
    val pendingInvite by appViewModel.pendingInvite.collectAsStateWithLifecycle()
    val pendingQrLogin by appViewModel.pendingQrLogin.collectAsStateWithLifecycle()
    val pendingVerify by appViewModel.pendingVerify.collectAsStateWithLifecycle()
    val pendingTransfer by appViewModel.pendingTransfer.collectAsStateWithLifecycle()

    val liveSelf = self.copy(
        status = presence[self.id] ?: self.status,
        devices = presenceDevices[self.id]?.toList() ?: self.devices,
        activities = presenceActivities[self.id] ?: self.activities,
    )

    val friendIds = remember(friends) { friends.map { it.user.id }.toSet() }

    val context = LocalContext.current
    var notifRationale by remember { mutableStateOf(false) }
    val notifPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }
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
            settingsViewModel.markNotificationPermissionAsked()
            notifRationale = false
        }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = dismissRationale,
            title = { androidx.compose.material3.Text(AppStrings.get(context, R.string.catalog_get_notified_about_messages_d4bc1f2e)) },
            text = {
                androidx.compose.material3.Text(
                    AppStrings.get(context, R.string.catalog_orangchat_can_let_you_know_when_someone_13d0aac2) +
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
                    androidx.compose.material3.Text(AppStrings.get(context, R.string.catalog_not_now_e4571490))
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
    val mutes by appViewModel.mutes.collectAsStateWithLifecycle()
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

    LaunchedEffect(detail?.server?.id) {
        detail?.channels
            ?.filter { it.type == ChannelType.VOICE }
            ?.map { it.id }
            ?.let { if (it.isNotEmpty()) callViewModel.seedVoiceChannels(it) }
    }
    var pendingCallChannel by remember { mutableStateOf<String?>(null) }
    var pendingVoiceChannel by remember { mutableStateOf<Pair<String, String>?>(null) }
    val callGate = rememberCallPermissionGate(
        onGranted = { video ->
            pendingCallChannel?.let { channelId ->
                if (callViewModel.incoming.value?.channelId == channelId) {
                    callViewModel.accept(video)
                } else {
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
    var searchChannelId by remember { mutableStateOf<String?>(null) }
    var showCreateServer by remember { mutableStateOf(false) }
    var showCreateChannel by remember { mutableStateOf(false) }
    var callExpanded by remember { mutableStateOf(false) }
    var soundboardOpen by remember { mutableStateOf(false) }
    var profileUser by remember { mutableStateOf<User?>(null) }
    var editChannelTarget by remember { mutableStateOf<Channel?>(null) }
    val myPerms = detail?.let { Hierarchy.effectivePermissions(it, self.id) } ?: 0L
    val canManageChannels = myPerms.hasPermission(Permissions.MANAGE_CHANNELS)
    val canInvite = myPerms.hasPermission(Permissions.MANAGE_INVITES)
    var groupAddTargetId by remember { mutableStateOf<String?>(null) }
    var pendingJumpMessageId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(activeCall?.channelId, activeCall?.kind) {
        callExpanded = activeCall?.kind == SessionKind.CALL
    }

    val chatOpen = openChat && currentChannelId != null
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val closeDrawer: () -> Unit = { scope.launch { drawerState.close() }; Unit }

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(error) {
        val message = error ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        appViewModel.clearError()
    }

    LaunchedEffect(chatOpen) { if (!chatOpen) drawerState.close() }

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
    LaunchedEffect(initialChannelId) {
        initialChannelId?.let { openConversation(it) }
    }

    LaunchedEffect(drawerState.isOpen) { if (drawerState.isOpen) callExpanded = false }

    BackHandler(enabled = drawerState.isOpen || overlay != Overlay.NONE || chatOpen) {
        when {
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
            mutes = mutes,
            onMute = appViewModel::mute,
            onUnmute = appViewModel::unmute,
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
                online = online,
                conversations = dms,
                presence = presence,
                presenceDevices = presenceDevices,
                presenceActivities = presenceActivities,
                friendIds = friendIds,
                activeConversationId = currentChannelId,
                unreads = unreads,
                typing = typing,
                mutes = mutes,
                onMute = appViewModel::mute,
                onUnmute = appViewModel::unmute,
                onOpenFriends = { overlay = Overlay.FRIENDS; closeDrawer() },
                onOpenConversation = { convo ->
                    appViewModel.selectChannel(convo.id)
                    openChat = true
                    closeDrawer()
                },
                onOpenSettings = { overlay = Overlay.SETTINGS; closeDrawer() },
                onStatusChange = appViewModel::updateStatus,
                onSearch = {
                    searchChannelId = null
                    overlay = Overlay.SEARCH
                    closeDrawer()
                },
                onNewGroup = { groupAddTargetId = null; overlay = Overlay.NEW_GROUP; closeDrawer() },
                onMarkRead = appViewModel::markChannelRead,
                onOpenProfile = { profileUser = it },
                onStartCall = { convo -> requestAndStartCall(convo.id, false) },
                onRemoveFriend = appViewModel::removeFriend,
                onLeaveConversation = { convo -> appViewModel.leaveConversation(convo.id) },
            )

            detail != null -> ChannelListPane(
                detail = detail!!,
                self = liveSelf,
                online = online,
                currentChannelId = currentChannelId,
                unreads = unreads,
                voiceParticipants = voiceParticipants,
                memberNames = detail!!.members.associate {
                    it.userId to (it.nickname ?: it.user.displayName)
                },
                onSelectChannel = { channel ->
                    when (channel.type) {
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
                onStatusChange = appViewModel::updateStatus,
                onAddChannel = { showCreateChannel = true; closeDrawer() },
                onSearch = {
                    searchChannelId = null
                    overlay = Overlay.SEARCH
                    closeDrawer()
                },
                onServerSettings = { overlay = Overlay.SERVER_SETTINGS; closeDrawer() },
                onOpenUserSettings = { overlay = Overlay.SETTINGS; closeDrawer() },
                mutes = mutes,
                onMute = appViewModel::mute,
                onUnmute = appViewModel::unmute,
                onEditChannel = if (canManageChannels) {
                    { channel -> editChannelTarget = channel; closeDrawer() }
                } else {
                    null
                },
            )
        }
    }

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
                                    presenceDevices = presenceDevices,
                                    addMode = addTarget != null,
                                    excludeUserIds = addTarget?.participants?.map { it.id }?.toSet() ?: emptySet(),
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

                            overlay == Overlay.CHANNEL_DETAILS && currentChannelId != null -> {
                                val channelId = currentChannelId!!
                                val chan = detail?.channels?.firstOrNull { it.id == channelId }
                                val convo = dms.firstOrNull { it.id == channelId }
                                val voice = chan?.type == ChannelType.VOICE
                                ChannelDetailsScreen(
                                    title = chan?.name
                                        ?: convo?.let { cv ->
                                            cv.name ?: cv.participants
                                                .filter { it.id != self.id }
                                                .joinToString(", ") { it.displayName }
                                        }
                                        ?: "Chat",
                                    kindLabel = when {
                                        convo?.type == ChannelType.GROUP_DM -> "Group"
                                        convo != null -> "Direct Message"
                                        voice -> "Voice Channel"
                                        else -> "Text Channel"
                                    },
                                    topic = chan?.topic,
                                    messages = messages[channelId].orEmpty(),
                                    members = convo?.participants?.map { u ->
                                        ServerMember(id = u.id, serverId = "", userId = u.id, user = u)
                                    } ?: detail?.members.orEmpty(),
                                    onBack = { overlay = Overlay.NONE },
                                    onSearch = {
                                        searchChannelId = channelId
                                        overlay = Overlay.SEARCH
                                    },
                                    iconUrl = convo?.iconUrl,
                                    headerUser = convo
                                        ?.takeIf { it.type == ChannelType.DM }
                                        ?.participants
                                        ?.firstOrNull { it.id != self.id },
                                    voice = voice,
                                    roles = detail?.roles.orEmpty(),
                                    presence = presence,
                                    presenceDevices = presenceDevices,
                                    presenceActivities = presenceActivities,
                                    muted = mutes[channelId].isActiveMute(),
                                    onMute = { duration -> appViewModel.mute(channelId, duration) },
                                    onUnmute = { appViewModel.unmute(channelId) },
                                    onOpenSettings = if (chan != null && canManageChannels) {
                                        { editChannelTarget = chan }
                                    } else {
                                        null
                                    },
                                    onInvite = if (chan != null && canInvite && detail != null) {
                                        {
                                            appViewModel.createInvite(detail!!.server.id) { code ->
                                                val send = Intent(Intent.ACTION_SEND).apply {
                                                    type = "text/plain"
                                                    putExtra(Intent.EXTRA_TEXT, InviteLink.urlFor(code))
                                                }
                                                context.startActivity(
                                                    Intent.createChooser(send, "Share invite"),
                                                )
                                            }
                                        }
                                    } else {
                                        null
                                    },
                                    onOpenProfile = { profileUser = it },
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
                                    onSetIcon = if (convo?.type == ChannelType.GROUP_DM) {
                                        { uri -> appViewModel.setDmIcon(channelId, uri) }
                                    } else {
                                        null
                                    },
                                    onRemoveIcon = if (convo?.type == ChannelType.GROUP_DM) {
                                        { appViewModel.clearDmIcon(channelId) }
                                    } else {
                                        null
                                    },
                                    onAddPeople = if (convo?.type == ChannelType.GROUP_DM) {
                                        { groupAddTargetId = channelId; overlay = Overlay.NEW_GROUP }
                                    } else {
                                        null
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
                                    members = convo?.participants?.map { u ->
                                        ServerMember(
                                            id = u.id,
                                            serverId = "",
                                            userId = u.id,
                                            user = u,
                                        )
                                    } ?: detail?.members.orEmpty(),
                                    presence = presence,
                                    presenceDevices = presenceDevices,
                                    typingUserIds = (typing[channelId].orEmpty() - self.id),
                                    onBack = { openChat = false; appViewModel.clearActiveChannel() },
                                    missedCount = unreads.unreadCountExcluding(channelId),
                                    onSend = { content, replyTo, attachmentIds, sealedAttachments, awaitUploads ->
                                        appViewModel.sendMessage(
                                            channelId,
                                            content,
                                            replyTo,
                                            attachmentIds,
                                            sealedAttachments,
                                            awaitUploads,
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
                                    onOpenDetails = { overlay = Overlay.CHANNEL_DETAILS },
                                    onlineCount = when {
                                        convo != null -> convo.participants.count {
                                            (presence[it.id] ?: PresenceStatus.OFFLINE) != PresenceStatus.OFFLINE
                                        }
                                        chan != null -> detail?.members?.count {
                                            (presence[it.userId] ?: PresenceStatus.OFFLINE) != PresenceStatus.OFFLINE
                                        }
                                        else -> null
                                    },
                                    onLoadOlder = { appViewModel.loadOlderMessages(channelId) },
                                    loadingOlder = channelId in loadingOlder,
                                    hasOlder = channelId !in channelsAtStart,
                                    jumpToMessageId = pendingJumpMessageId,
                                    onJumpHandled = { pendingJumpMessageId = null },
                                    compact = devicePrefs.compactMessages,
                                    reducedMotion = devicePrefs.reducedMotion,
                                    onStartCall = if (convo != null) {
                                        { video -> requestAndStartCall(channelId, video) }
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
                                    emojiServers = servers,
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
                                    iconUrl = convo?.iconUrl,
                                    isGroup = convo?.type == ChannelType.GROUP_DM,
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

    editChannelTarget?.let { channel ->
        ChannelSettingsSheet(
            channel = channel,
            onDismiss = { editChannelTarget = null },
            onSave = { patch ->
                detail?.let { d -> appViewModel.updateChannelSettings(d.server.id, channel.id, patch) }
            },
        )
    }

    profileUser?.let { target ->
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

    pendingQrLogin?.let { token ->
        QrLoginConfirmDialog(token = token, appViewModel = appViewModel)
    }

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
            title = { androidx.compose.material3.Text(AppStrings.get(context, R.string.catalog_encryption_security_alert_7b6e7671)) },
            text = {
                androidx.compose.material3.Text(
                    "$error\n\nMessaging is blocked until this identity or device-log change is resolved.",
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = appViewModel::clearE2eeError) {
                    androidx.compose.material3.Text(AppStrings.get(context, R.string.catalog_i_understand_842a9bdd))
                }
            },
        )
    }
    if (showCreateChannel && detail != null) {
        CreateEntityDialog(
            title = AppStrings.get(context, R.string.catalog_create_a_channel_3ac48642),
            label = AppStrings.get(context, R.string.catalog_channel_name_3be87bdd),
            onDismiss = { showCreateChannel = false },
            onConfirm = { name -> appViewModel.createChannel(detail!!.server.id, name, "text"); showCreateChannel = false },
        )
    }
}
