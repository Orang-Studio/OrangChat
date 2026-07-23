//! Custom per-server emoji. No TS equivalent - new for the Rust port.
//!
//! Messages store `<:name:id>`, so the id is the durable reference and the name
//! is only a handle: renaming an emoji leaves old messages rendering correctly,
//! and deleting one leaves them rendering as literal text rather than breaking.

use crate::error::{AppError, AppResult};
use crate::ids::cuid;
use crate::models::EmojiRow;
use crate::state::AppState;

/// Discord's own bound. Names are referenced inside `<:name:id>`, so anything
/// that could terminate that token (`:` or `>`) or split it (whitespace) is out.
const NAME_MAX: usize = 32;
const NAME_MIN: usize = 2;
/// A server's worth of emoji. Bounded because the whole set is sent to every
/// client that opens the picker.
pub const PER_SERVER_LIMIT: i64 = 200;

/// Trim and validate a name, or explain why it will not do.
pub fn normalize_name(raw: &str) -> AppResult<String> {
    let name = raw.trim().to_string();
    if name.len() < NAME_MIN || name.len() > NAME_MAX {
        return Err(AppError::BadRequest(format!(
            "Emoji names must be {NAME_MIN}-{NAME_MAX} characters"
        )));
    }
    if !name
        .chars()
        .all(|c| c.is_ascii_alphanumeric() || c == '_' || c == '-')
    {
        return Err(AppError::BadRequest(
            "Emoji names may only use letters, numbers, dashes and underscores".into(),
        ));
    }
    Ok(name)
}

pub async fn list_emojis(state: &AppState, server_id: &str) -> AppResult<Vec<EmojiRow>> {
    let rows = sqlx::query_as::<_, EmojiRow>(
        r#"SELECT id, "serverId", name, url, animated, "creatorId", "createdAt"
           FROM "Emoji" WHERE "serverId" = $1 ORDER BY name ASC"#,
    )
    .bind(server_id)
    .fetch_all(&state.pool)
    .await?;
    Ok(rows)
}

/// Every emoji from every server the user is in - what the picker needs to show
/// them everything they can actually type, including in DMs.
pub async fn list_usable_emojis(state: &AppState, user_id: &str) -> AppResult<Vec<EmojiRow>> {
    let rows = sqlx::query_as::<_, EmojiRow>(
        r#"SELECT e.id, e."serverId", e.name, e.url, e.animated, e."creatorId", e."createdAt"
           FROM "Emoji" e
           JOIN "ServerMember" m ON m."serverId" = e."serverId"
           WHERE m."userId" = $1
           ORDER BY e.name ASC"#,
    )
    .bind(user_id)
    .fetch_all(&state.pool)
    .await?;
    Ok(rows)
}

pub async fn get_emoji(state: &AppState, emoji_id: &str) -> AppResult<EmojiRow> {
    sqlx::query_as::<_, EmojiRow>(
        r#"SELECT id, "serverId", name, url, animated, "creatorId", "createdAt"
           FROM "Emoji" WHERE id = $1"#,
    )
    .bind(emoji_id)
    .fetch_optional(&state.pool)
    .await?
    .ok_or_else(|| AppError::NotFound("Emoji not found".into()))
}

pub async fn create_emoji(
    state: &AppState,
    server_id: &str,
    creator_id: &str,
    name: &str,
    url: &str,
    animated: bool,
) -> AppResult<EmojiRow> {
    let name = normalize_name(name)?;

    let count: i64 = sqlx::query_scalar(r#"SELECT COUNT(*) FROM "Emoji" WHERE "serverId" = $1"#)
        .bind(server_id)
        .fetch_one(&state.pool)
        .await?;
    if count >= PER_SERVER_LIMIT {
        return Err(AppError::BadRequest(format!(
            "This server has reached its {PER_SERVER_LIMIT} emoji limit"
        )));
    }

    let row = sqlx::query_as::<_, EmojiRow>(
        r#"INSERT INTO "Emoji" (id, "serverId", name, url, animated, "creatorId")
           VALUES ($1, $2, $3, $4, $5, $6)
           RETURNING id, "serverId", name, url, animated, "creatorId", "createdAt""#,
    )
    .bind(cuid())
    .bind(server_id)
    .bind(&name)
    .bind(url)
    .bind(animated)
    .bind(creator_id)
    .fetch_one(&state.pool)
    .await
    .map_err(unique_name_error)?;
    Ok(row)
}

pub async fn rename_emoji(state: &AppState, emoji_id: &str, name: &str) -> AppResult<EmojiRow> {
    let name = normalize_name(name)?;
    sqlx::query_as::<_, EmojiRow>(
        r#"UPDATE "Emoji" SET name = $2 WHERE id = $1
           RETURNING id, "serverId", name, url, animated, "creatorId", "createdAt""#,
    )
    .bind(emoji_id)
    .bind(&name)
    .fetch_optional(&state.pool)
    .await
    .map_err(unique_name_error)?
    .ok_or_else(|| AppError::NotFound("Emoji not found".into()))
}

pub async fn delete_emoji(state: &AppState, emoji_id: &str) -> AppResult<()> {
    sqlx::query(r#"DELETE FROM "Emoji" WHERE id = $1"#)
        .bind(emoji_id)
        .execute(&state.pool)
        .await?;
    Ok(())
}

/// The (serverId, name) unique index is the race-proof half of the duplicate
/// check, but on its own it surfaces as a 500. Name the real problem.
fn unique_name_error(err: sqlx::Error) -> AppError {
    if let sqlx::Error::Database(db) = &err {
        if db.code().as_deref() == Some("23505") {
            return AppError::BadRequest("An emoji with that name already exists".into());
        }
    }
    err.into()
}
