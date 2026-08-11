package lt.oranges.orangchat.util

import java.net.URI

object InviteLink {
    private val APP_HOSTS = setOf("chat.oranges.lt")

    private val CODE = Regex("^[A-Za-z0-9_-]{1,32}$")
    private val INVITE_PATH = Regex("^/invite/([A-Za-z0-9_-]{1,32})/?$")

    fun urlFor(code: String): String = "https://chat.oranges.lt/invite/$code"

    fun codeFrom(url: String): String? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        val host = uri.host?.lowercase()?.removePrefix("www.") ?: return null
        if (host !in APP_HOSTS) return null
        val scheme = uri.scheme?.lowercase()
        if (scheme != "https" && scheme != "http") return null
        return INVITE_PATH.find(uri.path.orEmpty())?.groupValues?.get(1)
    }

    fun parseInput(input: String): String? {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true)) {
            return codeFrom(trimmed)
        }
        return trimmed.takeIf { CODE.matches(it) }
    }
}
