package lt.oranges.orangchat.feature.invite

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import lt.oranges.orangchat.ui.components.ButtonSize
import lt.oranges.orangchat.ui.components.OrangButton
import lt.oranges.orangchat.ui.theme.OrangRadius
import lt.oranges.orangchat.ui.theme.OrangTheme

/**
 * An invite link posted in chat, unfurled into the server it leads to.
 *
 * Tapping hands the code to [PendingInviteStore] rather than joining outright,
 * so the join goes through the same confirm sheet a tapped link raises — the
 * embed previews, the sheet commits. That also spares the whole chat tree a
 * callback it would otherwise have to carry just to reach the server rail.
 */
@Composable
fun ChatInviteEmbed(
    code: String,
    modifier: Modifier = Modifier,
    viewModel: ChatInviteViewModel = hiltViewModel(),
) {
    val state by viewModel.previewFor(code).collectAsStateWithLifecycle()

    Spacer(Modifier.height(6.dp))
    when (val preview = state) {
        null -> Placeholder("Resolving invite…")
        else -> preview.fold(
            onFailure = { Placeholder("This invite is expired, revoked, or never existed.") },
            onSuccess = { p ->
                InviteCard(
                    preview = p,
                    modifier = modifier.clickable { viewModel.open(code) },
                    action = {
                        OrangButton(
                            text = if (p.isMember) "Open" else "Join",
                            onClick = { viewModel.open(code) },
                            size = ButtonSize.Sm,
                            enabled = p.blockedReason == null,
                        )
                    },
                )
            },
        )
    }
}

@Composable
private fun Placeholder(text: String) {
    val c = OrangTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(OrangRadius.xl2))
            .background(c.surface1)
            .border(1.dp, c.border, RoundedCornerShape(OrangRadius.xl2))
            .padding(12.dp),
    ) {
        Text(text, color = c.inkMuted, fontSize = 12.sp)
    }
}
