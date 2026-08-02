package lt.oranges.orangchat.data.local

import lt.oranges.orangchat.data.model.Message
import lt.oranges.orangchat.data.model.User
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class OfflineCachePolicyTest {
    private val author = User(id = "user", username = "orange", displayName = "Orange")

    @Test
    fun `keeps only the latest bounded channel history`() {
        val messages = (0 until MAX_OFFLINE_MESSAGES_PER_CHANNEL + 20).map(::message)

        val cached = cacheableMessages(messages)

        assertEquals(MAX_OFFLINE_MESSAGES_PER_CHANNEL, cached.size)
        assertEquals("message-20", cached.first().id)
        assertEquals("message-${MAX_OFFLINE_MESSAGES_PER_CHANNEL + 19}", cached.last().id)
    }

    @Test
    fun `does not duplicate pending outbox rows into history`() {
        val cached = cacheableMessages(
            listOf(message(1), message(2).copy(id = "pending:two"), message(3)),
        )

        assertEquals(listOf("message-1", "message-3"), cached.map { it.id })
        assertFalse(cached.any { it.id.startsWith("pending:") })
    }

    private fun message(index: Int) = Message(
        id = "message-$index",
        channelId = "channel",
        author = author,
        content = "message $index",
        createdAt = index.toString(),
    )
}
