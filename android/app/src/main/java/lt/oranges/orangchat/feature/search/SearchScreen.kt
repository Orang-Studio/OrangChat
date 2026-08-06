package lt.oranges.orangchat.feature.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import lt.oranges.orangchat.ui.components.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import lt.oranges.orangchat.data.model.User
import lt.oranges.orangchat.feature.chat.MessageText
import lt.oranges.orangchat.ui.components.Avatar
import lt.oranges.orangchat.ui.components.OrangTextField
import lt.oranges.orangchat.ui.theme.OrangRadius
import lt.oranges.orangchat.ui.theme.OrangTheme
import lt.oranges.orangchat.util.formatTime

/**
 * Message search. Servers use the remote full-text index and fall back to the
 * encrypted disk cache; DMs always use the on-device decrypted index.
 */
@Composable
fun SearchScreen(
    serverId: String?,
    channelIds: Set<String>,
    channelNames: Map<String, String>,
    authors: Map<String, User>,
    onBack: () -> Unit,
    /**
     * Open the hit's channel and scroll to the message itself. The id matters:
     * a hit is usually older than the page a channel opens on, and dropping it
     * left every result opening the conversation at the bottom instead.
     */
    onJumpToMessage: (channelId: String, messageId: String) -> Unit,
    modifier: Modifier = Modifier,
    vm: SearchViewModel = hiltViewModel(),
) {
    val c = OrangTheme.colors
    val state by vm.state.collectAsStateWithLifecycle()
    // Keep the renderer safe if an overlapping page comes back from the server.
    val results = state.results.distinctBy { it.id }

    LaunchedEffect(serverId, channelIds) { vm.reset() }

    Column(modifier = modifier.fillMaxSize().background(c.surface2)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                tint = c.inkSecondary,
                modifier = Modifier.clickable(onClick = onBack).padding(4.dp),
            )
            Spacer(Modifier.width(8.dp))
            OrangTextField(
                value = state.query,
                onValueChange = { vm.onQueryChange(serverId, channelIds, it) },
                label = null,
                placeholder = "Search messages",
                modifier = Modifier.weight(1f),
            )
        }
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(c.border))

        when {
            state.loading && results.isEmpty() -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator(color = c.primary) }

            state.error != null -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) { Text(state.error!!, color = c.danger, fontSize = 13.sp) }

            state.query.isBlank() -> Hint(
                if (serverId == null) "Type to search your cached direct messages."
                else "Type to search this server's messages.",
            )

            results.isEmpty() -> Hint(
                if (serverId == null || state.searchedLocally) {
                    "No cached messages matched \"${state.query}\". Messages not opened here may be missing."
                } else {
                    "No messages matched \"${state.query}\"."
                },
            )

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                if (serverId == null || state.searchedLocally) {
                    item(key = "local-search-notice") {
                        Text(
                            text = "Searched on this device. Messages not opened here may be missing.",
                            color = c.inkMuted,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                        )
                    }
                }
                items(results, key = { it.id }) { message ->
                    SearchHit(
                        message = message,
                        author = message.author ?: authors[message.authorId],
                        channelName = channelNames[message.channelId],
                        onClick = { onJumpToMessage(message.channelId, message.id) },
                    )
                }
                // Offset pagination: ask for the next page at the bottom.
                if (state.hasMore) {
                    item {
                        Text(
                            text = if (state.loading) "Loading…" else "Load more",
                            color = c.primary,
                            fontSize = 13.sp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = !state.loading) {
                                    vm.loadMore(serverId, channelIds)
                                }
                                .padding(16.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Hint(text: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, color = OrangTheme.colors.inkMuted, fontSize = 13.sp)
    }
}

@Composable
private fun SearchHit(
    message: SearchResult,
    author: User?,
    channelName: String?,
    onClick: () -> Unit,
) {
    val c = OrangTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .clip(RoundedCornerShape(OrangRadius.md))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        if (author != null) Avatar(author, size = 32.dp) else Spacer(Modifier.size(32.dp))
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    author?.displayName ?: "Unknown",
                    color = c.ink,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = channelName?.let { "#$it" } ?: "",
                    color = c.inkMuted,
                    fontSize = 11.sp,
                )
                Spacer(Modifier.width(6.dp))
                Text(formatTime(message.createdAt), color = c.inkMuted, fontSize = 11.sp)
            }
            MessageText(content = message.content, fontSize = 14.sp)
        }
    }
}
