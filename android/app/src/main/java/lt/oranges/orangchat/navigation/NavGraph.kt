package lt.oranges.orangchat.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import lt.oranges.orangchat.data.repository.SessionState
import lt.oranges.orangchat.feature.auth.AuthScreens
import lt.oranges.orangchat.feature.home.AppViewModel
import lt.oranges.orangchat.feature.home.HomeScreen
import lt.oranges.orangchat.feature.share.ShareScreen
import lt.oranges.orangchat.ui.theme.OrangTheme

/**
 * Root gate: SessionState drives whether we show the loading splash, the
 * auth flow, or the authenticated home shell. The AppViewModel is created here
 * and shared with descendants via its activity-scoped Hilt instance.
 */
@Composable
fun OrangChatNavHost(
    /** A conversation to land in as soon as the shell exists - a bubble's own
     *  channel, kept out of the process-wide pending store so expanding one
     *  cannot move the main window. */
    initialChannelId: String? = null,
) {
    val appViewModel: AppViewModel = hiltViewModel()
    val session by appViewModel.session.collectAsStateWithLifecycle()
    val pendingShare by appViewModel.pendingShare.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { appViewModel.bootstrap() }

    when (val s = session) {
        is SessionState.Loading -> LoadingSplash()
        is SessionState.Unauthenticated -> AuthScreens()
        is SessionState.Authenticated -> {
            LaunchedEffect(s.user.id) { appViewModel.loadInitialData() }
            if (pendingShare != null) {
                ShareScreen(share = pendingShare!!, onDismiss = appViewModel::clearPendingShare)
            } else {
                HomeScreen(
                    appViewModel = appViewModel,
                    self = s.user,
                    initialChannelId = initialChannelId,
                )
            }
        }
    }
}


@Composable
private fun LoadingSplash() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = OrangTheme.colors.primary)
    }
}
