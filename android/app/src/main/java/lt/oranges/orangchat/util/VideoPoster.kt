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
 * Only stills that already exist as an image qualify. Decoding a frame on the
 * device does not: coil-video has to have the whole clip before it can read
 * frame zero, so a poster cost a silent full download of every video in the
 * channel - which is exactly why previews took longer the bigger the file was,
 * and why an attachment on OrangMove (where every clip is over 10MB) showed
 * nothing at all after paying for the download. Bytes now move when play is
 * pressed and not before.
 *
 * Cloudinary renders stills on demand - `so_0` is its seek-offset-zero
 * transform - so those cost one small jpg instead of the clip.
 */
fun videoPosterUrl(attachment: Attachment): VideoPoster? {
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
