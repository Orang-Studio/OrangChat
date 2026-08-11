package lt.oranges.orangchat.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InviteLinkTest {

    @Test
    fun `accepts a canonical invite link`() {
        assertEquals("dQw4w9Wg", InviteLink.codeFrom("https://chat.oranges.lt/invite/dQw4w9Wg"))
    }

    @Test
    fun `round-trips its own generated link`() {
        assertEquals("aB3-_x9Z", InviteLink.codeFrom(InviteLink.urlFor("aB3-_x9Z")))
    }

    @Test
    fun `accepts base64url codes`() {
        assertEquals("a-b_c9Z0", InviteLink.codeFrom("https://chat.oranges.lt/invite/a-b_c9Z0"))
    }

    @Test
    fun `tolerates a trailing slash and www`() {
        assertEquals("abc123", InviteLink.codeFrom("https://chat.oranges.lt/invite/abc123/"))
        assertEquals("abc123", InviteLink.codeFrom("https://www.chat.oranges.lt/invite/abc123"))
    }

    @Test
    fun `rejects foreign hosts`() {
        assertNull(InviteLink.codeFrom("https://evil.example.com/invite/abc123"))
        assertNull(InviteLink.codeFrom("https://chat.oranges.lt.evil.com/invite/abc123"))
    }

    @Test
    fun `rejects other paths on our host`() {
        assertNull(InviteLink.codeFrom("https://chat.oranges.lt/"))
        assertNull(InviteLink.codeFrom("https://chat.oranges.lt/servers/abc"))
        assertNull(InviteLink.codeFrom("https://chat.oranges.lt/invite/"))
        assertNull(InviteLink.codeFrom("https://chat.oranges.lt/invite/abc/extra"))
    }

    @Test
    fun `rejects junk`() {
        assertNull(InviteLink.codeFrom("not a url"))
        assertNull(InviteLink.codeFrom(""))
    }

    @Test
    fun `parseInput takes a bare code or a full link`() {
        assertEquals("dQw4w9Wg", InviteLink.parseInput("dQw4w9Wg"))
        assertEquals("dQw4w9Wg", InviteLink.parseInput("  https://chat.oranges.lt/invite/dQw4w9Wg "))
    }

    @Test
    fun `parseInput rejects prose and foreign links`() {
        assertNull(InviteLink.parseInput("join my server please"))
        assertNull(InviteLink.parseInput("https://evil.example.com/invite/abc123"))
        assertNull(InviteLink.parseInput(""))
    }
}
