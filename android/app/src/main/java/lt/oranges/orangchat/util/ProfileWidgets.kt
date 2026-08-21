package lt.oranges.orangchat.util

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonElement
import lt.oranges.orangchat.data.model.ProfileWidget
import lt.oranges.orangchat.data.model.ProfileWidgetBlock
import lt.oranges.orangchat.data.model.ProfileWidgetDefinition
import java.time.Instant
import java.time.ZoneId

fun isListeningKind(kind: String): Boolean = kind == "listening" || kind == "spotify"

val BUILTIN_LAYOUT: List<String> = listOf(
    "pronouns",
    "now-playing",
    "badges",
    "bio",
    "connections",
    "member-since",
)

private val NATIVE_RENDER: Map<String, ProfileWidgetBlock> = mapOf(
    "bio" to ProfileWidgetBlock(
        block = "section",
        heading = "{config.title}",
        body = ProfileWidgetBlock(block = "native", component = "bio"),
    ),
    "pronouns" to ProfileWidgetBlock(block = "native", component = "pronouns"),
    "badges" to ProfileWidgetBlock(block = "native", component = "badges"),
    "now-playing" to ProfileWidgetBlock(block = "native", component = "activity"),
    "connections" to ProfileWidgetBlock(block = "native", component = "connections"),
    "member-since" to ProfileWidgetBlock(
        block = "section",
        heading = "{config.title}",
        body = ProfileWidgetBlock(block = "native", component = "member-since"),
    ),
)

fun fallbackWidgetDefinition(type: String): ProfileWidgetDefinition? =
    NATIVE_RENDER[type]?.let { ProfileWidgetDefinition(type = type, label = type, render = it) }

fun defaultWidgetLayout(): List<ProfileWidget> =
    BUILTIN_LAYOUT.mapIndexed { index, type -> ProfileWidget(id = "w$index", type = type) }

fun resolveWidgetLayout(widgets: List<ProfileWidget>?): List<ProfileWidget> =
    if (widgets.isNullOrEmpty()) defaultWidgetLayout() else widgets

data class PlaceholderSource(
    val username: String? = null,
    val displayName: String? = null,
    val pronouns: String? = null,
    val createdAt: String? = null,
    val fields: Map<String, String> = emptyMap(),
    val config: Map<String, JsonElement> = emptyMap(),
)

private val PLACEHOLDER = Regex("""\{([a-zA-Z0-9_.-]+)\}""")

private fun JsonElement.asPlainString(): String? = (this as? JsonPrimitive)?.let {
    if (it.isString) it.content else it.content.takeIf { raw -> raw != "null" }
}

fun lookupPlaceholder(name: String, source: PlaceholderSource): String? = when {
    name.startsWith("config.") -> source.config[name.removePrefix("config.")]?.asPlainString()
    name.startsWith("field.") -> source.fields[name.removePrefix("field.")]
    name == "username" -> source.username
    name == "displayName" -> source.displayName
    name == "pronouns" -> source.pronouns
    name == "joinedYear" -> source.createdAt?.let { joinedYear(it) }
    else -> null
}

private fun joinedYear(createdAt: String): String? = runCatching {
    Instant.parse(createdAt).atZone(ZoneId.systemDefault()).year.toString()
}.getOrNull()

/**
 * Substitutes `{name}` tokens against the widget's own config, the owner's
 * pushed fields and a few built-ins. An unknown token is left exactly as
 * written rather than blanked, so a typo stays visible.
 */
fun substituteWidgetText(template: String, source: PlaceholderSource): String =
    PLACEHOLDER.replace(template) { match ->
        lookupPlaceholder(match.groupValues[1], source) ?: match.value
    }

fun widgetListFrom(source: PlaceholderSource, path: String): List<Map<String, JsonElement>> {
    if (!path.startsWith("config.")) return emptyList()
    val raw = source.config[path.removePrefix("config.")] as? JsonArray ?: return emptyList()
    return raw.mapNotNull { item -> (item as? JsonObject)?.toMap() }
}

fun widgetItemText(item: Map<String, JsonElement>, key: String): String =
    item[key]?.asPlainString().orEmpty()
