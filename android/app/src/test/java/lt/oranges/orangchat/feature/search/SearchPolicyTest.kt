package lt.oranges.orangchat.feature.search

import lt.oranges.orangchat.data.model.User
import org.junit.Assert.assertEquals
import org.junit.Test

class SearchPolicyTest {
    @Test
    fun `full offline row replaces legacy decrypted hit and results sort newest first`() {
        val legacy = hit(id = "same", createdAt = "", content = "cached text")
        val full = hit(
            id = "same",
            createdAt = "2026-08-02T12:00:00Z",
            content = "cached text",
            author = User("u", "orange", "Orange"),
        )
        val older = hit(id = "older", createdAt = "2026-08-01T12:00:00Z")

        val merged = mergeLocalResults(listOf(legacy, older), listOf(full))

        assertEquals(listOf("same", "older"), merged.map { it.id })
        assertEquals("Orange", merged.first().author?.displayName)
    }

    @Test
    fun `local results honor their requested limit`() {
        val results = mergeLocalResults(
            decrypted = (0..5).map { hit("id-$it", "2026-08-02T12:00:0${it}Z") },
            offline = emptyList(),
            limit = 3,
        )

        assertEquals(listOf("id-5", "id-4", "id-3"), results.map { it.id })
    }

    @Test
    fun `overlapping server pages do not duplicate result ids`() {
        val existing = listOf(hit("same", "2026-08-02T12:00:00Z"))
        val page = listOf(
            hit("same", "2026-08-02T12:00:00Z"),
            hit("new", "2026-08-02T11:00:00Z"),
        )

        assertEquals(listOf("same", "new"), mergeSearchResults(existing, page, append = true).map { it.id })
    }

    private fun hit(
        id: String,
        createdAt: String,
        content: String = "orange",
        author: User? = null,
    ) = SearchResult(
        id = id,
        channelId = "channel",
        authorId = author?.id.orEmpty(),
        author = author,
        content = content,
        createdAt = createdAt,
    )
}
