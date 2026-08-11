
use axum::extract::{Path, State};
use axum::http::StatusCode;
use axum::response::IntoResponse;
use axum::routing::{delete, get, post};
use axum::{Json, Router};
use serde_json::{json, Value};

use crate::dto::{to_conversation, ConversationDto};
use crate::error::{AppError, AppResult};
use crate::http::{bad_request, AuthUser};
use crate::models::{ChannelRow, UserRow};
use crate::services::{dm, message, presence, rate_limit};
use crate::state::AppState;

pub fn routes() -> Router<AppState> {
    Router::new()
        .route("/dms", get(list_dms).post(create_dm))
        .route("/dms/:channelId/participants", post(add_participants))
        .route("/dms/:channelId", delete(leave_dm))
}

async fn leave_dm(
    State(state): State<AppState>,
    user: AuthUser,
    Path(channel_id): Path<String>,
) -> AppResult<Json<Value>> {
    let outcome = dm::leave_conversation(&state, &channel_id, &user.user_id).await?;

    match outcome {
        dm::LeaveOutcome::Left => {
            let _ = state
                .io()
                .to(format!("channel:{channel_id}"))
                .emit("dm:participantLeft", &json!({
                    "channelId": channel_id,
                    "userId": user.user_id,
                }));
            let _ = state
                .io()
                .to(format!("user:{}", user.user_id))
                .leave(format!("channel:{channel_id}"));
            Ok(Json(json!({ "status": "left" })))
        }
        dm::LeaveOutcome::Closed => Ok(Json(json!({ "status": "closed" }))),
    }
}

fn parse_user_ids(body: &Value) -> AppResult<Vec<String>> {
    let arr = body
        .get("userIds")
        .and_then(Value::as_array)
        .ok_or_else(|| bad_request("Invalid input"))?;
    if arr.is_empty() || arr.len() > 14 {
        return Err(bad_request("Invalid input"));
    }
    arr.iter()
        .map(|v| {
            v.as_str()
                .map(String::from)
                .ok_or_else(|| bad_request("Invalid input"))
        })
        .collect()
}

async fn load_conversation(
    state: &AppState,
    channel_id: &str,
    viewer_id: &str,
) -> AppResult<Option<Value>> {
    let ch: Option<ChannelRow> = sqlx::query_as(r#"SELECT * FROM "Channel" WHERE id = $1"#)
        .bind(channel_id)
        .fetch_optional(&state.pool)
        .await?;
    let Some(ch) = ch else { return Ok(None) };
    let users: Vec<UserRow> = sqlx::query_as(
        r#"SELECT u.* FROM "User" u
           JOIN "ChannelParticipant" p ON p."userId" = u.id
           WHERE p."channelId" = $1"#,
    )
    .bind(channel_id)
    .fetch_all(&state.pool)
    .await?;
    let latest = message::latest_for_channel(state, channel_id, viewer_id).await?;
    Ok(Some(json!(
        with_presence(state, to_conversation(&ch, &users, latest)).await?
    )))
}

async fn with_presence(
    state: &AppState,
    mut conversation: ConversationDto,
) -> AppResult<ConversationDto> {
    let ids: Vec<String> = conversation
        .participants
        .iter()
        .map(|user| user.id.clone())
        .collect();
    let statuses = presence::get_statuses(state, &ids).await?;
    let devices = presence::get_device_sets(state, &ids).await?;
    let activities = presence::get_activity_sets(state, &ids).await?;
    for user in &mut conversation.participants {
        user.status = statuses
            .get(&user.id)
            .cloned()
            .unwrap_or_else(|| "offline".into());
        user.devices = devices.get(&user.id).cloned().unwrap_or_default();
        user.activities = activities.get(&user.id).cloned().unwrap_or_default();
    }
    Ok(conversation)
}

async fn list_dms(State(state): State<AppState>, user: AuthUser) -> AppResult<Json<Value>> {
    let rows = dm::list_conversations(&state, &user.user_id).await?;
    let mut out = Vec::with_capacity(rows.len());
    for (channel, users) in &rows {
        let latest = message::latest_for_channel(&state, &channel.id, &user.user_id).await?;
        out.push(json!(
            with_presence(&state, to_conversation(channel, users, latest)).await?
        ));
    }
    Ok(Json(json!(out)))
}

async fn create_dm(
    State(state): State<AppState>,
    user: AuthUser,
    Json(body): Json<Value>,
) -> AppResult<impl IntoResponse> {
    rate_limit::check(
        &state,
        "dm:create",
        &user.user_id,
        rate_limit::DM_CREATE_PER_USER,
    )
    .await?;

    let user_ids = parse_user_ids(&body)?;
    let (id, created) = dm::get_or_create_direct_channel(&state, &user.user_id, &user_ids).await?;

    let conversation = load_conversation(&state, &id, &user.user_id)
        .await?
        .ok_or_else(|| AppError::Internal("Failed to load conversation".into()))?;

    for p in conversation["participants"].as_array().unwrap_or(&vec![]) {
        if let Some(pid) = p["id"].as_str() {
            let _ = state
                .io()
                .to(format!("user:{pid}"))
                .join(format!("channel:{id}"));
        }
    }

    let status = if created {
        StatusCode::CREATED
    } else {
        StatusCode::OK
    };
    Ok((status, Json(conversation)))
}

async fn add_participants(
    State(state): State<AppState>,
    user: AuthUser,
    Path(channel_id): Path<String>,
    Json(body): Json<Value>,
) -> AppResult<Json<Value>> {
    rate_limit::check(
        &state,
        "dm:create",
        &user.user_id,
        rate_limit::DM_CREATE_PER_USER,
    )
    .await?;

    let user_ids = parse_user_ids(&body)?;
    dm::add_group_participants(&state, &channel_id, &user.user_id, &user_ids).await?;

    let conversation = load_conversation(&state, &channel_id, &user.user_id)
        .await?
        .ok_or_else(|| AppError::NotFound("Conversation not found".into()))?;

    let participant_ids = dm::get_conversation_participants(&state, &channel_id).await?;
    for uid in participant_ids {
        let _ = state
            .io()
            .to(format!("user:{uid}"))
            .join(format!("channel:{channel_id}"));
    }
    Ok(Json(conversation))
}
