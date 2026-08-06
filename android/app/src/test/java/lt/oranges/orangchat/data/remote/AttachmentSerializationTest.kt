package lt.oranges.orangchat.data.remote

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import lt.oranges.orangchat.crypto.E2eePayloads
import lt.oranges.orangchat.crypto.SealedAttachmentRef
import lt.oranges.orangchat.data.model.Attachment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    @Test
    fun `upload response carries media preview metadata`() {
        val attachment = Json.decodeFromString<Attachment>(
            """
            {
              "id": "attachment-2",
              "url": "/attachments/attachment-2.mp4",
              "filename": "clip.mp4",
              "contentType": "video/mp4",
              "size": 4321,
              "duration": 83.5,
              "thumbnailUrl": "/attachments/attachment-2.jpg"
            }
            """.trimIndent(),
        )

        assertEquals(83.5, attachment.duration!!, 0.0)
        assertEquals("/attachments/attachment-2.jpg", attachment.thumbnailUrl)
        assertNull(attachment.width)
    }

    @Test
    fun `sealed attachment ref round-trips duration and thumb`() {
        val ref = SealedAttachmentRef(
            fileId = "file-1",
            attachmentId = "attachment-1",
            key = "a2V5",
            nonce = "bm9uY2U=",
            filename = "clip.mp4",
            contentType = "video/mp4",
            size = 1234,
            duration = 41.25,
            width = 1920,
            height = 1080,
            thumb = SealedAttachmentRef.Thumb(
                fileId = "file-2",
                attachmentId = "attachment-2",
                key = "a2V5Mg==",
                nonce = "bm9uY2Uy",
                contentType = "image/jpeg",
                size = 9876,
            ),
        )

        val encoded = E2eePayloads.encodeAttachments(listOf(ref))
        val decoded = E2eePayloads.decodeAttachments(encoded).single()

        assertEquals(41.25, decoded.duration!!, 0.0)
        assertEquals(1920, decoded.width)
        val thumb = decoded.thumb ?: throw AssertionError("sealed thumbnail was not decoded")
        assertEquals("file-2", thumb.fileId)
        assertEquals("image/jpeg", thumb.contentType)
    }

    @Test
    fun `sealed ref without preview decodes from old payloads`() {
        val decoded = E2eePayloads.decodeAttachments(
            """
            [{"fileId":"file-1","attachmentId":"attachment-1","key":"a2V5",
              "nonce":"bm9uY2U=","filename":"clip.mp4","contentType":"video/mp4","size":1234}]
            """.trimIndent(),
        ).single()

        assertEquals("clip.mp4", decoded.filename)
        assertNull(decoded.duration)
        assertNull(decoded.thumb)
    }
}
