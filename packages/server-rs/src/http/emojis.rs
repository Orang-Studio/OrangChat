
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

async fn list_usable(
    user: AuthUser,
    State(state): State<AppState>,
) -> AppResult<Json<Vec<EmojiDto>>> {
    let rows = emoji::list_usable_emojis(&state, &user.user_id).await?;
    Ok(Json(rows.iter().map(to_emoji).collect()))
}

async fn list(
    user: AuthUser,
    Path(server_id): Path<String>,
    State(state): State<AppState>,
) -> AppResult<Json<Vec<EmojiDto>>> {
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
                    return Err(AppError::BadRequest(
                        "Emoji is too large (max 256 kB)".into(),
                    ));
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
    let name = emoji::normalize_name(&name)?;

    let (out, ext) = tokio::task::spawn_blocking(move || process_image(&bytes, "emoji"))
        .await
        .map_err(|_| AppError::Internal("Image processing failed".into()))??;

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

async fn require_in_server(state: &AppState, emoji_id: &str, server_id: &str) -> AppResult<()> {
    let row = emoji::get_emoji(state, emoji_id).await?;
    if row.server_id != server_id {
        return Err(AppError::NotFound("Emoji not found".into()));
    }
    Ok(())
}
