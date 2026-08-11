
use std::time::Duration;

use axum::body::Body;
use axum::extract::{Query, State};
use axum::http::header::{
    ACCEPT_RANGES, CACHE_CONTROL, CONTENT_LENGTH, CONTENT_RANGE, CONTENT_TYPE, ETAG, IF_NONE_MATCH,
    LOCATION, RANGE,
};
use axum::http::{HeaderMap, HeaderValue, StatusCode};
use axum::response::{IntoResponse, Response};
use axum::routing::get;
use axum::{Json, Router};
use chrono::Utc;
use jsonwebtoken::{decode, encode, DecodingKey, EncodingKey, Header, Validation};
use reqwest::{redirect::Policy, Url};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};

use crate::error::{AppError, AppResult};
use crate::http::link_previews::resolve_public_destination;
use crate::http::{AuthUser, ClientIp};
use crate::services::rate_limit;
use crate::state::AppState;

const ISSUER: &str = "orangchat";
const PURPOSE: &str = "media-proxy";
const TOKEN_TTL_SECONDS: i64 = 12 * 60 * 60;
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
    url: String,
    purpose: String,
    iss: String,
    iat: i64,
    exp: i64,
}

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

const ASSET_KINDS: &[(&str, &str, &str)] = &[
    ("avatar", "User", "avatarUrl"),
    ("banner", "User", "bannerUrl"),
    ("app-icon", "User", "appIconUrl"),
    ("server-icon", "Server", "iconUrl"),
    ("server-banner", "Server", "bannerUrl"),
    ("emoji", "Emoji", "url"),
    ("sound", "Sound", "url"),
];

pub fn asset_url(kind: &str, id: &str) -> String {
    format!("/api/media/asset/{kind}/{id}")
}

pub fn same_origin_asset(url: Option<&str>, kind: &str, id: &str) -> Option<String> {
    let url = url?;
    if url.starts_with("http://") || url.starts_with("https://") {
        Some(asset_url(kind, id))
    } else {
        Some(url.to_string())
    }
}

pub fn is_asset_url(url: &str) -> bool {
    url.starts_with("/api/media/asset/")
}

#[derive(Deserialize)]
struct ProxyQuery {
    t: String,
}

async fn proxy(
    State(state): State<AppState>,
    ClientIp(ip): ClientIp,
    headers: HeaderMap,
    Query(query): Query<ProxyQuery>,
) -> AppResult<Response> {
    rate_limit::check(&state, "media-proxy", &ip, rate_limit::MEDIA_PROXY_PER_IP).await?;

    let target = verify_media_token(&state.config.jwt_access_secret, &query.t)?;
    let url = Url::parse(&target).map_err(|_| AppError::BadRequest("Invalid media URL".into()))?;
    let range = headers
        .get(RANGE)
        .and_then(|v| v.to_str().ok())
        .map(str::to_owned);

    let etag = media_etag(&url);
    if range.is_none() && etag_matches(&headers, &etag) {
        return Ok(not_modified(&etag, PROXY_CACHE_CONTROL, CORP_SAME_ORIGIN));
    }
    fetch_media(
        url,
        range.as_deref(),
        CORP_SAME_ORIGIN,
        PROXY_CACHE_CONTROL,
        &etag,
    )
    .await
}

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
    let stored: Option<String> = sqlx::query_scalar(&format!(
        r#"SELECT "{column}" FROM "{table}" WHERE id = $1"#
    ))
    .bind(&id)
    .fetch_optional(&state.pool)
    .await?
    .flatten();
    let stored = stored.ok_or_else(|| AppError::NotFound("Asset not found".into()))?;

    if is_asset_url(&stored) {
        return Err(AppError::NotFound("Asset not found".into()));
    }

    if !(stored.starts_with("http://") || stored.starts_with("https://")) {
        return Ok(axum::response::Redirect::temporary(&stored).into_response());
    }

    let url = Url::parse(&stored).map_err(|_| AppError::BadRequest("Invalid media URL".into()))?;
    let range = headers
        .get(RANGE)
        .and_then(|v| v.to_str().ok())
        .map(str::to_owned);
    let etag = media_etag(&url);
    if range.is_none() && etag_matches(&headers, &etag) {
        return Ok(not_modified(&etag, ASSET_CACHE_CONTROL, CORP_CROSS_ORIGIN));
    }
    fetch_media(
        url,
        range.as_deref(),
        CORP_CROSS_ORIGIN,
        ASSET_CACHE_CONTROL,
        &etag,
    )
    .await
}

const CORP_SAME_ORIGIN: &str = "same-origin";
const CORP_CROSS_ORIGIN: &str = "cross-origin";

const PROXY_CACHE_CONTROL: &str = "public, max-age=43200, immutable";

const ASSET_CACHE_CONTROL: &str = "public, max-age=86400, stale-while-revalidate=604800";

fn media_etag(url: &Url) -> String {
    let digest = Sha256::digest(url.as_str().as_bytes());
    let mut tag = String::with_capacity(18);
    tag.push('"');
    for byte in &digest[..8] {
        tag.push_str(&format!("{byte:02x}"));
    }
    tag.push('"');
    tag
}

fn etag_matches(headers: &HeaderMap, etag: &str) -> bool {
    headers
        .get(IF_NONE_MATCH)
        .and_then(|value| value.to_str().ok())
        .is_some_and(|raw| {
            raw.split(',').any(|candidate| {
                let candidate = candidate.trim();
                candidate == "*" || candidate.trim_start_matches("W/") == etag
            })
        })
}

fn not_modified(etag: &str, cache_control: &'static str, corp: &str) -> Response {
    let mut builder = Response::builder().status(StatusCode::NOT_MODIFIED);
    let out = builder.headers_mut().expect("fresh builder has headers");
    if let Ok(value) = HeaderValue::from_str(etag) {
        out.insert(ETAG, value);
    }
    out.insert(CACHE_CONTROL, HeaderValue::from_static(cache_control));
    if let Ok(value) = HeaderValue::from_str(corp) {
        out.insert("cross-origin-resource-policy", value);
    }
    builder
        .body(Body::empty())
        .unwrap_or_else(|_| StatusCode::NOT_MODIFIED.into_response())
}

async fn fetch_media(
    mut url: Url,
    range: Option<&str>,
    corp: &str,
    cache_control: &'static str,
    etag: &str,
) -> AppResult<Response> {
    for redirect_count in 0..=MAX_REDIRECTS {
        let (host, address) = resolve_public_destination(&url).await?;
        let client = reqwest::Client::builder()
            .redirect(Policy::none())
            .connect_timeout(Duration::from_secs(4))
            .timeout(Duration::from_secs(30))
            .user_agent("OrangChat-MediaProxy/1.0")
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

        return Ok(build_response(
            status,
            content_type,
            response,
            corp,
            cache_control,
            etag,
        ));
    }

    Err(AppError::BadRequest("Unable to fetch media".into()))
}

fn build_response(
    status: StatusCode,
    content_type: String,
    upstream: reqwest::Response,
    corp: &str,
    cache_control: &'static str,
    etag: &str,
) -> Response {
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
    if let Ok(value) = HeaderValue::from_str(corp) {
        out.insert("cross-origin-resource-policy", value);
    }
    out.insert(CACHE_CONTROL, HeaderValue::from_static(cache_control));
    if status == StatusCode::OK {
        if let Ok(value) = HeaderValue::from_str(etag) {
            out.insert(ETAG, value);
        }
    }

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
    fn etag_follows_the_upstream_url() {
        let old = media_etag(&Url::parse("https://res.cloudinary.com/x/v1/a.png").unwrap());
        let new = media_etag(&Url::parse("https://res.cloudinary.com/x/v2/a.png").unwrap());
        assert_ne!(old, new);
        assert_eq!(
            old,
            media_etag(&Url::parse("https://res.cloudinary.com/x/v1/a.png").unwrap())
        );
        assert!(old.starts_with('"') && old.ends_with('"'));
        assert!(!old.contains("cloudinary"));
    }

    #[test]
    fn conditional_requests_are_recognised() {
        let etag = media_etag(&Url::parse("https://cdn.example.com/a.png").unwrap());
        let with = |value: &str| {
            let mut headers = HeaderMap::new();
            headers.insert(IF_NONE_MATCH, HeaderValue::from_str(value).unwrap());
            etag_matches(&headers, &etag)
        };

        assert!(with(&etag));
        assert!(with("*"));
        assert!(with(&format!("W/{etag}")));
        assert!(with(&format!("\"other\", {etag}")));
        assert!(!with("\"other\""));
        assert!(!etag_matches(&HeaderMap::new(), &etag));
    }

    #[test]
    fn remote_asset_urls_become_same_origin() {
        assert_eq!(
            same_origin_asset(Some("https://res.cloudinary.com/x/a.gif"), "avatar", "u1"),
            Some("/api/media/asset/avatar/u1".into())
        );
        assert_eq!(
            same_origin_asset(Some("/uploads/a.png"), "avatar", "u1"),
            Some("/uploads/a.png".into())
        );
        assert_eq!(same_origin_asset(None, "avatar", "u1"), None);
    }

    #[test]
    fn wire_form_is_detected_as_an_asset_url() {
        assert!(is_asset_url(
            &same_origin_asset(Some("https://res.cloudinary.com/x/a.gif"), "avatar", "u1").unwrap()
        ));
        assert!(is_asset_url("/api/media/asset/server-icon/s1"));
        assert!(!is_asset_url("/uploads/a.png"));
        assert!(!is_asset_url("https://res.cloudinary.com/x/a.gif"));
        assert!(!is_asset_url("https://cdn.discordapp.com/avatars/1/h.png"));
    }

    #[test]
    fn rejects_token_signed_with_another_secret() {
        let signed = sign_media_url("real", "https://cdn.example.com/a.png").unwrap();
        assert!(verify_media_token("forged", token_of(&signed)).is_err());
    }

    #[test]
    fn rejects_a_token_missing_the_media_purpose() {
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
