package lt.oranges.orangchat.feature.profile

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import lt.oranges.orangchat.data.model.PresenceStatus
import lt.oranges.orangchat.data.model.User
import lt.oranges.orangchat.ui.components.Badge
import lt.oranges.orangchat.ui.theme.OrangColors
import lt.oranges.orangchat.util.BACKEND_ORIGIN
import lt.oranges.orangchat.util.absoluteUrl
import lt.oranges.orangchat.util.formatFullTime
import lt.oranges.orangchat.util.sanitizeProfileCss
import java.util.Locale

/**
 * The DOM the card WebView renders. Element structure and the oc-pf-* hook
 * classes are kept identical to packages/client/src/features/profile/ProfileCard.tsx,
 * because those classes are the contract users write their profile CSS against.
 */
data class ProfileCardHtml(val html: String, val imageAllowlist: Set<String>)

fun buildProfileCardHtml(
    user: User,
    colors: OrangColors,
    presence: PresenceStatus? = null,
): ProfileCardHtml {
    val avatar = absoluteUrl(user.avatarUrl)
    val banner = absoluteUrl(user.bannerUrl)
    val accent = user.accentColor?.let { String.format(Locale.US, "#%06X", it and 0xFFFFFF) }
        ?: colors.surface4.css()
    val themeCss = sanitizeProfileCss(user.profileCss)
    val status = presence ?: user.status
    val displayName = user.displayName
    val username = user.username
    val deviceStatus = if (status == PresenceStatus.OFFLINE) "" else user.devices.joinToString("") { device ->
        val (symbol, label) = when (device.name) {
            "MOBILE" -> "&#128241;" to "Mobile"
            "DESKTOP" -> "&#128421;" to "Desktop app"
            else -> "&#127760;" to "Browser"
        }
        """<span class="oc-pf-device" title="$label">$symbol</span>"""
    }
    val activity = (user.activities.firstOrNull { it.kind == "spotify" } ?: user.activities.firstOrNull())?.let {
        val prefix = if (it.kind == "spotify") "Listening to" else "Playing"
        val detail = it.details?.let { text -> " — ${text.escapeHtml()}" } ?: ""
        """<p class="oc-pf-activity">$prefix <strong>${it.name.escapeHtml()}</strong>$detail</p>"""
    } ?: ""

    val bannerInner = banner?.let { """<img class="oc-pf-banner-img" src="${it.escapeAttr()}" alt="">""" } ?: ""
    val avatarInner = if (avatar != null) {
        """<img class="oc-pf-avatar-img" src="${avatar.escapeAttr()}" alt="">"""
    } else {
        """<span class="oc-pf-avatar-fallback">${displayName.take(1).uppercase().escapeHtml()}</span>"""
    }
    val pronouns = user.pronouns?.takeIf { it.isNotBlank() }
        ?.let { """<span class="oc-pf-pronouns">${it.escapeHtml()}</span>""" } ?: ""
    // Same oc-pf-badge* hook classes as the web card, so one bit of profile CSS
    // styles both. Colours are inline because the catalog owns them, not the theme.
    val badges = Badge.resolve(user.badges).takeIf { it.isNotEmpty() }?.let { list ->
        val pills = list.joinToString("") { badge ->
            val hex = badge.color.css()
            """<span class="oc-pf-badge" style="color:$hex;border-color:$hex;" title="${badge.description.escapeAttr()}">${badge.label.escapeHtml()}</span>"""
        }
        """<div class="oc-pf-badges">$pills</div>"""
    } ?: ""
    val bio = user.bio?.takeIf { it.isNotBlank() }?.let {
        """<div class="oc-pf-bio"><h3 class="oc-pf-heading">About me</h3><p class="oc-pf-bio-text">${it.escapeHtml()}</p></div>"""
    } ?: ""
    val member = user.createdAt.takeIf { it.isNotBlank() }?.let {
        """<div class="oc-pf-member"><h3 class="oc-pf-heading">Member since</h3><p class="oc-pf-member-text">${formatFullTime(it).escapeHtml()}</p></div>"""
    } ?: ""

    // Avatars and banners are not all on the backend — Cloudinary serves its own
    // origin — so the policy has to name wherever this card's images actually
    // came from. Only those two URLs are ever put in the document, and
    // shouldInterceptRequest still hard-blocks anything else.
    val imageAllowlist = setOfNotNull(avatar, banner)
    val imgSrc = (listOf(BACKEND_ORIGIN) + imageAllowlist.mapNotNull(::originOf))
        .distinct()
        .joinToString(" ")

    val html = """
<!DOCTYPE html>
<html>
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta http-equiv="Content-Security-Policy" content="default-src 'none'; img-src $imgSrc data:; style-src 'unsafe-inline'; form-action 'none'; base-uri 'none'">
<style>${baseCss(colors, accent, status)}</style>
<style>$themeCss</style>
</head>
<body>
<div class="oc-profile-card">
  <div class="oc-pf-banner">$bannerInner</div>
  <div class="oc-pf-inner">
    <div class="oc-pf-avatar"><span class="oc-pf-avatar-frame">$avatarInner</span></div>
    <div class="oc-pf-body">
      <div class="oc-pf-head"><h2 class="oc-pf-name">${displayName.ifBlank { "—" }.escapeHtml()}</h2>$pronouns</div>
      <div class="oc-pf-identity"><p class="oc-pf-username">@${username.ifBlank { "username" }.escapeHtml()}</p>$deviceStatus</div>
      $activity
      $badges
      $bio
      $member
    </div>
  </div>
</div>
</body>
</html>
""".trimIndent()

    return ProfileCardHtml(html, imageAllowlist)
}

/**
 * The `scheme://host[:port]` of [url], which is the granularity CSP source
 * expressions work at. Null if it will not parse — such a URL is not going to
 * load either, so naming it in the policy would achieve nothing.
 */
internal fun originOf(url: String): String? = try {
    val uri = java.net.URI(url)
    val scheme = uri.scheme ?: return null
    val host = uri.host ?: return null
    if (uri.port > 0) "$scheme://$host:${uri.port}" else "$scheme://$host"
} catch (_: java.net.URISyntaxException) {
    null
}

/**
 * Ported from the Tailwind classes on the web card so an unstyled profile looks
 * the same in both clients. `initial-scale=1` makes 1 CSS px == 1 dp, so these
 * numbers match the native card's dp values.
 */
private fun baseCss(c: OrangColors, accent: String, status: PresenceStatus?): String = """
* { margin: 0; padding: 0; box-sizing: border-box; }
html, body { background: transparent; }
body {
  font-family: -apple-system, Roboto, "Helvetica Neue", sans-serif;
  color: ${c.ink.css()};
  -webkit-text-size-adjust: 100%;
}
.oc-profile-card {
  position: relative;
  isolation: isolate;
  overflow: hidden;
  contain: layout paint style;
  border: 1px solid ${c.border.css()};
  border-radius: 7px;
  background: ${c.surface2.css()};
}
.oc-pf-banner { height: 80px; width: 100%; background: $accent; }
.oc-pf-banner-img { height: 100%; width: 100%; object-fit: cover; display: block; }
.oc-pf-inner { padding: 0 16px 16px; }
.oc-pf-avatar { margin-top: -36px; margin-bottom: 8px; }
.oc-pf-avatar-frame {
  position: relative;
  display: inline-block;
  padding: 6px;
  border-radius: 4px;
  background: ${c.surface2.css()};
  line-height: 0;
}
.oc-pf-avatar-img { width: 56px; height: 56px; border-radius: 4px; object-fit: cover; display: block; }
.oc-pf-avatar-fallback {
  width: 56px; height: 56px; border-radius: 4px;
  display: flex; align-items: center; justify-content: center;
  background: ${c.primarySoft.css()};
  color: ${c.primary.css()};
  font-size: 24px; font-weight: 600; line-height: 1;
}
.oc-pf-identity { display: flex; align-items: center; gap: 5px; min-width: 0; }
.oc-pf-device { color: ${statusDotColor(status ?: PresenceStatus.OFFLINE, c).css()}; font-size: 13px; line-height: 1; }
.oc-pf-activity { margin-top: 4px; color: ${c.inkMuted.css()}; font-size: 11px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.oc-pf-badges { display: flex; flex-wrap: wrap; gap: 6px; margin-top: 8px; }
.oc-pf-badge {
  display: inline-flex; align-items: center; gap: 4px;
  border: 1px solid; border-radius: 6px; padding: 2px 6px;
  font-size: 11px; font-weight: 500;
}
.oc-pf-body { border-radius: 7px; background: ${c.surface1.css()}; padding: 12px; }
.oc-pf-head { display: flex; align-items: baseline; gap: 8px; }
.oc-pf-name {
  font-size: 16px; font-weight: 700; line-height: 1.4;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.oc-pf-pronouns { font-size: 12px; color: ${c.inkMuted.css()}; white-space: nowrap; }
.oc-pf-username {
  font-size: 14px; color: ${c.inkSecondary.css()};
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.oc-pf-bio, .oc-pf-member {
  margin-top: 10px; padding-top: 10px;
  border-top: 1px solid ${c.border.css()};
}
.oc-pf-heading {
  margin-bottom: 4px;
  font-size: 11px; font-weight: 600;
  text-transform: uppercase; letter-spacing: 0.4px;
  color: ${c.inkMuted.css()};
}
.oc-pf-bio-text { font-size: 14px; white-space: pre-wrap; overflow-wrap: break-word; }
.oc-pf-member-text { font-size: 14px; }
""".trimIndent()

private fun statusDotColor(status: PresenceStatus, c: OrangColors): Color = when (status) {
    PresenceStatus.ONLINE -> c.success
    PresenceStatus.IDLE -> c.warning
    PresenceStatus.DND -> c.danger
    PresenceStatus.OFFLINE -> c.inkMuted
}

private fun Color.css(): String {
    val argb = toArgb()
    val r = (argb shr 16) and 0xFF
    val g = (argb shr 8) and 0xFF
    val b = argb and 0xFF
    return if (alpha >= 1f) String.format(Locale.US, "#%02X%02X%02X", r, g, b)
    else "rgba($r, $g, $b, ${String.format(Locale.US, "%.3f", alpha)})"
}

private fun String.escapeHtml(): String = buildString(length) {
    for (ch in this@escapeHtml) when (ch) {
        '&' -> append("&amp;")
        '<' -> append("&lt;")
        '>' -> append("&gt;")
        '"' -> append("&quot;")
        '\'' -> append("&#39;")
        else -> append(ch)
    }
}

private fun String.escapeAttr(): String = escapeHtml()
