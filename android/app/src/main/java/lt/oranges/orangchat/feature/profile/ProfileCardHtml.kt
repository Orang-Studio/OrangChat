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
import java.time.Instant
import java.util.Locale
import java.util.UUID

private val BADGE_INTERACTION_SCRIPT = """
(() => {
  const closeBadges = () => {
    document.querySelectorAll('.oc-pf-badge[aria-expanded="true"]').forEach((badge) => {
      badge.setAttribute('aria-expanded', 'false');
    });
    document.querySelectorAll('.oc-pf-badge-label').forEach((label) => {
      label.hidden = true;
    });
  };

  document.addEventListener('click', (event) => {
    const badge = event.target.closest('.oc-pf-badge');
    const wasOpen = badge?.getAttribute('aria-expanded') === 'true';
    closeBadges();
    if (!badge || wasOpen) return;

    const row = badge.closest('.oc-pf-badges');
    const label = row?.querySelector('.oc-pf-badge-label');
    if (!row || !label) return;

    label.textContent = badge.dataset.badgeLabel;
    label.hidden = false;
    const badgeBounds = badge.getBoundingClientRect();
    const rowBounds = row.getBoundingClientRect();
    const center = badgeBounds.left - rowBounds.left + badgeBounds.width / 2;
    const halfLabel = label.offsetWidth / 2;
    label.style.left = Math.max(halfLabel, Math.min(row.clientWidth - halfLabel, center)) + 'px';
    badge.setAttribute('aria-expanded', 'true');
  });

  document.addEventListener('keydown', (event) => {
    const badge = event.target.closest('.oc-pf-badge');
    if (badge && (event.key === 'Enter' || event.key === ' ')) {
      event.preventDefault();
      badge.click();
    } else if (event.key === 'Escape') {
      closeBadges();
    }
  });
})();

(() => {
  const el = document.querySelector('.oc-pf-activity-elapsed[data-started-at]');
  if (!el) return;
  const started = Date.parse(el.dataset.startedAt);
  if (Number.isNaN(started)) return;
  const pad = (n) => String(n).padStart(2, '0');
  const tick = () => {
    const total = Math.max(0, Math.floor((Date.now() - started) / 1000));
    const s = total % 60;
    const m = Math.floor(total / 60) % 60;
    const h = Math.floor(total / 3600);
    el.textContent = 'for ' + (h > 0 ? h + ':' + pad(m) + ':' + pad(s) : m + ':' + pad(s));
  };
  tick();
  setInterval(tick, 1000);
})();
"""

data class ProfileCardHtml(val html: String, val imageAllowlist: Set<String>)

fun buildProfileCardHtml(
    user: User,
    colors: OrangColors,
    aboutMeLabel: String,
    memberSinceLabel: String,
    presence: PresenceStatus? = null,
): ProfileCardHtml {
    val badgeScriptNonce = UUID.randomUUID().toString()
    val avatar = absoluteUrl(user.avatarUrl)
    val banner = absoluteUrl(user.bannerUrl)
    val accent = user.accentColor?.let { String.format(Locale.US, "#%06X", it and 0xFFFFFF) }
        ?: colors.surface4.css()
    val themeCss = sanitizeProfileCss(user.profileCss)
    val status = presence ?: user.status
    val displayName = user.displayName
    val username = user.username
    val devices = if (status == PresenceStatus.OFFLINE || user.devices.isEmpty()) "" else {
        val icons = user.devices.joinToString("") { device ->
            val (symbol, label) = when (device.name) {
                "MOBILE" -> "&#128241;" to "Mobile"
                "DESKTOP" -> "&#128421;" to "Desktop app"
                else -> "&#127760;" to "Browser"
            }
            """<span class="oc-pf-device" data-device="${device.name.lowercase()}" title="$label">$symbol</span>"""
        }
        """<span class="oc-pf-devices">$icons</span>"""
    }
    val activity = user.activities.firstOrNull { it.kind == "spotify" } ?: user.activities.firstOrNull()
    val activityArtwork = activity?.imageUrl?.takeIf { it.isNotBlank() }?.let(::absoluteUrl)
    val activityCard = activity?.let {
        val label = if (it.kind == "spotify") "LISTENING TO" else "NOW PLAYING"
        val icon = if (it.kind == "spotify") "&#127925;" else "&#127918;"
        val artwork = if (activityArtwork != null) {
            """<img class="oc-pf-activity-artwork" src="${activityArtwork.escapeAttr()}" alt="">"""
        } else {
            """<span class="oc-pf-activity-artwork oc-pf-activity-artwork-fallback">$icon</span>"""
        }
        val detail = it.details?.takeIf { text -> text.isNotBlank() }
            ?.let { text -> """<span class="oc-pf-activity-details">${text.escapeHtml()}</span>""" } ?: ""
        val elapsed = it.startedAt?.takeIf { start -> start.isNotBlank() }?.let { start ->
            """<span class="oc-pf-activity-elapsed" data-started-at="${start.escapeAttr()}">for ${formatElapsed(start, System.currentTimeMillis())}</span>"""
        } ?: ""
        """<div class="oc-pf-activity" data-kind="${it.kind.escapeAttr()}">$artwork<span class="oc-pf-activity-text oc-pf-activity-meta"><span class="oc-pf-activity-label">$label</span><span class="oc-pf-activity-name">${it.name.escapeHtml()}</span>$detail$elapsed</span></div>"""
    } ?: ""

    val bannerInner = banner?.let { """<img class="oc-pf-banner-img" src="${it.escapeAttr()}" alt="">""" } ?: ""
    val avatarInner = if (avatar != null) {
        """<img class="oc-pf-avatar-img" src="${avatar.escapeAttr()}" alt="">"""
    } else {
        """<span class="oc-pf-avatar-fallback">${displayName.take(1).uppercase().escapeHtml()}</span>"""
    }
    val statusBadge = """<span class="oc-pf-status" data-status="${status.name.lowercase()}" title="${statusHtmlLabel(status)}">${statusIconSvg(status, statusDotColor(status, colors))}</span>"""
    val pronouns = user.pronouns?.takeIf { it.isNotBlank() }
        ?.let { """<span class="oc-pf-pronouns">${it.escapeHtml()}</span>""" } ?: ""
    val resolvedBadges = Badge.resolve(user.badges)
    val badgeUrls = resolvedBadges.map { "$BACKEND_ORIGIN/badges/${it.slug}.svg" }
    val badges = resolvedBadges.takeIf { it.isNotEmpty() }?.let { list ->
        val imgs = list.joinToString("") { badge ->
            val src = "$BACKEND_ORIGIN/badges/${badge.slug}.svg"
            val alt = "${badge.label}: ${badge.description}".escapeAttr()
            """<img class="oc-pf-badge oc-pf-badge-${badge.slug.replace('_', '-')}" data-badge="${badge.slug.escapeAttr()}" data-badge-label="${badge.label.escapeAttr()}" src="${src.escapeAttr()}" alt="$alt" title="$alt" role="button" tabindex="0" aria-expanded="false">"""
        }
        """<div class="oc-pf-badges">$imgs<span class="oc-pf-badge-label" role="tooltip" hidden></span></div>"""
    } ?: ""
    val bio = user.bio?.takeIf { it.isNotBlank() }?.let {
        """<div class="oc-pf-bio oc-pf-section"><h3 class="oc-pf-heading">${aboutMeLabel.escapeHtml()}</h3><p class="oc-pf-bio-text">${it.escapeHtml()}</p></div>"""
    } ?: ""
    val member = user.createdAt.takeIf { it.isNotBlank() }?.let {
        """<div class="oc-pf-member oc-pf-section"><h3 class="oc-pf-heading">${memberSinceLabel.escapeHtml()}</h3><p class="oc-pf-member-text">${formatFullTime(it).escapeHtml()}</p></div>"""
    } ?: ""

    val imageAllowlist = setOfNotNull(avatar, banner, activityArtwork) + badgeUrls
    val imgSrc = (listOf(BACKEND_ORIGIN) + imageAllowlist.mapNotNull(::originOf))
        .distinct()
        .joinToString(" ")

    val html = """
<!DOCTYPE html>
<html>
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta http-equiv="Content-Security-Policy" content="default-src 'none'; img-src $imgSrc data:; style-src 'unsafe-inline'; script-src 'nonce-$badgeScriptNonce'; form-action 'none'; base-uri 'none'">
<style>${baseCss(colors, accent, status)}</style>
<style>$themeCss</style>
</head>
<body>
<div class="oc-profile-card" data-status="${(status ?: PresenceStatus.OFFLINE).name.lowercase()}" data-has-banner="${banner != null}" data-has-avatar="${avatar != null}">
  <div class="oc-pf-banner">$bannerInner</div>
  <div class="oc-pf-inner">
    <div class="oc-pf-avatar"><span class="oc-pf-avatar-frame">$avatarInner$statusBadge</span></div>
    <div class="oc-pf-body">
      <div class="oc-pf-head"><h2 class="oc-pf-name">${displayName.ifBlank { "-" }.escapeHtml()}</h2>$pronouns</div>
      <div class="oc-pf-identity"><p class="oc-pf-username">@${username.ifBlank { "username" }.escapeHtml()}</p>$devices</div>
      $activityCard
      $badges
      $bio
      $member
    </div>
  </div>
</div>
<script nonce="$badgeScriptNonce">$BADGE_INTERACTION_SCRIPT</script>
</body>
</html>
""".trimIndent()

    return ProfileCardHtml(html, imageAllowlist)
}

internal fun originOf(url: String): String? = try {
    val uri = java.net.URI(url)
    val scheme = uri.scheme ?: return null
    val host = uri.host ?: return null
    if (uri.port > 0) "$scheme://$host:${uri.port}" else "$scheme://$host"
} catch (_: java.net.URISyntaxException) {
    null
}

private fun baseCss(c: OrangColors, accent: String, status: PresenceStatus?): String = """
* { margin: 0; padding: 0; box-sizing: border-box; }
html, body { background: transparent; }
body {
  font-family: -apple-system, Roboto, "Helvetica Neue", sans-serif;
  color: ${c.ink.css()};
  -webkit-text-size-adjust: 100%;
}
.oc-profile-card {
  --oc-pf-accent: $accent;
  position: relative;
  isolation: isolate;
  overflow: hidden;
  contain: layout paint style;
  border: 1px solid ${c.border.css()};
  border-radius: 7px;
  background: ${c.surface2.css()};
}
.oc-pf-banner { height: 80px; width: 100%; background: var(--oc-pf-accent); }
.oc-pf-banner-img { height: 100%; width: 100%; object-fit: cover; display: block; }
.oc-pf-inner { padding: 0 16px 16px; }
.oc-pf-avatar { margin-top: -36px; margin-bottom: 8px; }
.oc-pf-avatar-frame {
  position: relative;
  display: inline-block;
  padding: 6px;
  border-radius: 50%;
  background: ${c.surface2.css()};
  line-height: 0;
}
.oc-pf-avatar-img { width: 56px; height: 56px; border-radius: 50%; object-fit: cover; display: block; }
.oc-pf-avatar-fallback {
  width: 56px; height: 56px; border-radius: 50%;
  display: flex; align-items: center; justify-content: center;
  background: ${c.primarySoft.css()};
  color: ${c.primary.css()};
  font-size: 24px; font-weight: 600; line-height: 1;
}
.oc-pf-status {
  position: absolute; right: 4px; bottom: 4px;
  width: 20px; height: 20px; border-radius: 50%;
  background: ${c.surface2.css()};
  display: flex; align-items: center; justify-content: center;
  line-height: 0;
}
.oc-pf-status-icon { width: 15px; height: 15px; display: block; }
.oc-pf-identity { display: flex; align-items: center; gap: 5px; min-width: 0; }
.oc-pf-devices { display: inline-flex; align-items: center; gap: 4px; flex-shrink: 0; }
.oc-pf-device { color: ${statusDotColor(status ?: PresenceStatus.OFFLINE, c).css()}; font-size: 13px; line-height: 1; }
.oc-pf-activity {
  margin-top: 8px;
  display: flex; align-items: center; gap: 10px;
  padding: 12px;
  border: 1px solid ${c.border.css()};
  border-radius: 7px;
  background: ${c.surface3.css()};
}
.oc-pf-activity-artwork {
  width: 48px; height: 48px; flex-shrink: 0;
  border-radius: 4px; object-fit: cover; display: block;
}
.oc-pf-activity-artwork-fallback {
  display: flex; align-items: center; justify-content: center;
  background: ${c.surface2.css()};
  font-size: 24px; line-height: 1;
}
.oc-pf-activity-text { display: flex; flex-direction: column; gap: 1px; min-width: 0; }
.oc-pf-activity-label {
  font-size: 11px; font-weight: 500;
  text-transform: uppercase; letter-spacing: 0.4px;
  color: ${c.inkMuted.css()};
}
.oc-pf-activity-name {
  font-size: 14px; font-weight: 600; color: ${c.inkSecondary.css()};
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.oc-pf-activity-details {
  font-size: 12px; color: ${c.inkMuted.css()};
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.oc-pf-activity-elapsed { font-size: 12px; color: ${c.inkMuted.css()}; white-space: nowrap; }
.oc-pf-badges { position: relative; display: flex; flex-wrap: wrap; align-items: center; gap: 4px; margin-top: 8px; }
.oc-pf-badge { width: 20px; height: 20px; display: block; object-fit: contain; cursor: pointer; }
.oc-pf-badge:focus-visible { outline: 2px solid ${c.primary.css()}; outline-offset: 2px; border-radius: 4px; }
.oc-pf-badge-label {
  position: absolute; bottom: calc(100% + 6px); transform: translateX(-50%); z-index: 2;
  padding: 6px 10px; border: 1px solid ${c.border.css()}; border-radius: 8px;
  background: ${c.surface4.css()}; color: ${c.ink.css()};
  font-size: 12px; font-weight: 500; line-height: 1; white-space: nowrap;
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.3); pointer-events: none;
}
.oc-pf-badge-label[hidden] { display: none; }
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

private fun statusHtmlLabel(status: PresenceStatus): String = when (status) {
    PresenceStatus.ONLINE -> "Online"
    PresenceStatus.IDLE -> "Idle"
    PresenceStatus.DND -> "Do not disturb"
    PresenceStatus.OFFLINE -> "Offline"
}

private fun statusIconSvg(status: PresenceStatus, color: Color): String {
    val cut = when (status) {
        PresenceStatus.ONLINE -> ""
        PresenceStatus.IDLE -> """<circle cx="4.6" cy="4.6" r="4.4" fill="black"/>"""
        PresenceStatus.DND -> """<rect x="1.4" y="4.9" width="9.2" height="2.2" rx="1.1" fill="black"/>"""
        PresenceStatus.OFFLINE -> """<circle cx="6" cy="6" r="2.8" fill="black"/>"""
    }
    return """<svg class="oc-pf-status-icon" viewBox="0 0 12 12" aria-hidden="true"><mask id="ocStatusMask"><circle cx="6" cy="6" r="6" fill="white"/>$cut</mask><rect width="12" height="12" fill="${color.css()}" mask="url(#ocStatusMask)"/></svg>"""
}

private fun formatElapsed(startedAt: String, now: Long): String = runCatching {
    val total = ((now - Instant.parse(startedAt).toEpochMilli()) / 1_000).coerceAtLeast(0)
    val seconds = total % 60
    val minutes = (total / 60) % 60
    val hours = total / 3_600
    if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds)
    else "%d:%02d".format(minutes, seconds)
}.getOrDefault("0:00")

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
