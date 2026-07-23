//! Soundboard clips. No TS equivalent - new for the Rust port.

use crate::error::{AppError, AppResult};
use crate::ids::cuid;
use crate::models::SoundRow;
use crate::state::AppState;

const NAME_MAX: usize = 32;
const NAME_MIN: usize = 1;
/// Bounded because the whole board is sent to every client that opens it.
pub const PER_SERVER_LIMIT: i64 = 64;

/// Unlike emoji, sound names are only ever shown as labels - never parsed out of
/// message text - so they can hold spaces and punctuation.
pub fn normalize_name(raw: &str) -> AppResult<String> {
    let name = raw.trim().to_string();
    if name.chars().count() < NAME_MIN || name.chars().count() > NAME_MAX {
        return Err(AppError::BadRequest(format!(
            "Sound names must be {NAME_MIN}-{NAME_MAX} characters"
        )));
    }
    Ok(name)
}

/// Clamped rather than rejected: a slider that refuses to save is worse than one
/// that lands on its own edge.
pub fn normalize_volume(raw: Option<f64>) -> f64 {
    raw.unwrap_or(1.0).clamp(0.0, 1.0)
}

pub async fn list_sounds(state: &AppState, server_id: &str) -> AppResult<Vec<SoundRow>> {
    let rows = sqlx::query_as::<_, SoundRow>(
        r#"SELECT id, "serverId", name, url, duration, emoji, volume, "creatorId", "createdAt"
           FROM "Sound" WHERE "serverId" = $1 ORDER BY name ASC"#,
    )
    .bind(server_id)
    .fetch_all(&state.pool)
    .await?;
    Ok(rows)
}

pub async fn get_sound(state: &AppState, sound_id: &str) -> AppResult<SoundRow> {
    sqlx::query_as::<_, SoundRow>(
        r#"SELECT id, "serverId", name, url, duration, emoji, volume, "creatorId", "createdAt"
           FROM "Sound" WHERE id = $1"#,
    )
    .bind(sound_id)
    .fetch_optional(&state.pool)
    .await?
    .ok_or_else(|| AppError::NotFound("Sound not found".into()))
}

pub struct NewSound<'a> {
    pub server_id: &'a str,
    pub creator_id: &'a str,
    pub name: &'a str,
    pub url: &'a str,
    pub duration: f64,
    pub emoji: Option<&'a str>,
}

pub async fn create_sound(state: &AppState, input: NewSound<'_>) -> AppResult<SoundRow> {
    let name = normalize_name(input.name)?;

    let count: i64 = sqlx::query_scalar(r#"SELECT COUNT(*) FROM "Sound" WHERE "serverId" = $1"#)
        .bind(input.server_id)
        .fetch_one(&state.pool)
        .await?;
    if count >= PER_SERVER_LIMIT {
        return Err(AppError::BadRequest(format!(
            "This server has reached its {PER_SERVER_LIMIT} sound limit"
        )));
    }

    sqlx::query_as::<_, SoundRow>(
        r#"INSERT INTO "Sound" (id, "serverId", name, url, duration, emoji, "creatorId")
           VALUES ($1, $2, $3, $4, $5, $6, $7)
           RETURNING id, "serverId", name, url, duration, emoji, volume, "creatorId", "createdAt""#,
    )
    .bind(cuid())
    .bind(input.server_id)
    .bind(&name)
    .bind(input.url)
    .bind(input.duration)
    .bind(input.emoji)
    .bind(input.creator_id)
    .fetch_one(&state.pool)
    .await
    .map_err(unique_name_error)
}

#[derive(Default)]
pub struct SoundPatch {
    pub name: Option<String>,
    pub emoji: Option<String>,
    pub volume: Option<f64>,
}

pub async fn update_sound(
    state: &AppState,
    sound_id: &str,
    patch: SoundPatch,
) -> AppResult<SoundRow> {
    let current = get_sound(state, sound_id).await?;
    let name = match patch.name {
        Some(n) => normalize_name(&n)?,
        None => current.name,
    };
    let emoji = patch.emoji.or(current.emoji);
    let volume = match patch.volume {
        Some(v) => normalize_volume(Some(v)),
        None => current.volume,
    };

    sqlx::query_as::<_, SoundRow>(
        r#"UPDATE "Sound" SET name = $2, emoji = $3, volume = $4 WHERE id = $1
           RETURNING id, "serverId", name, url, duration, emoji, volume, "creatorId", "createdAt""#,
    )
    .bind(sound_id)
    .bind(&name)
    .bind(&emoji)
    .bind(volume)
    .fetch_optional(&state.pool)
    .await
    .map_err(unique_name_error)?
    .ok_or_else(|| AppError::NotFound("Sound not found".into()))
}

pub async fn delete_sound(state: &AppState, sound_id: &str) -> AppResult<()> {
    sqlx::query(r#"DELETE FROM "Sound" WHERE id = $1"#)
        .bind(sound_id)
        .execute(&state.pool)
        .await?;
    Ok(())
}

fn unique_name_error(err: sqlx::Error) -> AppError {
    if let sqlx::Error::Database(db) = &err {
        if db.code().as_deref() == Some("23505") {
            return AppError::BadRequest("A sound with that name already exists".into());
        }
    }
    err.into()
}
