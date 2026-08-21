
use redis::AsyncCommands;
use reqwest::Url;
use scraper::{ElementRef, Html, Selector};
use serde::{Deserialize, Serialize};

use crate::error::{AppError, AppResult};
use crate::http::link_previews::fetch_html;
use crate::state::AppState;

const CACHE_TTL_SECONDS: u64 = 15 * 60;
const RESOLVED_CACHE_TTL_SECONDS: u64 = 7 * 24 * 60 * 60;

const MAX_CAPTION_CHARS: usize = 400;

#[derive(Debug, Default, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Post {
    pub video_url: Option<String>,
    pub image_url: Option<String>,
    pub username: Option<String>,
    pub caption: Option<String>,
}

impl Post {
    fn has_media(&self) -> bool {
        self.video_url.is_some() || self.image_url.is_some()
    }
}

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
    let ttl = if post.has_media() {
        RESOLVED_CACHE_TTL_SECONDS
    } else {
        CACHE_TTL_SECONDS
    };
    let mut con = state.rd();
    let _: Result<(), _> = con.set_ex(cache_key(shortcode), encoded, ttl).await;
}

fn parse(html: &str) -> Post {
    let document = Html::parse_document(html);
    let username = text_of(&document, ".CaptionUsername").or_else(|| json_string(html, "username"));
    Post {
        video_url: json_string(html, "video_url").filter(|url| url.starts_with("https://")),
        image_url: json_string(html, "display_url")
            .or_else(|| meta_content(&document, "og:image"))
            .filter(|url| url.starts_with("https://")),
        caption: caption(&document),
        username,
    }
}

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

fn unescape(value: &str) -> String {
    let mut out = String::with_capacity(value.len());
    let mut chars = value.chars().peekable();
    while let Some(c) = chars.next() {
        if c != '\\' {
            out.push(c);
            continue;
        }
        if chars.peek() != Some(&'u') {
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
        assert_eq!(
            code("https://www.instagram.com/instagram/reel/DbqgNZoyeB3/?igsh=x"),
            Some("DbqgNZoyeB3".into())
        );
    }

    #[test]
    fn ignores_everything_that_is_not_a_post() {
        assert_eq!(code("https://www.instagram.com/instagram/"), None);
        assert_eq!(code("https://www.instagram.com/"), None);
        assert_eq!(code("https://www.instagram.com/stories/someone/123/"), None);
        assert_eq!(code("https://instagram.com.evil.example/p/Abc123/"), None);
        assert_eq!(code("https://notinstagram.com/p/Abc123/"), None);
        assert_eq!(code("https://www.instagram.com/p/has%20space/"), None);
    }

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
        assert_eq!(
            post.image_url.as_deref(),
            Some("https://cdn.example.com/poster.jpg?a=1&e=fQ%3D")
        );
        assert_eq!(post.username.as_deref(), Some("someone"));
        assert_eq!(post.caption.as_deref(), Some("A caption here"));
        assert!(post.has_media());
    }

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

    #[test]
    fn a_walled_post_resolves_to_nothing() {
        let html = r#"<html><body><script>{"contextJSON":null}</script>
            <a href="/accounts/login/">Log in</a></body></html>"#;
        let post = parse(html);
        assert_eq!(post, Post::default());
        assert!(!post.has_media());
    }

    #[test]
    fn falls_back_to_the_open_graph_image() {
        let html = r#"<html><head><meta property="og:image" content="https://cdn.example.com/og.jpg"></head></html>"#;
        assert_eq!(
            parse(html).image_url.as_deref(),
            Some("https://cdn.example.com/og.jpg")
        );
    }

    #[test]
    fn only_absolute_https_media_is_kept() {
        let html = r#"<script>{"video_url":"\/relative.mp4","display_url":"data:image\/gif;base64,AA"}</script>"#;
        assert_eq!(parse(html), Post::default());
    }
}
