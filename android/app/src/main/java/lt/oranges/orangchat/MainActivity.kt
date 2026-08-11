package lt.oranges.orangchat

import android.app.PictureInPictureParams
import android.os.Build
import android.os.Bundle
import android.content.Intent
import android.net.Uri
import android.util.Rational
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
class MainActivity : LocalizedActivity() {
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
                val base = LocalDensity.current
                CompositionLocalProvider(
                    LocalDensity provides Density(base.density, base.fontScale * devicePrefs.fontScale),
                ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = OrangTheme.colors.surface1,
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .windowInsetsPadding(WindowInsets.safeDrawing),
                    ) {
                        OrangChatNavHost()
                        CallHost()
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

    private fun applyLockScreenFlags(intent: Intent?) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1) return
        val incomingCall = intent?.getBooleanExtra(NotificationHelper.EXTRA_INCOMING_CALL, false) == true
        setShowWhenLocked(incomingCall)
        setTurnScreenOn(incomingCall)
    }

    private fun openConversation(intent: Intent?) {
        intent?.getStringExtra(NotificationHelper.EXTRA_CHANNEL_ID)?.let {
            notificationHelper.clearConversationNotifications(it)
            pendingConversationStore.offer(it)
        }
    }

    private fun captureInviteLink(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        intent.data?.toString()?.let(InviteLink::codeFrom)?.let(pendingInviteStore::offer)
    }

    private fun captureQrLogin(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val raw = intent.data?.toString() ?: return
        QrLoginLink.tokenFrom(raw)?.let(pendingQrLoginStore::offer)
        pendingVerifyStore.offer(raw)
        pendingTransferStore.offer(raw)
    }

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
        val channelId = intent.getStringExtra(Intent.EXTRA_SHORTCUT_ID)
            ?.takeIf { it.startsWith(NotificationHelper.CONVERSATION_SHORTCUT_PREFIX) }
            ?.removePrefix(NotificationHelper.CONVERSATION_SHORTCUT_PREFIX)
            ?.takeIf { it.isNotBlank() }
        if (text.isNotBlank() || uris.isNotEmpty()) {
            pendingShareStore.offer(PendingShare(text, uris, channelId))
        }
    }

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
