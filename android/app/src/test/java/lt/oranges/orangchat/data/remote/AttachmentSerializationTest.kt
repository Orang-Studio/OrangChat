package lt.oranges.orangchat.data.remote

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import lt.oranges.orangchat.data.model.Attachment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AttachmentSerializationTest {
    @Test
    fun `upload response decodes outside the model package`() {
        val attachment = Json.decodeFromString<Attachment>(
            """
            {
              "id": "attachment-1",
              "url": "/attachments/attachment-1.mp4",
              "filename": "instagram_video.mp4",
              "contentType": "video/mp4",
              "size": 1234
            }
            """.trimIndent(),
        )

        assertEquals("attachment-1", attachment.id)
        assertTrue(attachment.isVideo)
    }
}
