package lt.oranges.orangchat.util

import lt.oranges.orangchat.BuildConfig

val BACKEND_ORIGIN: String = BuildConfig.SOCKET_URL

fun absoluteUrl(url: String?): String? {
    if (url.isNullOrBlank()) return null
    return if (url.startsWith("http://") || url.startsWith("https://")) url
    else BACKEND_ORIGIN.trimEnd('/') + "/" + url.trimStart('/')
}

private const val ORANGMOVE_FILE_PREFIX = "/orangmove/file/"

fun inlineUrl(url: String?): String? {
    if (url.isNullOrBlank()) return null
    if (!url.startsWith(ORANGMOVE_FILE_PREFIX)) return url
    return "/orangmove/view/" + url.removePrefix(ORANGMOVE_FILE_PREFIX)
}
