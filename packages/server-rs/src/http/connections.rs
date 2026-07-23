//! Profile connection routes, mounted under /api.
//!
//! The link flow is deliberately two-legged. `/authorize` is a normal
//! authenticated XHR that *returns* a URL rather than redirecting, because the
//! browser would drop the Authorization header on a redirect. The client then
//! navigates to that URL itself. The callback comes back unauthenticated and
//! recovers the user from the Redis-bound state token (see services/connection).

use std::collections::HashMap;

use axum::extract::{Path, Query, State};
use axum::response::{IntoResponse, Redirect};
use axum::routing::{delete, get, post};
use axum::{Json, Router};
use serde_json::{json, Value};

use crate::connections;
use crate::dto::to_connection;
use crate::error::{AppError, AppResult};
use crate::http::{bad_request, AuthUser};
use crate::services::connection;
use crate::state::AppState;

pub fn routes() -> Router<AppState> {
    Router::new()
        .route("/connections", get(list_mine))
        .route("/connections/providers", get(list_providers))
        .route("/connections/custom", post(add_custom))
        .route("/connections/:provider/authorize", post(authorize))
        .route("/connections/:provider/callback", get(callback))
        .route("/connections/:id", delete(remove).patch(patch))
        .route("/users/:userId/connections", get(list_for_user))
}

/// Which providers this deployment can actually offer - an unconfigured one is
/// hidden rather than shown as a button that 500s.
async fn list_providers(State(state): State<AppState>, _user: AuthUser) -> Json<Value> {
    let items: Vec<Value> = connections::PROVIDERS
        .iter()
        .filter(|p| connections::is_configured(&state.config, p.key))
        .map(|p| json!({ "key": p.key, "label": p.label }))
        .collect();
    Json(json!({ "items": items }))
}

async fn list_mine(State(state): State<AppState>, user: AuthUser) -> AppResult<Json<Value>> {
    let rows = connection::list_for_user(&state, &user.user_id).await?;
    let items: Vec<_> = rows.iter().map(to_connection).collect();
    Ok(Json(json!({ "items": items })))
}

/// Someone else's card: visible rows only. Requires auth but no relationship -
/// a profile card is already reachable by anyone who shares a server or DM.
async fn list_for_user(
    State(state): State<AppState>,
    Path(user_id): Path<String>,
    _viewer: AuthUser,
) -> AppResult<Json<Value>> {
    let rows = connection::list_visible(&state, &user_id).await?;
    let items: Vec<_> = rows.iter().map(to_connection).collect();
    Ok(Json(json!({ "items": items })))
}

async fn authorize(
    State(state): State<AppState>,
    Path(provider_key): Path<String>,
    user: AuthUser,
) -> AppResult<Json<Value>> {
    let provider = connections::find(&provider_key)
        .ok_or_else(|| AppError::NotFound("Unknown provider".into()))?;
    if !connections::is_configured(&state.config, provider.key) {
        return Err(AppError::Internal(format!(
            "{} connections are not configured on this server",
            provider.label
        )));
    }

    let (token, verifier) = connection::begin_link(&state, &user.user_id, provider.key).await?;
    let url = if provider.key == "steam" {
        connections::steam_authorization_url(&state.config, &token)
    } else {
        connections::authorization_url(&state.config, provider, &token, &verifier)
    };
    Ok(Json(json!({ "url": url })))
}

/// Terminal leg of the link. Always redirects back into the settings UI - the
/// user is looking at a browser tab, so an error belongs in the page, not in a
/// JSON body they'd see as raw text.
async fn callback(
    State(state): State<AppState>,
    Path(provider_key): Path<String>,
    Query(params): Query<HashMap<String, String>>,
) -> impl IntoResponse {
    let origin = &state.config.client_origin;
    // Settings is a dialog, not a route - land on the app root and let the
    // client reopen settings on the Connections section from this query.
    let back = |q: &str| Redirect::to(&format!("{origin}/?{q}"));

    // The user pressed cancel/deny on the remote consent screen.
    if params.contains_key("error") {
        return back("connection=cancelled");
    }

    let result = if provider_key == "steam" {
        connection::complete_steam(&state, &params).await
    } else {
        match (params.get("code"), params.get("state")) {
            (Some(code), Some(token)) => {
                connection::complete_oauth(&state, &provider_key, code, token).await
            }
            _ => Err(AppError::BadRequest("Missing code or state".into())),
        }
    };

    match result {
        Ok(row) => back(&format!("connection=linked&provider={}", row.provider)),
        Err(e) => {
            tracing::warn!("connection link failed ({provider_key}): {}", e.message());
            back("connection=failed")
        }
    }
}

async fn add_custom(
    State(state): State<AppState>,
    user: AuthUser,
    Json(body): Json<Value>,
) -> AppResult<Json<Value>> {
    let name = body
        .get("name")
        .and_then(Value::as_str)
        .unwrap_or_default()
        .trim();
    let url = body
        .get("url")
        .and_then(Value::as_str)
        .unwrap_or_default()
        .trim();
    if name.is_empty() || name.len() > 40 || url.len() > 500 {
        return Err(bad_request("Invalid input"));
    }
    // Scheme allowlist: these URLs become hrefs on a public profile, so
    // javascript:/data: must never make it into storage.
    if !(url.starts_with("https://") || url.starts_with("http://")) {
        return Err(bad_request("Link must start with https://"));
    }
    let row = connection::add_custom(&state, &user.user_id, name, url).await?;
    Ok(Json(json!(to_connection(&row))))
}

async fn patch(
    State(state): State<AppState>,
    Path(id): Path<String>,
    user: AuthUser,
    Json(body): Json<Value>,
) -> AppResult<Json<Value>> {
    let visible = body
        .get("visible")
        .and_then(Value::as_bool)
        .ok_or_else(|| bad_request("Invalid input"))?;
    let row = connection::set_visible(&state, &user.user_id, &id, visible).await?;
    Ok(Json(json!(to_connection(&row))))
}

async fn remove(
    State(state): State<AppState>,
    Path(id): Path<String>,
    user: AuthUser,
) -> AppResult<Json<Value>> {
    let provider = connection::remove(&state, &user.user_id, &id).await?;
    if provider == "spotify"
        && crate::services::presence::set_activity(&state, &user.user_id, "spotify", None).await?
    {
        let status = crate::services::presence::get_status(&state, &user.user_id).await?;
        crate::socket::broadcast_presence(state.io(), &state, &user.user_id, &status).await;
    }
    Ok(Json(json!({ "ok": true })))
}
