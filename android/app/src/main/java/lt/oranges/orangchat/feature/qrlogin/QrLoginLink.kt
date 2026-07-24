package lt.oranges.orangchat.feature.qrlogin

import java.net.URI

/**
 * The QR sign-in deep link, `orangchat://login?token=<token>`. The web client
 * renders this as a QR; scanning it with the phone's camera opens the app here.
 *
 * Parsed with java.net.URI rather than android.net.Uri so it is reachable from
 * plain JVM unit tests, matching how [lt.oranges.orangchat.util.InviteLink]
 * handles invite links.
 */
object QrLoginLink {
    private val TOKEN = Regex("^[A-Za-z0-9-]{1,64}$")

    /** The token in a login deep link, or null if it isn't one. */
    fun tokenFrom(url: String): String? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        if (uri.scheme?.lowercase() != "orangchat") return null
        if (uri.host?.lowercase() != "login") return null
        val query = uri.rawQuery ?: return null
        val token = query
            .split('&')
            .mapNotNull { it.split('=', limit = 2).takeIf { p -> p.size == 2 } }
            .firstOrNull { it[0] == "token" }
            ?.get(1)
            ?: return null
        return token.takeIf { TOKEN.matches(it) }
    }
}
