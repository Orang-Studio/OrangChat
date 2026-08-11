package lt.oranges.orangchat.util

import lt.oranges.orangchat.data.model.Attachment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VideoPosterTest {

    private fun video(
        url: String,
        storage: String? = null,
        size: Long = 1_000_000,
    ) = Attachment(
        id = "a1",
        url = url,
        filename = "clip.mp4",
        contentType = "video/mp4",
        size = size,
        storage = storage,
    )

    @Test
    fun `cloudinary gets a server-rendered still, never a download`() {
        val poster = videoPosterUrl(
            video(
                "https://res.cloudinary.com/demo/video/upload/v1712/folder/clip.mp4",
                storage = "cloudinary",
            ),
        )
        assertEquals(
            "https://res.cloudinary.com/demo/video/upload/so_0/v1712/folder/clip.jpg",
            poster?.url,
        )
    }

    @Test
    fun `cloudinary rewrites only the last segment's extension`() {
        val poster = videoPosterUrl(
            video("https://res.cloudinary.com/demo/video/upload/v1/a.b/clip.webm", storage = "cloudinary"),
        )
        assertEquals("https://res.cloudinary.com/demo/video/upload/so_0/v1/a.b/clip.jpg", poster?.url)
    }

    @Test
    fun `a local clip is never downloaded for its own first frame`() {
        assertNull(videoPosterUrl(video("/attachments/x.mp4", storage = "local", size = 2_000_000)))
    }

    @Test
    fun `an orangmove clip gets no poster at all`() {
        assertNull(videoPosterUrl(video("/orangmove/file/t", storage = "orangmove", size = 80L * 1024 * 1024)))
    }

    @Test
    fun `a cloudinary url without the video upload segment has no poster`() {
        assertNull(
            videoPosterUrl(
                video("https://res.cloudinary.com/demo/raw/upload/v1/clip.mp4", storage = "cloudinary", size = 5),
            ),
        )
    }
}
