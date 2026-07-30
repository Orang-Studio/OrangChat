pub mod attachments;
pub mod auth;
pub mod bots;
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
pub mod updates;
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
use crate::services::update_policy;
use crate::state::AppState;

/// Whether a request is being made by a person or by a bot. Almost every route
/// treats the two identically - a bot is a `User` row and the permission checks
/// are the same - but the handful that must not be reachable by a bot need to be
/// able to tell. See [`deny_bots`].
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum CallerKind {
    User,
    Bot,
}

/// Authenticated caller, extracted from the `Authorization` header.
///
/// Two schemes: `Bearer <jwt>` for a signed-in person, `Bot <token>` for a bot.
/// The scheme is explicit rather than sniffed from the token's shape - guessing
/// would mean a malformed JWT could be probed against the bot-token table, and
/// SDK authors expect to state which credential they are presenting.
pub struct AuthUser {
    pub user_id: String,
    pub kind: CallerKind,
}

impl AuthUser {
    pub fn is_bot(&self) -> bool {
        self.kind == CallerKind::Bot
    }
}

#[axum::async_trait]
impl FromRequestParts<AppState> for AuthUser {
    type Rejection = AppError;

    async fn from_request_parts(
        parts: &mut Parts,
        state: &AppState,
    ) -> Result<Self, Self::Rejection> {
        let header = parts
            .headers
            .get(axum::http::header::AUTHORIZATION)
            .and_then(|v| v.to_str().ok())
            .ok_or_else(|| AppError::Unauthorized("Missing access token".into()))?;

        if let Some(token) = header.strip_prefix("Bot ") {
            let bot_id = crate::services::bot::authenticate(state, token)
                .await?
                .ok_or_else(|| AppError::Unauthorized("Invalid bot token".into()))?;
            return Ok(AuthUser {
                user_id: bot_id,
                kind: CallerKind::Bot,
            });
        }

        let token = header
            .strip_prefix("Bearer ")
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
            kind: CallerKind::User,
        })
    }
}

/// Refuses bot tokens outright, for the route groups that belong to a person.
///
/// Bots authenticate as `User` rows, which is what makes them work everywhere
/// without touching a single existing handler - and is exactly why this exists.
/// Without it, minting a bot token would also hand out account deletion, session
/// management, E2EE key material and the owner's linked accounts. Applied per
/// nest as a default-deny list, so the dangerous surface is enumerated in one
/// readable place rather than opted into route by route.
///
/// This inspects the header rather than extracting `AuthUser`, and that is not
/// an optimisation. These nests include the routes that *establish* a session -
/// login, signup, refresh, email verification, the OAuth and QR callbacks - all
/// of which are reached with no credential at all. Demanding a valid `AuthUser`
/// here would 401 every one of them and lock the whole site out. A bot token is
/// only ever presented as the `Bot` scheme, so refusing that scheme is both
/// sufficient and safe for anonymous callers.
pub async fn deny_bots(req: Request, next: Next) -> Result<Response, AppError> {
    let presents_bot_token = req
        .headers()
        .get(axum::http::header::AUTHORIZATION)
        .and_then(|v| v.to_str().ok())
        .is_some_and(|h| h.starts_with("Bot "));

    if presents_bot_token {
        return Err(AppError::Permission("Bots cannot use this endpoint".into()));
    }
    Ok(next.run(req).await)
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
/// Refuses any client build below its platform's `min_supported`.
///
/// This is the whole point of deciding severity on the server: a retired build
/// is one whose own update prompt may be exactly what is broken, so the refusal
/// has to happen where the client cannot route around it. Everything reachable
/// through /api is covered; `/updates/policy` is excluded by the caller so a
/// walled client can still discover what to install.
pub async fn require_supported_client(
    State(state): State<AppState>,
    headers: axum::http::HeaderMap,
    req: Request,
    next: Next,
) -> Result<Response, AppError> {
    let header = |name: &str| {
        headers
            .get(name)
            .and_then(|v| v.to_str().ok())
            .unwrap_or_default()
    };
    let platform = header(updates::PLATFORM_HEADER);
    let version = header(updates::VERSION_HEADER);

    if let Some(policy) = state.config.update_policy.for_platform(platform) {
        if policy.severity_for(version) == update_policy::Severity::Required {
            return Err(AppError::UpgradeRequired {
                message: "This version of OrangChat is no longer supported. Please update."
                    .into(),
                latest: policy.latest.clone(),
            });
        }
    }
    Ok(next.run(req).await)
}

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
    // Route groups that belong to a person and must never accept a bot token:
    // credentials and sessions, E2EE key material, the owner's linked accounts,
    // their drafts and push endpoints, and the friend graph. A bot authenticates
    // as a User row, so this has to be refused explicitly.
    let humans_only = Router::new()
        .nest("/auth", auth::routes())
        .nest("/security", security::routes())
        .merge(connections::routes())
        .merge(drafts::routes())
        .merge(e2ee::routes())
        .merge(friends::routes())
        .merge(push::routes())
        .route_layer(axum::middleware::from_fn(deny_bots));

    let api = Router::new()
        .route(
            "/",
            get(|| async { Json(json!({ "name": "orangchat-api", "version": "0.0.0" })) }),
        )
        .merge(humans_only)
        .merge(bots::routes())
        .merge(servers::routes())
        .merge(channels::routes())
        .merge(dms::routes())
        .merge(emojis::routes())
        .merge(events::routes())
        .merge(link_previews::routes())
        .merge(media_proxy::routes())
        .merge(roles::routes())
        .merge(reports::routes())
        .merge(sounds::routes())
        .merge(uploads::routes())
        .merge(attachments::routes())
        // The version wall covers everything merged above it. `route_layer`
        // rather than `layer` so it only runs for routes that actually matched,
        // and so /updates/policy - merged after it - stays reachable: a client
        // that was just refused still has to be able to ask what to upgrade to.
        .route_layer(axum::middleware::from_fn_with_state(
            state.clone(),
            require_supported_client,
        ))
        .merge(updates::routes())
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
mod deny_bots_tests {
    use super::*;
    use axum::body::Body;
    use axum::http::{Request as HttpRequest, StatusCode};
    use tower::ServiceExt;

    fn guarded_route() -> Router {
        Router::new()
            .route("/login", axum::routing::post(|| async { "ok" }))
            .route_layer(axum::middleware::from_fn(deny_bots))
    }

    async fn status_with_auth(header: Option<&str>) -> StatusCode {
        let mut req = HttpRequest::builder().method("POST").uri("/login");
        if let Some(value) = header {
            req = req.header(axum::http::header::AUTHORIZATION, value);
        }
        guarded_route()
            .oneshot(req.body(Body::empty()).unwrap())
            .await
            .unwrap()
            .status()
    }

    /// The regression this guard nearly shipped: these nests contain the routes
    /// that *establish* a session, so an anonymous caller must sail through.
    /// An earlier version extracted `AuthUser` here and would have 401'd every
    /// login and signup on the site.
    #[tokio::test]
    async fn an_anonymous_caller_is_not_challenged() {
        assert_eq!(status_with_auth(None).await, StatusCode::OK);
    }

    #[tokio::test]
    async fn a_signed_in_person_passes_through() {
        assert_eq!(
            status_with_auth(Some("Bearer some.jwt.value")).await,
            StatusCode::OK
        );
    }

    #[tokio::test]
    async fn a_bot_token_is_refused() {
        assert_eq!(
            status_with_auth(Some("Bot abc.def")).await,
            StatusCode::FORBIDDEN
        );
    }
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
