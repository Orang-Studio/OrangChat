package lt.oranges.orangchat.util

import java.net.URI

/**
 * The invite link scheme, `https://chat.oranges.lt/invite/<code>`. Port of the
 * web client's features/servers/invite-url.ts - same shape, same host list, so
 * a link minted on either side is understood by both.
 *
 * Parses with java.net.URI rather than android.net.Uri purely so it is reachable
 * from plain JVM unit tests; both resolve these links identically.
 */
object InviteLink {
    /** Hosts whose /invite/ links are ours, matching the manifest's app links. */
    private val APP_HOSTS = setOf("chat.oranges.lt")

    private val CODE = Regex("^[A-Za-z0-9_-]{1,32}$")
    private val INVITE_PATH = Regex("^/invite/([A-Za-z0-9_-]{1,32})/?$")

    /** The canonical link to share. */
    fun urlFor(code: String): String = "https://chat.oranges.lt/invite/$code"

    /** The invite code in a URL, or null if it isn't one of our invite links. */
    fun codeFrom(url: String): String? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        val host = uri.host?.lowercase()?.removePrefix("www.") ?: return null
        if (host !in APP_HOSTS) return null
        val scheme = uri.scheme?.lowercase()
        if (scheme != "https" && scheme != "http") return null
        return INVITE_PATH.find(uri.path.orEmpty())?.groupValues?.get(1)
    }

    /**
     * The code out of whatever was pasted - a full link or the bare code.
     * People paste the thing they were given, and that is now a URL.
     */
    fun parseInput(input: String): String? {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true)) {
            return codeFrom(trimmed)
        }
        // Not a URL, so treat it as a code - but only if it looks like one, so a
        // stray sentence fails here rather than as a puzzling 404 from the API.
        return trimmed.takeIf { CODE.matches(it) }
    }
}
