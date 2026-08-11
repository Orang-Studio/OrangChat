package lt.oranges.orangchat.feature.profile

import androidx.compose.ui.platform.LocalContext
import lt.oranges.orangchat.R
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import lt.oranges.orangchat.ui.components.ActivityStatus
import lt.oranges.orangchat.ui.components.ProfileBadges
import lt.oranges.orangchat.ui.components.DeviceIndicators
import lt.oranges.orangchat.ui.components.ButtonVariant
import lt.oranges.orangchat.ui.components.OrangButton
import lt.oranges.orangchat.ui.components.OrangDialog
import lt.oranges.orangchat.ui.theme.OrangRadius
import lt.oranges.orangchat.ui.theme.OrangTheme
import lt.oranges.orangchat.util.AppStrings
import lt.oranges.orangchat.util.absoluteUrl
import lt.oranges.orangchat.util.formatFullTime

enum class ProfileRelation { SELF, FRIEND, PENDING, STRANGER }

@Composable
fun ProfileCard(
    user: User,
    modifier: Modifier = Modifier,
    presence: PresenceStatus? = null,
) {
    if (!user.profileCss.isNullOrBlank()) {
        ProfileCardWebView(user = user, modifier = modifier, presence = presence)
    } else {
        ProfileCardNative(user = user, modifier = modifier, presence = presence)
    }
}

@Composable
private fun ProfileCardNative(
    user: User,
    modifier: Modifier = Modifier,
    presence: PresenceStatus? = null,
) {
    val c = OrangTheme.colors
    val outerShape = RoundedCornerShape(OrangRadius.xl)
    val avatarShape = RoundedCornerShape(OrangRadius.md)
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
                    .background(accent),
            ) {
                user.bannerUrl?.takeIf { it.isNotBlank() }?.let { url ->
                    AsyncImage(
                        model = absoluteUrl(url),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxWidth().height(80.dp),
                    )
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
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = user.displayName.ifBlank { "-" },
                            color = c.ink,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        user.pronouns?.takeIf { it.isNotBlank() }?.let { pronouns ->
                            Spacer(Modifier.width(8.dp))
                            Text(pronouns, color = c.inkMuted, fontSize = 12.sp, maxLines = 1)
                        }
                    }
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
                    ActivityStatus(
                        activities = user.activities,
                        modifier = Modifier.padding(top = 6.dp),
                        compact = false,
                    )

                    ProfileBadges(badges = user.badges, modifier = Modifier.padding(top = 8.dp))

                    user.bio?.takeIf { it.isNotBlank() }?.let { bio ->
                        ProfileDivider()
                        ProfileLabel("About me")
                        Text(bio, color = c.ink, fontSize = 14.sp)
                    }

                    user.createdAt.takeIf { it.isNotBlank() }?.let { createdAt ->
                        ProfileDivider()
                        ProfileLabel("Member since")
                        Text(formatFullTime(createdAt), color = c.ink, fontSize = 14.sp)
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
        }
    }
}

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
    OrangDialog(onDismiss = onDismiss, title = "${user.displayName}'s profile") {
        Column {
            ProfileCard(user = user, presence = presence)

            if (relation != ProfileRelation.SELF) {
                Row(
                    modifier = Modifier.padding(top = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
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
        }
    }
}

@Composable
private fun ProfileDivider() {
    Spacer(Modifier.height(10.dp))
    Box(Modifier.fillMaxWidth().height(1.dp).background(OrangTheme.colors.border))
    Spacer(Modifier.height(10.dp))
}

@Composable
private fun ProfileLabel(text: String) {
    Text(
        text = text.uppercase(),
        color = OrangTheme.colors.inkMuted,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.4.sp,
        modifier = Modifier.padding(bottom = 4.dp),
    )
}
