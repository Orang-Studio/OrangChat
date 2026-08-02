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

private const val ORANGMOVE_FILE_PREFIX = "/orangmove/file/"

/**
 * The url to hand a player or an image loader, as opposed to a downloader.
 *
 * OrangMove serves `/file/` as `application/octet-stream` with
 * `Content-Disposition: attachment` and `X-Content-Type-Options: nosniff` -
 * correct for saving a file, useless for showing one. Its `/view/` route serves
 * the same bytes inline under the type it detects from their magic bytes (and a
 * `default-src 'none'; sandbox` CSP, so a mislabelled file still can't run),
 * which is what every attachment over 10MB needs to preview at all.
 *
 * Downloads keep the original: `/file/` is the route that names the file.
 */
fun inlineUrl(url: String?): String? {
    if (url.isNullOrBlank()) return null
    if (!url.startsWith(ORANGMOVE_FILE_PREFIX)) return url
    return "/orangmove/view/" + url.removePrefix(ORANGMOVE_FILE_PREFIX)
}
