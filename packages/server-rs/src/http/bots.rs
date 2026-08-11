
use axum::extract::{Path, State};
use axum::routing::{get, post};
use axum::{Json, Router};
use serde_json::{json, Value};

use crate::error::{AppError, AppResult};
use crate::http::{bad_request, AuthUser};
use crate::services::bot;
use crate::state::AppState;

pub fn routes() -> Router<AppState> {
    Router::new()
        .route("/bot/me", get(bot_me))
        .route("/me/bots", get(list_bots).post(create_bot))
        .route(
            "/me/bots/:botId",
            get(get_bot).patch(patch_bot).delete(delete_bot),
        )
        .route("/me/bots/:botId/tokens", get(list_tokens).post(mint_token))
        .route("/me/bots/:botId/tokens/:tokenId", post(revoke_token_post).delete(revoke_token))
}

fn bot_json(b: &bot::BotRow) -> Value {
    json!({
        "id": b.id,
        "username": b.username,
        "displayName": b.display_name,
        "avatarUrl": b.avatar_url,
        "createdAt": b.created_at,
        "bot": true,
    })
}

async fn bot_me(State(state): State<AppState>, caller: AuthUser) -> AppResult<Json<Value>> {
    if !caller.is_bot() {
        return Err(AppError::Permission(
            "This endpoint is for bot tokens".into(),
        ));
    }
    let row: Option<crate::models::UserRow> =
        sqlx::query_as(r#"SELECT * FROM "User" WHERE id = $1"#)
            .bind(&caller.user_id)
            .fetch_optional(&state.pool)
            .await?;
    let row = row.ok_or_else(|| AppError::NotFound("Bot not found".into()))?;
    Ok(Json(json!(crate::dto::to_user(&row))))
}

async fn list_bots(State(state): State<AppState>, user: AuthUser) -> AppResult<Json<Value>> {
    let bots = bot::list(&state, &user.user_id).await?;
    let items: Vec<Value> = bots.iter().map(bot_json).collect();
    Ok(Json(json!({ "bots": items })))
}

#[derive(serde::Deserialize)]
#[serde(rename_all = "camelCase")]
struct CreateBotBody {
    username: String,
    display_name: Option<String>,
}

async fn create_bot(
    State(state): State<AppState>,
    user: AuthUser,
    Json(body): Json<CreateBotBody>,
) -> AppResult<Json<Value>> {
    let username = body.username.trim().to_lowercase();
    let display_name = body
        .display_name
        .as_deref()
        .map(str::trim)
        .filter(|s| !s.is_empty())
        .unwrap_or(&username)
        .to_string();

    let created = bot::create(&state, &user.user_id, &username, &display_name).await?;

    let token = bot::mint_token(&state, &user.user_id, &created.id).await?;

    Ok(Json(json!({
        "bot": bot_json(&created),
        "token": { "id": token.id, "token": token.token, "hint": token.hint },
    })))
}

async fn get_bot(
    State(state): State<AppState>,
    user: AuthUser,
    Path(bot_id): Path<String>,
) -> AppResult<Json<Value>> {
    let found = bot::get(&state, &user.user_id, &bot_id)
        .await?
        .ok_or_else(|| AppError::NotFound("Bot not found".into()))?;
    Ok(Json(bot_json(&found)))
}

async fn patch_bot(
    State(state): State<AppState>,
    user: AuthUser,
    Path(bot_id): Path<String>,
    Json(body): Json<Value>,
) -> AppResult<Json<Value>> {
    let obj = body.as_object().ok_or_else(|| bad_request("Invalid input"))?;

    let display_name = match obj.get("displayName") {
        Some(Value::String(s)) => Some(s.clone()),
        Some(_) => return Err(bad_request("Invalid input")),
        None => None,
    };

    let avatar_url = match obj.get("avatarUrl") {
        Some(Value::Null) => Some(None),
        Some(Value::String(s)) if s.trim().is_empty() => Some(None),
        Some(Value::String(s)) => Some(Some(s.clone())),
        Some(_) => return Err(bad_request("Invalid input")),
        None => None,
    };

    let updated = bot::update(
        &state,
        &user.user_id,
        &bot_id,
        display_name.as_deref(),
        avatar_url.as_ref().map(|o| o.as_deref()),
    )
    .await?;
    Ok(Json(bot_json(&updated)))
}

async fn delete_bot(
    State(state): State<AppState>,
    user: AuthUser,
    Path(bot_id): Path<String>,
) -> AppResult<Json<Value>> {
    bot::delete(&state, &user.user_id, &bot_id).await?;
    Ok(Json(json!({ "ok": true })))
}

async fn list_tokens(
    State(state): State<AppState>,
    user: AuthUser,
    Path(bot_id): Path<String>,
) -> AppResult<Json<Value>> {
    let tokens = bot::list_tokens(&state, &user.user_id, &bot_id).await?;
    let items: Vec<Value> = tokens
        .into_iter()
        .map(|t| {
            json!({
                "id": t.id,
                "hint": t.hint,
                "createdAt": t.created_at,
                "lastUsedAt": t.last_used_at,
            })
        })
        .collect();
    Ok(Json(json!({ "tokens": items })))
}

async fn mint_token(
    State(state): State<AppState>,
    user: AuthUser,
    Path(bot_id): Path<String>,
) -> AppResult<Json<Value>> {
    let minted = bot::mint_token(&state, &user.user_id, &bot_id).await?;
    Ok(Json(json!({
        "id": minted.id,
        "token": minted.token,
        "hint": minted.hint,
    })))
}

async fn revoke_token(
    State(state): State<AppState>,
    user: AuthUser,
    Path((bot_id, token_id)): Path<(String, String)>,
) -> AppResult<Json<Value>> {
    bot::revoke_token(&state, &user.user_id, &bot_id, &token_id).await?;
    Ok(Json(json!({ "ok": true })))
}

async fn revoke_token_post(
    state: State<AppState>,
    user: AuthUser,
    path: Path<(String, String)>,
) -> AppResult<Json<Value>> {
    revoke_token(state, user, path).await
}
