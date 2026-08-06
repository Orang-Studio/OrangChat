//! Instagram links, resolved to the media they actually point at.
//!
//! Instagram shows a logged-out visitor a login wall, so scraping a reel for
//! `og:` tags the way every other link is scraped comes back as a card titled
//! "Instagram" with a thumbnail and nothing to play - the least useful thing a
//! link to a video can turn into. Its *embed* page, the one behind the
//! `blockquote` snippet any site is invited to paste, still serves the post's
//! own media urls to anonymous clients, and that is what this reads.
//!
//! Only public posts resolve. A private, deleted, age-gated or login-walled
//! post parses to nothing, and the caller falls back to the ordinary preview -
//! the same card it would have shown anyway.
//!
//! The urls that come back live on a Facebook CDN behind a signed, expiring
//! query string. They are handed to clients through the same signed media proxy
//! as every other third-party image, so a viewer's browser never connects to
//! Instagram and the post's owner never learns who watched.

use redis::AsyncCommands;
use reqwest::Url;
use scraper::{ElementRef, Html, Selector};
use serde::{Deserialize, Serialize};

use crate::error::{AppError, AppResult};
use crate::http::link_previews::fetch_html;
use crate::state::AppState;

/// How long a resolved post is reused for. The CDN urls inside it stay good for
/// about a day, and a popular link is opened by many people at once, so a short
/// cache turns a channel full of readers into one fetch of instagram.com. Kept
/// well under the url's own life so a cached hit is never a dead one.
const CACHE_TTL_SECONDS: u64 = 15 * 60;

/// Captions run to 2200 characters; a preview card shows a few lines.
const MAX_CAPTION_CHARS: usize = 400;

/// What a public post gave up. Every field is optional because the embed page
/// is not a contract - it is a page - and a missing caption is not a reason to
/// throw away a working video url.
#[derive(Debug, Default, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Post {
    pub video_url: Option<String>,
    pub image_url: Option<String>,
    pub username: Option<String>,
    pub caption: Option<String>,
}

impl Post {
    /// Whether this is worth showing instead of the ordinary preview. Text
    /// alone isn't: a caption under a login-wall card is still a login wall.
    fn has_media(&self) -> bool {
        self.video_url.is_some() || self.image_url.is_some()
    }
}

/// The shortcode a url names, if it names a post at all. Covers `/p/`, `/reel/`,
/// `/reels/` and `/tv/`, both at the root and under a profile
/// (`instagram.com/someone/reel/CODE/`), which is the form the app shares.
pub fn shortcode(url: &Url) -> Option<String> {
    let host = url.host_str()?.trim_end_matches('.').to_ascii_lowercase();
    let host = host.strip_prefix("www.").unwrap_or(host.as_str());
    if !matches!(host, "instagram.com" | "m.instagram.com" | "instagr.am") {
        return None;
    }

    let segments: Vec<&str> = url
        .path_segments()?
        .filter(|segment| !segment.is_empty())
        .collect();
    let kind = segments
        .iter()
        .position(|segment| matches!(*segment, "p" | "reel" | "reels" | "tv"))?;
    let code = segments.get(kind + 1)?;
    let valid = !code.is_empty()
        && code.len() <= 24
        && code
            .chars()
            .all(|c| c.is_ascii_alphanumeric() || c == '-' || c == '_');
    valid.then(|| (*code).to_string())
}

/// The post behind `shortcode`, or `None` when nothing public came back.
///
/// Failures - a fetch error, a login wall, a page that changed shape - are all
/// the same answer here, and are cached as such: a link that cannot be resolved
/// must not send everyone who scrolls past it back to instagram.com.
pub async fn resolve(state: &AppState, shortcode: &str) -> Option<Post> {
    let post = match read_cache(state, shortcode).await {
        Some(cached) => cached,
        None => {
            let post = fetch(shortcode).await.unwrap_or_default();
            write_cache(state, shortcode, &post).await;
            post
        }
    };
    post.has_media().then_some(post)
}

async fn fetch(shortcode: &str) -> AppResult<Post> {
    // `captioned` is the same page with the caption markup left in.
    let url = Url::parse(&format!(
        "https://www.instagram.com/p/{shortcode}/embed/captioned/"
    ))
    .map_err(|_| AppError::BadRequest("Invalid Instagram link".into()))?;
    let (_, html) = fetch_html(url).await?;
    Ok(parse(&html))
}

fn cache_key(shortcode: &str) -> String {
    format!("instagram:v1:{shortcode}")
}

async fn read_cache(state: &AppState, shortcode: &str) -> Option<Post> {
    let mut con = state.rd();
    let raw: Option<String> = con.get(cache_key(shortcode)).await.ok()?;
    serde_json::from_str(&raw?).ok()
}

async fn write_cache(state: &AppState, shortcode: &str, post: &Post) {
    let Ok(encoded) = serde_json::to_string(post) else {
        return;
    };
    let mut con = state.rd();
    let _: Result<(), _> = con
        .set_ex(cache_key(shortcode), encoded, CACHE_TTL_SECONDS)
        .await;
}

/// Read a post out of the embed page.
///
/// The media urls come from the JSON the page carries, the caption and author
/// from its markup - each taken from wherever it is stated plainly rather than
/// from one grand parse, so a change to either side only costs the field it
/// touches.
fn parse(html: &str) -> Post {
    let document = Html::parse_document(html);
    let username = text_of(&document, ".CaptionUsername").or_else(|| json_string(html, "username"));
    Post {
        // A carousel and a single video look the same here: the first video is
        // the one a preview plays.
        video_url: json_string(html, "video_url").filter(|url| url.starts_with("https://")),
        image_url: json_string(html, "display_url")
            .or_else(|| meta_content(&document, "og:image"))
            .filter(|url| url.starts_with("https://")),
        caption: caption(&document),
        username,
    }
}

/// The caption itself: what the author wrote, without the chrome the embed
/// wraps it in - their name above it and the "View all N comments" link below.
fn caption(document: &Html) -> Option<String> {
    let selector = Selector::parse(".Caption").ok()?;
    let caption = document.select(&selector).next()?;
    let mut parts: Vec<String> = Vec::new();
    for child in caption.children() {
        if let Some(text) = child.value().as_text() {
            parts.push(text.to_string());
        } else if let Some(element) = ElementRef::wrap(child) {
            let class = element.value().attr("class").unwrap_or_default();
            if class.contains("CaptionUsername") || class.contains("CaptionComments") {
                continue;
            }
            parts.extend(element.text().map(str::to_owned));
        }
    }
    let clean = parts
        .join(" ")
        .split_whitespace()
        .collect::<Vec<_>>()
        .join(" ");
    if clean.is_empty() {
        return None;
    }
    Some(clean.chars().take(MAX_CAPTION_CHARS).collect())
}

fn text_of(document: &Html, selector: &str) -> Option<String> {
    let selector = Selector::parse(selector).ok()?;
    let text = document
        .select(&selector)
        .next()?
        .text()
        .collect::<Vec<_>>()
        .join(" ");
    let clean = text.split_whitespace().collect::<Vec<_>>().join(" ");
    (!clean.is_empty()).then_some(clean)
}

fn meta_content(document: &Html, property: &str) -> Option<String> {
    let selector = Selector::parse(&format!("meta[property='{property}']")).ok()?;
    document
        .select(&selector)
        .find_map(|element| element.value().attr("content").map(str::to_owned))
}

/// The first value of a JSON string field named `key`, however deeply the page
/// escaped it.
///
/// Instagram embeds a JSON document inside a JSON string inside the HTML, so
/// the same field reads `"video_url":"…"` on one page and `\"video_url\":\"…\"`
/// on the next. Scanning for the field beats parsing the whole thing: the
/// surrounding document is enormous, and its shape changes far more often than
/// the name of the field holding the video.
fn json_string(html: &str, key: &str) -> Option<String> {
    for (open, close) in [
        (format!("\\\"{key}\\\":\\\""), "\\\""),
        (format!("\"{key}\":\""), "\""),
    ] {
        let Some(start) = html.find(&open) else {
            continue;
        };
        let rest = &html[start + open.len()..];
        let Some(end) = rest.find(close) else {
            continue;
        };
        let value = unescape(&rest[..end]);
        if !value.is_empty() {
            return Some(value);
        }
    }
    None
}

/// A url as the page wrote it, back to a url.
///
/// Every layer of encoding adds backslashes, so a slash arrives as `\\\/` and
/// an ampersand as `\\u0026`; a `%` in a signed query string arrives as
/// `\\u0025` and must survive as `%`, which is why the `\uXXXX` escapes are
/// decoded rather than deleted along with their backslash. The value is a url,
/// so no backslash left in it was ever meant literally.
fn unescape(value: &str) -> String {
    let mut out = String::with_capacity(value.len());
    let mut chars = value.chars().peekable();
    while let Some(c) = chars.next() {
        if c != '\\' {
            out.push(c);
            continue;
        }
        if chars.peek() != Some(&'u') {
            // A backslash before anything else is escaping, not content.
            continue;
        }
        let escape: String = chars.clone().skip(1).take(4).collect();
        match u32::from_str_radix(&escape, 16)
            .ok()
            .and_then(char::from_u32)
        {
            Some(decoded) => {
                for _ in 0..5 {
                    chars.next();
                }
                out.push(decoded);
            }
            // Not an escape after all (or half a surrogate pair, which no url
            // contains): drop the backslash and let the `u` stand.
            None => continue,
        }
    }
    out.replace("&amp;", "&")
        .replace("&quot;", "\"")
        .replace("&#039;", "'")
}

#[cfg(test)]
mod tests {
    use super::*;

    fn code(url: &str) -> Option<String> {
        shortcode(&Url::parse(url).unwrap())
    }

    #[test]
    fn recognises_post_links() {
        assert_eq!(
            code("https://www.instagram.com/p/DbqgNZoyeB3/"),
            Some("DbqgNZoyeB3".into())
        );
        assert_eq!(
            code("https://instagram.com/reel/DbqgNZoyeB3"),
            Some("DbqgNZoyeB3".into())
        );
        assert_eq!(
            code("https://m.instagram.com/reels/Ab-c_1/"),
            Some("Ab-c_1".into())
        );
        assert_eq!(code("https://instagr.am/tv/Ab-c_1/"), Some("Ab-c_1".into()));
        // The form the app shares: the post nested under its author.
        assert_eq!(
            code("https://www.instagram.com/instagram/reel/DbqgNZoyeB3/?igsh=x"),
            Some("DbqgNZoyeB3".into())
        );
    }

    #[test]
    fn ignores_everything_that_is_not_a_post() {
        // A profile, the site itself, a story (not public), and a lookalike host.
        assert_eq!(code("https://www.instagram.com/instagram/"), None);
        assert_eq!(code("https://www.instagram.com/"), None);
        assert_eq!(code("https://www.instagram.com/stories/someone/123/"), None);
        assert_eq!(code("https://instagram.com.evil.example/p/Abc123/"), None);
        assert_eq!(code("https://notinstagram.com/p/Abc123/"), None);
        // A path segment that only looks like a shortcode.
        assert_eq!(code("https://www.instagram.com/p/has%20space/"), None);
    }

    /// The page's own escaping, character for character, around the two fields
    /// that matter.
    #[test]
    fn reads_media_out_of_the_embedded_json() {
        let html = concat!(
            r#"<html><body><div class="Caption">"#,
            r##"<a class="CaptionUsername" href="#">someone</a><br/>A caption   here"##,
            r##"<div class="CaptionComments"><a href="#">View all 12 comments</a></div></div>"##,
            r#"<script>{"contextJSON":"{\"is_video\":true,"#,
            r#"\"display_url\":\"https:\\\/\\\/cdn.example.com\\\/poster.jpg?a=1\\u0026e=fQ\\u00253D\","#,
            r#"\"video_url\":\"https:\\\/\\\/cdn.example.com\\\/clip.mp4?oe=1\"}"}</script>"#,
            "</body></html>",
        );
        let post = parse(html);
        assert_eq!(
            post.video_url.as_deref(),
            Some("https://cdn.example.com/clip.mp4?oe=1")
        );
        // The `%` in a signed query string arrives as an escape of its own and
        // has to come back as `%`, or the CDN rejects the url it signed.
        assert_eq!(
            post.image_url.as_deref(),
            Some("https://cdn.example.com/poster.jpg?a=1&e=fQ%3D")
        );
        assert_eq!(post.username.as_deref(), Some("someone"));
        // The author's name above the caption and the comments link below it
        // are the embed's chrome, not what anybody wrote.
        assert_eq!(post.caption.as_deref(), Some("A caption here"));
        assert!(post.has_media());
    }

    /// The plainly-escaped shape of the same page, and an image-only post.
    #[test]
    fn reads_media_out_of_plain_json() {
        let html = r#"<html><body><script>{"display_url":"https:\/\/cdn.example.com/p.jpg"}</script></body></html>"#;
        let post = parse(html);
        assert_eq!(
            post.image_url.as_deref(),
            Some("https://cdn.example.com/p.jpg")
        );
        assert_eq!(post.video_url, None);
        assert!(post.has_media());
    }

    /// What a login wall parses to. Nothing here should read as media.
    #[test]
    fn a_walled_post_resolves_to_nothing() {
        let html = r#"<html><body><script>{"contextJSON":null}</script>
            <a href="/accounts/login/">Log in</a></body></html>"#;
        let post = parse(html);
        assert_eq!(post, Post::default());
        assert!(!post.has_media());
    }

    /// The og: image is a fallback, not a preference - it is a cropped
    /// thumbnail where `display_url` is the post's own frame.
    #[test]
    fn falls_back_to_the_open_graph_image() {
        let html = r#"<html><head><meta property="og:image" content="https://cdn.example.com/og.jpg"></head></html>"#;
        assert_eq!(
            parse(html).image_url.as_deref(),
            Some("https://cdn.example.com/og.jpg")
        );
    }

    /// A relative or `data:` url is not something to sign and proxy.
    #[test]
    fn only_absolute_https_media_is_kept() {
        let html = r#"<script>{"video_url":"\/relative.mp4","display_url":"data:image\/gif;base64,AA"}</script>"#;
        assert_eq!(parse(html), Post::default());
    }
}
