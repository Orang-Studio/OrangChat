
use axum::extract::{Path, State};
use axum::http::StatusCode;
use axum::response::IntoResponse;
use axum::routing::{delete, get, post};
use axum::{Json, Router};
use serde_json::{json, Value};

use crate::dto::{to_friend, to_friend_request, to_user, FriendDto};
use crate::error::{AppError, AppResult};
use crate::http::{bad_request, AuthUser};
use crate::models::{FriendJoinRow, UserRow};
use crate::services::{friends, presence, rate_limit};
use crate::state::AppState;

pub fn routes() -> Router<AppState> {
    Router::new()
        .route("/friends", get(list_friends))
        .route("/friends/requests", get(list_requests).post(send_request))
        .route("/friends/requests/:id/accept", post(accept_request))
        .route("/friends/requests/:id", delete(delete_request))
        .route("/friends/:userId", delete(remove_friend))
}

async fn fetch_user(state: &AppState, user_id: &str) -> AppResult<UserRow> {
    sqlx::query_as(r#"SELECT * FROM "User" WHERE id = $1"#)
        .bind(user_id)
        .fetch_one(&state.pool)
        .await
        .map_err(Into::into)
}

async fn friend_dto_of_me(
    state: &AppState,
    me: &str,
    friendship_id: &str,
    created_at: &str,
) -> AppResult<FriendDto> {
    let user = fetch_user(state, me).await?;
    Ok(FriendDto {
        id: friendship_id.to_string(),
        user: to_user(&user),
        created_at: created_at.to_string(),
    })
}

async fn with_presence(state: &AppState, mut dto: FriendDto) -> FriendDto {
    if let Ok(status) = presence::get_status(state, &dto.user.id).await {
        dto.user.status = status;
    }
    if let Ok(devices) = presence::get_devices(state, &dto.user.id).await {
        dto.user.devices = devices;
    }
    if let Ok(activities) = presence::get_activities(state, &dto.user.id).await {
        dto.user.activities = activities;
    }
    dto
}

async fn list_friends(State(state): State<AppState>, user: AuthUser) -> AppResult<Json<Value>> {
    let rows = friends::list_friends(&state, &user.user_id).await?;
    let ids: Vec<String> = rows.iter().map(|r| r.user.id.clone()).collect();
    let statuses = presence::get_statuses(&state, &ids).await?;
    let devices = presence::get_device_sets(&state, &ids).await?;
    let activities = presence::get_activity_sets(&state, &ids).await?;
    let out: Vec<FriendDto> = rows
        .iter()
        .map(|r| {
            let mut dto = to_friend(r);
            if let Some(s) = statuses.get(&dto.user.id) {
                dto.user.status = s.clone();
            }
            if let Some(kinds) = devices.get(&dto.user.id) {
                dto.user.devices = kinds.clone();
            }
            if let Some(items) = activities.get(&dto.user.id) {
                dto.user.activities = items.clone();
            }
            dto
        })
        .collect();
    Ok(Json(json!(out)))
}

async fn list_requests(State(state): State<AppState>, user: AuthUser) -> AppResult<Json<Value>> {
    let incoming: Vec<FriendJoinRow> = friends::list_incoming(&state, &user.user_id).await?;
    let outgoing: Vec<FriendJoinRow> = friends::list_outgoing(&state, &user.user_id).await?;
    Ok(Json(json!({
        "incoming": incoming.iter().map(|r| to_friend_request(r, "incoming")).collect::<Vec<_>>(),
        "outgoing": outgoing.iter().map(|r| to_friend_request(r, "outgoing")).collect::<Vec<_>>(),
    })))
}

async fn send_request(
    State(state): State<AppState>,
    user: AuthUser,
    Json(body): Json<Value>,
) -> AppResult<impl IntoResponse> {
    rate_limit::check(
        &state,
        "friend:request",
        &user.user_id,
        rate_limit::FRIEND_REQUEST_PER_USER,
    )
    .await?;

    let by_id = match body.get("userId").and_then(Value::as_str) {
        Some(id) if !id.trim().is_empty() => {
            let name: Option<String> =
                sqlx::query_scalar(r#"SELECT username FROM "User" WHERE id = $1"#)
                    .bind(id.trim())
                    .fetch_optional(&state.pool)
                    .await?;
            Some(name.ok_or_else(|| AppError::NotFound("No such user".into()))?)
        }
        _ => None,
    };

    let username = match by_id.as_deref() {
        Some(name) => name,
        None => body
            .get("username")
            .and_then(Value::as_str)
            .map(str::trim)
            .filter(|s| !s.is_empty())
            .ok_or_else(|| bad_request("Username is required"))?,
    };

    let outcome = friends::send_request(&state, &user.user_id, username).await?;

    if outcome.accepted {
        let created = outcome.row.friendship_created_at;
        let created_iso = crate::timefmt::iso(created);
        let to_target = friend_dto_of_me(
            &state,
            &user.user_id,
            &outcome.row.friendship_id,
            &created_iso,
        )
        .await?;
        let _ = state
            .io()
            .to(format!("user:{}", outcome.target.id))
            .emit("friend:accepted", &with_presence(&state, to_target).await);
        let friend = with_presence(&state, to_friend(&outcome.row)).await;
        Ok((
            StatusCode::OK,
            Json(json!({ "accepted": true, "friend": friend })),
        ))
    } else {
        let incoming = to_friend_request(
            &FriendJoinRow {
                friendship_id: outcome.row.friendship_id.clone(),
                friendship_created_at: outcome.row.friendship_created_at,
                status: "pending".into(),
                user: fetch_user(&state, &user.user_id).await?,
            },
            "incoming",
        );
        let _ = state
            .io()
            .to(format!("user:{}", outcome.target.id))
            .emit("friend:request", &incoming);
        let request = to_friend_request(&outcome.row, "outgoing");
        Ok((
            StatusCode::CREATED,
            Json(json!({ "accepted": false, "request": request })),
        ))
    }
}

async fn accept_request(
    State(state): State<AppState>,
    user: AuthUser,
    Path(id): Path<String>,
) -> AppResult<Json<Value>> {
    let (row, requester_id) = friends::accept_request(&state, &user.user_id, &id).await?;
    let created_iso = crate::timefmt::iso(row.friendship_created_at);
    let to_requester =
        friend_dto_of_me(&state, &user.user_id, &row.friendship_id, &created_iso).await?;
    let _ = state.io().to(format!("user:{requester_id}")).emit(
        "friend:accepted",
        &with_presence(&state, to_requester).await,
    );
    let friend = with_presence(&state, to_friend(&row)).await;
    Ok(Json(json!(friend)))
}

async fn delete_request(
    State(state): State<AppState>,
    user: AuthUser,
    Path(id): Path<String>,
) -> AppResult<impl IntoResponse> {
    let other_id = friends::delete_request(&state, &user.user_id, &id).await?;
    let _ = state
        .io()
        .to(format!("user:{other_id}"))
        .emit("friend:request:removed", &json!({ "id": id }));
    Ok(StatusCode::NO_CONTENT)
}

async fn remove_friend(
    State(state): State<AppState>,
    user: AuthUser,
    Path(other_id): Path<String>,
) -> AppResult<impl IntoResponse> {
    if other_id == user.user_id {
        return Err(AppError::BadRequest("Invalid target".into()));
    }
    friends::remove_friend(&state, &user.user_id, &other_id).await?;
    let _ = state
        .io()
        .to(format!("user:{other_id}"))
        .emit("friend:removed", &json!({ "userId": user.user_id }));
    Ok(StatusCode::NO_CONTENT)
}
