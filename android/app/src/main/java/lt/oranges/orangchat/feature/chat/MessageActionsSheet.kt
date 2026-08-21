package lt.oranges.orangchat.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import lt.oranges.orangchat.R
import lt.oranges.orangchat.ui.components.MenuItem
import lt.oranges.orangchat.ui.components.Text
import lt.oranges.orangchat.ui.theme.OrangRadius
import lt.oranges.orangchat.ui.theme.OrangTheme
import lt.oranges.orangchat.util.AppStrings
import lt.oranges.orangchat.util.EmojiRef
import lt.oranges.orangchat.util.EmojiTokens
import lt.oranges.orangchat.util.absoluteUrl

private val REACTION_TOKEN = Regex("^${EmojiTokens.TOKEN_SOURCE}$", RegexOption.IGNORE_CASE)

/**
 * One reaction as it should be drawn: an image for a custom emoji this viewer
 * can see, the bare `:name:` for one they can't, plain text otherwise.
 */
@Composable
fun ReactionEmoji(emoji: String, emojis: Map<String, EmojiRef>, size: Dp = 16.dp) {
    val match = REACTION_TOKEN.find(emoji)
    if (match == null) {
        Text(emoji, fontSize = 13.sp)
        return
    }
    val custom = emojis[match.groupValues[3]]
    if (custom == null) {
        Text(":${match.groupValues[2]}:", fontSize = 13.sp)
        return
    }
    AsyncImage(
        model = absoluteUrl(custom.url),
        contentDescription = ":${custom.name}:",
        modifier = Modifier.size(size),
    )
}

/**
 * What holding a message offers, in two tiers: the emoji this viewer actually
 * reacts with, one tap away, and a `+` that turns the same sheet into the full
 * searchable picker. The message's other actions sit below the bar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageActionsSheet(
    groups: List<CustomEmojiGroup>,
    recent: List<String>,
    reacted: Set<String>,
    actions: List<MenuItem>,
    startInPicker: Boolean = false,
    onPick: (ReactionPick) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = OrangTheme.colors
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var picking by remember { mutableStateOf(startInPicker) }
    val quick = remember(recent, groups) { reactionQuickPicks(recent, groups) }

    val pick: (ReactionPick) -> Unit = { chosen ->
        onPick(chosen)
        onDismiss()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = c.surface3,
        contentColor = c.ink,
    ) {
        if (picking) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.75f)
                    .padding(horizontal = 8.dp)
                    .padding(bottom = 8.dp),
            ) {
                EmojiPickerPanel(
                    groups = groups,
                    recent = recent,
                    onPick = { insert, custom -> pick(ReactionPick(insert, custom)) },
                )
            }
            return@ModalBottomSheet
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp),
        ) {
            quick.forEach { entry ->
                val mine = reactionValue(entry) in reacted
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(OrangRadius.lg))
                        .background(if (mine) c.primarySoft else c.surface3)
                        .clickable { pick(entry) },
                    contentAlignment = Alignment.Center,
                ) {
                    if (entry.custom != null) {
                        AsyncImage(
                            model = absoluteUrl(entry.custom.url),
                            contentDescription = entry.insert,
                            modifier = Modifier.size(26.dp),
                        )
                    } else {
                        Text(entry.insert, fontSize = 24.sp)
                    }
                }
            }
            Spacer(Modifier.width(4.dp))
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(OrangRadius.lg))
                    .background(c.surface2)
                    .clickable { picking = true }
                    .semantics {
                        contentDescription =
                            AppStrings.get(context, R.string.catalog_add_reaction_cf05eca8)
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = null,
                    tint = c.inkMuted,
                    modifier = Modifier.size(22.dp),
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        HorizontalDivider(color = c.border)
        Spacer(Modifier.height(4.dp))

        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
            actions.forEach { action ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = action.enabled) {
                            action.onClick()
                            onDismiss()
                        }
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                ) {
                    val tint = if (action.destructive) c.danger else c.ink
                    action.icon?.let {
                        Icon(
                            it,
                            contentDescription = null,
                            tint = tint,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(16.dp))
                    }
                    Text(action.label, color = tint, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}
