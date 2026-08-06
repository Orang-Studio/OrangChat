use std::net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr};
use std::time::Duration;

use axum::extract::{Query, State};
use axum::http::header::{ACCEPT, CONTENT_TYPE, LOCATION, RANGE};
use axum::routing::get;
use axum::{Json, Router};
use reqwest::{redirect::Policy, Url};
use scraper::{Html, Selector};
use serde::{Deserialize, Serialize};

use crate::error::{AppError, AppResult};
use crate::http::AuthUser;
use crate::services::{instagram, rate_limit};
use crate::state::AppState;

const MAX_HTML_BYTES: usize = 512 * 1024;
const MAX_REDIRECTS: usize = 3;

pub fn routes() -> Router<AppState> {
    Router::new().route("/link-preview", get(preview))
}

#[derive(Deserialize)]
struct PreviewQuery {
    url: String,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct LinkPreview {
    url: String,
    site_name: String,
    title: Option<String>,
    description: Option<String>,
    image_url: Option<String>,
    /// Set when the link is a video we managed to resolve to a playable file -
    /// an Instagram post, today. Already a signed same-origin proxy url, like
    /// `image_url`, so a client can hand it straight to a video element; when
    /// it is set, `image_url` is that video's poster.
    video_url: Option<String>,
}

async fn preview(
    State(state): State<AppState>,
    auth: AuthUser,
    Query(query): Query<PreviewQuery>,
) -> AppResult<Json<LinkPreview>> {
    rate_limit::check(
        &state,
        "link-preview",
        &auth.user_id,
        rate_limit::LINK_PREVIEW_PER_USER,
    )
    .await?;

    let requested =
        Url::parse(&query.url).map_err(|_| AppError::BadRequest("Invalid preview URL".into()))?;

    // Instagram answers a logged-out scrape with a login wall, so a reel would
    // otherwise preview as a card for the word "Instagram". Resolving the post
    // gives the client the video itself; a post that will not resolve (private,
    // deleted, age-gated) falls through to that same card, which is what it
    // would have shown anyway.
    if let Some(shortcode) = instagram::shortcode(&requested) {
        if let Some(post) = instagram::resolve(&state, &shortcode).await {
            return Ok(Json(instagram_preview(&state, &requested, post).await?));
        }
    }

    let (final_url, html) = fetch_html(requested).await?;
    let mut result = parse_preview(&final_url, &html);
    result.image_url = proxied(&state, result.image_url.as_deref()).await?;
    Ok(Json(result))
}

/// An Instagram post as a preview: its caption as the text, its own frame as
/// the image, and - the point of the exercise - a video url the client can play
/// inline instead of a card linking back to a login wall.
async fn instagram_preview(
    state: &AppState,
    url: &Url,
    post: instagram::Post,
) -> AppResult<LinkPreview> {
    Ok(LinkPreview {
        url: url.to_string(),
        site_name: "Instagram".into(),
        title: post.username.map(|name| format!("@{name}")),
        description: post.caption,
        image_url: proxied(state, post.image_url.as_deref()).await?,
        video_url: proxied(state, post.video_url.as_deref()).await?,
    })
}

/// A third-party media url in the only form a client should be handed one: a
/// signed same-origin proxy url.
///
/// These are loaded by the *viewer's* browser, so returning the raw url would
/// hand whoever set it a zero-click IP log of everyone who scrolls past. Minted
/// only for a host that resolves public - the proxy re-checks before any bytes
/// move, but there is no point issuing a token it will refuse.
async fn proxied(state: &AppState, url: Option<&str>) -> AppResult<Option<String>> {
    let Some(raw) = url else {
        return Ok(None);
    };
    match Url::parse(raw) {
        Ok(parsed) if resolve_public_destination(&parsed).await.is_ok() => Some(
            super::media_proxy::sign_media_url(&state.config.jwt_access_secret, raw),
        )
        .transpose(),
        _ => Ok(None),
    }
}

pub(crate) async fn fetch_html(mut url: Url) -> AppResult<(Url, String)> {
    for redirect_count in 0..=MAX_REDIRECTS {
        let (host, address) = resolve_public_destination(&url).await?;
        let client = reqwest::Client::builder()
            .redirect(Policy::none())
            .connect_timeout(Duration::from_secs(4))
            .timeout(Duration::from_secs(8))
            .user_agent("OrangChat-LinkPreview/1.0")
            // Pin this request to the public address we validated. This avoids
            // resolving the hostname a second time after the SSRF check.
            .resolve(&host, address)
            .build()
            .map_err(|e| AppError::Internal(format!("preview client error: {e}")))?;

        let mut response = client
            .get(url.clone())
            .header(ACCEPT, "text/html,application/xhtml+xml;q=0.9")
            .header(RANGE, format!("bytes=0-{}", MAX_HTML_BYTES - 1))
            .send()
            .await
            .map_err(|_| AppError::BadRequest("Unable to fetch link preview".into()))?;

        if response.status().is_redirection() {
            if redirect_count == MAX_REDIRECTS {
                return Err(AppError::BadRequest("Too many preview redirects".into()));
            }
            let location = response
                .headers()
                .get(LOCATION)
                .and_then(|value| value.to_str().ok())
                .ok_or_else(|| AppError::BadRequest("Invalid preview redirect".into()))?;
            url = url
                .join(location)
                .map_err(|_| AppError::BadRequest("Invalid preview redirect".into()))?;
            continue;
        }

        if !response.status().is_success() {
            return Err(AppError::BadRequest("Unable to fetch link preview".into()));
        }
        let is_html = response
            .headers()
            .get(CONTENT_TYPE)
            .and_then(|value| value.to_str().ok())
            .is_some_and(|value| {
                let mime = value.to_ascii_lowercase();
                mime.starts_with("text/html") || mime.starts_with("application/xhtml+xml")
            });
        if !is_html {
            return Err(AppError::BadRequest(
                "Link does not contain an HTML page".into(),
            ));
        }
        if response
            .content_length()
            .is_some_and(|length| length > MAX_HTML_BYTES as u64)
        {
            return Err(AppError::BadRequest("Link preview is too large".into()));
        }

        let mut body = Vec::new();
        while let Some(chunk) = response
            .chunk()
            .await
            .map_err(|_| AppError::BadRequest("Unable to read link preview".into()))?
        {
            let remaining = MAX_HTML_BYTES.saturating_sub(body.len());
            if remaining == 0 {
                break;
            }
            body.extend_from_slice(&chunk[..chunk.len().min(remaining)]);
        }
        return Ok((url, String::from_utf8_lossy(&body).into_owned()));
    }

    Err(AppError::BadRequest("Unable to fetch link preview".into()))
}

pub(crate) async fn resolve_public_destination(url: &Url) -> AppResult<(String, SocketAddr)> {
    if !matches!(url.scheme(), "http" | "https")
        || !url.username().is_empty()
        || url.password().is_some()
    {
        return Err(AppError::BadRequest(
            "Only public HTTP(S) links can be previewed".into(),
        ));
    }

    let host = url
        .host_str()
        .ok_or_else(|| AppError::BadRequest("Preview URL has no host".into()))?
        .trim_end_matches('.')
        .to_ascii_lowercase();
    if host == "localhost" || host.ends_with(".localhost") || host.ends_with(".local") {
        return Err(AppError::BadRequest(
            "Private links cannot be previewed".into(),
        ));
    }
    let port = url
        .port_or_known_default()
        .ok_or_else(|| AppError::BadRequest("Preview URL has no valid port".into()))?;
    if !matches!((url.scheme(), port), ("http", 80) | ("https", 443)) {
        return Err(AppError::BadRequest(
            "Only standard web ports can be previewed".into(),
        ));
    }

    let addresses: Vec<SocketAddr> = tokio::net::lookup_host((host.as_str(), port))
        .await
        .map_err(|_| AppError::BadRequest("Unable to resolve preview host".into()))?
        .collect();
    if addresses.is_empty() || addresses.iter().any(|addr| !is_public_ip(addr.ip())) {
        return Err(AppError::BadRequest(
            "Private links cannot be previewed".into(),
        ));
    }

    Ok((host, addresses[0]))
}

pub(crate) fn is_public_ip(ip: IpAddr) -> bool {
    match ip {
        IpAddr::V4(ip) => is_public_ipv4(ip),
        IpAddr::V6(ip) => is_public_ipv6(ip),
    }
}

fn is_public_ipv4(ip: Ipv4Addr) -> bool {
    let [a, b, c, _] = ip.octets();
    !(a == 0
        || a == 10
        || a == 127
        || (a == 100 && (64..=127).contains(&b))
        || (a == 169 && b == 254)
        || (a == 172 && (16..=31).contains(&b))
        || (a == 192 && b == 0 && c == 0)
        || (a == 192 && b == 0 && c == 2)
        || (a == 192 && b == 88 && c == 99)
        || (a == 192 && b == 168)
        || (a == 198 && (b == 18 || b == 19))
        || (a == 198 && b == 51 && c == 100)
        || (a == 203 && b == 0 && c == 113)
        || a >= 224)
}

fn is_public_ipv6(ip: Ipv6Addr) -> bool {
    if let Some(mapped) = ip.to_ipv4_mapped() {
        return is_public_ipv4(mapped);
    }
    let segments = ip.segments();
    !(ip.is_unspecified()
        || ip.is_loopback()
        || ip.is_multicast()
        || (segments[0] & 0xfe00) == 0xfc00 // unique-local
        || (segments[0] & 0xffc0) == 0xfe80 // link-local
        || (segments[0] & 0xffc0) == 0xfec0 // deprecated site-local
        || (segments[0] == 0x2001 && segments[1] == 0x0db8)) // documentation
}

fn parse_preview(page_url: &Url, html: &str) -> LinkPreview {
    let document = Html::parse_document(html);
    let title = meta_content(&document, "meta[property='og:title']")
        .or_else(|| meta_content(&document, "meta[name='twitter:title']"))
        .or_else(|| element_text(&document, "title"))
        .and_then(|value| clean_text(&value, 200));
    let description = meta_content(&document, "meta[property='og:description']")
        .or_else(|| meta_content(&document, "meta[name='twitter:description']"))
        .or_else(|| meta_content(&document, "meta[name='description']"))
        .and_then(|value| clean_text(&value, 400));
    let site_name = meta_content(&document, "meta[property='og:site_name']")
        .and_then(|value| clean_text(&value, 80))
        .or_else(|| page_url.host_str().map(str::to_owned))
        .unwrap_or_else(|| "Link".into());
    let image_url = meta_content(&document, "meta[property='og:image']")
        .or_else(|| meta_content(&document, "meta[name='twitter:image']"))
        .or_else(|| meta_content(&document, "meta[name='twitter:image:src']"))
        .and_then(|value| page_url.join(value.trim()).ok())
        .filter(|image| matches!(image.scheme(), "http" | "https"))
        .and_then(|image| {
            let host = image.host_str()?.to_ascii_lowercase();
            if host == "localhost"
                || host.ends_with(".localhost")
                || host.ends_with(".local")
                || image.host().is_some_and(|host| match host {
                    url::Host::Ipv4(ip) => !is_public_ipv4(ip),
                    url::Host::Ipv6(ip) => !is_public_ipv6(ip),
                    url::Host::Domain(_) => false,
                })
            {
                None
            } else {
                Some(image.to_string())
            }
        });
    LinkPreview {
        url: page_url.to_string(),
        site_name,
        title,
        description,
        image_url,
        // A scraped page is a page; only a resolver (see `instagram_preview`)
        // knows a link is really a video.
        video_url: None,
    }
}

fn meta_content(document: &Html, selector: &str) -> Option<String> {
    let selector = Selector::parse(selector).ok()?;
    document
        .select(&selector)
        .find_map(|element| element.value().attr("content").map(str::to_owned))
}

fn element_text(document: &Html, selector: &str) -> Option<String> {
    let selector = Selector::parse(selector).ok()?;
    document
        .select(&selector)
        .next()
        .map(|element| element.text().collect::<Vec<_>>().join(" "))
}

fn clean_text(value: &str, max_chars: usize) -> Option<String> {
    let clean = value.split_whitespace().collect::<Vec<_>>().join(" ");
    if clean.is_empty() {
        return None;
    }
    Some(clean.chars().take(max_chars).collect())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn rejects_non_public_addresses() {
        for address in [
            "127.0.0.1",
            "10.2.3.4",
            "169.254.169.254",
            "172.20.0.1",
            "192.168.1.1",
            "::1",
            "fc00::1",
            "fe80::1",
        ] {
            assert!(!is_public_ip(address.parse().unwrap()), "{address}");
        }
        assert!(is_public_ip("8.8.8.8".parse().unwrap()));
        assert!(is_public_ip("2606:4700:4700::1111".parse().unwrap()));
    }

    #[test]
    fn extracts_open_graph_metadata() {
        let html = r#"
          <html><head>
            <meta property="og:site_name" content="Example Site">
            <meta property="og:title" content=" A useful page ">
            <meta property="og:description" content="Some   details here">
            <meta property="og:image" content="/preview.png">
          </head></html>
        "#;
        let preview = parse_preview(&Url::parse("https://example.com/posts/1").unwrap(), html);
        assert_eq!(preview.site_name, "Example Site");
        assert_eq!(preview.title.as_deref(), Some("A useful page"));
        assert_eq!(preview.description.as_deref(), Some("Some details here"));
        assert_eq!(
            preview.image_url.as_deref(),
            Some("https://example.com/preview.png")
        );
    }
}
