//! Direct-message / group-DM channels. Mirrors dm-service.ts.

use crate::error::{AppError, AppResult};
use crate::ids::cuid;
use crate::models::{ChannelRow, UserRow};
use crate::state::AppState;

pub async fn can_dm(state: &AppState, requester_id: &str, target_id: &str) -> AppResult<bool> {
    let privacy: Option<String> =
        sqlx::query_scalar(r#"SELECT "dmPrivacy" FROM "User" WHERE id = $1"#)
            .bind(target_id)
            .fetch_optional(&state.pool)
            .await?;
    match privacy.as_deref() {
        Some("everyone") | None => Ok(true),
        Some("none") => Ok(false),
        Some("friends") => {
            crate::services::friends::are_friends(state, requester_id, target_id).await
        }
        Some(_) => Ok(true),
    }
}

async fn find_existing_dm(state: &AppState, user_ids: &[String]) -> AppResult<Option<String>> {
    let n = user_ids.len() as i64;
    let id: Option<String> = sqlx::query_scalar(
        r#"SELECT c.id FROM "Channel" c
           WHERE c.type = 'dm'
             AND (SELECT count(*) FROM "ChannelParticipant" p WHERE p."channelId" = c.id) = $2
             AND NOT EXISTS (
                 SELECT 1 FROM "ChannelParticipant" p
                 WHERE p."channelId" = c.id AND NOT (p."userId" = ANY($1))
             )
           LIMIT 1"#,
    )
    .bind(user_ids)
    .bind(n)
    .fetch_optional(&state.pool)
    .await?;
    Ok(id)
}

pub async fn get_or_create_direct_channel(
    state: &AppState,
    requester_id: &str,
    other_user_ids: &[String],
) -> AppResult<(String, bool)> {
    let mut participant_ids: Vec<String> = Vec::new();
    participant_ids.push(requester_id.to_string());
    for id in other_user_ids {
        if !participant_ids.contains(id) {
            participant_ids.push(id.clone());
        }
    }

    if participant_ids.len() < 2 {
        return Err(AppError::Permission(
            "A conversation needs at least two people".into(),
        ));
    }

    let count: i64 = sqlx::query_scalar(r#"SELECT count(id) FROM "User" WHERE id = ANY($1)"#)
        .bind(&participant_ids)
        .fetch_one(&state.pool)
        .await?;
    if count != participant_ids.len() as i64 {
        return Err(AppError::Permission(
            "One or more users do not exist".into(),
        ));
    }

    let is_group = participant_ids.len() > 2;
    if !is_group {
        if let Some(existing) = find_existing_dm(state, &participant_ids).await? {
            return Ok((existing, false));
        }
    }

    // Opening a *new* conversation is what privacy gates; an existing thread
    // above stays reachable so tightening the setting can't strand history.
    for target_id in &participant_ids {
        if target_id == requester_id {
            continue;
        }
        if !can_dm(state, requester_id, target_id).await? {
            return Err(AppError::Permission(
                "This person isn't accepting messages from you".into(),
            ));
        }
    }

    let channel_id = cuid();
    let mut tx = state.pool.begin().await?;
    sqlx::query(
        r#"INSERT INTO "Channel" (id, type, name, "updatedAt") VALUES ($1, $2, NULL, now())"#,
    )
    .bind(&channel_id)
    .bind(if is_group { "group_dm" } else { "dm" })
    .execute(&mut *tx)
    .await?;
    for uid in &participant_ids {
        sqlx::query(
            r#"INSERT INTO "ChannelParticipant" (id, "channelId", "userId") VALUES ($1, $2, $3)"#,
        )
        .bind(cuid())
        .bind(&channel_id)
        .bind(uid)
        .execute(&mut *tx)
        .await?;
    }
    tx.commit().await?;
    Ok((channel_id, true))
}

pub async fn list_conversations(
    state: &AppState,
    user_id: &str,
) -> AppResult<Vec<(ChannelRow, Vec<UserRow>)>> {
    let channels: Vec<ChannelRow> = sqlx::query_as(
        r#"SELECT c.* FROM "Channel" c
           WHERE c.type IN ('dm', 'group_dm')
             AND EXISTS (
                 SELECT 1 FROM "ChannelParticipant" p
                 WHERE p."channelId" = c.id AND p."userId" = $1
             )
           ORDER BY c."updatedAt" DESC"#,
    )
    .bind(user_id)
    .fetch_all(&state.pool)
    .await?;

    let mut out = Vec::with_capacity(channels.len());
    for c in channels {
        let users: Vec<UserRow> = sqlx::query_as(
            r#"SELECT u.* FROM "User" u
               JOIN "ChannelParticipant" p ON p."userId" = u.id
               WHERE p."channelId" = $1"#,
        )
        .bind(&c.id)
        .fetch_all(&state.pool)
        .await?;
        out.push((c, users));
    }
    Ok(out)
}

pub async fn get_conversation_participants(
    state: &AppState,
    channel_id: &str,
) -> AppResult<Vec<String>> {
    Ok(
        sqlx::query_scalar(r#"SELECT "userId" FROM "ChannelParticipant" WHERE "channelId" = $1"#)
            .bind(channel_id)
            .fetch_all(&state.pool)
            .await?,
    )
}

pub async fn add_group_participants(
    state: &AppState,
    channel_id: &str,
    requester_id: &str,
    new_user_ids: &[String],
) -> AppResult<()> {
    let ctype: Option<String> = sqlx::query_scalar(r#"SELECT type FROM "Channel" WHERE id = $1"#)
        .bind(channel_id)
        .fetch_optional(&state.pool)
        .await?;
    if ctype.as_deref() != Some("group_dm") {
        return Err(AppError::Permission("Not a group conversation".into()));
    }

    let requester: Option<String> = sqlx::query_scalar(
        r#"SELECT id FROM "ChannelParticipant" WHERE "channelId" = $1 AND "userId" = $2"#,
    )
    .bind(channel_id)
    .bind(requester_id)
    .fetch_optional(&state.pool)
    .await?;
    if requester.is_none() {
        return Err(AppError::Permission(
            "Not a participant in this conversation".into(),
        ));
    }

    for uid in new_user_ids {
        sqlx::query(
            r#"INSERT INTO "ChannelParticipant" (id, "channelId", "userId") VALUES ($1, $2, $3)
               ON CONFLICT ("channelId", "userId") DO NOTHING"#,
        )
        .bind(cuid())
        .bind(channel_id)
        .bind(uid)
        .execute(&state.pool)
        .await?;
    }
    Ok(())
}
