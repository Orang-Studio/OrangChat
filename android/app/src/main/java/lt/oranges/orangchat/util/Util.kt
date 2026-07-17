package lt.oranges.orangchat.util

import lt.oranges.orangchat.BuildConfig

/** Origin (no /api) for resolving relative /uploads/... URLs the backend returns. */
val BACKEND_ORIGIN: String = BuildConfig.SOCKET_URL

/** Uploaded images come back as relative `/uploads/<file>`; absolutize them. */
fun absoluteUrl(url: String?): String? {
    if (url.isNullOrBlank()) return null
    return if (url.startsWith("http://") || url.startsWith("https://")) url
    else BACKEND_ORIGIN.trimEnd('/') + "/" + url.trimStart('/')
}
