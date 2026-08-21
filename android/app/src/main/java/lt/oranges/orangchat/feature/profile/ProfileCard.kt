package lt.oranges.orangchat.feature.profile

import androidx.compose.ui.platform.LocalContext
import lt.oranges.orangchat.R
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import lt.oranges.orangchat.ui.components.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import coil.compose.AsyncImage
import lt.oranges.orangchat.data.model.PresenceStatus
import lt.oranges.orangchat.data.model.User
import lt.oranges.orangchat.ui.components.Avatar
import lt.oranges.orangchat.ui.components.DeviceIndicators
import lt.oranges.orangchat.ui.components.ButtonVariant
import lt.oranges.orangchat.ui.components.OrangButton
import lt.oranges.orangchat.ui.theme.OrangRadius
import lt.oranges.orangchat.ui.theme.OrangTheme
import lt.oranges.orangchat.util.AppStrings
import lt.oranges.orangchat.util.absoluteUrl

enum class ProfileRelation { SELF, FRIEND, PENDING, STRANGER }

data class ProfileCardEdit(
    val onPickAvatar: () -> Unit,
    val onPickBanner: () -> Unit,
    val busy: ImageKind? = null,
)

@Composable
fun ProfileCard(
    user: User,
    modifier: Modifier = Modifier,
    presence: PresenceStatus? = null,
    actions: (@Composable RowScope.() -> Unit)? = null,
    edit: ProfileCardEdit? = null,
) {
    val definitions = LocalWidgetCatalog.current
    if (!user.profileCss.isNullOrBlank() && edit == null) {
        Column(modifier = modifier) {
            ProfileCardWebView(user = user, presence = presence, definitions = definitions)
            actions?.let {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) { it() }
            }
        }
    } else {
        ProfileCardNative(
            user = user,
            modifier = modifier,
            presence = presence,
            actions = actions,
            edit = edit,
        )
    }
}

@Composable
private fun ProfileCardNative(
    user: User,
    modifier: Modifier = Modifier,
    presence: PresenceStatus? = null,
    actions: (@Composable RowScope.() -> Unit)? = null,
    edit: ProfileCardEdit? = null,
) {
    val c = OrangTheme.colors
    val outerShape = RoundedCornerShape(OrangRadius.xl)
    val avatarShape = CircleShape
    val accent = user.accentColor?.let { Color(it).copy(alpha = 1f) } ?: c.surface4

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(outerShape)
            .background(c.surface2)
            .border(1.dp, c.border, outerShape),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(80.dp)
                    .background(accent)
                    .then(
                        if (edit != null) Modifier.clickable(onClick = edit.onPickBanner)
                        else Modifier,
                    ),
            ) {
                user.bannerUrl?.takeIf { it.isNotBlank() }?.let { url ->
                    AsyncImage(
                        model = absoluteUrl(url),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxWidth().height(80.dp),
                    )
                }
                edit?.let {
                    EditOverlay(
                        busy = it.busy == ImageKind.BANNER,
                        modifier = Modifier.fillMaxWidth().height(80.dp),
                    )
                    EditBadge(modifier = Modifier.align(Alignment.TopEnd).padding(6.dp))
                }
            }

            Column(
                modifier = Modifier.padding(start = 16.dp, top = 40.dp, end = 16.dp, bottom = 16.dp),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(c.surface1, RoundedCornerShape(OrangRadius.xl))
                        .padding(12.dp),
                ) {
                    Text(
                        text = user.displayName.ifBlank { "-" },
                        color = c.ink,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "@${user.username.ifBlank { "username" }}",
                            color = c.inkSecondary,
                            fontSize = 14.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.width(5.dp))
                        DeviceIndicators(
                            status = presence ?: user.status,
                            devices = user.devices.toSet(),
                            modifier = Modifier.height(14.dp),
                        )
                    }
                    ProfileWidgetsNative(
                        user = user,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )

                    actions?.let {
                        Spacer(Modifier.height(10.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) { it() }
                    }
                }
            }
        }

        Box(
            modifier = Modifier
                .offset(x = 16.dp, y = 44.dp)
                .zIndex(1f)
                .background(c.surface2, avatarShape)
                .padding(6.dp),
        ) {
            Avatar(
                user = user,
                size = 56.dp,
                status = presence ?: user.status,
                shape = avatarShape,
            )
            edit?.let {
                EditOverlay(
                    busy = it.busy == ImageKind.AVATAR,
                    modifier = Modifier
                        .size(56.dp)
                        .clip(avatarShape)
                        .clickable(onClick = it.onPickAvatar),
                )
                EditBadge(modifier = Modifier.align(Alignment.TopEnd))
            }
        }
    }
}

@Composable
private fun EditBadge(modifier: Modifier = Modifier) {
    val c = OrangTheme.colors
    Box(
        modifier = modifier
            .size(20.dp)
            .clip(CircleShape)
            .background(c.surface2)
            .border(1.dp, c.border, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Default.Edit,
            contentDescription = null,
            tint = c.ink,
            modifier = Modifier.size(12.dp),
        )
    }
}

@Composable
private fun EditOverlay(busy: Boolean, modifier: Modifier = Modifier) {
    if (busy) {
        Box(
            modifier = modifier.background(Color.Black.copy(alpha = 0.35f)),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(
                color = Color.White,
                strokeWidth = 2.dp,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/**
 * The profile card is the whole view here - no title bar or extra chrome
 * around it, just a close button overlaid in the corner like the web client.
 */
@Composable
fun ProfileDialog(
    user: User,
    relation: ProfileRelation,
    presence: PresenceStatus?,
    onDismiss: () -> Unit,
    onMessage: () -> Unit,
    onAddFriend: () -> Unit,
    onRemoveFriend: () -> Unit,
) {
    val context = LocalContext.current
    val profileActions: (@Composable RowScope.() -> Unit)? = if (relation == ProfileRelation.SELF) {
        null
    } else {
        {
            OrangButton(
                text = "Message",
                onClick = onMessage,
                variant = ButtonVariant.Secondary,
                modifier = Modifier.weight(1f),
            )
            when (relation) {
                ProfileRelation.FRIEND -> OrangButton(
                    text = "Remove",
                    onClick = onRemoveFriend,
                    variant = ButtonVariant.Ghost,
                )
                ProfileRelation.STRANGER -> OrangButton(
                    text = AppStrings.get(context, R.string.catalog_add_friend_e0a731d8),
                    onClick = onAddFriend,
                )
                ProfileRelation.PENDING -> OrangButton(
                    text = AppStrings.get(context, R.string.catalog_request_sent_168996e3),
                    onClick = {},
                    enabled = false,
                )
                ProfileRelation.SELF -> Unit
            }
        }
    }

    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            ProfileCard(user = user, presence = presence, actions = profileActions)
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Close",
                tint = Color.White,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 8.dp, end = 8.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.3f))
                    .clickable(onClick = onDismiss)
                    .padding(6.dp)
                    .size(18.dp),
            )
        }
    }
}


