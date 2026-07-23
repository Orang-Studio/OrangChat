//! Draft REST, mounted under /api. Requires auth.

use axum::extract::{Path, State};
use axum::routing::get;
use axum::{Json, Router};
use serde_json::{json, Value};

use crate::error::AppResult;
use crate::http::{bad_request, AuthUser};
use crate::services::draft;
use crate::state::AppState;

pub fn routes() -> Router<AppState> {
    Router::new()
        .route("/me/drafts", get(list_drafts))
        .route(
            "/channels/:channelId/draft",
            get(get_draft).put(put_draft).delete(delete_draft),
        )
}

async fn list_drafts(State(state): State<AppState>, user: AuthUser) -> AppResult<Json<Value>> {
    let drafts = draft::list(&state, &user.user_id).await?;
    let items: Vec<Value> = drafts
        .into_iter()
        .map(|(channel_id, content)| json!({ "channelId": channel_id, "content": content }))
        .collect();
    Ok(Json(json!({ "drafts": items })))
}

async fn get_draft(
    State(state): State<AppState>,
    user: AuthUser,
    Path(channel_id): Path<String>,
) -> AppResult<Json<Value>> {
    let content = draft::get(&state, &user.user_id, &channel_id).await?;
    Ok(Json(json!({ "content": content })))
}

#[derive(serde::Deserialize)]
struct DraftBody {
    content: String,
}

async fn put_draft(
    State(state): State<AppState>,
    user: AuthUser,
    Path(channel_id): Path<String>,
    Json(body): Json<DraftBody>,
) -> AppResult<Json<Value>> {
    if body.content.chars().count() > draft::MAX_DRAFT_LEN {
        return Err(bad_request("Draft is too long"));
    }
    // An empty draft is a cleared draft, not a stored blank.
    if body.content.trim().is_empty() {
        draft::clear(&state, &user.user_id, &channel_id).await?;
    } else {
        draft::set(&state, &user.user_id, &channel_id, &body.content).await?;
    }
    Ok(Json(json!({ "ok": true })))
}

async fn delete_draft(
    State(state): State<AppState>,
    user: AuthUser,
    Path(channel_id): Path<String>,
) -> AppResult<Json<Value>> {
    draft::clear(&state, &user.user_id, &channel_id).await?;
    Ok(Json(json!({ "ok": true })))
}
