//! Message drafts: unsent composer text kept per user and channel so it
//! survives leaving a channel or moving to another device.

use crate::error::AppResult;
use crate::services::channel;
use crate::state::AppState;

pub const MAX_DRAFT_LEN: usize = 4_000;

/// The viewer's draft for one channel, if any. Access-checked.
pub async fn get(state: &AppState, user_id: &str, channel_id: &str) -> AppResult<Option<String>> {
    channel::require_channel_access(state, channel_id, user_id).await?;
    let content: Option<String> =
        sqlx::query_scalar(r#"SELECT content FROM "Draft" WHERE "userId" = $1 AND "channelId" = $2"#)
            .bind(user_id)
            .bind(channel_id)
            .fetch_optional(&state.pool)
            .await?;
    Ok(content)
}

/// Every draft the viewer has, for hydrating the client on load.
pub async fn list(state: &AppState, user_id: &str) -> AppResult<Vec<(String, String)>> {
    let rows: Vec<(String, String)> =
        sqlx::query_as(r#"SELECT "channelId", content FROM "Draft" WHERE "userId" = $1"#)
            .bind(user_id)
            .fetch_all(&state.pool)
            .await?;
    Ok(rows)
}

/// Upsert a draft. Access-checked.
pub async fn set(state: &AppState, user_id: &str, channel_id: &str, content: &str) -> AppResult<()> {
    channel::require_channel_access(state, channel_id, user_id).await?;
    sqlx::query(
        r#"INSERT INTO "Draft" ("userId", "channelId", content, "updatedAt")
           VALUES ($1, $2, $3, CURRENT_TIMESTAMP)
           ON CONFLICT ("userId", "channelId")
           DO UPDATE SET content = EXCLUDED.content, "updatedAt" = CURRENT_TIMESTAMP"#,
    )
    .bind(user_id)
    .bind(channel_id)
    .bind(content)
    .execute(&state.pool)
    .await?;
    Ok(())
}

/// Drop a draft. Not access-checked: removing your own row is always fine, and
/// this runs in the send path where the check already happened.
pub async fn clear(state: &AppState, user_id: &str, channel_id: &str) -> AppResult<()> {
    sqlx::query(r#"DELETE FROM "Draft" WHERE "userId" = $1 AND "channelId" = $2"#)
        .bind(user_id)
        .bind(channel_id)
        .execute(&state.pool)
        .await?;
    Ok(())
}
