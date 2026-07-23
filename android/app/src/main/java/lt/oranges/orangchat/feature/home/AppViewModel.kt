package lt.oranges.orangchat.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import lt.oranges.orangchat.data.model.Channel
import lt.oranges.orangchat.data.model.ChannelType
import lt.oranges.orangchat.data.model.Conversation
import lt.oranges.orangchat.data.model.Friend
import lt.oranges.orangchat.data.model.FriendRequest
import lt.oranges.orangchat.data.model.Message
import lt.oranges.orangchat.data.model.PresenceDevice
import lt.oranges.orangchat.data.model.PresenceStatus
import lt.oranges.orangchat.data.model.UserActivity
import lt.oranges.orangchat.data.model.Reaction
import lt.oranges.orangchat.data.model.Server
import lt.oranges.orangchat.data.model.ServerDetail
import lt.oranges.orangchat.data.model.ServerMember
import lt.oranges.orangchat.data.model.Sound
import lt.oranges.orangchat.data.remote.PatchChannelRequest
import lt.oranges.orangchat.data.remote.UpdateRoleRequest
import lt.oranges.orangchat.data.remote.UpdateServerRequest
import lt.oranges.orangchat.data.repository.AuthRepository
import lt.oranges.orangchat.data.repository.ServerRepository
import lt.oranges.orangchat.data.repository.SessionState
import lt.oranges.orangchat.data.repository.SocialRepository
import lt.oranges.orangchat.feature.invite.PendingInviteStore
import lt.oranges.orangchat.feature.share.PendingShareStore
import lt.oranges.orangchat.feature.unread.UnreadStore
import lt.oranges.orangchat.notifications.NotificationHelper
import lt.oranges.orangchat.realtime.SocketEvent
import lt.oranges.orangchat.realtime.SocketManager
import lt.oranges.orangchat.util.AppForegroundState
import lt.oranges.orangchat.util.EmojiRef
import lt.oranges.orangchat.util.Mentions
import java.time.Instant
import java.util.UUID
import javax.inject.Inject

/**
 * Activity-scoped store for the authenticated app: server rail, channel/member
 * lists, per-channel message caches, DMs, friends, presence and typing. Applies
 * live Socket.IO events so the UI stays in sync with the backend.
 */
@HiltViewModel
class AppViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val serverRepository: ServerRepository,
    private val socialRepository: SocialRepository,
    private val socketManager: SocketManager,
    private val notificationHelper: NotificationHelper,
    private val unreadStore: UnreadStore,
    private val pendingInviteStore: PendingInviteStore,
    private val pendingShareStore: PendingShareStore,
) : ViewModel() {

    /** Unread dots + mention badges; hydrated on login, then kept live. */
    val unreads = unreadStore.states

    /** An invite link the app was opened with, once there's a shell to show it. */
    val pendingInvite = pendingInviteStore.code
    val pendingShare = pendingShareStore.share

    fun clearPendingInvite() = pendingInviteStore.consume()
    fun clearPendingShare() = pendingShareStore.consume()

    private val _loadingOlder = MutableStateFlow<Set<String>>(emptySet())
    /** Channels with an older-page fetch in flight. */
    val loadingOlder: StateFlow<Set<String>> = _loadingOlder.asStateFlow()

    /** Channels whose history we have reached the start of. */
    private val exhaustedChannels = mutableSetOf<String>()

    val session: StateFlow<SessionState> = authRepository.session

    private val _servers = MutableStateFlow<List<Server>>(emptyList())
    val servers: StateFlow<List<Server>> = _servers.asStateFlow()

    private val _serverDetail = MutableStateFlow<ServerDetail?>(null)
    val serverDetail: StateFlow<ServerDetail?> = _serverDetail.asStateFlow()

    private val _currentChannelId = MutableStateFlow<String?>(null)
    val currentChannelId: StateFlow<String?> = _currentChannelId.asStateFlow()

    private val _messages = MutableStateFlow<Map<String, List<Message>>>(emptyMap())
    val messages: StateFlow<Map<String, List<Message>>> = _messages.asStateFlow()

    /** Local optimistic rows that have not yet been confirmed by the server. */
    private val _pendingMessageIds = MutableStateFlow<Set<String>>(emptySet())
    val pendingMessageIds: StateFlow<Set<String>> = _pendingMessageIds.asStateFlow()

    private data class PendingOutgoing(
        val localId: String,
        val channelId: String,
        val content: String,
        val replyToId: String?,
        val attachmentIds: List<String>,
    )

    private val pendingOutbox = mutableListOf<PendingOutgoing>()
    private var outboxJob: Job? = null

    private val _typing = MutableStateFlow<Map<String, Set<String>>>(emptyMap())
    val typing: StateFlow<Map<String, Set<String>>> = _typing.asStateFlow()

    private val _presence = MutableStateFlow<Map<String, PresenceStatus>>(emptyMap())
    val presence: StateFlow<Map<String, PresenceStatus>> = _presence.asStateFlow()

    private val _presenceDevices = MutableStateFlow<Map<String, Set<PresenceDevice>>>(emptyMap())
    val presenceDevices: StateFlow<Map<String, Set<PresenceDevice>>> = _presenceDevices.asStateFlow()

    private val _presenceActivities = MutableStateFlow<Map<String, List<UserActivity>>>(emptyMap())
    val presenceActivities: StateFlow<Map<String, List<UserActivity>>> = _presenceActivities.asStateFlow()

    private val _dms = MutableStateFlow<List<Conversation>>(emptyList())
    val dms: StateFlow<List<Conversation>> = _dms.asStateFlow()

    private val _friends = MutableStateFlow<List<Friend>>(emptyList())
    val friends: StateFlow<List<Friend>> = _friends.asStateFlow()

    private val _incomingRequests = MutableStateFlow<List<FriendRequest>>(emptyList())
    val incomingRequests: StateFlow<List<FriendRequest>> = _incomingRequests.asStateFlow()

    private val _outgoingRequests = MutableStateFlow<List<FriendRequest>>(emptyList())
    val outgoingRequests: StateFlow<List<FriendRequest>> = _outgoingRequests.asStateFlow()

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _emojis = MutableStateFlow<Map<String, EmojiRef>>(emptyMap())
    val emojis: StateFlow<Map<String, EmojiRef>> = _emojis.asStateFlow()

    private val _sounds = MutableStateFlow<List<Sound>>(emptyList())
    val sounds: StateFlow<List<Sound>> = _sounds.asStateFlow()

    init {
        observeSocket()
    }

    fun bootstrap() {
        viewModelScope.launch { authRepository.restoreSession() }
    }

    /** Load everything the home shell needs once authenticated. */
    fun loadInitialData() {
        refreshServers()
        refreshDms()
        refreshFriends()
        refreshUnreads()
        refreshEmojis()
    }

    fun refreshUnreads() = viewModelScope.launch {
        runCatching { serverRepository.getUnreads() }
            .onSuccess { unreadStore.hydrate(it) }
    }

    fun refreshServers() = viewModelScope.launch {
        runCatching { serverRepository.listServers() }
            .onSuccess { _servers.value = it }
            .onFailure { _error.value = it.message }
    }

    fun refreshDms() = viewModelScope.launch {
        runCatching { socialRepository.listDms() }.onSuccess { _dms.value = it }
    }

    /**
     * Every emoji the viewer can type, across all their servers — messages carry
     * ids, so a DM can legitimately show an emoji from a shared server.
     */
    fun refreshEmojis() = viewModelScope.launch {
        runCatching { serverRepository.listUsableEmojis() }
            .onSuccess { list ->
                _emojis.value = list.associate {
                    it.id to EmojiRef(it.id, it.name, it.url, it.animated)
                }
            }
    }

    fun refreshSounds(serverId: String) = viewModelScope.launch {
        runCatching { serverRepository.listSounds(serverId) }
            .onSuccess { _sounds.value = it }
            .onFailure { _sounds.value = emptyList() }
    }

    fun refreshFriends() = viewModelScope.launch {
        runCatching { socialRepository.listFriends() }.onSuccess { list ->
            _friends.value = list
            _presence.update { m -> m + list.associate { it.user.id to it.user.status } }
            _presenceDevices.update { m -> m + list.associate { it.user.id to it.user.devices.toSet() } }
            _presenceActivities.update { m -> m + list.associate { it.user.id to it.user.activities } }
        }
        runCatching { socialRepository.listRequests() }.onSuccess {
            _incomingRequests.value = it.incoming
            _outgoingRequests.value = it.outgoing
        }
    }

    fun selectServer(serverId: String) = viewModelScope.launch {
        _serverDetail.value = null
        _sounds.value = emptyList()
        runCatching { serverRepository.getServer(serverId) }
            .onSuccess { detail ->
                _serverDetail.value = detail
                _presence.update { m -> m + detail.members.associate { it.user.id to it.user.status } }
                _presenceDevices.update { m -> m + detail.members.associate { it.user.id to it.user.devices.toSet() } }
                _presenceActivities.update { m -> m + detail.members.associate { it.user.id to it.user.activities } }
                detail.channels.firstOrNull { it.type == ChannelType.TEXT }?.let { selectChannel(it.id) }
                refreshSounds(serverId)
            }
            .onFailure { _error.value = it.message }
    }

    fun selectChannel(channelId: String) {
        _currentChannelId.value = channelId
        socketManager.joinChannel(channelId)
        if (_messages.value[channelId] == null) loadHistory(channelId)
        // Opening a channel reads it, locally and on the server.
        unreadStore.setActiveChannel(channelId)
        notificationHelper.clearConversationNotifications(channelId)
        viewModelScope.launch { runCatching { serverRepository.markChannelRead(channelId) } }
    }

    /** The chat pane closed; activity in that channel counts as unread again. */
    fun clearActiveChannel() {
        unreadStore.setActiveChannel(null)
    }

    /**
     * Fetch the page before the oldest message we hold. Channels we have read to
     * the end of are remembered so scrolling up cannot re-request forever.
     */
    fun loadOlderMessages(channelId: String) = viewModelScope.launch {
        if (channelId in _loadingOlder.value || channelId in exhaustedChannels) return@launch
        val oldest = _messages.value[channelId]?.firstOrNull() ?: return@launch
        _loadingOlder.update { it + channelId }
        runCatching { serverRepository.getHistory(channelId, before = oldest.id) }
            .onSuccess { page ->
                if (page.items.isEmpty()) {
                    exhaustedChannels += channelId
                } else {
                    _messages.update { m ->
                        m + (channelId to (page.items.reversed() + m[channelId].orEmpty()))
                    }
                }
            }
        _loadingOlder.update { it - channelId }
    }

    fun loadHistory(channelId: String) = viewModelScope.launch {
        runCatching { serverRepository.getHistory(channelId) }
            .onSuccess { page ->
                // History comes newest-first from the cursor API. Keep any
                // optimistic rows created while this request was in flight.
                _messages.update { current ->
                    val pending = current[channelId].orEmpty()
                        .filter { it.id in _pendingMessageIds.value }
                    current + (channelId to (page.items.reversed() + pending))
                }
            }
            .onFailure { _error.value = it.message }
    }

    fun sendMessage(
        channelId: String,
        content: String,
        replyToId: String? = null,
        attachmentIds: List<String> = emptyList(),
    ) {
        val author = authRepository.currentUser?.asUser() ?: return
        val localId = "pending:${UUID.randomUUID()}"
        val pending = PendingOutgoing(localId, channelId, content, replyToId, attachmentIds)
        pendingOutbox += pending
        _pendingMessageIds.update { it + localId }
        _messages.update { current ->
            val optimistic = Message(
                id = localId,
                channelId = channelId,
                author = author,
                content = content,
                createdAt = Instant.now().toString(),
                replyToId = replyToId,
            )
            current + (channelId to (current[channelId].orEmpty() + optimistic))
        }
        flushPendingMessages()
    }

    /** Send queued rows in order. Disconnecting cancels the active ack wait; the
     * same row is tried again after the next connection event. */
    private fun flushPendingMessages() {
        if (!_connected.value || !socketManager.isConnected || outboxJob?.isActive == true) return
        outboxJob = viewModelScope.launch {
            try {
                while (_connected.value && socketManager.isConnected) {
                    val pending = pendingOutbox.firstOrNull() ?: break
                    val result = runCatching {
                        socketManager.sendMessage(
                            pending.channelId,
                            pending.content,
                            pending.replyToId,
                            pending.attachmentIds,
                        )
                    }
                    result.onSuccess { sent ->
                        confirmPendingMessage(pending.localId, sent)
                        pendingOutbox.removeAll { it.localId == pending.localId }
                        unreadStore.markRead(pending.channelId)
                        notificationHelper.clearConversationNotifications(pending.channelId)
                    }.onFailure { error ->
                        if (!_connected.value || !socketManager.isConnected) return@launch
                        // A live server rejection (permissions, slowmode, etc.)
                        // will not improve after reconnecting; remove that row
                        // and surface the actual error instead of retrying it.
                        rejectPendingMessage(pending.localId)
                        pendingOutbox.removeAll { it.localId == pending.localId }
                        _error.value = error.message ?: "Failed to send"
                    }
                }
            } finally {
                outboxJob = null
                // A message can be queued after the loop observes an empty
                // outbox but before this job completes. Do not leave that race
                // waiting for a future reconnect that may never happen.
                if (_connected.value && pendingOutbox.isNotEmpty()) flushPendingMessages()
            }
        }
    }

    private fun confirmPendingMessage(localId: String, sent: Message) {
        _pendingMessageIds.update { it - localId }
        _messages.update { current ->
            val existing = current[sent.channelId].orEmpty()
            val withoutConfirmedCopy = existing.filterNot { it.id == sent.id }
            val replaced = withoutConfirmedCopy.map { if (it.id == localId) sent else it }
            current + (sent.channelId to if (replaced.any { it.id == sent.id }) replaced else replaced + sent)
        }
    }

    private fun rejectPendingMessage(localId: String) {
        _pendingMessageIds.update { it - localId }
        _messages.update { current ->
            current.mapValues { (_, messages) -> messages.filterNot { it.id == localId } }
        }
    }

    fun editMessage(channelId: String, messageId: String, content: String) = viewModelScope.launch {
        runCatching { socketManager.editMessage(channelId, messageId, content) }
            .onFailure { _error.value = it.message }
    }

    fun deleteMessage(channelId: String, messageId: String) = viewModelScope.launch {
        runCatching { socketManager.deleteMessage(channelId, messageId) }
            .onFailure { _error.value = it.message }
    }

    fun toggleReaction(channelId: String, message: Message, emoji: String) {
        val mine = message.reactions.any { it.emoji == emoji && it.me }
        if (mine) socketManager.removeReaction(channelId, message.id, emoji)
        else socketManager.addReaction(channelId, message.id, emoji)
    }

    fun startTyping(channelId: String) = socketManager.startTyping(channelId)

    fun createServer(name: String) = viewModelScope.launch {
        runCatching { serverRepository.createServer(name) }
            .onSuccess { refreshServers(); selectServer(it.id) }
            .onFailure { _error.value = it.message }
    }

    /** Adopt a server just joined by invite — the rail hasn't heard of it yet. */
    fun serverJoined(serverId: String) {
        refreshServers()
        selectServer(serverId)
    }

    fun renameServer(serverId: String, name: String) = viewModelScope.launch {
        runCatching { serverRepository.updateServer(serverId, name = name) }
            .onSuccess { refreshServers(); selectServer(serverId) }
            .onFailure { _error.value = it.message }
    }

    /**
     * Reload the detail in place. Unlike selectServer this keeps the open
     * channel, so a role or member edit does not bounce the user elsewhere.
     */
    fun refreshServerDetail(serverId: String) = viewModelScope.launch {
        runCatching { serverRepository.getServer(serverId) }
            .onSuccess { detail ->
                _serverDetail.value = detail
                _presence.update { m -> m + detail.members.associate { it.user.id to it.user.status } }
            }
            .onFailure { _error.value = it.message }
    }

    fun updateServerSettings(serverId: String, patch: UpdateServerRequest) = viewModelScope.launch {
        runCatching { serverRepository.updateServerSettings(serverId, patch) }
            .onSuccess { refreshServers(); refreshServerDetail(serverId) }
            .onFailure { _error.value = it.message }
    }

    fun updateChannelSettings(serverId: String, channelId: String, patch: PatchChannelRequest) = viewModelScope.launch {
        runCatching { serverRepository.patchChannel(channelId, patch) }
            .onSuccess { refreshServerDetail(serverId) }
            .onFailure { _error.value = it.message }
    }

    // Roles
    fun createRole(serverId: String, name: String) = viewModelScope.launch {
        runCatching { serverRepository.createRole(serverId, name, null, null) }
            .onSuccess { refreshServerDetail(serverId) }
            .onFailure { _error.value = it.message }
    }

    fun updateRole(serverId: String, roleId: String, patch: UpdateRoleRequest) = viewModelScope.launch {
        runCatching { serverRepository.updateRole(serverId, roleId, patch) }
            .onSuccess { refreshServerDetail(serverId) }
            .onFailure { _error.value = it.message }
    }

    fun deleteRole(serverId: String, roleId: String) = viewModelScope.launch {
        runCatching { serverRepository.deleteRole(serverId, roleId) }
            .onSuccess { refreshServerDetail(serverId) }
            .onFailure { _error.value = it.message }
    }

    // Members
    fun assignRole(serverId: String, userId: String, roleId: String) = viewModelScope.launch {
        runCatching { serverRepository.assignRole(serverId, userId, roleId) }
            .onSuccess { refreshServerDetail(serverId) }
            .onFailure { _error.value = it.message }
    }

    fun unassignRole(serverId: String, userId: String, roleId: String) = viewModelScope.launch {
        runCatching { serverRepository.unassignRole(serverId, userId, roleId) }
            .onSuccess { refreshServerDetail(serverId) }
            .onFailure { _error.value = it.message }
    }

    fun setNickname(serverId: String, userId: String, nickname: String?) = viewModelScope.launch {
        runCatching { serverRepository.setNickname(serverId, userId, nickname) }
            .onSuccess { refreshServerDetail(serverId) }
            .onFailure { _error.value = it.message }
    }

    fun timeoutMember(serverId: String, userId: String, durationSeconds: Long) = viewModelScope.launch {
        runCatching { serverRepository.timeoutMember(serverId, userId, durationSeconds) }
            .onSuccess { refreshServerDetail(serverId) }
            .onFailure { _error.value = it.message }
    }

    fun liftTimeout(serverId: String, userId: String) = viewModelScope.launch {
        runCatching { serverRepository.liftTimeout(serverId, userId) }
            .onSuccess { refreshServerDetail(serverId) }
            .onFailure { _error.value = it.message }
    }

    fun kickMember(serverId: String, userId: String) = viewModelScope.launch {
        runCatching { serverRepository.kickMember(serverId, userId) }
            .onSuccess { refreshServerDetail(serverId) }
            .onFailure { _error.value = it.message }
    }

    fun banMember(serverId: String, userId: String) = viewModelScope.launch {
        runCatching { serverRepository.banMember(serverId, userId) }
            .onSuccess { refreshServerDetail(serverId) }
            .onFailure { _error.value = it.message }
    }

    /** Leave a server, then fall back to the DM home. */
    fun leaveServer(serverId: String, onDone: () -> Unit = {}) = viewModelScope.launch {
        runCatching { serverRepository.leaveServer(serverId) }
            .onSuccess {
                _serverDetail.value = null
                _currentChannelId.value = null
                refreshServers()
                onDone()
            }
            .onFailure { _error.value = it.message }
    }

    fun deleteServer(serverId: String, onDone: () -> Unit = {}) = viewModelScope.launch {
        runCatching { serverRepository.deleteServer(serverId) }
            .onSuccess {
                _serverDetail.value = null
                _currentChannelId.value = null
                refreshServers()
                onDone()
            }
            .onFailure { _error.value = it.message }
    }

    /** Mint an invite code for sharing. */
    fun createInvite(serverId: String, onCode: (String) -> Unit) = viewModelScope.launch {
        runCatching { serverRepository.createInvite(serverId) }
            .onSuccess { onCode(it.code) }
            .onFailure { _error.value = it.message }
    }

    fun joinInvite(code: String) = viewModelScope.launch {
        runCatching { serverRepository.joinInvite(code) }
            .onSuccess { refreshServers(); selectServer(it.id) }
            .onFailure { _error.value = it.message }
    }

    fun createChannel(serverId: String, name: String, type: String) = viewModelScope.launch {
        runCatching { serverRepository.createChannel(serverId, name, type) }
            .onFailure { _error.value = it.message }
    }

    // ── friends actions ─────────────────────────────────
    fun sendFriendRequest(username: String, onDone: (Boolean) -> Unit = {}) = viewModelScope.launch {
        runCatching { socialRepository.sendRequest(username) }
            .onSuccess { refreshFriends(); onDone(true) }
            .onFailure { _error.value = it.message; onDone(false) }
    }

    fun acceptRequest(id: String) = viewModelScope.launch {
        runCatching { socialRepository.acceptRequest(id) }.onSuccess { refreshFriends() }
    }

    fun declineRequest(id: String) = viewModelScope.launch {
        runCatching { socialRepository.declineRequest(id) }.onSuccess { refreshFriends() }
    }

    fun removeFriend(userId: String) = viewModelScope.launch {
        runCatching { socialRepository.removeFriend(userId) }.onSuccess { refreshFriends() }
    }

    fun openDmWith(userId: String, onOpened: (String) -> Unit) = viewModelScope.launch {
        runCatching { socialRepository.createDm(listOf(userId)) }
            .onSuccess { convo -> refreshDms(); selectChannel(convo.id); onOpened(convo.id) }
            .onFailure { _error.value = it.message }
    }

    fun updateStatus(status: PresenceStatus) = viewModelScope.launch {
        val wire = when (status) {
            PresenceStatus.ONLINE -> "online"
            PresenceStatus.IDLE -> "idle"
            PresenceStatus.DND -> "dnd"
            PresenceStatus.OFFLINE -> "offline"
        }
        socketManager.updatePresence(wire)
        runCatching { authRepository.updateMe(lt.oranges.orangchat.data.remote.UpdateMeRequest(status = wire)) }
    }

    fun logout() = viewModelScope.launch { authRepository.logout() }

    fun clearError() { _error.value = null }

    // ── realtime ────────────────────────────────────────
    private fun observeSocket() = viewModelScope.launch {
        socketManager.events.collect { event -> applyEvent(event) }
    }

    private fun applyEvent(event: SocketEvent) {
        when (event) {
            is SocketEvent.ConnectionState -> {
                val wasConnected = _connected.value
                _connected.value = event.connected
                if (event.connected && !wasConnected) {
                    reconcileAfterReconnect()
                    flushPendingMessages()
                } else if (!event.connected && wasConnected) {
                    outboxJob?.cancel()
                    outboxJob = null
                    // Once the socket is down, its cached online values are no
                    // longer trustworthy: offline events may be missed until
                    // reconnect. Never keep presenting those values as live.
                    _presence.update { statuses ->
                        statuses.mapValues { PresenceStatus.OFFLINE }
                    }
                    _presenceDevices.value = emptyMap()
                    _presenceActivities.value = emptyMap()
                }
            }
            is SocketEvent.MessageNew -> {
                // The server broadcasts before acknowledging message:send. Use
                // that broadcast as confirmation of the matching queued row so
                // it never appears twice and is not retried after an ack loss.
                val selfId = authRepository.currentUser?.id
                val pending = pendingOutbox.firstOrNull {
                    event.message.author.id == selfId &&
                        it.channelId == event.message.channelId &&
                        it.content == event.message.content &&
                        it.replyToId == event.message.replyToId
                }
                if (pending != null) {
                    confirmPendingMessage(pending.localId, event.message)
                    pendingOutbox.removeAll { it.localId == pending.localId }
                } else {
                    appendMessage(event.message)
                }
            }
            is SocketEvent.UnreadActivityEvent -> {
                val selfId = authRepository.currentUser?.id
                // Our own messages are not unread to us.
                if (event.activity.authorId != selfId) {
                    unreadStore.onActivity(
                        channelId = event.activity.channelId,
                        serverId = event.activity.serverId,
                        mentionsMe = selfId != null && selfId in event.activity.mentions,
                    )
                }
            }
            is SocketEvent.ChannelRead -> {
                unreadStore.markRead(event.channelId)
                notificationHelper.clearConversationNotifications(event.channelId)
            }
            is SocketEvent.MessageUpdated -> replaceMessage(event.message)
            is SocketEvent.MessageDeleted -> removeMessage(event.channelId, event.messageId)
            is SocketEvent.Typing -> addTyping(event.channelId, event.userId)
            is SocketEvent.Presence -> {
                _presence.update { it + (event.userId to parseStatus(event.status)) }
                _presenceDevices.update {
                    it + (event.userId to event.devices.mapNotNull(::parseDevice).toSet())
                }
                _presenceActivities.update { it + (event.userId to event.activities) }
            }
            is SocketEvent.ReactionEvent -> applyReaction(event)
            is SocketEvent.ChannelCreated -> updateServerChannels { it + event.channel }
            is SocketEvent.ChannelUpdated -> updateServerChannels { list ->
                list.map { if (it.id == event.channel.id) event.channel else it }
            }
            is SocketEvent.ChannelDeleted -> updateServerChannels { list ->
                list.filterNot { it.id == event.channelId }
            }
            is SocketEvent.MemberJoined -> updateMembers { it + event.member }
            is SocketEvent.MemberUpdated -> updateMembers { list ->
                list.map { if (it.userId == event.member.userId) event.member else it }
            }
            is SocketEvent.MemberLeft -> updateMembers { list -> list.filterNot { it.userId == event.userId } }
            is SocketEvent.ServerUpdated -> _servers.update { list ->
                list.map { if (it.id == event.server.id) event.server else it }
            }
            is SocketEvent.ServerDeleted -> _servers.update { list -> list.filterNot { it.id == event.serverId } }
            is SocketEvent.FriendRequestReceived -> _incomingRequests.update { current ->
                if (current.any { it.id == event.request.id }) current else current + event.request
            }
            is SocketEvent.FriendAccepted -> refreshFriends()
            is SocketEvent.FriendRequestRemoved -> {
                _incomingRequests.update { l -> l.filterNot { it.id == event.id } }
                _outgoingRequests.update { l -> l.filterNot { it.id == event.id } }
            }
            is SocketEvent.FriendRemoved -> _friends.update { l -> l.filterNot { it.user.id == event.userId } }
            else -> Unit
        }
    }

    private fun reconcileAfterReconnect() {
        refreshUnreads()
        refreshFriends()
        refreshDms()
        refreshSelectedServer()
        val channelId = _currentChannelId.value ?: return
        viewModelScope.launch {
            runCatching { serverRepository.getHistory(channelId) }.onSuccess { page ->
                _messages.update { map ->
                    val merged = (map[channelId].orEmpty() + page.items)
                        .associateBy { it.id }
                        .values
                        .sortedBy { it.createdAt }
                    map + (channelId to merged)
                }
            }
        }
    }

    /**
     * Reload the open server after a reconnect. Presence events sent while the
     * app had no socket cannot be replayed, while GET /servers/:id overlays the
     * backend's current Redis presence snapshot onto every member.
     */
    private fun refreshSelectedServer() {
        val serverId = _serverDetail.value?.server?.id ?: return
        viewModelScope.launch {
            runCatching { serverRepository.getServer(serverId) }
                .onSuccess { refreshed ->
                    // A user may switch servers while this request is running.
                    // In that case the result still contains valid presence,
                    // but must not replace the newly selected server detail.
                    _serverDetail.update { current ->
                        if (current?.server?.id == serverId) refreshed else current
                    }
                    _presence.update { current ->
                        current + refreshed.members.associate {
                            it.user.id to it.user.status
                        }
                    }
                    _presenceDevices.update { current ->
                        current + refreshed.members.associate {
                            it.user.id to it.user.devices.toSet()
                        }
                    }
                    _presenceActivities.update { current ->
                        current + refreshed.members.associate {
                            it.user.id to it.user.activities
                        }
                    }
                }
        }
    }

    private fun appendMessage(message: Message) {
        var isNew = false
        _messages.update { map ->
            val list = map[message.channelId].orEmpty()
            if (list.any { it.id == message.id }) map
            else { isNew = true; map + (message.channelId to (list + message)) }
        }
        // Clear the author's typing indicator on new message.
        _typing.update { it + (message.channelId to (it[message.channelId].orEmpty() - message.author.id)) }
        if (isNew) maybeNotify(message)
    }

    /**
     * Local notification decision, driven by the live socket. Fires when the
     * message is not our own AND (the app is backgrounded OR the message's
     * channel isn't the focused one), prioritising DMs and @mentions of us.
     */
    private fun maybeNotify(message: Message) {
        val me = authRepository.currentUser
        if (me == null || message.author.id == me.id) return

        val isDm = _dms.value.any { it.id == message.channelId }
        val mentionsMe = Mentions.mentionsUser(message.content, me.id, me.username)
        val focused = AppForegroundState.isForeground && _currentChannelId.value == message.channelId
        if (focused) return
        // Non-DM, non-mention server chatter while merely backgrounded is noisy;
        // notify for DMs and mentions always, and for other messages only when
        // that channel isn't the one currently open in the foreground.
        if (!isDm && !mentionsMe && AppForegroundState.isForeground) return

        val title = when {
            isDm -> message.author.displayName
            mentionsMe -> "${message.author.displayName} mentioned you"
            else -> message.author.displayName
        }
        val nameOf: (String) -> String? = { uid ->
            _serverDetail.value?.members?.firstOrNull { it.userId == uid }?.user?.displayName
                ?: _friends.value.firstOrNull { it.user.id == uid }?.user?.displayName
        }
        val body = Mentions.render(message.content, nameOf).ifBlank { "Sent an attachment" }
        notificationHelper.notifyMessage(
            channelId = message.channelId,
            title = title,
            body = body,
            senderId = message.author.id,
            senderName = message.author.displayName,
            senderAvatarUrl = message.author.avatarUrl,
            isGroup = !isDm,
            messageId = message.id,
        )
    }

    private fun replaceMessage(message: Message) {
        _messages.update { map ->
            val list = map[message.channelId] ?: return@update map
            map + (message.channelId to list.map { if (it.id == message.id) message else it })
        }
    }

    private fun removeMessage(channelId: String, messageId: String) {
        _messages.update { map ->
            val list = map[channelId] ?: return@update map
            map + (channelId to list.filterNot { it.id == messageId })
        }
    }

    private fun addTyping(channelId: String, userId: String) {
        _typing.update { it + (channelId to (it[channelId].orEmpty() + userId)) }
        // Auto-expire after ~5s.
        viewModelScope.launch {
            kotlinx.coroutines.delay(5000)
            _typing.update { it + (channelId to (it[channelId].orEmpty() - userId)) }
        }
    }

    private fun applyReaction(e: SocketEvent.ReactionEvent) {
        _messages.update { map ->
            val list = map[e.channelId] ?: return@update map
            val myId = authRepository.currentUser?.id
            map + (e.channelId to list.map { msg ->
                if (msg.id != e.messageId) return@map msg
                val reactions = msg.reactions.toMutableList()
                val idx = reactions.indexOfFirst { it.emoji == e.emoji }
                if (e.added) {
                    if (idx >= 0) {
                        val r = reactions[idx]
                        reactions[idx] = r.copy(count = r.count + 1, me = r.me || e.userId == myId)
                    } else {
                        reactions.add(Reaction(e.emoji, 1, e.userId == myId))
                    }
                } else if (idx >= 0) {
                    val r = reactions[idx]
                    val count = (r.count - 1).coerceAtLeast(0)
                    if (count == 0) reactions.removeAt(idx)
                    else reactions[idx] = r.copy(count = count, me = r.me && e.userId != myId)
                }
                msg.copy(reactions = reactions)
            })
        }
    }

    private fun updateServerChannels(transform: (List<Channel>) -> List<Channel>) {
        _serverDetail.update { d -> d?.copy(channels = transform(d.channels)) }
    }

    private fun updateMembers(transform: (List<ServerMember>) -> List<ServerMember>) {
        _serverDetail.update { d -> d?.copy(members = transform(d.members)) }
    }

    private fun parseStatus(s: String): PresenceStatus = when (s) {
        "online" -> PresenceStatus.ONLINE
        "idle" -> PresenceStatus.IDLE
        "dnd" -> PresenceStatus.DND
        else -> PresenceStatus.OFFLINE
    }

    private fun parseDevice(s: String): PresenceDevice? = when (s) {
        "mobile" -> PresenceDevice.MOBILE
        "browser" -> PresenceDevice.BROWSER
        "desktop" -> PresenceDevice.DESKTOP
        else -> null
    }
}

/** Local StateFlow.update shim (kotlinx has it; aliased for older BOMs). */
private inline fun <T> MutableStateFlow<T>.update(function: (T) -> T) {
    while (true) {
        val prev = value
        val next = function(prev)
        if (compareAndSet(prev, next)) return
    }
}
