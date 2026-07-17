package lt.oranges.orangchat.feature.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * This feeds the card's CSP img-src. Too narrow and avatars silently vanish;
 * too broad and a profile could name a host we never intended to allow.
 */
class ProfileCardOriginTest {

    @Test
    fun `keeps scheme and host, drops path and query`() {
        assertEquals(
            "https://res.cloudinary.com",
            originOf("https://res.cloudinary.com/demo/image/upload/v1/a.png?x=1"),
        )
    }

    /** A non-default port is part of the origin — dropping it would block the load. */
    @Test
    fun `keeps an explicit port`() {
        assertEquals("http://localhost:3000", originOf("http://localhost:3000/uploads/a.png"))
    }

    @Test
    fun `rejects anything without a scheme and host`() {
        assertNull(originOf("/uploads/a.png"))
        assertNull(originOf("not a url"))
        assertNull(originOf(""))
    }
}
