package lt.oranges.orangchat.util

import lt.oranges.orangchat.data.model.Attachment

data class VideoPoster(val url: String)

private const val CLOUDINARY_VIDEO_UPLOAD = "/video/upload/"

fun videoPosterUrl(attachment: Attachment): VideoPoster? {
    val stored = absoluteUrl(attachment.thumbnailUrl)
    if (stored != null) return VideoPoster(stored)

    val href = absoluteUrl(attachment.url) ?: return null
    if (attachment.storage == "cloudinary") {
        val marker = href.indexOf(CLOUDINARY_VIDEO_UPLOAD)
        if (marker >= 0) {
            val head = href.substring(0, marker + CLOUDINARY_VIDEO_UPLOAD.length)
            val tail = href.substring(marker + CLOUDINARY_VIDEO_UPLOAD.length)
            return VideoPoster(head + "so_0/" + tail.replaceExtensionWithJpg())
        }
    }

    return null
}

private fun String.replaceExtensionWithJpg(): String {
    val lastSlash = lastIndexOf('/')
    val dot = lastIndexOf('.')
    return if (dot > lastSlash) substring(0, dot) + ".jpg" else "$this.jpg"
}
