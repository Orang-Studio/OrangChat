package lt.oranges.orangchat.feature.profile

import android.annotation.SuppressLint
import android.graphics.Color as AndroidColor
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import lt.oranges.orangchat.data.model.PresenceStatus
import lt.oranges.orangchat.data.model.User
import lt.oranges.orangchat.ui.theme.OrangTheme
import java.io.ByteArrayInputStream

private const val ESTIMATED_CARD_HEIGHT_DP = 240
private const val HEIGHT_POLL_ATTEMPTS = 12
private const val HEIGHT_POLL_INTERVAL_MS = 40L

/**
 * Renders a themed profile card through a WebView so that user profile CSS
 * behaves exactly as it does on the web client.
 *
 * The WebView is the sandbox: it contains only this one card, so user CSS has
 * no surrounding app to escape into and no sibling card to leak onto — the two
 * things the web sanitizer's scoping exists to prevent. What is left is network
 * egress (an external url() would leak the viewer's IP to the profile owner),
 * blocked here three ways: sanitizeProfileCss strips external url()/@import, the
 * CSP allows no load except images, and shouldInterceptRequest hard-blocks every
 * request that is not one of the avatar/banner URLs we put in the document.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ProfileCardWebView(
    user: User,
    modifier: Modifier = Modifier,
    presence: PresenceStatus? = null,
) {
    val colors = OrangTheme.colors
    val card = remember(user, presence, colors) {
        buildProfileCardHtml(user, colors, presence)
    }
    var heightDp by remember(user.id) { mutableIntStateOf(0) }

    AndroidView(
        modifier = modifier
            .fillMaxWidth()
            .height(if (heightDp > 0) heightDp.dp else ESTIMATED_CARD_HEIGHT_DP.dp),
        factory = { context ->
            WebView(context).apply {
                setBackgroundColor(AndroidColor.TRANSPARENT)
                isVerticalScrollBarEnabled = false
                isHorizontalScrollBarEnabled = false
                isFocusable = false
                overScrollMode = WebView.OVER_SCROLL_NEVER
                settings.apply {
                    javaScriptEnabled = false
                    domStorageEnabled = false
                    allowFileAccess = false
                    allowContentAccess = false
                    cacheMode = WebSettings.LOAD_DEFAULT
                    mediaPlaybackRequiresUserGesture = true
                    mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                }
            }
        },
        update = { view ->
            val client = ProfileCardWebViewClient(
                allowlist = card.imageAllowlist,
                onHeight = { measured -> if (measured > 0 && measured != heightDp) heightDp = measured },
            )
            view.webViewClient = client
            if (view.tag != card.html) {
                view.tag = card.html
                view.loadDataWithBaseURL(null, card.html, "text/html", "utf-8", null)
            }
        },
        onRelease = { it.destroy() },
    )
}

private class ProfileCardWebViewClient(
    private val allowlist: Set<String>,
    private val onHeight: (Int) -> Unit,
) : WebViewClient() {

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        val url = request.url.toString()
        if (url in allowlist) return null
        return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
    }

    /**
     * contentHeight is only meaningful once layout has settled, and there is no
     * JS available to report it back, so poll it briefly. initial-scale=1 makes
     * the CSS pixels it reports equal to dp.
     */
    override fun onPageFinished(view: WebView, url: String?) {
        fun poll(attempt: Int) {
            val measured = view.contentHeight
            if (measured > 0) onHeight(measured)
            else if (attempt < HEIGHT_POLL_ATTEMPTS) {
                view.postDelayed({ poll(attempt + 1) }, HEIGHT_POLL_INTERVAL_MS)
            }
        }
        poll(0)
    }
}
