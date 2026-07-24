//! Soundboard REST, mounted under /api. No TS equivalent - new for the Rust port.

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

/// Three seconds of audio is small at any sane bitrate; the rest of this budget
/// is headroom for container overhead and lossless uploads.
const MAX_SOUND_UPLOAD: usize = 1024 * 1024;

/// Extensions we hand symphonia as a parsing hint and store under.
const ALLOWED_EXT: [&str; 6] = ["mp3", "ogg", "wav", "flac", "m4a", "aac"];

pub fn routes() -> Router<AppState> {
    Router::new()
        .route("/servers/:serverId/sounds", get(list).post(create))
        // Every sound the user can play, across their servers - the picker's
        // source so the soundboard works outside any one server.
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
    // Membership alone is the read gate: anyone who can play a sound here can
    // see the board.
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
                // The filename is only ever a parsing hint for symphonia; the
                // probe is what actually decides whether this is playable audio.
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

    // Decoding is CPU-bound and blocking - keep it off the runtime.
    let probe_bytes = bytes.clone();
    let probe_ext = ext.clone();
    let duration = tokio::task::spawn_blocking(move || {
        audio::require_short_enough(probe_bytes, probe_ext.as_deref())
    })
    .await
    .map_err(|_| AppError::Internal("Audio processing failed".into()))??;

    // Stored as uploaded: re-encoding audio needs an encoder we deliberately do
    // not ship, and the probe has already bounded both length and size.
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

/// The permission check is scoped to the server in the path, so the sound has to
/// actually live there.
async fn require_in_server(state: &AppState, sound_id: &str, server_id: &str) -> AppResult<()> {
    let row = sound::get_sound(state, sound_id).await?;
    if row.server_id != server_id {
        return Err(AppError::NotFound("Sound not found".into()));
    }
    Ok(())
}
