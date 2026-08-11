use axum::extract::{Path, State};
use axum::routing::post;
use axum::{Json, Router};
use serde::Deserialize;
use serde_json::{json, Value};

use crate::error::AppResult;
use crate::http::AuthUser;
use crate::services::{rate_limit, report};
use crate::state::AppState;

pub fn routes() -> Router<AppState> {
    Router::new().route("/messages/:messageId/report", post(report_message))
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct ReportBody {
    #[serde(default)]
    reason: Option<String>,
    #[serde(default)]
    message_key: Option<String>,
}

async fn report_message(
    State(state): State<AppState>,
    user: AuthUser,
    Path(message_id): Path<String>,
    Json(body): Json<ReportBody>,
) -> AppResult<Json<Value>> {
    rate_limit::check(
        &state,
        "message:report",
        &user.user_id,
        rate_limit::MESSAGE_REPORT_PER_USER,
    )
    .await?;
    let result = report::message(
        &state,
        &user.user_id,
        &message_id,
        body.reason.as_deref(),
        body.message_key.as_deref(),
    )
    .await?;
    Ok(Json(json!({
        "id": result.id,
        "status": if result.already_existed { "already_received" } else { "received" },
        "encrypted": result.encrypted,
    })))
}
