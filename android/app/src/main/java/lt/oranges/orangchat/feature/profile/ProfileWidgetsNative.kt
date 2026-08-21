package lt.oranges.orangchat.feature.profile

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import lt.oranges.orangchat.R
import lt.oranges.orangchat.data.model.ProfileWidgetBlock
import lt.oranges.orangchat.data.model.User
import lt.oranges.orangchat.feature.chat.MessageText
import lt.oranges.orangchat.ui.components.ActivityStatus
import lt.oranges.orangchat.ui.components.ProfileBadges
import lt.oranges.orangchat.ui.components.Text
import lt.oranges.orangchat.ui.theme.OrangTheme
import lt.oranges.orangchat.util.AppStrings
import lt.oranges.orangchat.util.PlaceholderSource
import lt.oranges.orangchat.util.absoluteUrl
import lt.oranges.orangchat.util.fallbackWidgetDefinition
import lt.oranges.orangchat.util.formatFullTime
import lt.oranges.orangchat.util.resolveWidgetLayout
import lt.oranges.orangchat.util.substituteWidgetText
import lt.oranges.orangchat.util.widgetItemText
import lt.oranges.orangchat.util.widgetListFrom

private val SPACER_HEIGHTS = mapOf("none" to 0.dp, "sm" to 4.dp, "md" to 8.dp, "lg" to 16.dp)

private fun hasContent(node: ProfileWidgetBlock, user: User, source: PlaceholderSource): Boolean =
    when (node.block) {
        "native" -> when (node.component) {
            "bio" -> !user.bio.isNullOrBlank()
            "pronouns" -> !user.pronouns.isNullOrBlank()
            "badges" -> user.badges.isNotEmpty()
            "activity" -> user.activities.isNotEmpty()
            "member-since" -> user.createdAt.isNotBlank()
            else -> false
        }
        "section" -> node.body?.let { hasContent(it, user, source) } == true
        "text" -> node.value?.let { substituteWidgetText(it, source) }?.isNotBlank() == true
        "rows" -> node.from?.let { widgetListFrom(source, it) }?.isNotEmpty() == true
        "links" -> node.from?.let { widgetListFrom(source, it) }.orEmpty().any { item ->
            isExternalUrl(substituteWidgetText(widgetItemText(item, "url"), source))
        }
        "image" -> node.src?.let { isExternalUrl(substituteWidgetText(it, source)) } == true
        "spacer", "divider" -> true
        else -> false
    }

private fun isExternalUrl(url: String): Boolean =
    (url.startsWith("https://") || url.startsWith("http://")) && !url.contains('{')

@Composable
fun ProfileWidgetsNative(user: User, modifier: Modifier = Modifier) {
    val definitions = LocalWidgetCatalog.current
    val context = LocalContext.current
    val aboutMe = AppStrings.get(context, R.string.catalog_about_me_e3ba4ef3)
    val memberSince = AppStrings.get(context, R.string.catalog_member_since_f425b08f)

    val entries = resolveWidgetLayout(user.profileWidgets).mapNotNull { widget ->
        if (widget.hidden) return@mapNotNull null
        val render = (definitions[widget.type] ?: fallbackWidgetDefinition(widget.type))
            ?.render ?: return@mapNotNull null
        val source = PlaceholderSource(
            username = user.username,
            displayName = user.displayName,
            pronouns = user.pronouns,
            createdAt = user.createdAt.takeIf { it.isNotBlank() },
            fields = user.profileFields,
            config = widget.config,
        )
        if (!hasContent(render, user, source)) return@mapNotNull null
        Triple(widget.type, render, source)
    }
    if (entries.isEmpty()) return

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        entries.forEach { (type, render, source) ->
            val fallbackHeading = when (type) {
                "bio" -> aboutMe
                "member-since" -> memberSince
                else -> null
            }
            WidgetBlock(render, user, source, fallbackHeading)
        }
    }
}

@Composable
private fun WidgetBlock(
    node: ProfileWidgetBlock,
    user: User,
    source: PlaceholderSource,
    fallbackHeading: String?,
) {
    val c = OrangTheme.colors
    when (node.block) {
        "native" -> when (node.component) {
            "bio" -> user.bio?.takeIf { it.isNotBlank() }?.let { MessageText(content = it) }
            "pronouns" -> user.pronouns?.takeIf { it.isNotBlank() }
                ?.let { Text(it, color = c.inkMuted, fontSize = 12.sp) }
            "badges" -> ProfileBadges(badges = user.badges)
            "activity" -> ActivityStatus(activities = user.activities, compact = false)
            "member-since" -> user.createdAt.takeIf { it.isNotBlank() }
                ?.let { Text(formatFullTime(it), color = c.ink, fontSize = 14.sp) }
            else -> Unit
        }

        "section" -> {
            val body = node.body ?: return
            val heading = node.heading
                ?.let { substituteWidgetText(it, source) }
                ?.takeIf { it.isNotBlank() && !it.contains('{') }
                ?: fallbackHeading
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                heading?.let {
                    Text(
                        text = it.uppercase(),
                        color = c.inkMuted,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 0.4.sp,
                    )
                }
                WidgetBlock(body, user, source, null)
            }
        }

        "text" -> {
            val value = node.value?.let { substituteWidgetText(it, source) }.orEmpty()
            if (value.isBlank()) return
            if (node.markdown) MessageText(content = value)
            else Text(value, color = c.ink, fontSize = 14.sp)
        }

        "rows" -> {
            val items = node.from?.let { widgetListFrom(source, it) }.orEmpty()
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                items.forEach { item ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            text = substituteWidgetText(widgetItemText(item, "label"), source),
                            color = c.inkMuted,
                            fontSize = 14.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = substituteWidgetText(widgetItemText(item, "value"), source),
                            color = c.ink,
                            fontSize = 14.sp,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }

        "links" -> {
            val context = LocalContext.current
            val items = node.from?.let { widgetListFrom(source, it) }.orEmpty()
                .map { item ->
                    substituteWidgetText(widgetItemText(item, "url"), source) to
                        substituteWidgetText(widgetItemText(item, "label"), source)
                }
                .filter { (url, _) -> isExternalUrl(url) }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                items.forEach { (url, label) ->
                    Text(
                        text = label.ifBlank { url },
                        color = c.primary,
                        fontSize = 14.sp,
                        textDecoration = TextDecoration.Underline,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clickable {
                            runCatching {
                                context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
                            }
                        },
                    )
                }
            }
        }

        "image" -> {
            val src = node.src?.let { substituteWidgetText(it, source) }.orEmpty()
            if (!isExternalUrl(src)) return
            AsyncImage(
                model = absoluteUrl(src),
                contentDescription = node.alt?.let { substituteWidgetText(it, source) },
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 192.dp)
                    .clip(RoundedCornerShape(6.dp)),
            )
        }

        "spacer" -> {
            val size = node.size?.let { substituteWidgetText(it, source) } ?: "md"
            Box(Modifier.height(SPACER_HEIGHTS[size] ?: SPACER_HEIGHTS.getValue("md")))
        }

        "divider" -> Box(Modifier.fillMaxWidth().height(1.dp).background(c.border))

        else -> Unit
    }
}
