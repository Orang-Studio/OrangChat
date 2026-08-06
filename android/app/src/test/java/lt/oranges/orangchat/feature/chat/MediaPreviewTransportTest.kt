package lt.oranges.orangchat.feature.chat

import lt.oranges.orangchat.data.model.Attachment
import org.junit.Assert.assertEquals
import org.junit.Test

class MediaPreviewTransportTest {
    @Test
    fun attachmentSurvivesActivityTransport() {
        val attachment = Attachment(
            id = "attachment-1",
            url = "/api/attachments/attachment-1",
            filename = "clip.mp4",
            contentType = "video/mp4",
            size = 123_456,
            width = 1080,
            height = 1920,
            duration = 12.5,
            thumbnailUrl = "/api/attachments/thumbnail-1",
            storage = "local",
            flagged = false,
        )

        assertEquals(
            attachment,
            MediaPreviewTransport.decode(MediaPreviewTransport.encode(attachment)),
        )
    }
}
