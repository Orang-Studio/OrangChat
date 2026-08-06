package lt.oranges.orangchat.feature.chat

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
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
import androidx.compose.material.icons.filled.GroupAdd
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.material3.CircularProgressIndicator
import lt.oranges.orangchat.ui.components.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.hilt.navigation.compose.hiltViewModel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
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
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import lt.oranges.orangchat.util.absoluteUrl
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
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
import lt.oranges.orangchat.data.model.UserActivity
import lt.oranges.orangchat.ui.components.ActivityStatus
import lt.oranges.orangchat.ui.components.Avatar
import lt.oranges.orangchat.ui.components.BotTag
import lt.oranges.orangchat.ui.components.MenuItem
import lt.oranges.orangchat.ui.components.OrangDropdownMenu
import lt.oranges.orangchat.ui.components.OrangTextField
import lt.oranges.orangchat.ui.components.OrangButton
import lt.oranges.orangchat.ui.components.OrangDialog
import lt.oranges.orangchat.ui.components.ButtonSize
import lt.oranges.orangchat.ui.components.ButtonVariant
import lt.oranges.orangchat.ui.theme.OrangRadius
import lt.oranges.orangchat.ui.theme.OrangTheme
import lt.oranges.orangchat.util.EmojiRef
import lt.oranges.orangchat.util.MentionUser
import lt.oranges.orangchat.util.Mentions
import lt.oranges.orangchat.util.formatTime
import lt.oranges.orangchat.util.parseInstant
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit

/** How far a message must slide left before it becomes a reply. */
private val REPLY_TRIGGER = 64.dp

/** Keep pulling your own message past this and it opens for editing instead. */
private val EDIT_TRIGGER = 148.dp

/** What a swipe of the current length would do if the finger lifted now. */
private enum class SwipeAction { NONE, REPLY, EDIT }

/**
 * Leftward drags only, and only once past touch slop.
 *
 * Rightward drags are deliberately left unclaimed so they reach the navigation
 * drawer, which then tracks the finger. Anything a child has already consumed -
 * a media scrubber, above all - is that child's gesture and never a swipe:
 * dragging the playhead must not also arm a reply.
 */
private suspend fun PointerInputScope.detectSwipeToReply(
    onDelta: (Float) -> Unit,
    onRelease: () -> Unit,
    onCancel: () -> Unit,
) {
    awaitEachGesture {
        // Not requireUnconsumed, and the down's own consumed flag is ignored:
        // this row's combinedClickable claims every down it sees. Movement is
        // what tells a swipe apart from a child's gesture, so that is what gets
        // checked below.
        val down = awaitFirstDown(requireUnconsumed = false)
        val slop = viewConfiguration.touchSlop
        var travel = Offset.Zero
        var claimed = false
        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            // The lift is checked before consumption: who consumed the up does
            // not matter, only how far this row actually travelled.
            if (!change.pressed) {
                if (claimed) onRelease()
                return@awaitEachGesture
            }
            if (change.isConsumed) {
                if (claimed) onCancel()
                return@awaitEachGesture
            }
            if (claimed) {
                // Read the delta before consuming: positionChange() reports zero
                // once the change is marked consumed.
                val dx = change.positionChange().x
                change.consume()
                onDelta(dx)
                continue
            }
            travel += change.positionChange()
            // Scrolling the list, or heading for the drawer: not ours.
            if (abs(travel.y) > slop || travel.x > slop) return@awaitEachGesture
            if (travel.x < -slop) {
                claimed = true
                change.consume()
                // Only the distance past slop, so the row starts from rest
                // instead of jumping by the slop it took to get here.
                onDelta(travel.x + slop)
            }
        }
    }
}

/** Consecutive messages within this window from the same author are grouped. */
private const val GROUP_WINDOW_MS = 5 * 60 * 1000L

/**
 * Shortest gap between two typing packets from this device. Matches the web
 * composer; receivers hold the indicator for a window plus grace, so a sender
 * who backgrounds the app fades out instead of sticking.
 */
private const val TYPING_THROTTLE_MS = 4_000L

/** How long a jumped-to message stays lit before fading back. */
private const val HIGHLIGHT_MS = 1_600L

private fun formatRecordingTime(totalSeconds: Long): String {
    val minutes = totalSeconds / 60
    return "$minutes:${(totalSeconds % 60).toString().padStart(2, '0')}"
}

/**
 * How far the finger has to travel off the mic before a swipe counts. Far
 * enough that the wobble of holding a phone one-handed never trips it, close
 * enough to reach with the same thumb that is already pressing.
 */
private val VOICE_SWIPE_THRESHOLD = 72.dp

/**
 * A press shorter than this was a tap, not a hold. Anything this brief is
 * inaudible anyway, so it is discarded with a hint rather than sent as a
 * quarter-second of room tone.
 */
private const val VOICE_MIN_HOLD_MS = 600L

/**
 * Older pages a jump is allowed to pull in before it gives up. 12 pages of 50
 * is six hundred messages back - far enough for a search hit anyone actually
 * meant to open, and short of quietly downloading an entire channel.
 */
private const val JUMP_MAX_PAGES = 12

/** Gap between checks while a page is in flight. */
private const val JUMP_POLL_MS = 200L

/** Roughly ten seconds of waiting for a channel's own first page to arrive. */
private const val JUMP_MAX_INITIAL_WAITS = 50

private const val JUMP_MISSING_NOTICE_MS = 4_000L

/** A message plus its grouping flag, decided chronologically before the list is
 * reversed for display. [notice] is set when the row is a system notice rather
 * than something somebody said. */
private data class MessageRowData(
    val message: Message,
    val grouped: Boolean,
    val notice: SystemNotice? = null,
)

/** Namespaced so a local client id can never collide with a server message id. */
private fun messageRowKey(message: Message): String =
    message.clientId?.let { "client:$it" } ?: "server:${message.id}"

/**
 * Whether [message] should hide its avatar and header because it continues
 * [previous]'s run - same author, close in time, and not a reply (a reply needs
 * its own context line, so it always starts a fresh block).
 */
private fun isGrouped(previous: Message?, message: Message): Boolean {
    if (previous == null) return false
    // A notice is a break in the conversation, not a line of it: letting the
    // message after one keep its run would hide its header behind a divider.
    if (SystemNotice.of(message.content) != null) return false
    if (SystemNotice.of(previous.content) != null) return false
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
    /** Identifies the draft: switching channels drops any staged attachments. */
    channelId: String,
    messages: List<Message>,
    pendingMessageIds: Set<String> = emptySet(),
    selfId: String,
    members: List<ServerMember>,
    presence: Map<String, PresenceStatus>,
    typingUserIds: Set<String>,
    onBack: () -> Unit,
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
    /** Fetch the page before the oldest message held. */
    onLoadOlder: () -> Unit = {},
    loadingOlder: Boolean = false,
    /** False once history has been read back to the very first message. */
    hasOlder: Boolean = true,
    /**
     * A message to scroll to on arrival - a search hit, or any other deep link
     * into the middle of a conversation. Pages history in until it is loaded.
     */
    jumpToMessageId: String? = null,
    /** Called once the jump has landed or been given up on, so it fires once. */
    onJumpHandled: () -> Unit = {},
    /** Accessibility: tighter row spacing. */
    compact: Boolean = false,
    /** Accessibility: jump instead of animating the scroll-to-bottom. */
    reducedMotion: Boolean = false,
    /** Non-null only for DMs / group DMs, which are the only callable channels. */
    onStartCall: ((video: Boolean) -> Unit)? = null,
    /** Non-null only for group DMs: opens the friend picker to add people. */
    onAddPeople: (() -> Unit)? = null,
    /** True while we are already on this conversation's call. */
    onCall: Boolean = false,
    /** The other DM participant, shown beside the conversation title. */
    headerUser: User? = null,
    /** Live activity for the other DM participant. */
    headerActivities: List<UserActivity> = emptyList(),
    /** Open someone's profile card. Hoisted, because the actions it offers -
     *  message, add friend - are the host's to perform, not the chat's. */
    onOpenProfile: (User) -> Unit = {},
    /** emojiId -> emoji, for resolving `<:name:id>` in message text and for the picker. */
    emojis: Map<String, EmojiRef> = emptyMap(),
    encryptionInfo: AppViewModel.ConversationEncryptionInfo? = null,
    onResetEncryption: (() -> Unit)? = null,
    onSetStrict: ((Boolean) -> Unit)? = null,
    onVerifyContact: ((String, String, (Boolean, String?) -> Unit) -> Unit)? = null,
    /** Verification for people who are not in the same room (§6.6). */
    onCompareSafetyNumber: (
        (String, (AppViewModel.SafetyNumberVerdict) -> Unit) -> Unit
    )? = null,
    /** DM-only: shared chat background shown behind the messages. */
    backgroundUrl: String? = null,
    /** Non-null only for DMs / group DMs: pick an image for the background. */
    onSetBackground: ((Uri) -> Unit)? = null,
    /** Clear the shared background. Shown only while one is set. */
    onRemoveBackground: (() -> Unit)? = null,
) {
    val customEmojis = remember(emojis) { emojis.values.sortedBy { it.name.lowercase() } }
    val c = OrangTheme.colors
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

    // Rendered newest-first into a reverseLayout list, so index 0 is the visual
    // bottom. This is what pins the view to the newest message and keeps the
    // viewport still when an older page is prepended - the browser client gets
    // the same property from `flex-col-reverse`. Grouping is decided on the
    // chronological order first, then the rows are flipped for display.
    val rows = remember(messages) {
        // History and a socket ack can overlap while a page is loading. Keep one
        // row per server id, then guard the UI key against namespace collisions.
        val deduped = messages
            .distinctBy { it.id }
            .distinctBy(::messageRowKey)
        deduped
            .mapIndexed { i, m ->
                MessageRowData(m, isGrouped(deduped.getOrNull(i - 1), m), SystemNotice.of(m.content))
            }
            .asReversed()
    }

    // Ask for the page before the oldest row once it comes into view. Driven by
    // the *last* visible index (the visual top under reverseLayout) rather than
    // by messages.size, so a prepended page cannot retrigger the load that
    // produced it - that feedback loop used to pull the whole channel in at once.
    LaunchedEffect(listState, rows.size) {
        snapshotFlow {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            rows.isNotEmpty() && last >= rows.size - 3
        }
            .distinctUntilChanged()
            .collect { nearOldest -> if (nearOldest) onLoadOlder() }
    }

    // Follow the conversation only when already at the bottom; someone reading
    // back through history should not be yanked forward by a new arrival.
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

    // Landing on a message you did not scroll to is disorienting without it
    // saying which one it was. Keyed on the id, so a second jump restarts the
    // fade rather than being cut short by the first one's timer.
    var highlightedId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(highlightedId) {
        if (highlightedId != null) {
            delay(HIGHLIGHT_MS)
            highlightedId = null
        }
    }

    /**
     * A search hit is usually older than the page a channel opens on, so the
     * message being jumped to is very often not loaded yet - which is why
     * tapping a result used to do nothing but open the channel at the bottom.
     * Pull older pages in until it appears, the same walk the browser client
     * does, and give up rather than read a whole channel back to its first
     * message.
     */
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
                // Snap rather than animate: the hit is usually several pages up,
                // and scrolling there flings the whole conversation past the
                // reader for no gain. The highlight flash is what points it out.
                listState.scrollToItem(index)
                highlightedId = target
                landed = true
                break
            }
            if (liveRows.isEmpty()) {
                // The channel's own first page is still in flight. Waiting for
                // it is not one of our page budget - there is nothing to page
                // back from yet.
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
        // Deleted, or further back than we are willing to walk.
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

    // <@id> resolution for the markdown renderer.
    val mentionNames = remember(members) {
        members.associate { it.userId to (it.nickname ?: it.user.displayName) }
    }

    // @username resolution for the markdown renderer.
    val mentionUsers = remember(members) {
        members.associate {
            it.user.username.lowercase() to
                MentionUser(it.userId, it.nickname ?: it.user.displayName)
        }
    }

    // Swipe-to-reply target, cleared once the reply is sent or dismissed.
    var replyTo by remember { mutableStateOf<Message?>(null) }
    var encryptionOpen by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val relaxStrict = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) onSetStrict?.invoke(false)
    }

    // Tapping a reply's quoted line jumps to what it answers. Only messages
    // already loaded can be reached - an unloaded parent renders no quote line
    // to tap in the first place, so there is nothing to jump to.
    val jumpScope = rememberCoroutineScope()
    val jumpToMessage: (String) -> Unit = { id ->
        val index = rows.indexOfFirst { it.message.id == id }
        if (index >= 0) {
            jumpScope.launch {
                if (reducedMotion) listState.scrollToItem(index) else listState.animateScrollToItem(index)
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

    // The soft keyboard is handled by the root safeDrawing inset in MainActivity,
    // which lifts this whole pane; a second imePadding here would double it.
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(c.surface2),
    ) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(c.surface2)
                .padding(horizontal = 8.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                tint = c.inkSecondary,
                modifier = Modifier.clickable(onClick = onBack).padding(4.dp),
            )
            if (headerUser != null) {
                Avatar(
                    user = headerUser,
                    size = 28.dp,
                    // A DM participant's embedded status is their saved
                    // preference; absent live presence must read as offline.
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
                    contentDescription = if (onCall) "Already on this call" else "Start voice call",
                    tint = if (onCall) c.inkMuted.copy(alpha = 0.4f) else c.inkSecondary,
                    modifier = Modifier
                        .clickable(enabled = !onCall) { onStartCall(false) }
                        .padding(6.dp)
                        .size(20.dp),
                )
                Icon(
                    Icons.Default.Videocam,
                    contentDescription = if (onCall) "Already on this call" else "Start video call",
                    tint = if (onCall) c.inkMuted.copy(alpha = 0.4f) else c.inkSecondary,
                    modifier = Modifier
                        .clickable(enabled = !onCall) { onStartCall(true) }
                        .padding(6.dp)
                        .size(20.dp),
                )
            }
            // Everything that isn't a call or a state indicator goes behind one
            // menu. A phone header fits about four taps before the title starts
            // truncating, and a row of anonymous glyphs - a bare X for "remove
            // the background" among them - is not readable at any width.
            val overflow = buildList {
                if (onSearch != null) {
                    add(MenuItem("Search messages", Icons.Default.Search, onClick = onSearch))
                }
                if (onAddPeople != null) {
                    add(MenuItem("Add people", Icons.Default.GroupAdd, onClick = onAddPeople))
                }
                if (onSetBackground != null) {
                    add(
                        MenuItem(
                            if (backgroundUrl != null) "Change background" else "Set chat background",
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
                                "Remove background for everyone",
                                Icons.Default.Delete,
                                destructive = true,
                                onClick = onRemoveBackground,
                            ),
                        )
                    }
                }
            }
            // A channel header carries search alone rather than hiding the one
            // thing in it behind a second tap.
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

        // A jump that could not reach its message has to say so. Silently
        // sitting at the bottom of the channel is indistinguishable from the
        // tap having been ignored, which is exactly how this read before.
        if (jumpMissing) {
            Text(
                text = "Couldn't reach that message - it may be too far back or deleted.",
                color = c.inkMuted,
                fontSize = 12.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(c.surface1)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }

        // The return affordance floats over history; giving it a row of its own
        // would shorten the conversation precisely when someone is reading it.
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            // Shared DM background, under everything else. Plaintext, like
            // avatars: everyone in the conversation sees the same image. The
            // scrim on top is what keeps the messages readable over whatever
            // picture somebody picked.
            if (backgroundUrl != null) {
                AsyncImage(
                    model = absoluteUrl(backgroundUrl),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                Box(Modifier.fillMaxSize().background(c.surface2.copy(alpha = 0.8f)))
            }
            // Messages. reverseLayout: first item = visual bottom = newest.
            LazyColumn(
                state = listState,
                reverseLayout = true,
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp),
            ) {
                // Key on the local id where there is one: a message's `id` changes
                // when the server confirms it, and keying on that alone disposes the
                // row and builds a new one, replaying the insert animation.
                items(rows, key = { messageRowKey(it.message) }) { row ->
                    val message = row.message
                    val notice = row.notice
                    if (notice != null) {
                        SystemNoticeRow(notice, message, selfId)
                        return@items
                    }
                    MessageRow(
                        message = message,
                        pending = message.id in pendingMessageIds,
                        selfId = selfId,
                        presence = presence[message.author.id],
                        grouped = row.grouped,
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
                // Last in a reversed list = the visual top, where older history loads.
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

            val latestLabel = if (hasNewMessages) "New messages" else "Jump to latest"
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

        // A conversation still in plaintext says so, and names who it is waiting
        // on, rather than just showing no padlock and letting people assume
        // (docs/E2EE.md §10.1).
        encryptionInfo?.waitingOn?.takeIf { it.isNotEmpty() }?.let { waiting ->
            val names = waiting.mapNotNull(nameOf)
            val who = when (names.size) {
                0 -> "Someone here"
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
                        "This conversation is not encrypted yet",
                        color = c.ink,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        "$who ${if (one) "has" else "have"} not set up encryption on any device, " +
                            "and a locked message needs a key on their side to open it. " +
                            "Messages here are stored the ordinary way until then - it switches on by itself " +
                            "the moment ${if (one) "they open" else "they all open"} OrangChat on a phone or computer.",
                        color = c.inkSecondary,
                        fontSize = 11.sp,
                    )
                }
            }
        }

        // Typing indicator
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

        // Reply banner: what you swiped, and a way out of it.
        replyTo?.let { target ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(c.surface3)
                    .padding(horizontal = 16.dp, vertical = 6.dp),
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
                    contentDescription = "Cancel reply",
                    tint = c.inkMuted,
                    modifier = Modifier.size(16.dp).clickable { replyTo = null },
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
                    if (reportSent) "Report received" else "Report message",
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
                                "Reporting reveals this one decrypted message and a one-message key. It does not reveal the conversation key or any other message.",
                                color = c.inkMuted,
                                fontSize = 12.sp,
                            )
                        }
                        OrangTextField(
                            value = reportReason,
                            onValueChange = { reportReason = it.take(1000) },
                            label = "What happened? (optional)",
                            placeholder = "Add context for the reviewer",
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
                        Text(if (reportSent) "Done" else "Send report", color = if (reportSent) c.primary else c.danger)
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
            title = "Scan verification code",
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
                        "Is this ${contact?.displayName ?: "the right person"}?"
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
                        Text(if (contactVerified) "Done" else "Yes, that's them", color = c.primary)
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

/**
 * A system notice travels as an ordinary message because there is no
 * system-message channel to carry it, but reading it as one of the sender's
 * remarks gets it wrong - it is a fact about the conversation. So it is drawn
 * centred and unbubbled, keeping the name, since who changed what is the whole
 * point of sending it.
 */
@Composable
private fun SystemNoticeRow(notice: SystemNotice, message: Message, selfId: String) {
    val c = OrangTheme.colors
    val name = if (message.author.id == selfId) "You" else message.author.displayName
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "- ${notice.describe(name)} -",
            color = c.inkMuted,
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageRow(
    message: Message,
    pending: Boolean,
    selfId: String,
    presence: PresenceStatus?,
    grouped: Boolean,
    compact: Boolean,
    nameOf: (String) -> String?,
    mentionNames: Map<String, String>,
    mentionUsers: Map<String, MentionUser>,
    /** Members offered by @mention autocomplete while editing this message. */
    members: List<ServerMember>,
    /** The message this one replies to, if it is loaded. */
    repliedTo: Message?,
    /** Briefly tinted, because someone just jumped here. */
    highlighted: Boolean,
    /** The row selected by the reply composer. */
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
    // Tint the whole row when the message pings us (never our own messages).
    val pinged = !isMine && lt.oranges.orangchat.util.mentionsSelf(
        message.content,
        lt.oranges.orangchat.util.MentionContext(mentionNames, mentionUsers, selfId),
    )

    // Swipe right-to-left to reply; on your own messages, keep going and it
    // becomes an edit instead. Springs back either way.
    val dragScope = rememberCoroutineScope()
    val dragX = remember(message.id) { Animatable(0f) }
    val density = LocalDensity.current
    val replyPx = with(density) { REPLY_TRIGGER.toPx() }
    val editPx = with(density) { EDIT_TRIGGER.toPx() }
    // Only your own messages have anywhere to go past the reply threshold.
    val maxDrag = if (isMine) editPx * 1.2f else replyPx * 1.4f
    val haptics = LocalHapticFeedback.current

    val armed = when {
        isMine && dragX.value <= -editPx -> SwipeAction.EDIT
        dragX.value <= -replyPx -> SwipeAction.REPLY
        else -> SwipeAction.NONE
    }
    // A tick as each threshold is crossed, so the swipe says what it will do
    // before the finger commits to it. Gated on the finger still being down:
    // the spring back to rest re-crosses every threshold on its way, and those
    // are not crossings the user made.
    // Fades back to the normal row color once the highlight lapses, so the jump
    // draws the eye without leaving the message looking permanently marked.
    val restColor = when {
        replyingTo -> c.primary.copy(alpha = 0.11f)
        pinged -> c.primary.copy(alpha = 0.08f)
        else -> c.surface2
    }
    val rowBackground by animateColorAsState(
        targetValue = if (highlighted) c.primarySoft else restColor,
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
        // Affordance revealed at the right edge as the row slides left.
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
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (pending) 0.5f else 1f)
            .offset { IntOffset(dragX.value.roundToInt(), 0) }
            .then(
                if (replyingTo || pinged) {
                    Modifier.shadow(
                        elevation = 8.dp,
                        shape = RoundedCornerShape(OrangRadius.md),
                        ambientColor = c.primary.copy(alpha = 0.18f),
                        spotColor = c.primary.copy(alpha = 0.18f),
                    )
                } else {
                    Modifier
                },
            )
            .pointerInput(message.id, isMine, pending) {
                if (!pending) detectSwipeToReply(
                    onDelta = { amount ->
                        dragging = true
                        dragScope.launch {
                            dragX.snapTo((dragX.value + amount).coerceIn(-maxDrag, 0f))
                        }
                    },
                    // Read dragX here rather than closing over `armed`: this
                    // lambda is captured once, but the Animatable is live.
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
            .background(rowBackground)
            // Long-press, not tap: a tap lands on a message constantly while
            // reading, and every one of them used to open this menu.
            .combinedClickable(
                enabled = !pending,
                onClick = {},
                onLongClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    menuOpen = true
                },
            )
            .padding(
                start = 16.dp,
                end = 16.dp,
                top = if (grouped) 1.dp else if (compact) 2.dp else 4.dp,
                bottom = if (compact) 1.dp else 2.dp,
            ),
        verticalAlignment = Alignment.Top,
    ) {
        // A grouped message keeps the avatar column's width so text stays aligned,
        // but shows the timestamp there on hover-free mobile it stays blank.
        if (grouped) {
            Spacer(Modifier.width(38.dp))
        } else {
            Avatar(
                message.author,
                size = 38.dp,
                status = presence,
                // Takes the tap before the row's own click, so opening a profile
                // does not also open the message menu behind it.
                modifier = Modifier.clickable { onOpenProfile(message.author) },
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            // Reply context, as a single quoted line above the message.
            repliedTo?.let { parent ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    // Takes the tap ahead of the row's long-press menu: this
                    // quote is a link back to what it answers.
                    modifier = Modifier
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
                        Text("(edited)", color = c.inkMuted, fontSize = 10.sp)
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
                // Attachment-only messages have no text to draw.
                if (message.content.isNotBlank()) {
                    MessageText(
                        content = message.content,
                        mentionNames = mentionNames,
                        mentionUsers = mentionUsers,
                        selfId = selfId,
                        emojis = renderEmojis,
                        fontSize = 15.sp,
                        onMentionClick = { userId ->
                            members.firstOrNull { it.userId == userId }?.user?.let(onOpenProfile)
                        },
                    )
                }
                MessageAttachments(message.attachments)
            }
            if (message.reactions.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    message.reactions.forEach { r ->
                        Row(
                            modifier = Modifier
                                .background(
                                    if (r.me) c.primarySoft else c.surface3,
                                    RoundedCornerShape(OrangRadius.xl),
                                )
                                .border(
                                    1.dp,
                                    if (r.me) c.primary else c.border,
                                    RoundedCornerShape(OrangRadius.xl),
                                )
                                .clickable { onReact(message, r.emoji) }
                                .padding(horizontal = 8.dp, vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(r.emoji, fontSize = 13.sp)
                            Spacer(Modifier.width(4.dp))
                            Text("${r.count}", color = if (r.me) c.primary else c.inkSecondary, fontSize = 12.sp)
                        }
                    }
                    Box {
                        Icon(
                            Icons.Default.AddReaction,
                            contentDescription = "Add reaction",
                            tint = c.inkMuted,
                            modifier = Modifier.size(20.dp).clickable { emojiOpen = true },
                        )
                    }
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
                            MenuItem("Copy message", Icons.Default.ContentCopy) {
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
                            MenuItem("Report message", Icons.Default.Flag, destructive = true) {
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

@Composable
private fun MessageEditForm(
    initial: String,
    allowEmpty: Boolean,
    saving: Boolean,
    error: String?,
    onCancel: () -> Unit,
    onSave: (String) -> Unit,
) {
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
        Text("Edit message", color = c.inkSecondary, fontSize = 11.sp, fontWeight = FontWeight.Medium)
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
            } ?: Text("Enter to save · Shift+Enter for a new line", color = c.inkMuted, fontSize = 11.sp, modifier = Modifier.weight(1f))
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

/** A `@query` fragment ending at the caret, at line start or after whitespace. */
private data class MentionQuery(val start: Int, val query: String)

private val MENTION_QUERY = Regex("(^|\\s)@([^\\s@]{0,32})$")

private fun activeMentionQuery(text: String, caret: Int): MentionQuery? {
    if (caret !in 0..text.length) return null
    val m = MENTION_QUERY.find(text.substring(0, caret)) ?: return null
    val q = m.groupValues[2]
    return MentionQuery(caret - q.length - 1, q)
}

/**
 * The message box. [channelId] non-null turns on attachments - it's null when
 * this is reused to edit an existing message, which can only change text.
 */
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
    /**
     * Lets the box be submitted with nothing in it. Set when editing a message
     * that has attachments: they carry it on their own, so deleting the text is
     * a real edit rather than an empty message, and requiring a character left
     * no way to make one.
     */
    allowEmpty: Boolean = false,
    customEmojis: List<EmojiRef> = emptyList(),
    /** Members offered by @mention autocomplete (empty in DMs). */
    members: List<ServerMember> = emptyList(),
    drafts: AttachmentDraftViewModel = hiltViewModel(),
    textDrafts: MessageDraftViewModel = hiltViewModel(),
) {
    val c = OrangTheme.colors
    // TextFieldState rather than a plain String: keyboard content (GIFs,
    // stickers, pasted screenshots) arrives through commitContent, and only the
    // state-based BasicTextField wires that up - see contentReceiver below.
    val textState = rememberTextFieldState(initial)
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    var emojiOpen by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val voiceRecorder = remember(context) { VoiceMessageRecorder(context) }
    var recording by remember(channelId) { mutableStateOf(false) }
    // A locked recording keeps going with nothing held down, so it is driven by
    // the stop and delete buttons instead of by the finger.
    var recordingLocked by remember(channelId) { mutableStateOf(false) }
    /** How far the holding finger has travelled, in px; drives the swipe hints. */
    var recordingDrag by remember(channelId) { mutableStateOf(0f) }
    var recordingStartedAt by remember(channelId) { mutableStateOf(0L) }
    var recordingSeconds by remember(channelId) { mutableStateOf(0L) }
    var recordingError by remember(channelId) { mutableStateOf<String?>(null) }
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
    val recordPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            runCatching {
                voiceRecorder.start()
            }.onSuccess {
                recordingStartedAt = System.currentTimeMillis()
                recordingSeconds = 0L
                recordingDrag = 0f
                recordingError = null
                recording = true
                // The finger that asked for this is long gone - it went to the
                // permission dialog - so there is nothing left holding the mic
                // down. Start locked rather than recording into a void.
                recordingLocked = true
            }.onFailure {
                recordingError = "Could not start voice recording"
            }
        } else {
            recordingError = "Microphone access is needed to record a voice message"
        }
    }

    /**
     * Begins a hold-to-record. Returns whether anything is actually recording,
     * so the gesture can stop tracking a press that only opened the permission
     * dialog.
     */
    fun startVoiceRecording(): Boolean {
        if (recording || channelId == null) return false
        recordingError = null
        recordingDrag = 0f
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            runCatching {
                voiceRecorder.start()
            }.onSuccess {
                recordingStartedAt = System.currentTimeMillis()
                recordingSeconds = 0L
                recording = true
                recordingLocked = false
            }.onFailure {
                recordingError = "Could not start voice recording"
            }
        } else {
            recordPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
        return recording
    }

    fun finishVoiceRecording(cancel: Boolean) {
        if (!recording) return
        // Too brief to hear, and almost always a tap by somebody who expected the
        // old press-to-start button. Say what the mic wants instead of sending it.
        val tapped = !cancel && System.currentTimeMillis() - recordingStartedAt < VOICE_MIN_HOLD_MS
        val uri = if (cancel || tapped) {
            voiceRecorder.cancel()
            null
        } else {
            voiceRecorder.stop()
        }
        recording = false
        recordingLocked = false
        recordingDrag = 0f
        when {
            tapped -> recordingError = "Hold the mic to record, then let go to send"
            cancel -> Unit
            uri != null && channelId != null ->
                drafts.add(listOf(uri), channelId, temporaryUris = setOf(uri))
            uri == null -> recordingError = "The recording was too short"
        }
    }

    LaunchedEffect(recording) {
        while (recording) {
            recordingSeconds = ((System.currentTimeMillis() - recordingStartedAt) / 1000L).coerceAtLeast(0L)
            delay(250L)
        }
    }

    LaunchedEffect(channelId, initial) {
        if (channelId == null) {
            textState.setTextAndPlaceCursorAtEnd(initial)
            focusRequester.requestFocus()
            keyboard?.show()
        }
    }

    // Hydrate the box with this channel's saved draft on open (edit mode has none).
    LaunchedEffect(channelId) {
        if (channelId != null) {
            val saved = textDrafts.load(channelId)
            if (saved.isNotEmpty() && textState.text.isEmpty()) {
                textState.setTextAndPlaceCursorAtEnd(saved)
            }
        }
    }

    // A sent message starts a new sentence, but the IME has no way to know that
    // and stays on whatever symbol or emoji page it was left on. restartInput is
    // what makes it re-read the field and fall back to its letter layout.
    val view = LocalView.current
    val resetKeyboard: () -> Unit = {
        view.context.getSystemService(InputMethodManager::class.java)?.restartInput(view)
    }

    // @mention autocomplete. Derived straight from the field rather than kept in
    // its own state, so it can never disagree with what is actually typed.
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

    /** Swap the typed `@query` for the picked handle - that is the wire format. */
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

    val uploads by drafts.uploads.collectAsState()
    val draftError by drafts.error.collectAsState()

    // The picker returns content uris; uploading starts immediately.
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

    // Was an onValueChange side effect before the state migration. Also persists
    // the draft as it's typed. Keyed on channelId so a channel switch routes
    // saves to the right channel.
    LaunchedEffect(channelId) {
        // One packet per window while they are actually typing, none in a window
        // they typed nothing in. Per-keystroke emits were both needlessly chatty
        // and enough to trip the server's typing rate limit on a fast typist.
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

    /**
     * Images handed over by the keyboard. Declaring this is also what tells the
     * IME we take them at all - without a receiver, Gboard greys out its GIF and
     * sticker tabs with "this app does not support images here".
     *
     * Compose has already called requestPermission() on the IME's content uri by
     * the time this runs, so it can be read like any other pick.
     */
    val contentListener = remember(drafts) {
        object : ReceiveContentListener {
            override fun onReceive(transferableContent: TransferableContent): TransferableContent? {
                if (!transferableContent.hasMediaType(MediaType.Image)) return transferableContent
                val received = mutableListOf<Uri>()
                // Take only the items that are actually files; anything left over
                // goes back so the text field can handle it (pasted text, say).
                val remaining = transferableContent.consume { item ->
                    val uri = item.uri
                    if (uri == null) false else { received.add(uri); true }
                }
                if (received.isNotEmpty()) drafts.add(received, channelId)
                return remaining
            }
        }
    }

    // A draft belongs to its channel. Switching away cancels anything still
    // uploading rather than carrying it to wherever we land next.
    if (channelId != null) {
        DisposableEffect(channelId) {
            onDispose {
                // Persist whatever is in the box for the channel we're leaving.
                textDrafts.saveNow(channelId, textState.text.toString().trim())
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
        if (channelId != null) {
            ComposerAttachments(uploads = uploads, onRemove = drafts::remove)
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
        }

    // Attachments can carry a message on their own, so blank text is fine as
    // long as something is going with it - but not while it's still uploading.
    // Both the sealed file and the sealed preview beside it: an attachment id
    // the message never claims is swept as an abandoned upload, which took the
    // thumbnail out from under every ref that pointed at it.
    val ready = uploads.flatMap { upload ->
        listOfNotNull(upload.attachment?.id, upload.sealed?.thumb?.attachmentId)
    }
    val enabled = (textState.text.isNotBlank() || ready.isNotEmpty() || allowEmpty) &&
        uploads.none { !it.settled } &&
        uploads.none { it.error != null }
    // A pasted login or bot token is a credential, not conversation text: ask
    // before sending it, since Android's paste goes straight into the field.
    // Schema match only; whether the token is live is never asked.
    val sendDraft: () -> Unit = {
        val content = textState.text.toString().trim()
        val kind = findPastedTokenKind(content)
        if (kind != null && content != acknowledgedTokenContent) {
            tokenWarning = kind
        } else {
            onSend(
                content,
                ready,
                uploads.mapNotNull { it.sealed },
            )
            textState.setTextAndPlaceCursorAtEnd("")
            drafts.clear()
            channelId?.let { textDrafts.clear(it) }
            resetKeyboard()
        }
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
                contentDescription = "Add emoji or GIF",
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
                // GIFs are standalone messages. Any text or attachment draft
                // stays in place for the user's next send.
                onGif = { url -> onSend(url, emptyList(), emptyList()) },
            )
        }
        if (channelId != null) {
            Box {
                Icon(
                    Icons.Default.AttachFile,
                    contentDescription = "Attach files",
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
                        MenuItem("Choose files", Icons.Default.AttachFile) { picker.launch("*/*") },
                        MenuItem("Take a picture", Icons.Default.CameraAlt) { launchCamera() },
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
            if (recording) {
                val threshold = with(LocalDensity.current) { VOICE_SWIPE_THRESHOLD.toPx() }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(
                        modifier = Modifier.size(8.dp).background(c.danger, CircleShape),
                    )
                    Text(
                        formatRecordingTime(recordingSeconds),
                        color = c.inkSecondary,
                        fontSize = 14.sp,
                    )
                    // The hint has to say which way does what while the finger is
                    // still down: a swipe with no label is a gesture nobody finds.
                    // Each half lights up as its own threshold comes into reach.
                    if (!recordingLocked) {
                        Text(
                            "‹ lock",
                            color = if (recordingDrag <= -threshold / 2) c.primary else c.inkMuted,
                            fontSize = 13.sp,
                        )
                        Text(
                            "delete ›",
                            color = if (recordingDrag >= threshold / 2) c.danger else c.inkMuted,
                            fontSize = 13.sp,
                        )
                    } else {
                        Text("Locked - tap send when you're done", color = c.inkMuted, fontSize = 13.sp)
                    }
                }
            } else {
                if (textState.text.isEmpty()) {
                    Text("Message", color = c.inkMuted, fontSize = 15.sp)
                }
                BasicTextField(
                    state = textState,
                    textStyle = TextStyle(color = c.ink, fontSize = 15.sp),
                    cursorBrush = SolidColor(c.primary),
                    lineLimits = TextFieldLineLimits.MultiLine(),
                    // BasicTextField defaults to no capitalization - that default is
                    // why Gboard auto-capitalized everywhere but here.
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                        keyboardType = KeyboardType.Text,
                    ),
                    modifier = Modifier
                        .focusRequester(focusRequester)
                        .fillMaxWidth()
                        // Editing a sent message can't gain attachments, so it has
                        // no receiver and the IME hides its image tabs there.
                        .then(
                            if (channelId != null) Modifier.contentReceiver(contentListener)
                            else Modifier,
                        ),
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        if (recording && recordingLocked) {
            Icon(
                Icons.Default.Delete,
                contentDescription = "Delete recording",
                tint = c.danger,
                modifier = Modifier
                    .size(38.dp)
                    .clickable { finishVoiceRecording(cancel = true) }
                    .padding(7.dp),
            )
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(c.primary, CircleShape)
                    .clickable { finishVoiceRecording(cancel = false) },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Send,
                    contentDescription = "Send voice message",
                    tint = c.inkOnPrimary,
                    modifier = Modifier.size(20.dp),
                )
            }
        } else {
            if (channelId != null) {
                // One composable for both the idle mic and the held one: the
                // gesture below lives on this node, and swapping it out mid-press
                // would tear down the pointer loop with the finger still down.
                Icon(
                    Icons.Default.Mic,
                    contentDescription = "Hold to record a voice message",
                    tint = if (recording) c.danger else c.inkMuted,
                    modifier = Modifier
                        .size(if (recording) 44.dp else 38.dp)
                        .pointerInput(channelId) {
                            val threshold = VOICE_SWIPE_THRESHOLD.toPx()
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                down.consume()
                                if (!startVoiceRecording()) return@awaitEachGesture
                                var settled = false
                                while (!settled) {
                                    val change = awaitPointerEvent().changes
                                        .firstOrNull { it.id == down.id }
                                        ?: break
                                    recordingDrag = change.position.x - down.position.x
                                    change.consume()
                                    when {
                                        // Left hands the recording over to the
                                        // buttons; the finger is free after this,
                                        // so the gesture stops watching it.
                                        recordingDrag <= -threshold -> {
                                            recordingLocked = true
                                            recordingDrag = 0f
                                            settled = true
                                        }
                                        recordingDrag >= threshold -> {
                                            finishVoiceRecording(cancel = true)
                                            settled = true
                                        }
                                        !change.pressed -> {
                                            finishVoiceRecording(cancel = false)
                                            settled = true
                                        }
                                    }
                                }
                                // A pointer that vanished - the gesture was stolen,
                                // or the window went away - must not leave the mic
                                // recording forever with nothing on screen saying so.
                                if (!settled && recording && !recordingLocked) {
                                    finishVoiceRecording(cancel = true)
                                }
                            }
                        }
                        .padding(7.dp),
                )
            }
            // The return affordance floats over history; giving it a row of its own
            // would shorten the conversation precisely when someone is reading it.
            if (!recording) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .background(if (enabled) c.primary else c.surface4, CircleShape)
                        .clickable(enabled = enabled) { sendDraft() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.Send,
                        contentDescription = submitLabel ?: "Send",
                        tint = if (enabled) c.inkOnPrimary else c.inkMuted,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
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
                    Text("Send anyway", color = c.danger)
                }
            }
        }
    }
    }
}

/** How many members the @mention menu offers at once. Matches the web client. */
private const val MENTION_LIMIT = 8

private enum class PastedTokenKind { LOGIN, BOT }

/**
 * Schema match only - nothing here is checked against the server. A login token
 * is a v4 UUID; a bot token is `<base64url(bot id)>.<32 random bytes, base64url>`.
 */
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

/**
 * The @mention picker, shown above the composer while a `@query` is being typed.
 * Both the label and the handle are listed: the label is what people recognise,
 * the handle is what actually gets inserted.
 */
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
