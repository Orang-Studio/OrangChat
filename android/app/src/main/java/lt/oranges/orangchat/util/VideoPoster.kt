package lt.oranges.orangchat.util

import lt.oranges.orangchat.data.model.Attachment

/**
 * Where a video's still frame comes from.
 *
 * [decodeFrame] means [url] is the clip itself and coil-video has to pull a
 * frame out of it locally — which costs a download of the whole file. False
 * means [url] is already an image.
 */
data class VideoPoster(val url: String, val decodeFrame: Boolean)

/**
 * Past this, decoding a frame locally costs more than the dark placeholder is
 * worth. Only OrangMove-backed clips can reach it: anything over 10MB goes
 * there, and everything under stays local.
 */
private const val FRAME_DECODE_SIZE_LIMIT = 25L * 1024 * 1024

/** Cloudinary's transform segment sits between `/upload/` and the version. */
private const val CLOUDINARY_VIDEO_UPLOAD = "/video/upload/"

/**
 * A still to show for [attachment] before anyone presses play, or null to leave
 * it dark.
 *
 * Cloudinary renders stills on demand — `so_0` is its seek-offset-zero
 * transform — so those cost one small jpg instead of the clip. Nothing else has
 * a server-side thumbnail, so the frame has to be decoded on the device, and
 * that is only worth doing for clips small enough that fetching them early is
 * not a real cost.
 */
fun videoPosterUrl(attachment: Attachment): VideoPoster? {
    val href = absoluteUrl(attachment.url) ?: return null

    if (attachment.storage == "cloudinary") {
        val marker = href.indexOf(CLOUDINARY_VIDEO_UPLOAD)
        if (marker >= 0) {
            val head = href.substring(0, marker + CLOUDINARY_VIDEO_UPLOAD.length)
            val tail = href.substring(marker + CLOUDINARY_VIDEO_UPLOAD.length)
            return VideoPoster(head + "so_0/" + tail.replaceExtensionWithJpg(), decodeFrame = false)
        }
    }

    if (attachment.size in 1..FRAME_DECODE_SIZE_LIMIT) {
        return VideoPoster(href, decodeFrame = true)
    }
    return null
}

/** Only the last path segment's extension; a version like `/v1712/` must survive. */
private fun String.replaceExtensionWithJpg(): String {
    val lastSlash = lastIndexOf('/')
    val dot = lastIndexOf('.')
    return if (dot > lastSlash) substring(0, dot) + ".jpg" else "$this.jpg"
}
