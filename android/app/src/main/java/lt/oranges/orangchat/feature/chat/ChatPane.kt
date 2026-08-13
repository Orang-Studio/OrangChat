package lt.oranges.orangchat.feature.chat

import lt.oranges.orangchat.R
import android.Manifest
import android.app.Activity
import android.app.KeyguardManager
import android.content.pm.PackageManager
import android.net.Uri
import android.view.inputmethod.InputMethodManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.content.MediaType
import androidx.compose.foundation.content.ReceiveContentListener
import androidx.compose.foundation.content.TransferableContent
import androidx.compose.foundation.content.consume
import androidx.compose.foundation.content.contentReceiver
import androidx.compose.foundation.content.hasMediaType
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.maxLength
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AddReaction
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.GroupAdd
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.minimumInteractiveComponentSize
import lt.oranges.orangchat.ui.components.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import lt.oranges.orangchat.util.AppStrings
import lt.oranges.orangchat.util.absoluteUrl
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import lt.oranges.orangchat.data.model.Message
import lt.oranges.orangchat.data.model.PresenceStatus
import lt.oranges.orangchat.data.model.ServerMember
import lt.oranges.orangchat.data.model.User
import lt.oranges.orangchat.crypto.SealedAttachmentRef
import lt.oranges.orangchat.feature.home.AppViewModel
import lt.oranges.orangchat.feature.e2ee.ConversationEncryptionDialog
import lt.oranges.orangchat.feature.transfer.ContactQrScanner
import lt.oranges.orangchat.feature.chat.voicemessage.VoiceDeleteButton
import lt.oranges.orangchat.feature.chat.voicemessage.VoiceMicButton
import lt.oranges.orangchat.feature.chat.voicemessage.VoicePhase
import lt.oranges.orangchat.feature.chat.voicemessage.VoiceRecordingStrip
import lt.oranges.orangchat.feature.chat.voicemessage.orangVoiceRecorderColors
import lt.oranges.orangchat.feature.chat.voicemessage.rememberVoiceRecorderState
import lt.oranges.orangchat.data.model.UserActivity
import lt.oranges.orangchat.ui.components.ActivityStatus
import lt.oranges.orangchat.feature.unread.UnreadCountBadge
import lt.oranges.orangchat.ui.components.Avatar
import lt.oranges.orangchat.ui.components.GroupIcon
import lt.oranges.orangchat.ui.components.BotTag
import lt.oranges.orangchat.ui.components.MenuItem
import lt.oranges.orangchat.ui.components.OrangDropdownMenu
import lt.oranges.orangchat.ui.components.OrangTextField
import lt.oranges.orangchat.ui.components.OrangButton
import lt.oranges.orangchat.ui.components.OrangDialog
import lt.oranges.orangchat.ui.components.ButtonSize
import lt.oranges.orangchat.ui.components.ButtonVariant
import lt.oranges.orangchat.ui.theme.LocalOrangColors
import lt.oranges.orangchat.ui.theme.OrangRadius
import lt.oranges.orangchat.ui.theme.OrangTheme
import lt.oranges.orangchat.util.EmojiRef
import lt.oranges.orangchat.util.EmojiSearch
import lt.oranges.orangchat.util.MentionUser
import lt.oranges.orangchat.util.Mentions
import lt.oranges.orangchat.util.dayKey
import lt.oranges.orangchat.util.daysAgo
import lt.oranges.orangchat.util.formatDayLabel
import lt.oranges.orangchat.util.formatTime
import lt.oranges.orangchat.util.parseInstant
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.time.Duration
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit

private val REPLY_TRIGGER = 64.dp

private val AVATAR_COLUMN_WIDTH = 48.dp

private val MESSAGE_AVATAR_SIZE = 38.dp

private val EDIT_TRIGGER = 148.dp

private enum class SwipeAction { NONE, REPLY, EDIT }

private suspend fun PointerInputScope.detectSwipeToReply(
    onDelta: (Float) -> Unit,
    onRelease: () -> Unit,
    onCancel: () -> Unit,
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val slop = viewConfiguration.touchSlop
        var travel = Offset.Zero
        var claimed = false
        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            if (!change.pressed) {
                if (claimed) onRelease()
                return@awaitEachGesture
            }
            if (change.isConsumed) {
                if (claimed) onCancel()
                return@awaitEachGesture
            }
            if (claimed) {
                val dx = change.positionChange().x
                change.consume()
                onDelta(dx)
                continue
            }
            travel += change.positionChange()
            if (abs(travel.y) > slop || travel.x > slop) return@awaitEachGesture
            if (travel.x < -slop) {
                claimed = true
                change.consume()
                onDelta(travel.x + slop)
            }
        }
    }
}

private const val GROUP_WINDOW_MS = 5 * 60 * 1000L

private const val TYPING_THROTTLE_MS = 4_000L

private const val MESSAGE_MAX_LENGTH = 4_000

private const val MESSAGE_LENGTH_WARNING_THRESHOLD = MESSAGE_MAX_LENGTH - 400

private const val HIGHLIGHT_MS = 1_600L

private const val VOICE_HINT_MS = 2_500L

private const val JUMP_MAX_PAGES = 12

private const val JUMP_POLL_MS = 200L

private const val JUMP_MAX_INITIAL_WAITS = 50

private const val JUMP_MISSING_NOTICE_MS = 4_000L

private const val JUMP_VIEWPORT_FRACTION = 0.72f

private fun LazyListState.jumpOffset(): Int =
    (layoutInfo.viewportSize.height * JUMP_VIEWPORT_FRACTION).toInt()

private data class MessageRowData(
    val message: Message,
    val grouped: Boolean,
    val isNotice: Boolean = false,
    val notice: SystemNotice? = null,
    val groupEnd: Boolean = true,
    val newDay: Boolean = false,
)

private fun messageRowKey(message: Message): String =
    message.clientId?.let { "client:$it" } ?: "server:${message.id}"

private fun isGrouped(previous: Message?, message: Message): Boolean {
    if (previous == null) return false
    if (message.isSystemNotice()) return false
    if (previous.isSystemNotice()) return false
    if (previous.author.id != message.author.id) return false
    if (message.replyToId != null) return false
    val prev = parseInstant(previous.createdAt)?.toEpochMilli() ?: return false
    val curr = parseInstant(message.createdAt)?.toEpochMilli() ?: return false
    return curr - prev in 0..GROUP_WINDOW_MS
}

@Composable
fun ChatPane(
    title: String,
    topic: String?,
    channelId: String,
    messages: List<Message>,
    pendingMessageIds: Set<String> = emptySet(),
    failedMessageIds: Set<String> = emptySet(),
    onRetryMessage: (String) -> Unit = {},
    onDiscardMessage: (String) -> Unit = {},
    selfId: String,
    members: List<ServerMember>,
    presence: Map<String, PresenceStatus>,
    typingUserIds: Set<String>,
    onBack: () -> Unit,
    connected: Boolean = true,
    missedCount: Int = 0,
    onSend: (
        content: String,
        replyToId: String?,
        attachmentIds: List<String>,
        sealedAttachments: List<SealedAttachmentRef>,
    ) -> Unit,
    onEdit: (String, String, (String?) -> Unit) -> Unit,
    onDelete: (String) -> Unit,
    onReport: (Message, String, (String?) -> Unit) -> Unit,
    onReact: (Message, String) -> Unit,
    onTyping: () -> Unit,
    onSearch: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    onLoadOlder: () -> Unit = {},
    loadingOlder: Boolean = false,
    hasOlder: Boolean = true,
    jumpToMessageId: String? = null,
    onJumpHandled: () -> Unit = {},
    compact: Boolean = false,
    reducedMotion: Boolean = false,
    onStartCall: ((video: Boolean) -> Unit)? = null,
    onAddPeople: (() -> Unit)? = null,
    onCall: Boolean = false,
    headerUser: User? = null,
    headerActivities: List<UserActivity> = emptyList(),
    onOpenProfile: (User) -> Unit = {},
    emojis: Map<String, EmojiRef> = emptyMap(),
    encryptionInfo: AppViewModel.ConversationEncryptionInfo? = null,
    onResetEncryption: (() -> Unit)? = null,
    onSetStrict: ((Boolean) -> Unit)? = null,
    onVerifyContact: ((String, String, (Boolean, String?) -> Unit) -> Unit)? = null,
    onCompareSafetyNumber: (
        (String, (AppViewModel.SafetyNumberVerdict) -> Unit) -> Unit
    )? = null,
    backgroundUrl: String? = null,
    onSetBackground: ((Uri) -> Unit)? = null,
    onRemoveBackground: (() -> Unit)? = null,
    iconUrl: String? = null,
    onSetIcon: ((Uri) -> Unit)? = null,
    onRemoveIcon: (() -> Unit)? = null,
) {
    val customEmojis = remember(emojis) { emojis.values.sortedBy { it.name.lowercase() } }
    val c = OrangTheme.colors
    val rowColors = remember(c, backgroundUrl != null) {
        if (backgroundUrl == null) c else c.copy(surface1 = c.surface3, surface2 = c.surface4)
    }
    val listState = rememberLazyListState()
    var reportTarget by remember { mutableStateOf<Message?>(null) }
    var reportReason by remember { mutableStateOf("") }
    var reportSending by remember { mutableStateOf(false) }
    var reportSent by remember { mutableStateOf(false) }
    var reportError by remember { mutableStateOf<String?>(null) }
    var contactScannerOpen by remember { mutableStateOf(false) }
    var scannedContactCode by remember { mutableStateOf<String?>(null) }
    var contactVerifyBusy by remember { mutableStateOf(false) }
    var contactVerifyError by remember { mutableStateOf<String?>(null) }
    var contactVerified by remember { mutableStateOf(false) }
    var headerMenuOpen by remember { mutableStateOf(false) }
    val backgroundPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri -> if (uri != null) onSetBackground?.invoke(uri) }
    val iconPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri -> if (uri != null) onSetIcon?.invoke(uri) }

    DisposableEffect(Unit) { onDispose { MediaPreviewHost.unbind() } }
    LaunchedEffect(messages, onReact) { MediaPreviewHost.bind(messages, onReact) }

    val rows = remember(messages) {
        val deduped = messages
            .distinctBy { it.id }
            .distinctBy(::messageRowKey)
        val days = deduped.map { dayKey(it.createdAt) }
        val newDay = deduped.indices.map { i -> i == 0 || days[i] != days[i - 1] }
        val grouped = deduped.mapIndexed { i, m ->
            !newDay[i] && isGrouped(deduped.getOrNull(i - 1), m)
        }
        deduped
            .mapIndexed { i, m ->
                MessageRowData(
                    m,
                    grouped[i],
                    m.isSystemNotice(),
                    SystemNotice.of(m),
                    groupEnd = grouped.getOrNull(i + 1) != true,
                    newDay = newDay[i],
                )
            }
            .asReversed()
    }

    LaunchedEffect(listState, rows.size) {
        snapshotFlow {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            rows.isNotEmpty() && last >= rows.size - 3
        }
            .distinctUntilChanged()
            .collect { nearOldest -> if (nearOldest) onLoadOlder() }
    }

    val newestId = messages.lastOrNull()?.id
    val awayFromBottom by remember(listState) {
        derivedStateOf { listState.firstVisibleItemIndex > 2 }
    }
    var lastObservedNewestId by remember(channelId) { mutableStateOf(newestId) }
    var hasNewMessages by remember(channelId) { mutableStateOf(false) }
    LaunchedEffect(newestId) {
        if (
            newestId != null &&
            lastObservedNewestId != null &&
            newestId != lastObservedNewestId &&
            awayFromBottom
        ) {
            hasNewMessages = true
        }
        lastObservedNewestId = newestId
        if (newestId != null && !awayFromBottom) {
            if (reducedMotion) listState.scrollToItem(0) else listState.animateScrollToItem(0)
        }
    }
    LaunchedEffect(awayFromBottom) {
        if (!awayFromBottom) hasNewMessages = false
    }

    var highlightedId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(highlightedId) {
        if (highlightedId != null) {
            delay(HIGHLIGHT_MS)
            highlightedId = null
        }
    }

    var jumpMissing by remember(channelId) { mutableStateOf(false) }
    val liveRows by rememberUpdatedState(rows)
    val liveHasOlder by rememberUpdatedState(hasOlder)
    val liveLoadingOlder by rememberUpdatedState(loadingOlder)
    LaunchedEffect(jumpToMessageId, channelId) {
        val target = jumpToMessageId ?: return@LaunchedEffect
        jumpMissing = false
        var pagesAsked = 0
        var waitedForFirstPage = 0
        var landed = false
        while (pagesAsked <= JUMP_MAX_PAGES) {
            val index = liveRows.indexOfFirst { it.message.id == target }
            if (index >= 0) {
                listState.scrollToItem(index, listState.jumpOffset())
                highlightedId = target
                landed = true
                break
            }
            if (liveRows.isEmpty()) {
                if (waitedForFirstPage++ >= JUMP_MAX_INITIAL_WAITS) break
                delay(JUMP_POLL_MS)
                continue
            }
            if (!liveHasOlder) break
            if (!liveLoadingOlder) {
                onLoadOlder()
                pagesAsked++
            }
            delay(JUMP_POLL_MS)
        }
        if (!landed) jumpMissing = true
        onJumpHandled()
    }
    LaunchedEffect(jumpMissing) {
        if (jumpMissing) {
            delay(JUMP_MISSING_NOTICE_MS)
            jumpMissing = false
        }
    }

    val nameOf: (String) -> String? = { uid ->
        members.firstOrNull { it.userId == uid }?.let { it.nickname ?: it.user.displayName }
    }

    val mentionNames = remember(members) {
        members.associate { it.userId to (it.nickname ?: it.user.displayName) }
    }

    val mentionUsers = remember(members) {
        members.associate {
            it.user.username.lowercase() to
                MentionUser(it.userId, it.nickname ?: it.user.displayName)
        }
    }

    var replyTo by remember { mutableStateOf<Message?>(null) }
    var encryptionOpen by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val relaxStrict = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) onSetStrict?.invoke(false)
    }

    val jumpScope = rememberCoroutineScope()
    val jumpToMessage: (String) -> Unit = { id ->
        val index = rows.indexOfFirst { it.message.id == id }
        if (index >= 0) {
            jumpScope.launch {
                val offset = listState.jumpOffset()
                if (reducedMotion) {
                    listState.scrollToItem(index, offset)
                } else {
                    listState.animateScrollToItem(index, offset)
                }
                highlightedId = id
            }
        }
    }
    val jumpToLatest: () -> Unit = {
        hasNewMessages = false
        jumpScope.launch {
            if (reducedMotion) listState.scrollToItem(0) else listState.animateScrollToItem(0)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(c.surface2),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(c.surface2)
                .padding(horizontal = 8.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = Modifier.clickable(onClick = onBack).padding(4.dp)) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = c.inkSecondary,
                )
                UnreadCountBadge(
                    count = missedCount,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .offset(x = 6.dp, y = 6.dp),
                )
            }
            if (onSetIcon != null) {
                GroupIcon(
                    iconUrl = iconUrl,
                    size = 28.dp,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            } else if (headerUser != null) {
                Avatar(
                    user = headerUser,
                    size = 28.dp,
                    status = presence[headerUser.id] ?: PresenceStatus.OFFLINE,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            } else {
                Icon(Icons.Default.Tag, contentDescription = null, tint = c.inkMuted, modifier = Modifier.padding(horizontal = 6.dp).size(18.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(title, color = c.ink, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                if (headerUser != null) {
                    ActivityStatus(headerActivities)
                } else if (!topic.isNullOrBlank()) {
                    Text(topic, color = c.inkMuted, fontSize = 12.sp, maxLines = 1)
                }
            }
            if (encryptionInfo != null) {
                Icon(
                    imageVector = if (encryptionInfo.verified) Icons.Default.Shield else Icons.Default.Lock,
                    contentDescription = if (encryptionInfo.verified) {
                        "Encrypted and verified"
                    } else {
                        "Encrypted"
                    },
                    tint = c.inkSecondary,
                    modifier = Modifier
                        .clickable { encryptionOpen = true }
                        .padding(6.dp)
                        .size(20.dp),
                )
            }
            if (onStartCall != null) {
                Icon(
                    Icons.Default.Call,
                    contentDescription = if (onCall) AppStrings.get(context, R.string.catalog_already_on_this_call_abb9cae6) else AppStrings.get(context, R.string.catalog_start_voice_call_f5e80dd9),
                    tint = if (onCall) c.inkMuted.copy(alpha = 0.4f) else c.inkSecondary,
                    modifier = Modifier
                        .clickable(enabled = !onCall) { onStartCall(false) }
                        .padding(6.dp)
                        .size(20.dp),
                )
                Icon(
                    Icons.Default.Videocam,
                    contentDescription = if (onCall) AppStrings.get(context, R.string.catalog_already_on_this_call_abb9cae6) else AppStrings.get(context, R.string.catalog_start_video_call_dac036c6),
                    tint = if (onCall) c.inkMuted.copy(alpha = 0.4f) else c.inkSecondary,
                    modifier = Modifier
                        .clickable(enabled = !onCall) { onStartCall(true) }
                        .padding(6.dp)
                        .size(20.dp),
                )
            }
            val overflow = buildList {
                if (onSearch != null) {
                    add(MenuItem(AppStrings.get(context, R.string.catalog_search_messages_abea65ae), Icons.Default.Search, onClick = onSearch))
                }
                if (onAddPeople != null) {
                    add(MenuItem(AppStrings.get(context, R.string.catalog_add_people_b9c735ea), Icons.Default.GroupAdd, onClick = onAddPeople))
                }
                if (onSetBackground != null) {
                    add(
                        MenuItem(
                            if (backgroundUrl != null) AppStrings.get(context, R.string.catalog_change_background_0c868243) else AppStrings.get(context, R.string.catalog_set_chat_background_d07e7165),
                            Icons.Default.Photo,
                        ) {
                            backgroundPicker.launch(
                                PickVisualMediaRequest(
                                    ActivityResultContracts.PickVisualMedia.ImageOnly,
                                ),
                            )
                        },
                    )
                    if (backgroundUrl != null && onRemoveBackground != null) {
                        add(
                            MenuItem(
                                AppStrings.get(context, R.string.catalog_remove_background_for_everyone_275dc99e),
                                Icons.Default.Delete,
                                destructive = true,
                                onClick = onRemoveBackground,
                            ),
                        )
                    }
                }
                if (onSetIcon != null) {
                    add(
                        MenuItem(
                            if (iconUrl != null) AppStrings.get(context, R.string.catalog_change_group_icon_715ac3cf) else AppStrings.get(context, R.string.catalog_set_group_icon_48042fd7),
                            Icons.Default.Group,
                        ) {
                            iconPicker.launch(
                                PickVisualMediaRequest(
                                    ActivityResultContracts.PickVisualMedia.ImageOnly,
                                ),
                            )
                        },
                    )
                    if (iconUrl != null && onRemoveIcon != null) {
                        add(
                            MenuItem(
                                AppStrings.get(context, R.string.catalog_remove_group_icon_5869f3b2),
                                Icons.Default.Delete,
                                destructive = true,
                                onClick = onRemoveIcon,
                            ),
                        )
                    }
                }
            }
            if (overflow.size == 1) {
                val only = overflow.first()
                Icon(
                    only.icon ?: Icons.Default.MoreVert,
                    contentDescription = only.label,
                    tint = c.inkSecondary,
                    modifier = Modifier
                        .clickable(onClick = only.onClick)
                        .padding(6.dp)
                        .size(20.dp),
                )
            } else if (overflow.isNotEmpty()) {
                Box {
                    Icon(
                        Icons.Default.MoreVert,
                        contentDescription = "More",
                        tint = c.inkSecondary,
                        modifier = Modifier
                            .clickable { headerMenuOpen = true }
                            .padding(6.dp)
                            .size(20.dp),
                    )
                    OrangDropdownMenu(
                        expanded = headerMenuOpen,
                        onDismiss = { headerMenuOpen = false },
                        items = overflow,
                    )
                }
            }
        }
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(c.border))

        if (!connected) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(c.primarySoft)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                Icon(
                    Icons.Default.WifiOff,
                    contentDescription = null,
                    tint = c.warning,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = AppStrings.get(
                        context,
                        R.string.catalog_offline_messages_are_saved_and_will_be_96baffa1,
                    ),
                    color = c.warning,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                )
            }
        }

        if (jumpMissing) {
            Text(
                text = AppStrings.get(context, R.string.catalog_couldn_t_reach_that_message_it_may_896fe39d),
                color = c.inkMuted,
                fontSize = 12.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(c.surface1)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }

        Box(modifier = Modifier.weight(1f).fillMaxWidth().clipToBounds()) {
            if (backgroundUrl != null) {
                AsyncImage(
                    model = absoluteUrl(backgroundUrl),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().scale(1.1f).blur(2.dp),
                )
                Box(Modifier.fillMaxSize().background(c.surface2.copy(alpha = 0.3f)))
            }
            LazyColumn(
                state = listState,
                reverseLayout = true,
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp),
            ) {
                items(rows, key = { messageRowKey(it.message) }) { row ->
                    val message = row.message
                    Column(modifier = Modifier.fillMaxWidth()) {
                    if (row.newDay) DateSeparatorRow(message.createdAt)
                    if (row.isNotice) {
                        val call = message.callNotice()
                        if (call != null) {
                            CallCardRow(
                                message = message,
                                notice = call,
                                selfId = selfId,
                                members = members,
                                onCall = onCall,
                                onStartCall = onStartCall,
                            )
                        } else {
                            SystemNoticeRow(row.notice, message, selfId)
                        }
                        return@Column
                    }
                    CompositionLocalProvider(LocalOrangColors provides rowColors) {
                        MessageRow(
                            message = message,
                            pending = message.id in pendingMessageIds,
                            failed = message.id in failedMessageIds,
                            onRetry = { onRetryMessage(message.id) },
                            onDiscard = { onDiscardMessage(message.id) },
                            selfId = selfId,
                            grouped = row.grouped,
                            plated = backgroundUrl != null,
                            groupEnd = row.groupEnd,
                            compact = compact,
                            nameOf = nameOf,
                            mentionNames = mentionNames,
                            mentionUsers = mentionUsers,
                            members = members,
                            repliedTo = message.replyToId?.let { id -> messages.firstOrNull { it.id == id } },
                            highlighted = message.id == highlightedId,
                            replyingTo = replyTo?.id == message.id,
                            onJumpToMessage = jumpToMessage,
                            onReply = { replyTo = it },
                            onEdit = onEdit,
                            onDelete = onDelete,
                            onReport = { reportTarget = it },
                            onReact = onReact,
                            onOpenProfile = onOpenProfile,
                            emojis = emojis,
                        )
                    }
                    }
                }
                if (loadingOlder) {
                    item(key = "loading-older") {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(color = c.inkMuted, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }

            val latestLabel = if (hasNewMessages) AppStrings.get(context, R.string.catalog_new_messages_6d867124) else AppStrings.get(context, R.string.catalog_jump_to_latest_5aa2e089)
            androidx.compose.animation.AnimatedVisibility(
                visible = awayFromBottom,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 12.dp),
                enter = if (reducedMotion) EnterTransition.None else fadeIn(tween(160)),
                exit = if (reducedMotion) ExitTransition.None else fadeOut(tween(120)),
            ) {
                val shape = RoundedCornerShape(OrangRadius.lg)
                Row(
                    modifier = Modifier
                        .clip(shape)
                        .background(c.surface3)
                        .border(1.dp, c.border, shape)
                        .clickable(role = Role.Button, onClick = jumpToLatest)
                        .semantics { contentDescription = latestLabel }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Default.ArrowDownward,
                        contentDescription = null,
                        tint = c.inkSecondary,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        latestLabel,
                        color = c.ink,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }

        encryptionInfo?.waitingOn?.takeIf { it.isNotEmpty() }?.let { waiting ->
            val names = waiting.mapNotNull(nameOf)
            val who = when (names.size) {
                0 -> AppStrings.get(context, R.string.catalog_someone_here_f47623b5)
                1 -> names[0]
                2 -> "${names[0]} and ${names[1]}"
                else -> "${names.dropLast(1).joinToString(", ")} and ${names.last()}"
            }
            val one = waiting.size == 1
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .background(c.warning.copy(alpha = 0.12f), RoundedCornerShape(OrangRadius.lg))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    Icons.Default.LockOpen,
                    contentDescription = null,
                    tint = c.warning,
                    modifier = Modifier.size(16.dp),
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        AppStrings.get(context, R.string.catalog_this_conversation_is_not_encrypted_yet_00080602),
                        color = c.ink,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        "$who ${if (one) "has" else "have"} not set up encryption on any device, " +
                            "and a locked message needs a key on their side to open it. " +
                            "Messages here are stored the ordinary way until then - it switches on by itself " +
                            "the moment ${if (one) AppStrings.get(context, R.string.catalog_they_open_371267bf) else AppStrings.get(context, R.string.catalog_they_all_open_8a0f4e5d)} OrangChat on a phone or computer.",
                        color = c.inkSecondary,
                        fontSize = 11.sp,
                    )
                }
            }
        }

        val typers = typingUserIds.mapNotNull { nameOf(it) }
        if (typers.isNotEmpty()) {
            Text(
                text = when (typers.size) {
                    1 -> "${typers[0]} is typing…"
                    else -> "${typers.size} people are typing…"
                },
                color = c.inkMuted,
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
            )
        }

        replyTo?.let { target ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(c.surface3)
                    .padding(start = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Reply,
                    contentDescription = null,
                    tint = c.inkMuted,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "Replying to ${nameOf(target.author.id) ?: target.author.displayName}",
                    color = c.inkSecondary,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    Icons.Default.Close,
                    contentDescription = AppStrings.get(context, R.string.catalog_cancel_reply_bd62d2dd),
                    tint = c.inkMuted,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .size(16.dp)
                        .clickable { replyTo = null },
                )
            }
        }
        Composer(
            onSend = { content, attachmentIds, sealedAttachments ->
                onSend(content, replyTo?.id, attachmentIds, sealedAttachments)
                replyTo = null
            },
            onTyping = onTyping,
            channelId = channelId,
            customEmojis = customEmojis,
            members = members,
        )
    }

    if (encryptionOpen && encryptionInfo != null) {
        ConversationEncryptionDialog(
            info = encryptionInfo,
            peerName = headerUser?.displayName,
            canScan = headerUser != null && onVerifyContact != null,
            onScan = {
                encryptionOpen = false
                contactScannerOpen = true
            },
            onSetStrict = onSetStrict,
            onRelaxStrict = {
                val keyguard = context.getSystemService(KeyguardManager::class.java)
                val intent = keyguard?.createConfirmDeviceCredentialIntent(
                    "Send without checking them first",
                    "Confirm your screen lock before letting messages go to an unchecked contact.",
                )
                if (intent != null) relaxStrict.launch(intent)
            },
            onResetEncryption = onResetEncryption,
            onCompareSafetyNumber = onCompareSafetyNumber,
            onDismiss = { encryptionOpen = false },
        )
    }

    reportTarget?.let { target ->
        AlertDialog(
            onDismissRequest = {
                if (!reportSending) {
                    reportTarget = null
                    reportReason = ""
                    reportError = null
                    reportSent = false
                }
            },
            title = {
                Text(
                    if (reportSent) AppStrings.get(context, R.string.catalog_report_received_2568a541) else AppStrings.get(context, R.string.catalog_report_message_babfeaf5),
                    color = c.ink,
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (reportSent) {
                        Text(
                            if (target.ciphertext != null) {
                                "Only this message was disclosed. OrangChat verified its encryption tag and sender-device signature; the rest of the conversation remains private."
                            } else {
                                "The message was preserved for review."
                            },
                            color = c.inkSecondary,
                            fontSize = 14.sp,
                        )
                    } else {
                        Text(
                            "Report ${target.author.displayName}'s message for review?",
                            color = c.inkSecondary,
                            fontSize = 14.sp,
                        )
                        Text(
                            target.content.ifBlank { "Attachment-only message" },
                            color = c.ink,
                            fontSize = 13.sp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(c.surface1, RoundedCornerShape(12.dp))
                                .padding(12.dp),
                        )
                        if (target.ciphertext != null) {
                            Text(
                                AppStrings.get(context, R.string.catalog_reporting_reveals_this_one_decrypted_message_and_1e7d77a7),
                                color = c.inkMuted,
                                fontSize = 12.sp,
                            )
                        }
                        OrangTextField(
                            value = reportReason,
                            onValueChange = { reportReason = it.take(1000) },
                            label = AppStrings.get(context, R.string.catalog_what_happened_optional_9a81bdcf),
                            placeholder = AppStrings.get(context, R.string.catalog_add_context_for_the_reviewer_e44f9147),
                        )
                        Text(
                            "${reportReason.length}/1000",
                            color = c.inkMuted,
                            fontSize = 11.sp,
                            modifier = Modifier.align(Alignment.End),
                        )
                        reportError?.let { Text(it, color = c.danger, fontSize = 12.sp) }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !reportSending,
                    onClick = {
                        if (reportSent) {
                            reportTarget = null
                            reportReason = ""
                            reportSent = false
                        } else {
                            reportSending = true
                            reportError = null
                            onReport(target, reportReason) { error ->
                                reportSending = false
                                reportError = error
                                reportSent = error == null
                            }
                        }
                    },
                ) {
                    if (reportSending) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = c.danger,
                        )
                    } else {
                        Text(if (reportSent) "Done" else AppStrings.get(context, R.string.catalog_send_report_a5b32af9), color = if (reportSent) c.primary else c.danger)
                    }
                }
            },
            dismissButton = if (reportSent) null else {
                {
                    TextButton(
                        enabled = !reportSending,
                        onClick = {
                            reportTarget = null
                            reportReason = ""
                            reportError = null
                        },
                    ) { Text("Cancel", color = c.inkSecondary) }
                }
            },
        )
    }

    if (contactScannerOpen) {
        OrangDialog(
            onDismiss = { contactScannerOpen = false },
            title = AppStrings.get(context, R.string.catalog_scan_verification_code_9569a83c),
        ) {
            ContactQrScanner(
                onScanned = {
                    contactScannerOpen = false
                    scannedContactCode = it
                    contactVerifyError = null
                    contactVerified = false
                },
                onCancel = { contactScannerOpen = false },
            )
        }
    }

    scannedContactCode?.let { raw ->
        val contact = headerUser
        AlertDialog(
            onDismissRequest = {
                if (!contactVerifyBusy) {
                    scannedContactCode = null
                    contactVerifyError = null
                    contactVerified = false
                }
            },
            title = {
                Text(
                    if (contactVerified) {
                        "Checked"
                    } else {
                        "Is this ${contact?.displayName ?: AppStrings.get(context, R.string.catalog_the_right_person_3860646e)}?"
                    },
                    color = c.ink,
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        if (contactVerified) {
                            "This phone will remember the lock you saw in person, so a swapped one no longer matches. Now show them your own code - one scan only proves one direction."
                        } else {
                            "This phone will remember the lock on that code as theirs, and warn you if it ever changes. Only continue if this person showed you the code themselves."
                        },
                        color = c.inkSecondary,
                        fontSize = 14.sp,
                    )
                    contactVerifyError?.let { Text(it, color = c.danger, fontSize = 12.sp) }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !contactVerifyBusy && contact != null && onVerifyContact != null,
                    onClick = {
                        if (contactVerified) {
                            scannedContactCode = null
                            contactVerified = false
                        } else if (contact != null && onVerifyContact != null) {
                            contactVerifyBusy = true
                            contactVerifyError = null
                            onVerifyContact(raw, contact.id) { ok, error ->
                                contactVerifyBusy = false
                                contactVerifyError = error
                                contactVerified = ok
                            }
                        }
                    },
                ) {
                    if (contactVerifyBusy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = c.primary,
                        )
                    } else {
                        Text(if (contactVerified) "Done" else AppStrings.get(context, R.string.catalog_yes_that_s_them_03f398c0), color = c.primary)
                    }
                }
            },
            dismissButton = if (contactVerified) null else {
                {
                    TextButton(
                        enabled = !contactVerifyBusy,
                        onClick = {
                            scannedContactCode = null
                            contactVerifyError = null
                        },
                    ) { Text("Cancel", color = c.inkSecondary) }
                }
            },
        )
    }
}

@Composable
private fun SystemNoticeRow(notice: SystemNotice?, message: Message, selfId: String) {
    val c = OrangTheme.colors
    val name = if (message.author.id == selfId) "You" else message.author.displayName
    val text = notice?.describe(name) ?: message.content
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "- $text -",
            color = c.inkMuted,
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun DateSeparatorRow(createdAt: String) {
    val c = OrangTheme.colors
    val context = LocalContext.current
    val label = when (daysAgo(createdAt)) {
        0L -> AppStrings.get(context, R.string.catalog_today_24345a14)
        1L -> AppStrings.get(context, R.string.catalog_yesterday_da24830f)
        else -> formatDayLabel(createdAt)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f).height(1.dp).background(c.border))
        Text(
            text = label,
            color = c.inkMuted,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 10.dp),
        )
        Box(Modifier.weight(1f).height(1.dp).background(c.border))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageRow(
    message: Message,
    pending: Boolean,
    failed: Boolean,
    onRetry: () -> Unit,
    onDiscard: () -> Unit,
    selfId: String,
    grouped: Boolean,
    plated: Boolean,
    groupEnd: Boolean,
    compact: Boolean,
    nameOf: (String) -> String?,
    mentionNames: Map<String, String>,
    mentionUsers: Map<String, MentionUser>,
    members: List<ServerMember>,
    repliedTo: Message?,
    highlighted: Boolean,
    replyingTo: Boolean,
    onJumpToMessage: (String) -> Unit,
    onReply: (Message) -> Unit,
    onEdit: (String, String, (String?) -> Unit) -> Unit,
    onDelete: (String) -> Unit,
    onReport: (Message) -> Unit,
    onReact: (Message, String) -> Unit,
    onOpenProfile: (User) -> Unit,
    emojis: Map<String, EmojiRef>,
) {
        val context = LocalContext.current
    val c = OrangTheme.colors
    val renderEmojis = remember(emojis, message.emojis) {
        emojis + message.emojis.associate {
            it.id to EmojiRef(it.id, it.name, it.url, it.animated)
        }
    }
    var menuOpen by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var editSaving by remember(message.id) { mutableStateOf(false) }
    var editError by remember(message.id) { mutableStateOf<String?>(null) }
    var emojiOpen by remember { mutableStateOf(false) }
    val isMine = message.author.id == selfId
    val clipboard = LocalClipboardManager.current
    val pinged = !isMine && lt.oranges.orangchat.util.mentionsSelf(
        message.content,
        lt.oranges.orangchat.util.MentionContext(mentionNames, mentionUsers, selfId),
    )

    val dragScope = rememberCoroutineScope()
    val dragX = remember(message.id) { Animatable(0f) }
    val density = LocalDensity.current
    val replyPx = with(density) { REPLY_TRIGGER.toPx() }
    val editPx = with(density) { EDIT_TRIGGER.toPx() }
    val maxDrag = if (isMine) editPx * 1.2f else replyPx * 1.4f
    val haptics = LocalHapticFeedback.current

    val armed = when {
        isMine && dragX.value <= -editPx -> SwipeAction.EDIT
        dragX.value <= -replyPx -> SwipeAction.REPLY
        else -> SwipeAction.NONE
    }

    val plateShape = if (!plated) {
        RectangleShape
    } else {
        RoundedCornerShape(
            topStart = if (!grouped) OrangRadius.xl2 else 0.dp,
            topEnd = if (!grouped) OrangRadius.xl2 else 0.dp,
            bottomStart = if (groupEnd) OrangRadius.xl2 else 0.dp,
            bottomEnd = if (groupEnd) OrangRadius.xl2 else 0.dp,
        )
    }
    val rowSurface = if (plated) c.plate else c.surface2
    val restTint = when {
        replyingTo -> c.primary.copy(alpha = 0.11f)
        pinged -> c.primary.copy(alpha = 0.08f)
        else -> c.primary.copy(alpha = 0f)
    }
    val rowTint by animateColorAsState(
        targetValue = if (highlighted) c.primarySoft else restTint,
        animationSpec = tween(durationMillis = if (highlighted) 0 else 600),
        label = "message-highlight",
    )

    var dragging by remember(message.id) { mutableStateOf(false) }
    LaunchedEffect(armed) {
        if (dragging && armed != SwipeAction.NONE) {
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
    }

    Box(modifier = Modifier.fillMaxWidth()) {
        Icon(
            if (armed == SwipeAction.EDIT) Icons.Default.Edit else Icons.AutoMirrored.Filled.Reply,
            contentDescription = null,
            tint = if (armed == SwipeAction.NONE) c.inkMuted else c.primary,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 16.dp)
                .size(18.dp)
                .alpha((-dragX.value / replyPx).coerceIn(0f, 1f)),
        )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (pending) 0.5f else 1f)
            .offset { IntOffset(dragX.value.roundToInt(), 0) }
            .then(
                if (plated) {
                    Modifier.padding(
                        start = 8.dp,
                        end = 8.dp,
                        top = if (!grouped) 4.dp else 0.dp,
                        bottom = if (groupEnd) 4.dp else 0.dp,
                    )
                } else {
                    Modifier
                },
            )
            .then(
                if (replyingTo || pinged) {
                    Modifier.shadow(
                        elevation = 8.dp,
                        shape = if (plated) plateShape else RoundedCornerShape(OrangRadius.md),
                        ambientColor = c.primary.copy(alpha = 0.18f),
                        spotColor = c.primary.copy(alpha = 0.18f),
                    )
                } else {
                    Modifier
                },
            )
            .then(if (plated) Modifier.clip(plateShape) else Modifier)
            .pointerInput(message.id, isMine, pending, failed) {
                if (!pending && !failed) detectSwipeToReply(
                    onDelta = { amount ->
                        dragging = true
                        dragScope.launch {
                            dragX.snapTo((dragX.value + amount).coerceIn(-maxDrag, 0f))
                        }
                    },
                    onRelease = {
                        val x = dragX.value
                        dragging = false
                        when {
                            isMine && x <= -editPx -> {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                editError = null
                                editSaving = false
                                editing = true
                            }
                            x <= -replyPx -> {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                onReply(message)
                            }
                        }
                        dragScope.launch { dragX.animateTo(0f) }
                    },
                    onCancel = {
                        dragging = false
                        dragScope.launch { dragX.animateTo(0f) }
                    },
                )
            }
            .background(rowSurface, plateShape)
            .background(rowTint, plateShape)
            .combinedClickable(
                enabled = !pending && !failed,
                onClick = {},
                onLongClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    menuOpen = true
                },
            )
            .padding(
                start = if (plated) 12.dp else 16.dp,
                end = if (plated) 12.dp else 16.dp,
                top = if (grouped) 0.dp else if (compact) 4.dp else 8.dp,
                bottom = if (!groupEnd) 0.dp else if (compact) 1.dp else 2.dp,
            ),
    ) {
        repliedTo?.let { parent ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .padding(start = AVATAR_COLUMN_WIDTH)
                    .clickable { onJumpToMessage(parent.id) }
                    .padding(vertical = 2.dp),
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Reply,
                    contentDescription = null,
                    tint = c.inkMuted,
                    modifier = Modifier.size(12.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = nameOf(parent.author.id) ?: parent.author.displayName,
                    color = c.inkSecondary,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 11.sp,
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = parent.content.replace("\n", " "),
                    color = c.inkMuted,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Row(verticalAlignment = Alignment.Top) {
        if (grouped) {
            Spacer(Modifier.width(AVATAR_COLUMN_WIDTH))
        } else {
            Box(
                modifier = Modifier
                    .size(width = AVATAR_COLUMN_WIDTH, height = MESSAGE_AVATAR_SIZE)
                    .clickable { onOpenProfile(message.author) },
                contentAlignment = Alignment.TopStart,
            ) {
                Avatar(message.author, size = MESSAGE_AVATAR_SIZE)
            }
        }
        Spacer(Modifier.width(2.dp))
        Column(modifier = Modifier.weight(1f)) {
            if (!grouped) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = nameOf(message.author.id) ?: message.author.displayName,
                        color = c.ink,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp,
                        modifier = Modifier.clickable { onOpenProfile(message.author) },
                    )
                    if (message.author.bot) {
                        Spacer(Modifier.width(5.dp))
                        BotTag()
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(formatTime(message.createdAt), color = c.inkMuted, fontSize = 11.sp)
                    if (message.editedAt != null) {
                        Spacer(Modifier.width(4.dp))
                        Text(AppStrings.get(context, R.string.catalog_edited_b6da09e0), color = c.inkMuted, fontSize = 10.sp)
                    }
                }
            }
            if (editing) {
                MessageEditForm(
                    initial = message.content,
                    allowEmpty = message.attachments.isNotEmpty(),
                    saving = editSaving,
                    error = editError,
                    onCancel = {
                        if (!editSaving) {
                            editError = null
                            editing = false
                        }
                    },
                    onSave = { content ->
                        editError = null
                        if (content == message.content) {
                            editing = false
                        } else {
                            editSaving = true
                            onEdit(message.id, content) { error ->
                                editSaving = false
                                if (error == null) {
                                    editing = false
                                } else {
                                    editError = error
                                }
                            }
                        }
                    },
                )
            } else {
                if (message.content.isNotBlank()) {
                    MessageText(
                        content = message.content,
                        mentionNames = mentionNames,
                        mentionUsers = mentionUsers,
                        selfId = selfId,
                        emojis = renderEmojis,
                        fontSize = 15.sp,
                        color = if (failed) c.danger else null,
                        onMentionClick = { userId ->
                            members.firstOrNull { it.userId == userId }?.user?.let(onOpenProfile)
                        },
                    )
                }
                MessageAttachments(message.attachments)
            }
            if (message.reactions.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                val chipShape = RoundedCornerShape(OrangRadius.xl)
                val chipHeight = 28.dp
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    message.reactions.forEach { r ->
                        Row(
                            modifier = Modifier
                                .height(chipHeight)
                                .clip(chipShape)
                                .background(if (r.me) c.primarySoft else c.surface3)
                                .border(1.dp, if (r.me) c.primary else c.border, chipShape)
                                .clickable { onReact(message, r.emoji) }
                                .padding(horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(r.emoji, fontSize = 13.sp)
                            Spacer(Modifier.width(4.dp))
                            Text("${r.count}", color = if (r.me) c.primary else c.inkSecondary, fontSize = 12.sp)
                        }
                    }
                    Box(
                        modifier = Modifier
                            .height(chipHeight)
                            .widthIn(min = 36.dp)
                            .clip(chipShape)
                            .background(c.surface3)
                            .border(1.dp, c.border, chipShape)
                            .clickable { emojiOpen = true }
                            .semantics { contentDescription = AppStrings.get(context, R.string.catalog_add_reaction_cf05eca8) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Default.AddReaction,
                            contentDescription = null,
                            tint = c.inkMuted,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }
            if (failed) {
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.ErrorOutline,
                        contentDescription = null,
                        tint = c.danger,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(AppStrings.get(context, R.string.catalog_not_sent_587c501e), color = c.danger, fontSize = 11.sp)
                    Spacer(Modifier.width(4.dp))
                    FailedMessageAction("Retry", c.primary, onRetry)
                    FailedMessageAction("Delete", c.danger, onDiscard)
                }
            }
        }
        Box {
            OrangDropdownMenu(
                expanded = menuOpen,
                onDismiss = { menuOpen = false },
                items = buildList {
                    add(MenuItem("Reply", Icons.AutoMirrored.Filled.Reply) { onReply(message) })
                    add(MenuItem("React", Icons.Default.AddReaction) { emojiOpen = true })
                    if (message.content.isNotBlank()) {
                        add(
                            MenuItem(AppStrings.get(context, R.string.catalog_copy_message_26902efd), Icons.Default.ContentCopy) {
                                clipboard.setText(AnnotatedString(message.content))
                            },
                        )
                    }
                    if (isMine) {
                        add(MenuItem("Edit", Icons.Default.Edit) {
                            editError = null
                            editSaving = false
                            editing = true
                        })
                        add(MenuItem("Delete", Icons.Default.Delete, destructive = true) { onDelete(message.id) })
                    } else {
                        add(
                            MenuItem(AppStrings.get(context, R.string.catalog_report_message_babfeaf5), Icons.Default.Flag, destructive = true) {
                                onReport(message)
                            },
                        )
                    }
                },
            )
            EmojiPicker(emojiOpen, { emojiOpen = false }) { onReact(message, it) }
        }
    }
    }
}

}

@Composable
private fun FailedMessageAction(label: String, tint: Color, onClick: () -> Unit) {
    Text(
        label,
        color = tint,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

@Composable
private fun MessageEditForm(
    initial: String,
    allowEmpty: Boolean,
    saving: Boolean,
    error: String?,
    onCancel: () -> Unit,
    onSave: (String) -> Unit,
) {
        val context = LocalContext.current
    val c = OrangTheme.colors
    val textState = rememberTextFieldState(initial)
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(initial) {
        if (!saving) textState.setTextAndPlaceCursorAtEnd(initial)
    }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    val content = textState.text.toString().trim()
    val canSave = !saving && (content.isNotEmpty() || allowEmpty)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(AppStrings.get(context, R.string.catalog_edit_message_96116a52), color = c.inkSecondary, fontSize = 11.sp, fontWeight = FontWeight.Medium)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(c.surface3, RoundedCornerShape(OrangRadius.lg))
                .border(1.dp, if (error == null) c.border else c.danger, RoundedCornerShape(OrangRadius.lg))
                .padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            BasicTextField(
                state = textState,
                textStyle = TextStyle(color = c.ink, fontSize = 14.sp),
                cursorBrush = SolidColor(c.primary),
                lineLimits = TextFieldLineLimits.MultiLine(maxHeightInLines = 6),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when {
                            event.key == Key.Escape -> {
                                onCancel()
                                true
                            }
                            event.key == Key.Enter && !event.isShiftPressed -> {
                                if (canSave) onSave(content)
                                true
                            }
                            else -> false
                        }
                    },
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            error?.let {
                Text(it, color = c.danger, fontSize = 11.sp, modifier = Modifier.weight(1f))
            } ?: Text(AppStrings.get(context, R.string.catalog_enter_to_save_shift_enter_for_a_74c26ca9), color = c.inkMuted, fontSize = 11.sp, modifier = Modifier.weight(1f))
            OrangButton(
                text = "Cancel",
                onClick = onCancel,
                variant = ButtonVariant.Ghost,
                size = ButtonSize.Sm,
                enabled = !saving,
            )
            Spacer(Modifier.width(4.dp))
            OrangButton(
                text = "Save",
                onClick = { onSave(content) },
                size = ButtonSize.Sm,
                enabled = canSave,
                loading = saving,
            )
        }
    }
}

@Composable
private fun EmojiPicker(expanded: Boolean, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val c = OrangTheme.colors
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        modifier = Modifier
            .background(c.surface3, RoundedCornerShape(OrangRadius.xl))
            .border(1.dp, c.border, RoundedCornerShape(OrangRadius.xl))
            .padding(6.dp),
    ) {
        EmojiGrid(
            columns = 6,
            modifier = Modifier.width(248.dp).height(280.dp),
        ) { emoji ->
            onDismiss()
            onPick(emoji)
        }
    }

}

private data class MentionQuery(val start: Int, val query: String)

private val MENTION_QUERY = Regex("(^|\\s)@([^\\s@]{0,32})$")

private fun activeMentionQuery(text: String, caret: Int): MentionQuery? {
    if (caret !in 0..text.length) return null
    val m = MENTION_QUERY.find(text.substring(0, caret)) ?: return null
    val q = m.groupValues[2]
    return MentionQuery(caret - q.length - 1, q)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Composer(
    onSend: (
        content: String,
        attachmentIds: List<String>,
        sealedAttachments: List<SealedAttachmentRef>,
    ) -> Unit,
    onTyping: () -> Unit,
    initial: String = "",
    submitLabel: String? = null,
    channelId: String? = null,
    allowEmpty: Boolean = false,
    customEmojis: List<EmojiRef> = emptyList(),
    members: List<ServerMember> = emptyList(),
    drafts: AttachmentDraftViewModel = hiltViewModel(),
    textDrafts: MessageDraftViewModel = hiltViewModel(),
) {
    val c = OrangTheme.colors
    val textState = rememberTextFieldState(initial)
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    var emojiOpen by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val voiceRecorder = remember(context) { VoiceMessageRecorder(context) }
    var recordingHint by remember(channelId) { mutableStateOf<String?>(null) }
    var recordingError by remember(channelId) { mutableStateOf<String?>(null) }
    var autoSendVoiceKey by remember(channelId) { mutableStateOf<String?>(null) }
    val voiceColors = orangVoiceRecorderColors()
    var attachmentMenuOpen by remember(channelId) { mutableStateOf(false) }
    var pendingCameraUri by remember(channelId) { mutableStateOf<Uri?>(null) }
    var tokenWarning by remember(channelId) { mutableStateOf<PastedTokenKind?>(null) }
    var acknowledgedTokenContent by remember(channelId) { mutableStateOf<String?>(null) }

    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture(),
    ) { saved ->
        val uri = pendingCameraUri
        pendingCameraUri = null
        if (saved && uri != null) {
            drafts.add(listOf(uri), channelId, temporaryUris = setOf(uri))
        } else if (uri != null) {
            runCatching { context.contentResolver.delete(uri, null, null) }
        }
    }
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val uri = pendingCameraUri
        if (granted && uri != null) {
            cameraLauncher.launch(uri)
        } else if (uri != null) {
            pendingCameraUri = null
            runCatching { context.contentResolver.delete(uri, null, null) }
        }
    }
    fun openMic() {
        recordingError = null
        runCatching {
            voiceRecorder.start()
        }.onFailure {
            recordingError = "Could not start voice recording"
        }
    }

    fun sendVoice(duration: Duration) {
        val uri = voiceRecorder.stop() ?: run {
            if (channelId != null) recordingError = "Could not record the voice message"
            return
        }
        if (channelId == null) return
        val keys = drafts.add(listOf(uri), channelId, temporaryUris = setOf(uri))
        autoSendVoiceKey = keys.firstOrNull()
        if (autoSendVoiceKey == null) {
            runCatching { context.contentResolver.delete(uri, null, null) }
            recordingError = "Could not record the voice message"
        }
    }

    val voiceState = key(channelId) {
        rememberVoiceRecorderState(
            onStart = { openMic() },
            onCancel = { voiceRecorder.cancel() },
            onSend = { duration -> sendVoice(duration) },
            onTooShort = { recordingHint = AppStrings.get(context, R.string.catalog_hold_the_mic_to_record_then_let_48d81b83) },
        )
    }

    val recordPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            voiceState.start()
            voiceState.lock()
        } else {
            recordingError = AppStrings.get(context, R.string.catalog_microphone_access_is_needed_to_record_a_9afc8aaa)
        }
    }

    val canStartMic: () -> Boolean = {
        if (channelId == null) {
            false
        } else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            recordingError = null
            true
        } else {
            recordPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            false
        }
    }

    LaunchedEffect(voiceState.phase) {
        while (voiceState.phase.isRecording) {
            voiceState.tick()
            voiceState.pushAmplitude(voiceRecorder.amplitude())
            delay(100L)
        }
    }

    LaunchedEffect(recordingHint) {
        if (recordingHint != null) {
            delay(VOICE_HINT_MS)
            recordingHint = null
        }
    }

    LaunchedEffect(channelId, initial) {
        if (channelId == null) {
            textState.setTextAndPlaceCursorAtEnd(initial)
            focusRequester.requestFocus()
            keyboard?.show()
        }
    }

    LaunchedEffect(channelId) {
        if (channelId != null) {
            val saved = textDrafts.load(channelId)
            if (saved.isNotEmpty() && textState.text.isEmpty()) {
                textState.setTextAndPlaceCursorAtEnd(saved)
            }
        }
    }

    val view = LocalView.current
    val resetKeyboard: () -> Unit = {
        view.context.getSystemService(InputMethodManager::class.java)?.restartInput(view)
    }

    val mention = if (members.isEmpty()) null else {
        activeMentionQuery(textState.text.toString(), textState.selection.end)
    }
    val matches = remember(mention?.query, members) {
        val q = mention?.query?.lowercase() ?: return@remember emptyList()
        members.filter {
            (it.nickname ?: it.user.displayName).lowercase().contains(q) ||
                it.user.username.lowercase().contains(q)
        }.take(MENTION_LIMIT)
    }

    val pickMention: (ServerMember) -> Unit = { m ->
        val q = mention
        if (q != null) {
            val handle = m.user.username
            textState.edit {
                replace(q.start, q.start + q.query.length + 1, "@$handle ")
                selection = TextRange(q.start + handle.length + 2)
            }
        }
    }

    val shortcode = EmojiSearch.activeShortcode(textState.text.toString(), textState.selection.end)
    val emojiMatches = remember(shortcode?.query, customEmojis) {
        val q = shortcode?.query ?: return@remember emptyList()
        val custom = customEmojis
            .filter { it.name.lowercase().contains(q) }
            .map { EmojiSuggestion(":${it.name}:", ":${it.name}:", url = it.url) }
        val unicode = EmojiSearch.search(q, EMOJI_SUGGESTION_LIMIT)
            .map { EmojiSuggestion(":${it.name}:", it.char, char = it.char) }
        (custom + unicode).take(EMOJI_SUGGESTION_LIMIT)
    }

    val pickEmoji: (EmojiSuggestion) -> Unit = { suggestion ->
        val q = shortcode
        if (q != null) {
            val caret = textState.selection.end
            textState.edit {
                replace(q.start, caret, "${suggestion.insert} ")
                selection = TextRange(q.start + suggestion.insert.length + 1)
            }
            onTyping()
        }
    }

    val uploads by drafts.uploads.collectAsState()
    val draftError by drafts.error.collectAsState()
    val visibleUploads = uploads.filter { it.key != autoSendVoiceKey }

    LaunchedEffect(autoSendVoiceKey, uploads) {
        val key = autoSendVoiceKey ?: return@LaunchedEffect
        val upload = uploads.firstOrNull { it.key == key } ?: return@LaunchedEffect
        if (upload.error != null) {
            autoSendVoiceKey = null
            drafts.remove(key)
            recordingError = upload.error
            return@LaunchedEffect
        }
        if (upload.settled) {
            autoSendVoiceKey = null
            drafts.remove(key)
            onSend(
                "",
                listOfNotNull(upload.attachment?.id, upload.sealed?.thumb?.attachmentId),
                listOfNotNull(upload.sealed),
            )
        }
    }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetMultipleContents(),
    ) { uris -> drafts.add(uris, channelId) }

    fun launchCamera() {
        if (channelId == null) return
        val uri = runCatching {
            val directory = java.io.File(context.cacheDir, "camera").apply { mkdirs() }
            val file = java.io.File(directory, "photo-${java.util.UUID.randomUUID()}.jpg")
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        }.getOrNull() ?: run {
            recordingError = "Could not prepare the camera"
            return
        }
        pendingCameraUri = uri
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            cameraLauncher.launch(uri)
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    LaunchedEffect(channelId) {
        var lastTypingSent = 0L
        snapshotFlow { textState.text.toString() }
            .drop(1)
            .collect { text ->
                val now = System.currentTimeMillis()
                if (text.isNotEmpty() && now - lastTypingSent > TYPING_THROTTLE_MS) {
                    lastTypingSent = now
                    onTyping()
                }
                if (channelId != null) textDrafts.save(channelId, text.trim())
            }
    }

    val contentListener = remember(drafts) {
        object : ReceiveContentListener {
            override fun onReceive(transferableContent: TransferableContent): TransferableContent? {
                if (!transferableContent.hasMediaType(MediaType.Image)) return transferableContent
                val received = mutableListOf<Uri>()
                val remaining = transferableContent.consume { item ->
                    val uri = item.uri
                    if (uri == null) false else { received.add(uri); true }
                }
                if (received.isNotEmpty()) drafts.add(received, channelId)
                return remaining
            }
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) voiceState.forceCancel()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (channelId != null) {
        DisposableEffect(channelId) {
            onDispose {
                textDrafts.saveNow(channelId, textState.text.toString().trim())
                voiceState.forceCancel()
                voiceRecorder.cancel()
                pendingCameraUri?.let { uri ->
                    runCatching { context.contentResolver.delete(uri, null, null) }
                }
                drafts.clear()
            }
        }
    }

    Column {
        if (matches.isNotEmpty()) {
            MentionSuggestions(matches = matches, onPick = pickMention)
        }
        if (shortcode != null && emojiMatches.isNotEmpty()) {
            EmojiSuggestions(
                query = shortcode.query,
                matches = emojiMatches,
                onPick = pickEmoji,
            )
        }
        if (channelId != null) {
            ComposerAttachments(uploads = visibleUploads, onRemove = drafts::remove)
            autoSendVoiceKey?.let { key ->
                val pending = uploads.firstOrNull { it.key == key }
                if (pending != null && !pending.settled) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                    ) {
                        LinearProgressIndicator(
                            progress = { pending.progress },
                            color = c.primary,
                            trackColor = c.surface1,
                            modifier = Modifier.width(64.dp).height(3.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(AppStrings.get(context, R.string.catalog_sending_voice_message_a48b5648), color = c.inkSecondary, fontSize = 12.sp)
                    }
                }
            }
            draftError?.let {
                Text(
                    it,
                    color = c.danger,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                    .clickable { drafts.dismissError() },
                )
            }
            recordingError?.let {
                Text(
                    it,
                    color = c.danger,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                        .clickable { recordingError = null },
                )
            }
            recordingHint?.let {
                Text(
                    it,
                    color = c.inkSecondary,
                    fontSize = 13.sp,
                    modifier = Modifier
                        .padding(vertical = 2.dp)
                        .align(Alignment.CenterHorizontally)
                        .background(c.surface4, RoundedCornerShape(OrangRadius.xl2))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }

    val ready = visibleUploads.flatMap { upload ->
        listOfNotNull(upload.attachment?.id, upload.sealed?.thumb?.attachmentId)
    }
    val enabled = (textState.text.isNotBlank() || ready.isNotEmpty() || allowEmpty) &&
        visibleUploads.none { !it.settled } &&
        visibleUploads.none { it.error != null }
    val sendDraft: () -> Unit = {
        val content = textState.text.toString().trim()
        val kind = findPastedTokenKind(content)
        if (kind != null && content != acknowledgedTokenContent) {
            tokenWarning = kind
        } else {
            onSend(
                content,
                ready,
                visibleUploads.mapNotNull { it.sealed },
            )
            textState.setTextAndPlaceCursorAtEnd("")
            drafts.dismissError()
            visibleUploads.forEach { drafts.remove(it.key) }
            channelId?.let { textDrafts.clear(it) }
            resetKeyboard()
        }
    }

    val draftLength = textState.text.length
    if (draftLength >= MESSAGE_LENGTH_WARNING_THRESHOLD) {
        val atLimit = draftLength >= MESSAGE_MAX_LENGTH
        Text(
            "$draftLength/$MESSAGE_MAX_LENGTH",
            color = if (atLimit) c.danger else c.inkMuted,
            fontSize = 12.sp,
            fontWeight = if (atLimit) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier
                .align(Alignment.End)
                .padding(horizontal = 16.dp, vertical = 2.dp),
        )
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            Icon(
                Icons.Default.EmojiEmotions,
                contentDescription = AppStrings.get(context, R.string.catalog_add_emoji_or_gif_1d91d61d),
                tint = c.inkMuted,
                modifier = Modifier
                    .size(38.dp)
                    .clickable { emojiOpen = true }
                    .padding(7.dp),
            )
            ExpressionPickerDialog(
                expanded = emojiOpen,
                onDismiss = { emojiOpen = false },
                gifsEnabled = channelId != null,
                customEmojis = customEmojis,
                onEmoji = { emoji ->
                    textState.edit { append(emoji) }
                    onTyping()
                },
                onGif = { url -> onSend(url, emptyList(), emptyList()) },
            )
        }
        if (channelId != null) {
            Box {
                Icon(
                    Icons.Default.AttachFile,
                    contentDescription = AppStrings.get(context, R.string.catalog_attach_files_137135db),
                    tint = c.inkMuted,
                    modifier = Modifier
                        .size(38.dp)
                        .clickable { attachmentMenuOpen = true }
                        .padding(7.dp),
                )
                OrangDropdownMenu(
                    expanded = attachmentMenuOpen,
                    onDismiss = { attachmentMenuOpen = false },
                    items = listOf(
                        MenuItem(AppStrings.get(context, R.string.catalog_choose_files_5910acf0), Icons.Default.AttachFile) { picker.launch("*/*") },
                        MenuItem(AppStrings.get(context, R.string.catalog_take_a_picture_6d76bc64), Icons.Default.CameraAlt) { launchCamera() },
                    ),
                )
            }
        }
        Spacer(Modifier.width(6.dp))
        Box(
            modifier = Modifier
                .weight(1f)
                .background(c.surface3, RoundedCornerShape(OrangRadius.xl))
                .border(1.dp, c.border, RoundedCornerShape(OrangRadius.xl))
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            if (voiceState.phase.isRecording) {
                VoiceRecordingStrip(state = voiceState, colors = voiceColors)
            } else {
                if (textState.text.isEmpty()) {
                    Text("Message", color = c.inkMuted, fontSize = 15.sp)
                }
                val composerStyle = TextStyle(color = c.ink, fontSize = 15.sp)
                val draftText = textState.text.toString()
                BasicText(
                    text = remember(draftText, customEmojis) {
                        highlightEmoji(draftText, c.info, customEmojis)
                    },
                    style = composerStyle,
                    modifier = Modifier.fillMaxWidth(),
                )
                BasicTextField(
                    state = textState,
                    textStyle = composerStyle.copy(color = Color.Transparent),
                    cursorBrush = SolidColor(c.primary),
                    lineLimits = TextFieldLineLimits.MultiLine(),
                    inputTransformation = InputTransformation.maxLength(MESSAGE_MAX_LENGTH),
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                        keyboardType = KeyboardType.Text,
                    ),
                    modifier = Modifier
                        .focusRequester(focusRequester)
                        .fillMaxWidth()
                        .then(
                            if (channelId != null) Modifier.contentReceiver(contentListener)
                            else Modifier,
                        ),
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        if (channelId != null) {
            if (voiceState.phase == VoicePhase.RecordingLocked) {
                VoiceDeleteButton(
                    onClick = voiceState::cancelFromLock,
                    colors = voiceColors,
                )
            } else {
                VoiceMicButton(
                    state = voiceState,
                    canStart = canStartMic,
                    onTapTooShort = { recordingHint = AppStrings.get(context, R.string.catalog_hold_the_mic_to_record_then_let_48d81b83) },
                    colors = voiceColors,
                )
            }
        }
        val sendingVoice = voiceState.phase == VoicePhase.RecordingLocked
        val sendEnabled = when {
            sendingVoice -> true
            voiceState.phase.isRecording -> false
            else -> enabled
        }
        Box(
            modifier = Modifier
                .size(44.dp)
                .background(if (sendEnabled) c.primary else c.surface4, CircleShape)
                .clickable(enabled = sendEnabled) {
                    if (sendingVoice) voiceState.sendFromLock() else sendDraft()
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.AutoMirrored.Filled.Send,
                contentDescription = submitLabel ?: "Send",
                tint = if (sendEnabled) c.inkOnPrimary else c.inkMuted,
                modifier = Modifier.size(20.dp),
            )
        }
    }

    tokenWarning?.let { kind ->
        OrangDialog(
            onDismiss = { tokenWarning = null },
            title = if (kind == PastedTokenKind.LOGIN) {
                "That looks like a login token"
            } else {
                "That looks like a bot token"
            },
        ) {
            Text(
                if (kind == PastedTokenKind.LOGIN) {
                    "A login token lets anyone who has it sign into an OrangChat " +
                        "account. Sending it would hand that account to everyone who can " +
                        "read this conversation."
                } else {
                    "A bot token is the password for a bot account: anyone with it can " +
                        "sign in as the bot, post in its servers and read its " +
                        "conversations. Sending it would hand that control to everyone " +
                        "who can read this conversation."
                },
                color = c.inkSecondary,
                fontSize = 14.sp,
            )
            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = { tokenWarning = null }) {
                    Text("Cancel", color = c.inkSecondary)
                }
                TextButton(
                    onClick = {
                        acknowledgedTokenContent = textState.text.toString().trim()
                        tokenWarning = null
                        sendDraft()
                    },
                ) {
                    Text(AppStrings.get(context, R.string.catalog_send_anyway_911263fe), color = c.danger)
                }
            }
        }
    }
    }
}

private const val MENTION_LIMIT = 8

private const val EMOJI_SUGGESTION_LIMIT = 8

/** A row of the `:xx` panel. [insert] is what lands in the composer. */
data class EmojiSuggestion(
    val label: String,
    val insert: String,
    val url: String? = null,
    val char: String? = null,
)

@Composable
private fun EmojiSuggestions(
    query: String,
    matches: List<EmojiSuggestion>,
    onPick: (EmojiSuggestion) -> Unit,
) {
    val c = OrangTheme.colors
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .background(c.surface3, RoundedCornerShape(OrangRadius.lg))
            .border(1.dp, c.border, RoundedCornerShape(OrangRadius.lg))
            .padding(4.dp),
    ) {
        Text(
            AppStrings.get(context, R.string.catalog_emoji_matching_1_s_2d947ad2, ":$query"),
            color = c.inkMuted,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
        matches.forEach { suggestion ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(OrangRadius.md))
                    .clickable { onPick(suggestion) }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            ) {
                if (suggestion.url != null) {
                    AsyncImage(
                        model = suggestion.url,
                        contentDescription = suggestion.label,
                        modifier = Modifier.size(22.dp),
                    )
                } else {
                    Text(suggestion.char.orEmpty(), fontSize = 18.sp)
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    suggestion.label,
                    color = c.ink,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private enum class PastedTokenKind { LOGIN, BOT }

private fun findPastedTokenKind(text: String): PastedTokenKind? {
    val login = Regex(
        "\\b[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}\\b",
        RegexOption.IGNORE_CASE,
    )
    if (login.containsMatchIn(text)) return PastedTokenKind.LOGIN
    val bot = Regex("\\b[A-Za-z0-9_-]{6,24}\\.[A-Za-z0-9_-]{32,80}\\b")
    if (bot.containsMatchIn(text)) return PastedTokenKind.BOT
    return null
}

@Composable
private fun MentionSuggestions(
    matches: List<ServerMember>,
    onPick: (ServerMember) -> Unit,
) {
    val c = OrangTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .background(c.surface3, RoundedCornerShape(OrangRadius.lg))
            .border(1.dp, c.border, RoundedCornerShape(OrangRadius.lg))
            .padding(4.dp),
    ) {
        matches.forEach { m ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(OrangRadius.md))
                    .clickable { onPick(m) }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            ) {
                Avatar(user = m.user, size = 24.dp)
                Spacer(Modifier.width(8.dp))
                Text(
                    m.nickname ?: m.user.displayName,
                    color = c.ink,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "@${m.user.username}",
                    color = c.inkMuted,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
