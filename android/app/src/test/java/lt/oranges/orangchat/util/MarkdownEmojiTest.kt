package lt.oranges.orangchat.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownEmojiTest {

    private val ctx = MentionContext(
        emojis = mapOf(
            "abc123" to EmojiRef("abc123", "orange", "/uploads/orange.png", animated = false),
            "gif456" to EmojiRef("gif456", "dance", "/uploads/dance.gif", animated = true),
        ),
    )

    private fun inline(source: String): List<MdNode> {
        val blocks = parseMarkdown(source, ctx)
        return (blocks.single() as MdBlock.Paragraph).children
    }

    @Test
    fun `resolves a static custom emoji`() {
        val nodes = inline("<:orange:abc123>")
        val emoji = nodes.single() as MdNode.CustomEmoji

        assertEquals("abc123", emoji.id)
        assertEquals("orange", emoji.name)
        assertEquals("/uploads/orange.png", emoji.url)
        assertTrue(!emoji.animated)
    }

    @Test
    fun `resolves an animated custom emoji`() {
        val emoji = inline("<a:dance:gif456>").single() as MdNode.CustomEmoji

        assertTrue(emoji.animated)
        assertEquals("dance", emoji.name)
    }

    @Test
    fun `an unknown id falls back to the raw name, never a broken image`() {
        val node = inline("<:ghost:doesnotexist>").single()

        assertEquals(MdNode.Text(":ghost:"), node)
    }

    @Test
    fun `the id resolves, so a stale name in the token still renders the emoji`() {
        val emoji = inline("<:oldname:abc123>").single() as MdNode.CustomEmoji

        assertEquals("orange", emoji.name)
    }

    @Test
    fun `emoji sit alongside surrounding text`() {
        val nodes = inline("hi <:orange:abc123> there")

        assertEquals(MdNode.Text("hi "), nodes[0])
        assertTrue(nodes[1] is MdNode.CustomEmoji)
        assertEquals(MdNode.Text(" there"), nodes[2])
    }

    @Test
    fun `emoji survive inside bold`() {
        val bold = inline("**<:orange:abc123>**").single() as MdNode.Bold

        assertTrue(bold.children.single() is MdNode.CustomEmoji)
    }

    @Test
    fun `a user mention is not mistaken for an emoji`() {
        val nodes = inline("<@user1>")

        assertTrue(nodes.single() is MdNode.Mention)
    }

    @Test
    fun `plain colon text is left alone`() {
        val nodes = inline("ratio 3:1 today")

        assertEquals(listOf(MdNode.Text("ratio 3:1 today")), nodes)
    }
}
