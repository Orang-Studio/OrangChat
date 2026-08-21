package lt.oranges.orangchat.util

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import lt.oranges.orangchat.data.model.ProfileWidget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileWidgetsTest {

    private val source = PlaceholderSource(
        username = "orang",
        displayName = "Orang",
        pronouns = "they/them",
        createdAt = "2024-03-09T10:11:12Z",
        fields = mapOf("status" to "shipping things"),
        config = mapOf(
            "title" to JsonPrimitive("About me"),
            "count" to JsonPrimitive(3),
            "rows" to JsonArray(
                listOf(
                    JsonObject(
                        mapOf(
                            "label" to JsonPrimitive("Status"),
                            "value" to JsonPrimitive("{field.status}"),
                        ),
                    ),
                    JsonPrimitive("not an object"),
                ),
            ),
        ),
    )

    @Test
    fun `an empty layout falls back to the default one`() {
        assertEquals(defaultWidgetLayout(), resolveWidgetLayout(null))
        assertEquals(defaultWidgetLayout(), resolveWidgetLayout(emptyList()))
        assertEquals(BUILTIN_LAYOUT, defaultWidgetLayout().map { it.type })
    }

    @Test
    fun `a customised layout is kept as written`() {
        val custom = listOf(ProfileWidget(id = "a", type = "text"))
        assertEquals(custom, resolveWidgetLayout(custom))
    }

    @Test
    fun `placeholders resolve against config, fields and built-ins`() {
        assertEquals("About me", lookupPlaceholder("config.title", source))
        assertEquals("3", lookupPlaceholder("config.count", source))
        assertEquals("shipping things", lookupPlaceholder("field.status", source))
        assertEquals("orang", lookupPlaceholder("username", source))
        assertEquals("Orang", lookupPlaceholder("displayName", source))
        assertEquals("they/them", lookupPlaceholder("pronouns", source))
        assertEquals("2024", lookupPlaceholder("joinedYear", source))
        assertNull(lookupPlaceholder("nope", source))
        assertNull(lookupPlaceholder("field.missing", source))
    }

    @Test
    fun `an unknown placeholder is left visible instead of blanked`() {
        assertEquals(
            "hi Orang, {field.nope} since 2024",
            substituteWidgetText("hi {displayName}, {field.nope} since {joinedYear}", source),
        )
    }

    @Test
    fun `a malformed created at date yields no year`() {
        assertNull(lookupPlaceholder("joinedYear", PlaceholderSource(createdAt = "yesterday")))
    }

    @Test
    fun `list sources only read config arrays of objects`() {
        val items = widgetListFrom(source, "config.rows")
        assertEquals(1, items.size)
        assertEquals("Status", widgetItemText(items[0], "label"))
        assertEquals(
            "shipping things",
            substituteWidgetText(widgetItemText(items[0], "value"), source),
        )
        assertTrue(widgetListFrom(source, "config.title").isEmpty())
        assertTrue(widgetListFrom(source, "field.status").isEmpty())
        assertEquals("", widgetItemText(items[0], "missing"))
    }

    @Test
    fun `every built-in widget renders without the catalogue`() {
        BUILTIN_LAYOUT.forEach { type ->
            assertTrue(type, fallbackWidgetDefinition(type)?.render != null)
        }
        assertNull(fallbackWidgetDefinition("text"))
    }

    @Test
    fun `the legacy listening kind still counts as listening`() {
        assertTrue(isListeningKind("listening"))
        assertTrue(isListeningKind("spotify"))
        assertFalse(isListeningKind("game"))
    }

    @Test
    fun `catalogue keys this build has never seen still read as words`() {
        assertEquals("Some New Widget", humaniseWidgetKey("widget.someNewWidget"))
        assertEquals("Bare", humaniseWidgetKey("bare"))
        assertEquals("", humaniseWidgetKey(""))
    }
}
