package lt.oranges.orangchat.util

import lt.oranges.orangchat.data.model.Attachment

/** Where a video's still frame comes from. Always an image, never the clip. */
data class VideoPoster(val url: String)

/** Cloudinary's transform segment sits between `/upload/` and the version. */
private const val CLOUDINARY_VIDEO_UPLOAD = "/video/upload/"

/**
 * A still to show for [attachment] before anyone presses play, or null to leave
 * it dark behind the play button.
 *
 * New uploads carry a real still: the sender's client captured the first frame
 * at upload time and it is stored next to the bytes, so every storage type -
 * local, Cloudinary and OrangMove alike - gets a preview that costs a small
 * image instead of the clip.
 *
 * Older Cloudinary rows predate that and have no stored still, so the clip
 * itself is rendered on demand - `so_0` is Cloudinary's seek-offset-zero
 * transform - which is what made those previews possible at all before uploads
 * started carrying one.
 */
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

/** Only the last path segment's extension; a version like `/v1712/` must survive. */
private fun String.replaceExtensionWithJpg(): String {
    val lastSlash = lastIndexOf('/')
    val dot = lastIndexOf('.')
    return if (dot > lastSlash) substring(0, dot) + ".jpg" else "$this.jpg"
}
