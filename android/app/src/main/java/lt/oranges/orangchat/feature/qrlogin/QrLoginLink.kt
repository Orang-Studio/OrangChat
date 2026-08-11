package lt.oranges.orangchat.feature.qrlogin

import java.net.URI

object QrLoginLink {
    private val TOKEN = Regex("^[A-Za-z0-9-]{1,64}$")

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
