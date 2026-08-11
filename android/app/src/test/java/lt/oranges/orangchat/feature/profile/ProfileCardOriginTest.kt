package lt.oranges.orangchat.feature.profile

import lt.oranges.orangchat.data.model.User
import lt.oranges.orangchat.data.model.UserActivity
import lt.oranges.orangchat.ui.theme.DarkOrangColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileCardOriginTest {

    @Test
    fun `keeps scheme and host, drops path and query`() {
        assertEquals(
            "https://res.cloudinary.com",
            originOf("https://res.cloudinary.com/demo/image/upload/v1/a.png?x=1"),
        )
    }

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

    @Test
    fun `themed card badges include dismissible tap labels`() {
        val card = buildProfileCardHtml(
            user = User(
                id = "user-1",
                username = "badge-user",
                displayName = "Badge User",
                badges = listOf("beta"),
            ),
            colors = DarkOrangColors,
            aboutMeLabel = "About me",
            memberSinceLabel = "Member since",
        )

        assertTrue(card.html.contains("data-badge-label=\"Beta\""))
        assertTrue(card.html.contains("aria-expanded=\"false\""))
        assertTrue(card.html.contains("class=\"oc-pf-badge-label\""))
        assertTrue(card.html.contains("const closeBadges"))
        val nonce = Regex("""script-src 'nonce-([^']+)'""")
            .find(card.html)
            ?.groupValues
            ?.get(1)
        assertTrue(!nonce.isNullOrBlank())
        assertTrue(card.html.contains("<script nonce=\"$nonce\">"))
    }

    @Test
    fun `activity renders as a card with artwork, elapsed ticker and CSP entry`() {
        val imageUrl = "https://res.cloudinary.com/demo/cover.png"
        val startedAt = "2026-08-05T10:00:00Z"
        val card = buildProfileCardHtml(
            user = User(
                id = "user-2",
                username = "gamer",
                displayName = "Gamer",
                activities = listOf(
                    UserActivity(
                        kind = "game",
                        name = "Elden Ring",
                        details = "Limgrave",
                        imageUrl = imageUrl,
                        startedAt = startedAt,
                    ),
                ),
            ),
            colors = DarkOrangColors,
            aboutMeLabel = "About me",
            memberSinceLabel = "Member since",
        )

        assertTrue(card.html.contains("""class="oc-pf-activity" data-kind="game""""))
        assertTrue(card.html.contains("oc-pf-activity-label\">NOW PLAYING"))
        assertTrue(card.html.contains("oc-pf-activity-name\">Elden Ring"))
        assertTrue(card.html.contains("oc-pf-activity-details\">Limgrave"))
        assertTrue(card.html.contains("""data-started-at="$startedAt""""))
        assertTrue(card.html.contains("""src="$imageUrl" alt="""""))
        assertTrue(card.html.contains("const tick"))
        assertTrue(imageUrl in card.imageAllowlist)
        assertTrue(card.html.contains("img-src https://chat.oranges.lt https://res.cloudinary.com"))
    }

    @Test
    fun `activity card without artwork keeps the emoji fallback and no image origin`() {
        val card = buildProfileCardHtml(
            user = User(
                id = "user-3",
                username = "listener",
                displayName = "Listener",
                activities = listOf(
                    UserActivity(kind = "spotify", name = "Some Song", startedAt = "2026-08-05T09:00:00Z"),
                ),
            ),
            colors = DarkOrangColors,
            aboutMeLabel = "About me",
            memberSinceLabel = "Member since",
        )

        assertTrue(card.html.contains("oc-pf-activity-label\">LISTENING TO"))
        assertTrue(card.html.contains("oc-pf-activity-artwork-fallback"))
        assertFalse(card.html.contains("""<span class="oc-pf-activity-details">"""))
    }
}
