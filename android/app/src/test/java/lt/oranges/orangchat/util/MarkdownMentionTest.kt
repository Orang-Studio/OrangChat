package lt.oranges.orangchat.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Both mention encodings have to keep working: composers write plain
 * `@username`, but `<@id>` predates that and still sits in older messages.
 * Mirrors the server's `parse_mention_tests`.
 */
class MarkdownMentionTest {

    private val ctx = MentionContext(
        names = mapOf("ckx9f2abc" to "Alice"),
        usernames = mapOf(
            "alice" to MentionUser("ckx9f2abc", "Alice"),
            "first.last" to MentionUser("ckq1", "Dotty"),
        ),
        selfId = "ckx9f2abc",
    )

    private fun inline(source: String): List<MdNode> {
        val blocks = parseMarkdown(source, ctx)
        return (blocks.single() as MdBlock.Paragraph).children
    }

    private fun mentions(source: String): List<MdNode.Mention> =
        inline(source).filterIsInstance<MdNode.Mention>()

    private fun text(source: String): String =
        inline(source).joinToString("") {
            when (it) {
                is MdNode.Text -> it.text
                is MdNode.Mention -> "@${it.name}"
                else -> ""
            }
        }

    @Test
    fun `resolves a plain handle to its user`() {
        val m = mentions("hey @alice").single()
        assertEquals("ckx9f2abc", m.userId)
        assertEquals("Alice", m.name)
        assertTrue(m.isSelf)
    }

    @Test
    fun `handles are case insensitive`() {
        assertEquals("ckx9f2abc", mentions("@Alice").single().userId)
    }

    @Test
    fun `a sentence final dot is punctuation not part of the handle`() {
        assertEquals("ask @Alice.", text("ask @alice."))
        assertEquals("ckq1", mentions("ask @first.last.").single().userId)
    }

    @Test
    fun `an unknown handle stays literal text`() {
        assertTrue(mentions("hey @nobody").isEmpty())
        assertEquals("hey @nobody", text("hey @nobody"))
    }

    @Test
    fun `an email host is not a mention`() {
        assertTrue(mentions("mail bob@example.com").isEmpty())
    }

    @Test
    fun `legacy id tokens still resolve`() {
        val m = mentions("hey <@ckx9f2abc>").single()
        assertEquals("ckx9f2abc", m.userId)
        assertEquals("Alice", m.name)
    }

    @Test
    fun `broadcast tokens are not read as handles`() {
        assertTrue(inline("@everyone ship it").first() is MdNode.Everyone)
        assertTrue(inline("@here ship it").first() is MdNode.Everyone)
    }

    @Test
    fun `notification matching accepts either encoding`() {
        assertTrue(Mentions.mentionsUser("hey @alice", "ckx9f2abc", "alice"))
        assertTrue(Mentions.mentionsUser("hey <@ckx9f2abc>", "ckx9f2abc", "alice"))
        assertTrue(Mentions.mentionsUser("hey @Alice", "ckx9f2abc", "alice"))
        assertFalse(Mentions.mentionsUser("hey @alicia", "ckx9f2abc", "alice"))
        assertFalse(Mentions.mentionsUser("mail bob@alice.com", "ckx9f2abc", "alice"))
    }
}
