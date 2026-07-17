package lt.oranges.orangchat.util

import lt.oranges.orangchat.data.model.Attachment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The poster URL decides whether showing a still costs a small jpg or the whole
 * clip, so a slip here is a data-usage bug rather than a visual one.
 */
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
        assertFalse(poster!!.decodeFrame)
    }

    /** The `/v1712/` version segment has a dot-free name but must survive intact. */
    @Test
    fun `cloudinary rewrites only the last segment's extension`() {
        val poster = videoPosterUrl(
            video("https://res.cloudinary.com/demo/video/upload/v1/a.b/clip.webm", storage = "cloudinary"),
        )
        assertEquals("https://res.cloudinary.com/demo/video/upload/so_0/v1/a.b/clip.jpg", poster?.url)
    }

    @Test
    fun `a small local clip is decoded on the device`() {
        val poster = videoPosterUrl(video("/attachments/x.mp4", storage = "local", size = 2_000_000))
        assertTrue(poster!!.decodeFrame)
        assertEquals(absoluteUrl("/attachments/x.mp4"), poster.url)
    }

    /** Fetching a large clip early costs more than the dark box is worth. */
    @Test
    fun `an oversized clip gets no poster at all`() {
        assertNull(videoPosterUrl(video("/orangmove/file/t", storage = "orangmove", size = 80L * 1024 * 1024)))
    }

    @Test
    fun `an unknown size is not guessed at`() {
        assertNull(videoPosterUrl(video("/orangmove/file/t", storage = "orangmove", size = 0)))
    }

    /** Cloudinary storage but an image-style URL: fall back rather than mangle it. */
    @Test
    fun `a cloudinary url without the video upload segment falls back`() {
        val poster = videoPosterUrl(
            video("https://res.cloudinary.com/demo/raw/upload/v1/clip.mp4", storage = "cloudinary", size = 5),
        )
        assertTrue(poster!!.decodeFrame)
    }
}
