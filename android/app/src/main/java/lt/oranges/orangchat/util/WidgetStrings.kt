package lt.oranges.orangchat.util

import android.content.Context
import lt.oranges.orangchat.R

/**
 * The widget catalogue ships i18n keys, not display text, so the server can add
 * a widget type without a client release. Keys this build knows resolve to a
 * real string; anything newer falls back to the key itself, humanised.
 */
private val WIDGET_STRINGS: Map<String, Int> = mapOf(
    "profileCard.aboutMe" to R.string.widget_bio,
    "profileCard.memberSince" to R.string.widget_member_since,
    "widget.badges" to R.string.widget_badges,
    "widget.bio" to R.string.widget_bio,
    "widget.bio.desc" to R.string.widget_bio_desc,
    "widget.connections" to R.string.widget_connections,
    "widget.divider" to R.string.widget_divider,
    "widget.field.alt" to R.string.widget_field_alt,
    "widget.field.body" to R.string.widget_field_body,
    "widget.field.label" to R.string.widget_field_label,
    "widget.field.links" to R.string.widget_field_links,
    "widget.field.rows" to R.string.widget_field_rows,
    "widget.field.size" to R.string.widget_field_size,
    "widget.field.text" to R.string.widget_field_text,
    "widget.field.title" to R.string.widget_field_title,
    "widget.field.url" to R.string.widget_field_url,
    "widget.field.value" to R.string.widget_field_value,
    "widget.fields" to R.string.widget_fields,
    "widget.fields.desc" to R.string.widget_fields_desc,
    "widget.heading" to R.string.widget_heading,
    "widget.image" to R.string.widget_image,
    "widget.links" to R.string.widget_links,
    "widget.memberSince" to R.string.widget_member_since,
    "widget.nowPlaying" to R.string.widget_now_playing,
    "widget.nowPlaying.desc" to R.string.widget_now_playing_desc,
    "widget.pronouns" to R.string.widget_pronouns,
    "widget.size.lg" to R.string.widget_size_lg,
    "widget.size.md" to R.string.widget_size_md,
    "widget.size.sm" to R.string.widget_size_sm,
    "widget.spacer" to R.string.widget_spacer,
    "widget.text" to R.string.widget_text,
    "widget.text.desc" to R.string.widget_text_desc,
)

fun widgetString(context: Context, key: String): String {
    WIDGET_STRINGS[key]?.let { return AppStrings.get(context, it) }
    return humaniseWidgetKey(key)
}

internal fun humaniseWidgetKey(key: String): String {
    val tail = key.substringAfterLast('.')
    if (tail.isBlank()) return key
    val spaced = buildString(tail.length + 4) {
        tail.forEachIndexed { index, ch ->
            if (index > 0 && ch.isUpperCase()) append(' ')
            append(if (ch == '-' || ch == '_') ' ' else ch)
        }
    }
    return spaced.replaceFirstChar { it.uppercase() }
}
