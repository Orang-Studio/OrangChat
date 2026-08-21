use std::collections::HashMap;

use axum::extract::{Path, Query, State};
use axum::http::{HeaderMap, StatusCode};
use axum::response::IntoResponse;
use axum::routing::{delete, get, post};
use axum::{Json, Router};
use chrono::NaiveDateTime;
use serde_json::{json, Value};

use crate::dto::to_user;
use crate::error::{AppError, AppResult};
use crate::http::AuthUser;
use crate::models::UserRow;
use crate::services::{profile_widget, rate_limit, user};
use crate::state::AppState;
use crate::timefmt::{iso, iso_opt};

pub fn routes() -> Router<AppState> {
    Router::new()
        .route("/profile/widgets/catalog", get(catalog))
        .route("/profile/fields", post(push_fields))
        .route("/me/field-tokens", get(list_tokens).post(mint_token))
        .route("/me/field-tokens/{tokenId}", delete(revoke_token))
}

async fn catalog(
    State(state): State<AppState>,
    _user: AuthUser,
    Query(q): Query<HashMap<String, String>>,
) -> Result<axum::response::Response, AppError> {
    let rev = state.widgets.rev();
    if q.get("rev").map(String::as_str) == Some(rev) {
        return Ok(StatusCode::NOT_MODIFIED.into_response());
    }
    Ok(Json(json!({
        "rev": rev,
        "widgets": state.widgets.widgets(),
        "defaultLayout": state.widgets.default_layout(),
    }))
    .into_response())
}

type TokenRow = (String, String, String, NaiveDateTime, Option<NaiveDateTime>);

fn token_json(row: &TokenRow) -> Value {
    json!({
        "id": row.0,
        "label": row.1,
        "hint": row.2,
        "createdAt": iso(row.3),
        "lastUsedAt": iso_opt(row.4),
    })
}

async fn list_tokens(State(state): State<AppState>, user: AuthUser) -> AppResult<Json<Value>> {
    let rows: Vec<TokenRow> = sqlx::query_as(
        r#"SELECT id, label, hint, "createdAt", "lastUsedAt"
             FROM "ProfileFieldToken"
            WHERE "userId" = $1
            ORDER BY "createdAt" DESC"#,
    )
    .bind(&user.user_id)
    .fetch_all(&state.pool)
    .await?;
    Ok(Json(json!(rows.iter().map(token_json).collect::<Vec<_>>())))
}

async fn mint_token(
    State(state): State<AppState>,
    user: AuthUser,
    Json(body): Json<Value>,
) -> AppResult<Json<Value>> {
    rate_limit::check(
        &state,
        "profile:token",
        &user.user_id,
        rate_limit::PROFILE_TOKEN_MINT_PER_USER,
    )
    .await?;

    let label = body.get("label").and_then(Value::as_str).unwrap_or("Token");
    let (id, token, hint) = profile_widget::mint_token(&state, &user.user_id, label).await?;
    Ok(Json(json!({ "id": id, "token": token, "hint": hint })))
}

async fn revoke_token(
    State(state): State<AppState>,
    user: AuthUser,
    Path(token_id): Path<String>,
) -> AppResult<StatusCode> {
    profile_widget::revoke_token(&state, &user.user_id, &token_id).await?;
    Ok(StatusCode::NO_CONTENT)
}

fn bearer_widget_token(headers: &HeaderMap) -> Option<&str> {
    let raw = headers.get(axum::http::header::AUTHORIZATION)?.to_str().ok()?;
    raw.strip_prefix("Widget ").map(str::trim).filter(|t| !t.is_empty())
}

async fn push_fields(
    State(state): State<AppState>,
    headers: HeaderMap,
    Json(body): Json<Value>,
) -> AppResult<Json<Value>> {
    let token = bearer_widget_token(&headers).ok_or_else(|| {
        AppError::Unauthorized("Send Authorization: Widget <token>".into())
    })?;
    let user_id = profile_widget::authenticate(&state, token).await?;

    rate_limit::check(
        &state,
        "profile:fields",
        &user_id,
        rate_limit::PROFILE_FIELD_PUSH_PER_USER,
    )
    .await?;

    let incoming = match (body.get("fields"), body.get("field")) {
        (Some(Value::Object(map)), _) => map.clone(),
        (_, Some(Value::String(field))) => {
            let value = body.get("value").cloned().unwrap_or(Value::Null);
            let mut map = serde_json::Map::new();
            map.insert(field.clone(), value);
            map
        }
        _ => {
            return Err(AppError::BadRequest(
                "send either {\"field\":\"name\",\"value\":…} or {\"fields\":{…}}".into(),
            ))
        }
    };
    if incoming.is_empty() {
        return Err(AppError::BadRequest("no fields to update".into()));
    }

    let existing: Value =
        sqlx::query_scalar(r#"SELECT "profileFields" FROM "User" WHERE id = $1"#)
            .bind(&user_id)
            .fetch_optional(&state.pool)
            .await?
            .ok_or_else(|| AppError::NotFound("User not found".into()))?;

    let merged = profile_widget::merge_fields(&existing, &incoming)?;

    let updated: UserRow = sqlx::query_as(
        r#"UPDATE "User" SET "profileFields" = $1, "updatedAt" = now()
            WHERE id = $2 RETURNING *"#,
    )
    .bind(&merged)
    .bind(&user_id)
    .fetch_one(&state.pool)
    .await?;

    let rooms = user::get_profile_audience_rooms(&state, &user_id).await?;
    let public = to_user(&updated);
    for room in rooms {
        let _ = state.io().to(room).emit("user:updated", &public);
    }

    Ok(Json(json!({ "fields": merged })))
}
