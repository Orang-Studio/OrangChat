package lt.oranges.orangchat

import android.app.PictureInPictureParams
import android.os.Bundle
import android.content.Intent
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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.hilt.navigation.compose.hiltViewModel
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import lt.oranges.orangchat.feature.invite.PendingInviteStore
import lt.oranges.orangchat.feature.settings.SettingsViewModel
import lt.oranges.orangchat.feature.voice.CallHost
import lt.oranges.orangchat.feature.voice.CallManager
import lt.oranges.orangchat.navigation.OrangChatNavHost
import lt.oranges.orangchat.feature.settings.ThemeViewModel
import lt.oranges.orangchat.ui.theme.OrangChatTheme
import lt.oranges.orangchat.ui.theme.OrangTheme
import lt.oranges.orangchat.notifications.NotificationHelper
import lt.oranges.orangchat.util.InviteLink

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var callManager: CallManager
    @Inject lateinit var notificationHelper: NotificationHelper
    @Inject lateinit var pendingInviteStore: PendingInviteStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        clearOpenedConversation(intent)
        captureInviteLink(intent)
        enableEdgeToEdge()
        setContent {
            val themeVm: ThemeViewModel = hiltViewModel()
            val settingsVm: SettingsViewModel = hiltViewModel()
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
                    }
                }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        clearOpenedConversation(intent)
        captureInviteLink(intent)
    }

    private fun clearOpenedConversation(intent: Intent?) {
        intent?.getStringExtra(NotificationHelper.EXTRA_CHANNEL_ID)?.let {
            notificationHelper.clearConversationHistory(it)
        }
    }

    /**
     * Park an invite link the app was opened with. The join UI lives inside the
     * authenticated shell, which may not exist yet — the store holds the code
     * until it does, even if that means waiting out a whole sign-in.
     */
    private fun captureInviteLink(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        intent.data?.toString()?.let(InviteLink::codeFrom)?.let(pendingInviteStore::offer)
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
