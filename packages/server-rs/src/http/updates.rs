
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

async fn policy(
    State(state): State<AppState>,
    Query(q): Query<HashMap<String, String>>,
) -> AppResult<Json<Value>> {
    let platform = q.get("platform").map(String::as_str).unwrap_or_default();
    let version = q.get("version").map(String::as_str).unwrap_or_default();

    let Some(policy) = state.config.update_policy.for_platform(platform) else {
        return Ok(Json(json!({ "severity": Severity::None })));
    };

    Ok(Json(json!({
        "severity": policy.severity_for(version),
        "latest": policy.latest,
        "minSupported": policy.min_supported,
    })))
}
