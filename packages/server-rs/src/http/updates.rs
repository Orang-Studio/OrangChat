//! The client-update endpoint, and the headers every client identifies itself
//! with.
//!
//! Clients send their build on each request:
//!
//! ```text
//! X-Client-Platform: android | desktop
//! X-Client-Version:  47 | 0.1.5
//! ```
//!
//! Both are advisory for the policy lookup below, and load-bearing for
//! `require_supported_client` in http.rs, which is what actually turns
//! `required` into a refusal.

use axum::extract::{Query, State};
use axum::routing::get;
use axum::{Json, Router};
use serde_json::{json, Value};
use std::collections::HashMap;

use crate::error::AppResult;
use crate::services::update_policy::Severity;
use crate::state::AppState;

pub const PLATFORM_HEADER: &str = "x-client-platform";
pub const VERSION_HEADER: &str = "x-client-version";

pub fn routes() -> Router<AppState> {
    Router::new().route("/updates/policy", get(policy))
}

/// What a client of `platform` running `version` should do about updating.
///
/// Deliberately unauthenticated and exempt from the version wall: a client that
/// has just been refused with a 426 still has to be able to find out what to
/// upgrade to, and asking it to hold a valid session first would strand anyone
/// whose token expired while their build was being retired.
async fn policy(
    State(state): State<AppState>,
    Query(q): Query<HashMap<String, String>>,
) -> AppResult<Json<Value>> {
    let platform = q.get("platform").map(String::as_str).unwrap_or_default();
    let version = q.get("version").map(String::as_str).unwrap_or_default();

    let Some(policy) = state.config.update_policy.for_platform(platform) else {
        // An unknown platform (or the web client, which is always whatever this
        // server just served) has nothing to update to.
        return Ok(Json(json!({ "severity": Severity::None })));
    };

    Ok(Json(json!({
        "severity": policy.severity_for(version),
        "latest": policy.latest,
        "minSupported": policy.min_supported,
    })))
}
