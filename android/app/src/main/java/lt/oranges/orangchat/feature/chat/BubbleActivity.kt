package lt.oranges.orangchat.feature.chat

import android.os.Bundle
import androidx.activity.compose.setContent
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
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import lt.oranges.orangchat.LocalizedActivity
import lt.oranges.orangchat.feature.settings.SettingsViewModel
import lt.oranges.orangchat.feature.settings.ThemeViewModel
import lt.oranges.orangchat.navigation.OrangChatNavHost
import lt.oranges.orangchat.notifications.NotificationHelper
import lt.oranges.orangchat.ui.theme.OrangChatTheme
import lt.oranges.orangchat.ui.theme.OrangTheme

/**
 * The floating window behind a conversation bubble.
 *
 * A bubble is the one place Android will host a chat outside the app, and it can
 * only host an activity that is embeddable, resizeable and document-launched -
 * which [lt.oranges.orangchat.MainActivity], being singleTask, can never be.
 * Hence a second entry point rather than a flag on the first.
 *
 * It opens the shell straight into the conversation it was expanded for, so what
 * the bubble shows is the same chat the app shows, with the same view model
 * wiring. The channel is passed down the composition rather than through
 * [lt.oranges.orangchat.feature.home.PendingConversationStore]: the store is
 * process-wide, and a bubble expanding must not drag the main window off
 * whatever the user was reading there.
 */
@AndroidEntryPoint
class BubbleActivity : LocalizedActivity() {
    @Inject lateinit var notificationHelper: NotificationHelper

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val channelId = intent?.getStringExtra(NotificationHelper.EXTRA_CHANNEL_ID)
        // Expanding the bubble is reading it, the same as opening the app.
        channelId?.let(notificationHelper::clearConversationNotifications)

        setContent {
            val themeVm: ThemeViewModel = hiltViewModel()
            val settingsVm: SettingsViewModel = hiltViewModel()
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
                            OrangChatNavHost(initialChannelId = channelId)
                        }
                    }
                }
            }
        }
    }
}
