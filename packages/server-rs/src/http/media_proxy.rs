//! Same-origin media proxy.
//!
//! Chat messages and link previews can reference images/video/audio on
//! arbitrary third-party hosts. Rendering those directly means the *viewer's*
//! browser connects to that host, handing a message author a zero-click IP log
//! of everyone who so much as scrolls past. This proxy makes the server the
//! only party that talks to the remote host, so a reader's browser only ever
//! touches our origin.
//!
//! Access is by a short-lived HMAC-signed URL rather than a session: an `<img>`
//! or `<video>` tag can't carry a bearer token, so the mint step (`/media/sign`,
//! authenticated) issues a signed `/media/proxy?t=…` URL that the fetch step
//! verifies on its own. That signature is what proves *our* server authorized
//! the fetch; the fetch itself re-runs the SSRF checks and is rate-limited per
//! address so the signed URL can't be turned into an open proxy.

use std::time::Duration;

use axum::body::Body;
use axum::extract::{Query, State};
use axum::http::header::{
    ACCEPT_RANGES, CACHE_CONTROL, CONTENT_LENGTH, CONTENT_RANGE, CONTENT_TYPE, LOCATION, RANGE,
};
use axum::http::{HeaderMap, HeaderValue, StatusCode};
use axum::response::{IntoResponse, Response};
use axum::routing::get;
use axum::{Json, Router};
use chrono::Utc;
use jsonwebtoken::{decode, encode, DecodingKey, EncodingKey, Header, Validation};
use reqwest::{redirect::Policy, Url};
use serde::{Deserialize, Serialize};

use crate::error::{AppError, AppResult};
use crate::http::link_previews::resolve_public_destination;
use crate::http::{AuthUser, ClientIp};
use crate::services::rate_limit;
use crate::state::AppState;

const ISSUER: &str = "orangchat";
const PURPOSE: &str = "media-proxy";
/// A signed URL outlives a viewport: a reader may scroll a message back hours
/// later and the thumbnail should still load without a re-mint. The token only
/// authorizes fetching one public URL through the SSRF-checked proxy, so a long
/// window is cheap.
const TOKEN_TTL_SECONDS: i64 = 12 * 60 * 60;
/// Upstream media is streamed straight through, but a declared length past this
/// is refused up front so the proxy can't be aimed at a multi-gigabyte file.
const MAX_BYTES: u64 = 50 * 1024 * 1024;
const MAX_REDIRECTS: usize = 3;

pub fn routes() -> Router<AppState> {
    Router::new()
        .route("/media/sign", get(sign))
        .route("/media/proxy", get(proxy))
        .route("/media/asset/:kind/:id", get(asset))
}

#[derive(Debug, Serialize, Deserialize)]
struct MediaClaims {
    /// The third-party URL this token authorizes a fetch of.
    url: String,
    /// Pins the token to this route; an access token decoded here lacks it (and
    /// `url`), so the two kinds can't be swapped even though both are HS256 over
    /// the same secret.
    purpose: String,
    iss: String,
    iat: i64,
    exp: i64,
}

/// Mint a signed same-origin proxy URL for `target`. `target` is trusted to be
/// an already-parsed absolute http(s) URL; the fetch step re-validates it.
pub fn sign_media_url(secret: &str, target: &str) -> AppResult<String> {
    let now = Utc::now().timestamp();
    let claims = MediaClaims {
        url: target.to_string(),
        purpose: PURPOSE.to_string(),
        iss: ISSUER.to_string(),
        iat: now,
        exp: now + TOKEN_TTL_SECONDS,
    };
    let token = encode(
        &Header::default(),
        &claims,
        &EncodingKey::from_secret(secret.as_bytes()),
    )
    .map_err(|e| AppError::Internal(format!("media token sign: {e}")))?;
    Ok(format!("/api/media/proxy?t={token}"))
}

fn verify_media_token(secret: &str, token: &str) -> AppResult<String> {
    let mut validation = Validation::new(jsonwebtoken::Algorithm::HS256);
    validation.set_issuer(&[ISSUER]);
    validation.validate_aud = false;
    let claims = decode::<MediaClaims>(
        token,
        &DecodingKey::from_secret(secret.as_bytes()),
        &validation,
    )
    .map(|d| d.claims)
    .map_err(|_| AppError::BadRequest("Invalid or expired media token".into()))?;
    if claims.purpose != PURPOSE {
        return Err(AppError::BadRequest("Invalid media token".into()));
    }
    Ok(claims.url)
}

#[derive(Deserialize)]
struct SignQuery {
    url: String,
}

#[derive(Serialize)]
struct SignResponse {
    url: String,
}

/// Authenticated: hands the client a signed proxy URL for a remote media link.
/// Kept cheap and does no fetch - it only confirms the URL is a public http(s)
/// address worth minting a token for. The proxy revalidates before any bytes
/// move, so a host that turns private between mint and fetch is still refused.
async fn sign(
    State(state): State<AppState>,
    auth: AuthUser,
    Query(query): Query<SignQuery>,
) -> AppResult<Json<SignResponse>> {
    rate_limit::check(
        &state,
        "media-sign",
        &auth.user_id,
        rate_limit::MEDIA_SIGN_PER_USER,
    )
    .await?;

    let url =
        Url::parse(&query.url).map_err(|_| AppError::BadRequest("Invalid media URL".into()))?;
    resolve_public_destination(&url).await?;
    Ok(Json(SignResponse {
        url: sign_media_url(&state.config.jwt_access_secret, url.as_str())?,
    }))
}

/// The stored-asset kinds, as `(url path segment, table, url column)`. These are
/// rows *we* wrote - an avatar upload, a soundboard clip - so the url is trusted
/// enough to name by row id instead of by signed token, and the resulting url is
/// stable and cacheable rather than changing on every mint.
const ASSET_KINDS: &[(&str, &str, &str)] = &[
    ("avatar", "User", "avatarUrl"),
    ("banner", "User", "bannerUrl"),
    ("server-icon", "Server", "iconUrl"),
    ("server-banner", "Server", "bannerUrl"),
    ("emoji", "Emoji", "url"),
    ("sound", "Sound", "url"),
];

/// The same-origin url for a stored asset of `kind` on row `id`.
pub fn asset_url(kind: &str, id: &str) -> String {
    format!("/api/media/asset/{kind}/{id}")
}

/// Wire form of a stored asset url. Anything absolute lives on a third-party
/// host (Cloudinary, or an OAuth provider's CDN for an imported avatar) and is
/// swapped for its same-origin route: the page's `img-src`/`media-src` is
/// `'self'` precisely so a third-party host can't be handed a viewer's IP, and
/// a raw cdn url simply would not render. Relative urls are already ours.
pub fn same_origin_asset(url: Option<&str>, kind: &str, id: &str) -> Option<String> {
    let url = url?;
    if url.starts_with("http://") || url.starts_with("https://") {
        Some(asset_url(kind, id))
    } else {
        Some(url.to_string())
    }
}

#[derive(Deserialize)]
struct ProxyQuery {
    t: String,
}

/// Unauthenticated but signed: streams the remote media through this origin.
async fn proxy(
    State(state): State<AppState>,
    ClientIp(ip): ClientIp,
    headers: HeaderMap,
    Query(query): Query<ProxyQuery>,
) -> AppResult<Response> {
    rate_limit::check(&state, "media-proxy", &ip, rate_limit::MEDIA_PROXY_PER_IP).await?;

    let target = verify_media_token(&state.config.jwt_access_secret, &query.t)?;
    let url = Url::parse(&target).map_err(|_| AppError::BadRequest("Invalid media URL".into()))?;
    // Only a byte range is worth forwarding; the browser sets it while seeking a
    // <video>/<audio>, and dropping it would force a full download per seek.
    let range = headers
        .get(RANGE)
        .and_then(|v| v.to_str().ok())
        .map(str::to_owned);
    fetch_media(url, range.as_deref()).await
}

/// Unauthenticated: streams a stored asset (avatar, emoji, soundboard clip)
/// through this origin. Unauthenticated because an `<img>` carries no token and
/// these bytes sat on a public cdn url until now; what the route adds is that
/// the *viewer* no longer connects to that cdn.
async fn asset(
    State(state): State<AppState>,
    ClientIp(ip): ClientIp,
    headers: HeaderMap,
    axum::extract::Path((kind, id)): axum::extract::Path<(String, String)>,
) -> AppResult<Response> {
    rate_limit::check(&state, "media-proxy", &ip, rate_limit::MEDIA_PROXY_PER_IP).await?;

    let (_, table, column) = ASSET_KINDS
        .iter()
        .find(|(name, _, _)| *name == kind)
        .ok_or_else(|| AppError::NotFound("Unknown asset".into()))?;
    // Identifiers come from the table above, never from the request.
    let stored: Option<String> = sqlx::query_scalar(&format!(
        r#"SELECT "{column}" FROM "{table}" WHERE id = $1"#
    ))
    .bind(&id)
    .fetch_optional(&state.pool)
    .await?
    .flatten();
    let stored = stored.ok_or_else(|| AppError::NotFound("Asset not found".into()))?;

    // A row written before Cloudinary was configured stores a relative url that
    // nginx serves directly; there is nothing to proxy.
    if !(stored.starts_with("http://") || stored.starts_with("https://")) {
        return Ok(axum::response::Redirect::temporary(&stored).into_response());
    }

    let url = Url::parse(&stored).map_err(|_| AppError::BadRequest("Invalid media URL".into()))?;
    let range = headers
        .get(RANGE)
        .and_then(|v| v.to_str().ok())
        .map(str::to_owned);
    fetch_media(url, range.as_deref()).await
}

async fn fetch_media(mut url: Url, range: Option<&str>) -> AppResult<Response> {
    for redirect_count in 0..=MAX_REDIRECTS {
        let (host, address) = resolve_public_destination(&url).await?;
        let client = reqwest::Client::builder()
            .redirect(Policy::none())
            .connect_timeout(Duration::from_secs(4))
            .timeout(Duration::from_secs(30))
            .user_agent("OrangChat-MediaProxy/1.0")
            // Pin to the address we validated so the name can't re-resolve to a
            // private host between the check and the connection.
            .resolve(&host, address)
            .build()
            .map_err(|e| AppError::Internal(format!("media proxy client: {e}")))?;

        let mut request = client.get(url.clone());
        if let Some(range) = range {
            request = request.header(RANGE, range);
        }
        let response = request
            .send()
            .await
            .map_err(|_| AppError::BadRequest("Unable to fetch media".into()))?;

        let status = response.status();
        if status.is_redirection() {
            if redirect_count == MAX_REDIRECTS {
                return Err(AppError::BadRequest("Too many media redirects".into()));
            }
            let location = response
                .headers()
                .get(LOCATION)
                .and_then(|value| value.to_str().ok())
                .ok_or_else(|| AppError::BadRequest("Invalid media redirect".into()))?;
            url = url
                .join(location)
                .map_err(|_| AppError::BadRequest("Invalid media redirect".into()))?;
            continue;
        }

        // 200 for a whole file, 206 when the range was honoured; anything else
        // (403/404/416/5xx) isn't media we should relay.
        if status != StatusCode::OK && status != StatusCode::PARTIAL_CONTENT {
            return Err(AppError::BadRequest("Unable to fetch media".into()));
        }

        let content_type = response
            .headers()
            .get(CONTENT_TYPE)
            .and_then(|value| value.to_str().ok())
            .map(str::to_owned)
            .ok_or_else(|| AppError::BadRequest("Media has no content type".into()))?;
        let mime = content_type.to_ascii_lowercase();
        if !(mime.starts_with("image/") || mime.starts_with("video/") || mime.starts_with("audio/"))
        {
            return Err(AppError::BadRequest(
                "Link is not an image, video, or audio".into(),
            ));
        }
        if response
            .content_length()
            .is_some_and(|length| length > MAX_BYTES)
        {
            return Err(AppError::BadRequest("Media is too large to proxy".into()));
        }

        return Ok(build_response(status, content_type, response));
    }

    Err(AppError::BadRequest("Unable to fetch media".into()))
}

/// Relay the upstream response: its status (200/206), the validated content
/// type, and the range plumbing a media element needs to seek - but served
/// inert (nosniff + a null CSP) so a mislabelled HTML polyglot can't run.
fn build_response(
    status: StatusCode,
    content_type: String,
    upstream: reqwest::Response,
) -> Response {
    // Pull the passthrough headers before bytes_stream() consumes the response.
    let content_length = upstream.headers().get(CONTENT_LENGTH).cloned();
    let content_range = upstream.headers().get(CONTENT_RANGE).cloned();

    let mut builder = Response::builder().status(status);
    let out = builder.headers_mut().expect("fresh builder has headers");

    if let Ok(value) = HeaderValue::from_str(&content_type) {
        out.insert(CONTENT_TYPE, value);
    }
    if let Some(value) = content_length {
        out.insert(CONTENT_LENGTH, value);
    }
    if let Some(value) = content_range {
        out.insert(CONTENT_RANGE, value);
    }
    out.insert(ACCEPT_RANGES, HeaderValue::from_static("bytes"));
    out.insert(
        "x-content-type-options",
        HeaderValue::from_static("nosniff"),
    );
    out.insert(
        "content-security-policy",
        HeaderValue::from_static("default-src 'none'; sandbox"),
    );
    out.insert(
        "cross-origin-resource-policy",
        HeaderValue::from_static("same-origin"),
    );
    out.insert(
        CACHE_CONTROL,
        HeaderValue::from_static("public, max-age=3600"),
    );

    builder
        .body(Body::from_stream(upstream.bytes_stream()))
        .unwrap_or_else(|_| StatusCode::BAD_GATEWAY.into_response())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn token_of(signed: &str) -> &str {
        signed.strip_prefix("/api/media/proxy?t=").unwrap()
    }

    #[test]
    fn signed_url_round_trips() {
        let signed = sign_media_url("s3cret", "https://cdn.example.com/a.png").unwrap();
        assert_eq!(
            verify_media_token("s3cret", token_of(&signed)).unwrap(),
            "https://cdn.example.com/a.png"
        );
    }

    #[test]
    fn remote_asset_urls_become_same_origin() {
        assert_eq!(
            same_origin_asset(Some("https://res.cloudinary.com/x/a.gif"), "avatar", "u1"),
            Some("/api/media/asset/avatar/u1".into())
        );
        // Already ours: left alone, so pre-Cloudinary rows keep working.
        assert_eq!(
            same_origin_asset(Some("/uploads/a.png"), "avatar", "u1"),
            Some("/uploads/a.png".into())
        );
        assert_eq!(same_origin_asset(None, "avatar", "u1"), None);
    }

    #[test]
    fn rejects_token_signed_with_another_secret() {
        let signed = sign_media_url("real", "https://cdn.example.com/a.png").unwrap();
        assert!(verify_media_token("forged", token_of(&signed)).is_err());
    }

    #[test]
    fn rejects_a_token_missing_the_media_purpose() {
        // A well-formed HS256 token over the same secret but without our
        // `url`/`purpose` claims (shape of an access token) must not verify.
        #[derive(Serialize)]
        struct Other {
            sub: String,
            iss: String,
            exp: i64,
        }
        let other = Other {
            sub: "user-1".into(),
            iss: ISSUER.into(),
            exp: Utc::now().timestamp() + 600,
        };
        let forged = encode(
            &Header::default(),
            &other,
            &EncodingKey::from_secret(b"shared"),
        )
        .unwrap();
        assert!(verify_media_token("shared", &forged).is_err());
    }
}
