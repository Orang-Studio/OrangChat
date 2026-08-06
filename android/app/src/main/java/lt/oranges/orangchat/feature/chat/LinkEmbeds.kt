package lt.oranges.orangchat.feature.chat

import android.annotation.SuppressLint
import android.graphics.Color
import android.net.Uri
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import lt.oranges.orangchat.ui.components.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import lt.oranges.orangchat.data.model.Attachment
import lt.oranges.orangchat.data.remote.LinkPreviewData
import lt.oranges.orangchat.feature.invite.ChatInviteEmbed
import lt.oranges.orangchat.util.InviteLink
import lt.oranges.orangchat.ui.theme.OrangRadius
import lt.oranges.orangchat.ui.theme.OrangTheme
import java.time.Instant

private const val MAX_EMBEDS = 5
private val URL = Regex("https?://[^\\s<]+", RegexOption.IGNORE_CASE)
private val FENCED_CODE = Regex("```[\\s\\S]*?```")
private val INLINE_CODE = Regex("`[^`\\n]*`")
private val IMAGE_PATH = Regex("\\.(?:avif|gif|jpe?g|png|webp)$", RegexOption.IGNORE_CASE)
private val VIDEO_PATH = Regex("\\.(?:m4v|mov|mp4|ogv|webm)$", RegexOption.IGNORE_CASE)
private val AUDIO_PATH = Regex("\\.(?:aac|flac|m4a|mp3|oga|ogg|opus|wav)$", RegexOption.IGNORE_CASE)

internal fun extractEmbedUrls(content: String): List<String> {
    val prose = content.replace(FENCED_CODE, " ").replace(INLINE_CODE, " ")
    val urls = linkedSetOf<String>()
    for (match in URL.findAll(prose)) {
        var candidate = match.value.trimEnd('.', ',', ';', ':', '!', '?', '"', '\'')
        listOf('(' to ')', '[' to ']', '{' to '}').forEach { (open, close) ->
            while (candidate.endsWith(close) && candidate.count { it == close } > candidate.count { it == open }) {
                candidate = candidate.dropLast(1)
            }
        }
        val parsed = runCatching { Uri.parse(candidate) }.getOrNull() ?: continue
        if (parsed.scheme?.lowercase() !in setOf("http", "https") || parsed.host.isNullOrBlank()) continue
        urls += parsed.buildUpon().fragment(null).build().toString()
        if (urls.size == MAX_EMBEDS) break
    }
    return urls.toList()
}

/** Standalone media links show the media itself without repeating the raw URL. */
internal fun isDirectMediaMessage(content: String): Boolean {
    val trimmed = content.trim()
    if (trimmed.any(Char::isWhitespace)) return false
    val uri = runCatching { Uri.parse(trimmed) }.getOrNull() ?: return false
    val path = uri.path.orEmpty()
    return uri.scheme?.lowercase() in setOf("http", "https") &&
        (IMAGE_PATH.containsMatchIn(path) || VIDEO_PATH.containsMatchIn(path) || AUDIO_PATH.containsMatchIn(path))
}

private data class YoutubeVideo(val id: String, val start: Int?)

private fun youtubeVideo(uri: Uri): YoutubeVideo? {
    val host = uri.host?.lowercase()?.removePrefix("www.") ?: return null
    val segments = uri.pathSegments
    val id = when {
        host == "youtu.be" -> segments.firstOrNull()
        host in setOf("youtube.com", "m.youtube.com", "music.youtube.com") && uri.path == "/watch" ->
            uri.getQueryParameter("v")
        host in setOf("youtube.com", "m.youtube.com", "music.youtube.com") &&
            segments.firstOrNull() in setOf("embed", "live", "shorts") -> segments.getOrNull(1)
        else -> null
    }
    if (id == null || !id.matches(Regex("[a-zA-Z0-9_-]{11}"))) return null
    val start = parseStartTime(uri.getQueryParameter("t") ?: uri.getQueryParameter("start"))
    return YoutubeVideo(id, start)
}

private fun parseStartTime(value: String?): Int? {
    if (value == null) return null
    value.toIntOrNull()?.let { return it }
    val match = Regex("^(?:(\\d+)h)?(?:(\\d+)m)?(?:(\\d+)s)?$").matchEntire(value) ?: return null
    val seconds = (match.groupValues[1].toIntOrNull() ?: 0) * 3600 +
        (match.groupValues[2].toIntOrNull() ?: 0) * 60 +
        (match.groupValues[3].toIntOrNull() ?: 0)
    return seconds.takeIf { it > 0 }
}

@Composable
fun LinkEmbeds(
    content: String,
    modifier: Modifier = Modifier,
    viewModel: LinkPreviewViewModel = hiltViewModel(),
) {
    val urls = remember(content) { extractEmbedUrls(content) }
    if (urls.isEmpty()) return
    Column(modifier = modifier.fillMaxWidth()) {
        urls.forEach { url ->
            val uri = remember(url) { Uri.parse(url) }
            val youtube = remember(uri) { youtubeVideo(uri) }
            val invite = remember(url) { InviteLink.codeFrom(url) }
            when {
                // Our own invite links resolve against our API, so they get a
                // real card instead of a scraped og:title of the login page.
                invite != null -> ChatInviteEmbed(invite)
                youtube != null -> YoutubeEmbed(youtube)
                IMAGE_PATH.containsMatchIn(uri.path.orEmpty()) -> LinkedImageEmbed(url)
                VIDEO_PATH.containsMatchIn(uri.path.orEmpty()) -> {
                    Spacer(Modifier.height(6.dp))
                    VideoAttachment(
                        linkedAttachment(url, "video/${uri.path.orEmpty().substringAfterLast('.')}"),
                        null,
                        Instant.now(),
                    )
                }
                AUDIO_PATH.containsMatchIn(uri.path.orEmpty()) -> {
                    Spacer(Modifier.height(6.dp))
                    AudioCard(
                        linkedAttachment(url, "audio/${uri.path.orEmpty().substringAfterLast('.')}"),
                        null,
                        Instant.now(),
                    )
                }
                else -> PageEmbed(url, uri.host.orEmpty(), viewModel)
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun YoutubeEmbed(video: YoutubeVideo) {
    val c = OrangTheme.colors
    val embedUrl = "https://www.youtube-nocookie.com/embed/${video.id}" +
        (video.start?.let { "?start=$it" } ?: "")
    var webView by remember(video.id, video.start) { mutableStateOf<WebView?>(null) }
    Spacer(Modifier.height(6.dp))
    AndroidView(
        factory = { context ->
            WebView(context).apply {
                setBackgroundColor(Color.BLACK)
                webViewClient = WebViewClient()
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.mediaPlaybackRequiresUserGesture = true
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                tag = embedUrl
                loadUrl(embedUrl)
                webView = this
            }
        },
        update = { view ->
            if (view.tag != embedUrl) {
                view.tag = embedUrl
                view.loadUrl(embedUrl)
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .height(210.dp)
            .clip(RoundedCornerShape(OrangRadius.md))
            .border(1.dp, c.border, RoundedCornerShape(OrangRadius.md)),
    )
    DisposableEffect(webView) {
        onDispose {
            webView?.stopLoading()
            webView?.destroy()
        }
    }
}

@Composable
private fun LinkedImageEmbed(url: String) {
    val c = OrangTheme.colors
    val context = LocalContext.current
    val attachment = remember(url) { linkedAttachment(url, "image/${Uri.parse(url).path.orEmpty().substringAfterLast('.')}") }
    Spacer(Modifier.height(6.dp))
    AsyncImage(
        model = url,
        contentDescription = "Linked image",
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .fillMaxWidth()
            .height(240.dp)
            .clip(RoundedCornerShape(OrangRadius.md))
            .border(1.dp, c.border, RoundedCornerShape(OrangRadius.md))
            .clickable {
                context.startActivity(MediaPreviewActivity.intent(context, attachment))
            },
    )
}

private fun linkedAttachment(url: String, contentType: String): Attachment {
    val uri = Uri.parse(url)
    return Attachment(
        id = "link-${url.hashCode()}",
        url = url,
        filename = uri.lastPathSegment?.takeIf(String::isNotBlank) ?: "media",
        contentType = contentType,
        size = 0,
    )
}

@Composable
private fun PageEmbed(url: String, host: String, viewModel: LinkPreviewViewModel) {
    var preview by remember(url) { mutableStateOf<LinkPreviewData?>(null) }
    LaunchedEffect(url) {
        preview = viewModel.preview(url)
    }
    val c = OrangTheme.colors
    val uriHandler = LocalUriHandler.current
    Spacer(Modifier.height(6.dp))
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(OrangRadius.md))
            .border(1.dp, c.border, RoundedCornerShape(OrangRadius.md))
            .clickable { uriHandler.openUri(url) },
    ) {
        preview?.imageUrl?.let { imageUrl ->
            AsyncImage(
                model = imageUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().height(140.dp),
            )
        }
        Column(Modifier.padding(10.dp)) {
            Text(
                text = preview?.siteName ?: host.removePrefix("www."),
                color = c.inkMuted,
                fontSize = 11.sp,
                maxLines = 1,
            )
            Text(
                text = preview?.title ?: host.removePrefix("www."),
                color = c.primary,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            preview?.description?.let { description ->
                Text(
                    text = description,
                    color = c.inkSecondary,
                    fontSize = 12.sp,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
