pub mod attachments;
pub mod auth;
pub mod channels;
pub mod connections;
pub mod dms;
pub mod drafts;
pub mod e2ee;
pub mod emojis;
pub mod events;
pub mod friends;
pub mod link_previews;
pub mod media_proxy;
pub mod push;
pub mod reports;
pub mod roles;
pub mod security;
pub mod servers;
pub mod sounds;
pub mod uploads;

use std::convert::Infallible;
use std::net::SocketAddr;

use axum::extract::{ConnectInfo, FromRequestParts, Request, State};
use axum::http::request::Parts;
use axum::http::{HeaderMap, HeaderValue, Method};
use axum::middleware::Next;
use axum::response::{IntoResponse, Response};
use axum::routing::get;
use axum::{Json, Router};
use serde_json::json;
use tower_http::cors::CorsLayer;

use crate::auth::verify_access_token;
use crate::error::AppError;
use crate::services::rate_limit;
use crate::state::AppState;

/// Authenticated user, extracted from the `Authorization: Bearer` header.
/// Mirrors the `authenticate` preHandler guard.
pub struct AuthUser {
    pub user_id: String,
}

#[axum::async_trait]
impl FromRequestParts<AppState> for AuthUser {
    type Rejection = AppError;

    async fn from_request_parts(
        parts: &mut Parts,
        state: &AppState,
    ) -> Result<Self, Self::Rejection> {
        let token = parts
            .headers
            .get(axum::http::header::AUTHORIZATION)
            .and_then(|v| v.to_str().ok())
            .and_then(|h| h.strip_prefix("Bearer "))
            .ok_or_else(|| AppError::Unauthorized("Missing access token".into()))?;
        let claims = verify_access_token(&state.config, token)?;
        let verified: Option<bool> =
            sqlx::query_scalar(r#"SELECT "emailVerifiedAt" IS NOT NULL FROM "User" WHERE id = $1"#)
                .bind(&claims.sub)
                .fetch_optional(&state.pool)
                .await?;
        if verified != Some(true) {
            return Err(AppError::Unauthorized(
                "Verify your email before accessing OrangChat".into(),
            ));
        }
        Ok(AuthUser {
            user_id: claims.sub,
        })
    }
}

/// The user, if there is one. For routes a signed-out visitor may reach but
/// whose answer is richer once we know who is asking - an invite link lands on
/// a logged-out browser as readily as an authenticated one.
///
/// A bad or expired token reads as absent rather than failing the request: the
/// caller wanted the anonymous answer to be acceptable, and a stale token that
/// hard-errored would be worse than no token at all.
pub struct OptionalAuthUser(pub Option<String>);

#[axum::async_trait]
impl FromRequestParts<AppState> for OptionalAuthUser {
    type Rejection = std::convert::Infallible;

    async fn from_request_parts(
        parts: &mut Parts,
        state: &AppState,
    ) -> Result<Self, Self::Rejection> {
        Ok(OptionalAuthUser(
            AuthUser::from_request_parts(parts, state)
                .await
                .ok()
                .map(|u| u.user_id),
        ))
    }
}

/// The requesting client's address, for rate-limit bucketing.
///
/// `X-Real-IP` is what the nginx vhost in front of us sets from `$remote_addr`,
/// overwriting anything the client sent, so it is the only header here a caller
/// can't forge. `X-Forwarded-For` is a fallback for other deployments - its last
/// entry is the nearest hop's observation - and the peer address covers running
/// without a proxy at all.
pub struct ClientIp(pub String);

fn client_ip(headers: &HeaderMap, peer: Option<&SocketAddr>) -> String {
    if let Some(ip) = headers.get("x-real-ip").and_then(|v| v.to_str().ok()) {
        let ip = ip.trim();
        if !ip.is_empty() {
            return ip.to_string();
        }
    }
    if let Some(fwd) = headers.get("x-forwarded-for").and_then(|v| v.to_str().ok()) {
        if let Some(last) = fwd.rsplit(',').map(str::trim).find(|s| !s.is_empty()) {
            return last.to_string();
        }
    }
    peer.map(|p| p.ip().to_string())
        .unwrap_or_else(|| "unknown".to_string())
}

#[axum::async_trait]
impl<S: Send + Sync> FromRequestParts<S> for ClientIp {
    type Rejection = Infallible;

    async fn from_request_parts(parts: &mut Parts, _state: &S) -> Result<Self, Self::Rejection> {
        let peer = parts
            .extensions
            .get::<ConnectInfo<SocketAddr>>()
            .map(|ci| ci.0);
        Ok(ClientIp(client_ip(&parts.headers, peer.as_ref())))
    }
}

/// Coarse per-address ceiling over the whole API. Individual routes layer their
/// own tighter quotas on top; this one only exists to stop blunt hammering.
pub async fn api_rate_limit(
    State(state): State<AppState>,
    ClientIp(ip): ClientIp,
    req: Request,
    next: Next,
) -> Result<Response, AppError> {
    rate_limit::check(&state, "api", &ip, rate_limit::API_PER_IP).await?;
    Ok(next.run(req).await)
}

/// Credentialed CORS: explicit origin + mirrored header list (Any is invalid with credentials).
/// Applied as the outermost layer in main so it also covers Socket.IO requests.
pub fn cors_layer(state: &AppState) -> CorsLayer {
    CorsLayer::new()
        .allow_origin(
            state
                .config
                .client_origin
                .parse::<HeaderValue>()
                .expect("valid CLIENT_ORIGIN"),
        )
        .allow_credentials(true)
        .allow_methods([
            Method::GET,
            Method::POST,
            Method::PATCH,
            Method::PUT,
            Method::DELETE,
            Method::OPTIONS,
        ])
        .allow_headers([
            axum::http::header::AUTHORIZATION,
            axum::http::header::CONTENT_TYPE,
        ])
        .expose_headers([
            axum::http::header::SET_COOKIE,
            axum::http::header::RETRY_AFTER,
        ])
}

pub fn router(state: AppState) -> Router {
    let api = Router::new()
        .route(
            "/",
            get(|| async { Json(json!({ "name": "orangchat-api", "version": "0.0.0" })) }),
        )
        .nest("/auth", auth::routes())
        .nest("/security", security::routes())
        .merge(servers::routes())
        .merge(channels::routes())
        .merge(connections::routes())
        .merge(dms::routes())
        .merge(drafts::routes())
        .merge(e2ee::routes())
        .merge(emojis::routes())
        .merge(events::routes())
        .merge(friends::routes())
        .merge(link_previews::routes())
        .merge(media_proxy::routes())
        .merge(push::routes())
        .merge(roles::routes())
        .merge(reports::routes())
        .merge(sounds::routes())
        .merge(uploads::routes())
        .merge(attachments::routes())
        .layer(axum::middleware::from_fn_with_state(
            state.clone(),
            api_rate_limit,
        ));

    Router::new()
        .route("/health", get(health))
        .nest("/api", api)
        .with_state(state)
}

async fn health(State(state): State<AppState>) -> impl IntoResponse {
    let db_ok = sqlx::query("SELECT 1").execute(&state.pool).await.is_ok();
    let redis_ok = {
        let mut con = state.rd();
        redis::cmd("PING")
            .query_async::<String>(&mut con)
            .await
            .is_ok()
    };
    let ok = db_ok && redis_ok;
    Json(json!({
        "status": if ok { "ok" } else { "degraded" },
        "db": if db_ok { "up" } else { "down" },
        "redis": if redis_ok { "up" } else { "down" },
        "uptime": crate::uptime_seconds(),
        "version": env!("CARGO_PKG_VERSION"),
    }))
}

// ── Validation helpers (mirror the zod schemas) ─────────

pub fn valid_email(s: &str) -> bool {
    let parts: Vec<&str> = s.split('@').collect();
    parts.len() == 2 && !parts[0].is_empty() && parts[1].contains('.') && !parts[1].starts_with('.')
}

pub fn valid_username(s: &str) -> bool {
    s.len() >= 2
        && s.len() <= 32
        && s.chars()
            .all(|c| c.is_ascii_lowercase() || c.is_ascii_digit() || c == '_' || c == '.')
}

pub fn bad_request(msg: &str) -> AppError {
    AppError::BadRequest(msg.into())
}

#[cfg(test)]
mod client_ip_tests {
    use super::*;

    fn headers(pairs: &[(&'static str, &str)]) -> HeaderMap {
        let mut h = HeaderMap::new();
        for (k, v) in pairs {
            h.insert(*k, HeaderValue::from_str(v).unwrap());
        }
        h
    }

    fn peer() -> SocketAddr {
        "10.0.0.9:5000".parse().unwrap()
    }

    #[test]
    fn prefers_x_real_ip() {
        let h = headers(&[("x-real-ip", "1.2.3.4"), ("x-forwarded-for", "9.9.9.9")]);
        assert_eq!(client_ip(&h, Some(&peer())), "1.2.3.4");
    }

    /// A forged XFF prefix must not become the bucket key; the last entry is the
    /// only one a proxy actually observed.
    #[test]
    fn falls_back_to_the_last_forwarded_for_entry() {
        let h = headers(&[("x-forwarded-for", "6.6.6.6, 1.2.3.4")]);
        assert_eq!(client_ip(&h, Some(&peer())), "1.2.3.4");
    }

    #[test]
    fn falls_back_to_the_peer_address() {
        assert_eq!(client_ip(&headers(&[]), Some(&peer())), "10.0.0.9");
    }

    #[test]
    fn empty_headers_do_not_shadow_the_peer() {
        let h = headers(&[("x-real-ip", "  "), ("x-forwarded-for", "")]);
        assert_eq!(client_ip(&h, Some(&peer())), "10.0.0.9");
    }

    #[test]
    fn unknown_when_there_is_nothing_to_go_on() {
        assert_eq!(client_ip(&headers(&[]), None), "unknown");
    }
}
