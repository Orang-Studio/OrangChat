
use axum::extract::{DefaultBodyLimit, Multipart, Path, State};
use axum::http::StatusCode;
use axum::response::IntoResponse;
use axum::routing::{get, patch};
use axum::{Json, Router};
use serde::Deserialize;

use crate::dto::{to_sound, SoundDto};
use crate::error::{AppError, AppResult};
use crate::http::uploads::store_audio;
use crate::http::AuthUser;
use crate::permissions::MANAGE_EXPRESSIONS;
use crate::services::{audio, membership, rate_limit, sound};
use crate::state::AppState;

const MAX_SOUND_UPLOAD: usize = 1024 * 1024;

const ALLOWED_EXT: [&str; 6] = ["mp3", "ogg", "wav", "flac", "m4a", "aac"];

pub fn routes() -> Router<AppState> {
    Router::new()
        .route("/servers/:serverId/sounds", get(list).post(create))
        .route("/sounds", get(list_usable))
        .route(
            "/servers/:serverId/sounds/:soundId",
            patch(update).delete(remove),
        )
        .layer(DefaultBodyLimit::max(MAX_SOUND_UPLOAD + 64 * 1024))
}

async fn list(
    user: AuthUser,
    Path(server_id): Path<String>,
    State(state): State<AppState>,
) -> AppResult<Json<Vec<SoundDto>>> {
    membership::require_permission(&state, &server_id, &user.user_id, 0).await?;
    let rows = sound::list_sounds(&state, &server_id).await?;
    Ok(Json(rows.iter().map(to_sound).collect()))
}

async fn list_usable(
    user: AuthUser,
    State(state): State<AppState>,
) -> AppResult<Json<Vec<SoundDto>>> {
    let rows = sound::list_usable_sounds(&state, &user.user_id).await?;
    Ok(Json(rows.iter().map(to_sound).collect()))
}

async fn create(
    user: AuthUser,
    Path(server_id): Path<String>,
    State(state): State<AppState>,
    mut multipart: Multipart,
) -> AppResult<(StatusCode, Json<SoundDto>)> {
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
    let mut emoji: Option<String> = None;
    let mut ext: Option<String> = None;

    while let Some(field) = multipart
        .next_field()
        .await
        .map_err(|_| AppError::BadRequest("Invalid upload".into()))?
    {
        match field.name() {
            Some("file") => {
                ext = field
                    .file_name()
                    .and_then(|f| f.rsplit_once('.'))
                    .map(|(_, e)| e.to_ascii_lowercase())
                    .filter(|e| ALLOWED_EXT.contains(&e.as_str()));
                let data = field
                    .bytes()
                    .await
                    .map_err(|_| AppError::BadRequest("Sound is too large (max 1 MB)".into()))?;
                if data.len() > MAX_SOUND_UPLOAD {
                    return Err(AppError::BadRequest("Sound is too large (max 1 MB)".into()));
                }
                bytes = Some(data.to_vec());
            }
            Some("name") => name = field.text().await.ok(),
            Some("emoji") => emoji = field.text().await.ok().filter(|e| !e.trim().is_empty()),
            _ => {}
        }
    }

    let bytes = bytes.ok_or_else(|| AppError::BadRequest("No file provided".into()))?;
    let name = name.ok_or_else(|| AppError::BadRequest("No name provided".into()))?;
    let name = sound::normalize_name(&name)?;

    let probe_bytes = bytes.clone();
    let probe_ext = ext.clone();
    let duration = tokio::task::spawn_blocking(move || {
        audio::require_short_enough(probe_bytes, probe_ext.as_deref())
    })
    .await
    .map_err(|_| AppError::Internal("Audio processing failed".into()))??;

    let url = store_audio(&state, bytes, ext.as_deref().unwrap_or("mp3")).await?;

    let row = sound::create_sound(
        &state,
        sound::NewSound {
            server_id: &server_id,
            creator_id: &user.user_id,
            name: &name,
            url: &url,
            duration,
            emoji: emoji.as_deref(),
        },
    )
    .await?;
    Ok((StatusCode::CREATED, Json(to_sound(&row))))
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct UpdatePayload {
    name: Option<String>,
    emoji: Option<String>,
    volume: Option<f64>,
}

async fn update(
    user: AuthUser,
    Path((server_id, sound_id)): Path<(String, String)>,
    State(state): State<AppState>,
    Json(payload): Json<UpdatePayload>,
) -> AppResult<Json<SoundDto>> {
    membership::require_permission(&state, &server_id, &user.user_id, MANAGE_EXPRESSIONS).await?;
    require_in_server(&state, &sound_id, &server_id).await?;
    let row = sound::update_sound(
        &state,
        &sound_id,
        sound::SoundPatch {
            name: payload.name,
            emoji: payload.emoji,
            volume: payload.volume,
        },
    )
    .await?;
    Ok(Json(to_sound(&row)))
}

async fn remove(
    user: AuthUser,
    Path((server_id, sound_id)): Path<(String, String)>,
    State(state): State<AppState>,
) -> AppResult<impl IntoResponse> {
    membership::require_permission(&state, &server_id, &user.user_id, MANAGE_EXPRESSIONS).await?;
    require_in_server(&state, &sound_id, &server_id).await?;
    sound::delete_sound(&state, &sound_id).await?;
    Ok(StatusCode::NO_CONTENT)
}

async fn require_in_server(state: &AppState, sound_id: &str, server_id: &str) -> AppResult<()> {
    let row = sound::get_sound(state, sound_id).await?;
    if row.server_id != server_id {
        return Err(AppError::NotFound("Sound not found".into()));
    }
    Ok(())
}
