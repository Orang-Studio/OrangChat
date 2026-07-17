//! Custom emoji REST, mounted under /api. No TS equivalent — new for the Rust port.

use axum::extract::{DefaultBodyLimit, Multipart, Path, State};
use axum::http::StatusCode;
use axum::response::IntoResponse;
use axum::routing::{get, patch};
use axum::{Json, Router};
use serde::Deserialize;

use crate::dto::{to_emoji, EmojiDto};
use crate::error::{AppError, AppResult};
use crate::http::uploads::{process_image, store_image};
use crate::http::AuthUser;
use crate::permissions::MANAGE_EXPRESSIONS;
use crate::services::{emoji, membership, rate_limit};
use crate::state::AppState;

/// The upload cap users are told about. Emoji are re-encoded to 128px anyway, so
/// this only bounds what has to be read and decoded before that happens.
const MAX_EMOJI_UPLOAD: usize = 256 * 1024;

pub fn routes() -> Router<AppState> {
    Router::new()
        .route("/emojis", get(list_usable))
        .route("/servers/:serverId/emojis", get(list).post(create))
        .route(
            "/servers/:serverId/emojis/:emojiId",
            patch(rename).delete(remove),
        )
        .layer(DefaultBodyLimit::max(MAX_EMOJI_UPLOAD + 64 * 1024))
}

/// Every emoji the caller can type, across all their servers.
async fn list_usable(user: AuthUser, State(state): State<AppState>) -> AppResult<Json<Vec<EmojiDto>>> {
    let rows = emoji::list_usable_emojis(&state, &user.user_id).await?;
    Ok(Json(rows.iter().map(to_emoji).collect()))
}

async fn list(
    user: AuthUser,
    Path(server_id): Path<String>,
    State(state): State<AppState>,
) -> AppResult<Json<Vec<EmojiDto>>> {
    // Membership alone is the read gate: anyone who can type an emoji here can
    // see the list of them.
    membership::require_permission(&state, &server_id, &user.user_id, 0).await?;
    let rows = emoji::list_emojis(&state, &server_id).await?;
    Ok(Json(rows.iter().map(to_emoji).collect()))
}

async fn create(
    user: AuthUser,
    Path(server_id): Path<String>,
    State(state): State<AppState>,
    mut multipart: Multipart,
) -> AppResult<(StatusCode, Json<EmojiDto>)> {
    membership::require_permission(&state, &server_id, &user.user_id, MANAGE_EXPRESSIONS).await?;
    rate_limit::check(
        &state,
        "upload:image",
        &user.user_id,
        rate_limit::UPLOAD_IMAGE_PER_USER,
    )
    .await?;

    let mut bytes: Option<Vec<u8>> = None;
    let mut name: Option<String> = None;
    while let Some(field) = multipart
        .next_field()
        .await
        .map_err(|_| AppError::BadRequest("Invalid upload".into()))?
    {
        match field.name() {
            Some("file") => {
                let data = field
                    .bytes()
                    .await
                    .map_err(|_| AppError::BadRequest("Emoji is too large (max 256 kB)".into()))?;
                if data.len() > MAX_EMOJI_UPLOAD {
                    return Err(AppError::BadRequest("Emoji is too large (max 256 kB)".into()));
                }
                bytes = Some(data.to_vec());
            }
            Some("name") => {
                name = field.text().await.ok();
            }
            _ => {}
        }
    }

    let bytes = bytes.ok_or_else(|| AppError::BadRequest("No file provided".into()))?;
    let name = name.ok_or_else(|| AppError::BadRequest("No name provided".into()))?;
    // Reject a bad name before spending CPU on the image.
    let name = emoji::normalize_name(&name)?;

    // Image decode/encode is CPU-bound and blocking — keep it off the runtime.
    let (out, ext) = tokio::task::spawn_blocking(move || process_image(&bytes, "emoji"))
        .await
        .map_err(|_| AppError::Internal("Image processing failed".into()))??;

    // The re-encode is what decides this, not the upload's filename: a .gif that
    // turned out to hold a single frame is a static emoji.
    let animated = ext == "gif";
    let url = store_image(&state, out, ext).await?;

    let row = emoji::create_emoji(&state, &server_id, &user.user_id, &name, &url, animated).await?;
    Ok((StatusCode::CREATED, Json(to_emoji(&row))))
}

#[derive(Deserialize)]
struct RenamePayload {
    name: String,
}

async fn rename(
    user: AuthUser,
    Path((server_id, emoji_id)): Path<(String, String)>,
    State(state): State<AppState>,
    Json(payload): Json<RenamePayload>,
) -> AppResult<Json<EmojiDto>> {
    membership::require_permission(&state, &server_id, &user.user_id, MANAGE_EXPRESSIONS).await?;
    require_in_server(&state, &emoji_id, &server_id).await?;
    let row = emoji::rename_emoji(&state, &emoji_id, &payload.name).await?;
    Ok(Json(to_emoji(&row)))
}

async fn remove(
    user: AuthUser,
    Path((server_id, emoji_id)): Path<(String, String)>,
    State(state): State<AppState>,
) -> AppResult<impl IntoResponse> {
    membership::require_permission(&state, &server_id, &user.user_id, MANAGE_EXPRESSIONS).await?;
    require_in_server(&state, &emoji_id, &server_id).await?;
    emoji::delete_emoji(&state, &emoji_id).await?;
    Ok(StatusCode::NO_CONTENT)
}

/// The permission check is scoped to the server in the path, so the emoji has to
/// actually live there — otherwise MANAGE_EXPRESSIONS on any one server would be
/// MANAGE_EXPRESSIONS on all of them.
async fn require_in_server(state: &AppState, emoji_id: &str, server_id: &str) -> AppResult<()> {
    let row = emoji::get_emoji(state, emoji_id).await?;
    if row.server_id != server_id {
        return Err(AppError::NotFound("Emoji not found".into()));
    }
    Ok(())
}
