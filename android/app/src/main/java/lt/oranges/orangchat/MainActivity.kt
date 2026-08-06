package lt.oranges.orangchat

import android.app.PictureInPictureParams
import android.os.Build
import android.os.Bundle
import android.content.Intent
import android.net.Uri
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.hilt.navigation.compose.hiltViewModel
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import lt.oranges.orangchat.feature.home.PendingConversationStore
import lt.oranges.orangchat.feature.invite.PendingInviteStore
import lt.oranges.orangchat.feature.qrlogin.PendingQrLoginStore
import lt.oranges.orangchat.feature.verify.PendingVerifyStore
import lt.oranges.orangchat.feature.transfer.PendingTransferStore
import lt.oranges.orangchat.feature.qrlogin.QrLoginLink
import lt.oranges.orangchat.feature.share.PendingShare
import lt.oranges.orangchat.feature.share.PendingShareStore
import lt.oranges.orangchat.feature.settings.SettingsViewModel
import lt.oranges.orangchat.feature.voice.CallHost
import lt.oranges.orangchat.feature.voice.CallManager
import lt.oranges.orangchat.navigation.OrangChatNavHost
import lt.oranges.orangchat.feature.settings.ThemeViewModel
import lt.oranges.orangchat.data.remote.UpdateGate
import lt.oranges.orangchat.feature.updates.UpdateAvailableDialog
import lt.oranges.orangchat.feature.updates.UpdateRequiredDialog
import lt.oranges.orangchat.feature.updates.UpdateUiState
import lt.oranges.orangchat.feature.updates.UpdateViewModel
import lt.oranges.orangchat.ui.theme.OrangChatTheme
import lt.oranges.orangchat.ui.theme.OrangTheme
import lt.oranges.orangchat.notifications.NotificationHelper
import lt.oranges.orangchat.util.InviteLink

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var callManager: CallManager
    @Inject lateinit var notificationHelper: NotificationHelper
    @Inject lateinit var pendingInviteStore: PendingInviteStore
    @Inject lateinit var pendingConversationStore: PendingConversationStore
    @Inject lateinit var pendingShareStore: PendingShareStore
    @Inject lateinit var pendingQrLoginStore: PendingQrLoginStore
    @Inject lateinit var pendingVerifyStore: PendingVerifyStore
    @Inject lateinit var pendingTransferStore: PendingTransferStore
    @Inject lateinit var updateGate: UpdateGate

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyLockScreenFlags(intent)
        openConversation(intent)
        captureInviteLink(intent)
        captureQrLogin(intent)
        captureSharedContent(intent)
        enableEdgeToEdge()
        setContent {
            val themeVm: ThemeViewModel = hiltViewModel()
            val settingsVm: SettingsViewModel = hiltViewModel()
            val updateVm: UpdateViewModel = hiltViewModel()
            val updateState by updateVm.state.collectAsStateWithLifecycle()
            val upgradeRequired by updateGate.upgradeRequired.collectAsStateWithLifecycle()
            var dismissedUpdateVersion by rememberSaveable { mutableStateOf<String?>(null) }
            LaunchedEffect(Unit) { updateVm.check() }
            val pref by themeVm.preference.collectAsStateWithLifecycle()
            val devicePrefs by settingsVm.prefs.collectAsStateWithLifecycle()
            OrangChatTheme(preference = pref) {
                // The accessibility text-size pref scales the whole UI by
                // overriding the ambient font-scale density.
                val base = LocalDensity.current
                CompositionLocalProvider(
                    LocalDensity provides Density(base.density, base.fontScale * devicePrefs.fontScale),
                ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = OrangTheme.colors.surface1,
                ) {
                    // A ringing call must sit above every screen, including the
                    // auth/home swap, so it lives outside the nav tree.
                    // safeDrawing keeps content clear of the status/nav bars and
                    // the keyboard while the window itself stays edge-to-edge, so
                    // the surface colour still bleeds under the system bars.
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .windowInsetsPadding(WindowInsets.safeDrawing),
                    ) {
                        OrangChatNavHost()
                        CallHost()
                        // Outranks the ordinary "available" prompt: once the
                        // server has refused this build there is nothing behind
                        // the dialog that still works.
                        if (upgradeRequired) {
                            UpdateRequiredDialog(
                                latestVersion = updateGate.latestVersion,
                                onUpdate = {
                                    if (updateVm.canInstall()) updateVm.check()
                                    else startActivity(updateVm.installPermissionIntent())
                                },
                            )
                        } else (updateState as? UpdateUiState.Available)?.let { available ->
                            if (dismissedUpdateVersion != available.manifest.versionName) {
                                UpdateAvailableDialog(
                                    manifest = available.manifest,
                                    onDismiss = { dismissedUpdateVersion = available.manifest.versionName },
                                    onUpdate = {
                                        if (updateVm.canInstall()) updateVm.download(available.manifest)
                                        else startActivity(updateVm.installPermissionIntent())
                                    },
                                )
                            }
                        }
                    }
                }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyLockScreenFlags(intent)
        openConversation(intent)
        captureInviteLink(intent)
        captureQrLogin(intent)
        captureSharedContent(intent)
    }

    /**
     * Show over the lockscreen and wake the screen only for a ringing call -
     * that alone justifies taking over a locked phone. Every other launch (a
     * message tap, the launcher) must respect the keyguard, so the flags are set
     * per-intent here rather than declared statically in the manifest, where
     * they would apply to the whole app on every launch.
     */
    private fun applyLockScreenFlags(intent: Intent?) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1) return
        val incomingCall = intent?.getBooleanExtra(NotificationHelper.EXTRA_INCOMING_CALL, false) == true
        setShowWhenLocked(incomingCall)
        setTurnScreenOn(incomingCall)
    }

    /**
     * Open whatever conversation the app was launched into - a notification tap,
     * a conversation shortcut, a bubble - and take its notification out of the
     * shade. Until this existed a tapped notification only dropped the user
     * wherever the app happened to be, which for the one gesture the whole
     * notification exists to invite is close to useless.
     */
    private fun openConversation(intent: Intent?) {
        intent?.getStringExtra(NotificationHelper.EXTRA_CHANNEL_ID)?.let {
            notificationHelper.clearConversationNotifications(it)
            pendingConversationStore.offer(it)
        }
    }

    /**
     * Park an invite link the app was opened with. The join UI lives inside the
     * authenticated shell, which may not exist yet - the store holds the code
     * until it does, even if that means waiting out a whole sign-in.
     */
    private fun captureInviteLink(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        intent.data?.toString()?.let(InviteLink::codeFrom)?.let(pendingInviteStore::offer)
    }

    /**
     * Park a QR sign-in token the app was opened with. Approving the web session
     * only makes sense once this phone is signed in, so the shell raises the
     * confirm prompt when it can - the token survives a sign-in first if needed.
     */
    private fun captureQrLogin(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val raw = intent.data?.toString() ?: return
        QrLoginLink.tokenFrom(raw)?.let(pendingQrLoginStore::offer)
        // A contact code scanned with the phone's own camera lands here too. The
        // stores reject anything of the wrong kind by name, which is the whole
        // point of the type tags: one of these three codes authorises a device.
        pendingVerifyStore.offer(raw)
        pendingTransferStore.offer(raw)
    }

    /** Capture text, links, and content URIs sent through Android's share sheet. */
    @Suppress("DEPRECATION")
    private fun captureSharedContent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND && intent?.action != Intent.ACTION_SEND_MULTIPLE) return
        val uris = buildList {
            if (intent.action == Intent.ACTION_SEND) {
                intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let(::add)
            } else {
                intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let(::addAll)
            }
            intent.clipData?.let { clip ->
                repeat(clip.itemCount) { index -> clip.getItemAt(index).uri?.let(::add) }
            }
        }.distinct()
        val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString().orEmpty()
        // Picked from the share sheet's direct-share row rather than from the
        // app's own icon: Android names the choice with the id of the long-lived
        // conversation shortcut the notification published.
        val channelId = intent.getStringExtra(Intent.EXTRA_SHORTCUT_ID)
            ?.takeIf { it.startsWith(NotificationHelper.CONVERSATION_SHORTCUT_PREFIX) }
            ?.removePrefix(NotificationHelper.CONVERSATION_SHORTCUT_PREFIX)
            ?.takeIf { it.isNotBlank() }
        if (text.isNotBlank() || uris.isNotEmpty()) {
            pendingShareStore.offer(PendingShare(text, uris, channelId))
        }
    }

    /** Keep an active call visible as a system PiP window when Home is pressed. */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (callManager.current.value == null || isInPictureInPictureMode) return
        enterPictureInPictureMode(
            PictureInPictureParams.Builder()
                .setAspectRatio(Rational(16, 9))
                .build(),
        )
    }
}
