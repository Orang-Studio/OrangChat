//! Channel + overwrite persistence and access checks. Mirrors channel-service.ts.

use sqlx::QueryBuilder;

use crate::error::{AppError, AppResult};
use crate::ids::cuid;
use crate::models::{ChannelOverwriteRow, ChannelRow};
use crate::permissions::{self, MANAGE_CHANNELS, MANAGE_MESSAGES, VIEW_CHANNEL};
use crate::services::membership;
use crate::state::AppState;

pub struct NewChannel {
    pub name: String,
    pub channel_type: String,
    pub topic: Option<String>,
    pub parent_category_id: Option<String>,
}

/// PATCH semantics: outer None = field absent; Some(inner) = set to inner
/// (inner None clears the column).
#[derive(Default)]
pub struct ChannelPatch {
    pub name: Option<String>,
    pub topic: Option<Option<String>>,
    pub position: Option<i32>,
    pub parent_category_id: Option<Option<String>>,
    pub nsfw: Option<bool>,
    pub rate_limit_per_user: Option<i32>,
    pub user_limit: Option<i32>,
    pub bitrate: Option<i32>,
}

pub async fn create_channel(
    state: &AppState,
    server_id: &str,
    input: NewChannel,
) -> AppResult<ChannelRow> {
    let last: Option<i32> = sqlx::query_scalar(
        r#"SELECT position FROM "Channel" WHERE "serverId" = $1 ORDER BY position DESC LIMIT 1"#,
    )
    .bind(server_id)
    .fetch_optional(&state.pool)
    .await?;

    let row = sqlx::query_as::<_, ChannelRow>(
        r#"INSERT INTO "Channel" (id, "serverId", name, type, topic, "parentCategoryId", position, "updatedAt")
           VALUES ($1, $2, $3, $4, $5, $6, $7, now())
           RETURNING *"#,
    )
    .bind(cuid())
    .bind(server_id)
    .bind(&input.name)
    .bind(&input.channel_type)
    .bind(&input.topic)
    .bind(&input.parent_category_id)
    .bind(last.unwrap_or(-1) + 1)
    .fetch_one(&state.pool)
    .await?;
    Ok(row)
}

pub async fn get_channel(state: &AppState, channel_id: &str) -> AppResult<Option<ChannelRow>> {
    Ok(sqlx::query_as(r#"SELECT * FROM "Channel" WHERE id = $1"#)
        .bind(channel_id)
        .fetch_optional(&state.pool)
        .await?)
}

pub async fn delete_channel(state: &AppState, channel_id: &str) -> AppResult<()> {
    let mut tx = state.pool.begin().await?;
    // systemChannelId/afkChannelId are soft pointers with no FK, so nothing else
    // clears them. Leaving a dangling id would make the setting silently inert
    // and show a blank in the settings UI.
    sqlx::query(r#"UPDATE "Server" SET "systemChannelId" = NULL WHERE "systemChannelId" = $1"#)
        .bind(channel_id)
        .execute(&mut *tx)
        .await?;
    sqlx::query(r#"UPDATE "Server" SET "afkChannelId" = NULL WHERE "afkChannelId" = $1"#)
        .bind(channel_id)
        .execute(&mut *tx)
        .await?;
    sqlx::query(r#"DELETE FROM "Channel" WHERE id = $1"#)
        .bind(channel_id)
        .execute(&mut *tx)
        .await?;
    tx.commit().await?;
    Ok(())
}

pub async fn update_channel(
    state: &AppState,
    channel_id: &str,
    patch: ChannelPatch,
) -> AppResult<ChannelRow> {
    let mut qb: QueryBuilder<sqlx::Postgres> = QueryBuilder::new(r#"UPDATE "Channel" SET "#);
    let mut sep = qb.separated(", ");
    if let Some(name) = patch.name {
        sep.push(r#""name" = "#).push_bind_unseparated(name);
    }
    if let Some(topic) = patch.topic {
        sep.push(r#"topic = "#).push_bind_unseparated(topic);
    }
    if let Some(position) = patch.position {
        sep.push(r#"position = "#).push_bind_unseparated(position);
    }
    if let Some(parent) = patch.parent_category_id {
        sep.push(r#""parentCategoryId" = "#)
            .push_bind_unseparated(parent);
    }
    if let Some(nsfw) = patch.nsfw {
        sep.push(r#"nsfw = "#).push_bind_unseparated(nsfw);
    }
    if let Some(rate) = patch.rate_limit_per_user {
        sep.push(r#""rateLimitPerUser" = "#)
            .push_bind_unseparated(rate);
    }
    if let Some(limit) = patch.user_limit {
        sep.push(r#""userLimit" = "#).push_bind_unseparated(limit);
    }
    if let Some(bitrate) = patch.bitrate {
        sep.push(r#"bitrate = "#).push_bind_unseparated(bitrate);
    }
    qb.push(r#" WHERE id = "#)
        .push_bind(channel_id)
        .push(r#" RETURNING *"#);
    Ok(qb
        .build_query_as::<ChannelRow>()
        .fetch_one(&state.pool)
        .await?)
}

/// Bulk reorder / re-parent, for drag-and-drop channel lists. One transaction, so
/// a rejected entry cannot leave the sidebar half-rewritten. Ids are checked
/// against `serverId` first: otherwise a caller could drag a channel out of a
/// server they merely share a room with.
pub async fn reorder_channels(
    state: &AppState,
    server_id: &str,
    items: &[(String, i32, Option<Option<String>>)],
) -> AppResult<Vec<ChannelRow>> {
    let existing: Vec<ChannelRow> =
        sqlx::query_as(r#"SELECT * FROM "Channel" WHERE "serverId" = $1"#)
            .bind(server_id)
            .fetch_all(&state.pool)
            .await?;

    for (id, _, parent) in items {
        let ch = existing
            .iter()
            .find(|c| &c.id == id)
            .ok_or_else(|| AppError::BadRequest("Channel not found in this server".into()))?;
        if let Some(Some(parent_id)) = parent {
            let target = existing
                .iter()
                .find(|c| &c.id == parent_id)
                .ok_or_else(|| AppError::BadRequest("Category not found in this server".into()))?;
            if target.channel_type != "category" {
                return Err(AppError::BadRequest("Parent must be a category".into()));
            }
            // A category inside a category has no meaning in the sidebar, and a
            // category parented to itself would render as a cycle.
            if ch.channel_type == "category" {
                return Err(AppError::BadRequest(
                    "A category cannot be nested in another category".into(),
                ));
            }
        }
    }

    let mut tx = state.pool.begin().await?;
    for (id, position, parent) in items {
        match parent {
            Some(p) => {
                sqlx::query(
                    r#"UPDATE "Channel" SET position = $1, "parentCategoryId" = $2
                       WHERE id = $3 AND "serverId" = $4"#,
                )
                .bind(position)
                .bind(p)
                .bind(id)
                .bind(server_id)
                .execute(&mut *tx)
                .await?;
            }
            None => {
                sqlx::query(
                    r#"UPDATE "Channel" SET position = $1 WHERE id = $2 AND "serverId" = $3"#,
                )
                .bind(position)
                .bind(id)
                .bind(server_id)
                .execute(&mut *tx)
                .await?;
            }
        }
    }
    tx.commit().await?;

    Ok(
        sqlx::query_as(r#"SELECT * FROM "Channel" WHERE "serverId" = $1 ORDER BY position ASC"#)
            .bind(server_id)
            .fetch_all(&state.pool)
            .await?,
    )
}

// ── Overwrites ──────────────────────────────────────────

pub async fn list_overwrites(
    state: &AppState,
    channel_id: &str,
) -> AppResult<Vec<ChannelOverwriteRow>> {
    Ok(
        sqlx::query_as(r#"SELECT * FROM "ChannelOverwrite" WHERE "channelId" = $1"#)
            .bind(channel_id)
            .fetch_all(&state.pool)
            .await?,
    )
}

pub async fn upsert_overwrite(
    state: &AppState,
    channel_id: &str,
    ow_type: &str,
    target_id: &str,
    allow: i64,
    deny: i64,
) -> AppResult<ChannelOverwriteRow> {
    let row = sqlx::query_as::<_, ChannelOverwriteRow>(
        r#"INSERT INTO "ChannelOverwrite" (id, "channelId", type, "targetId", allow, deny)
           VALUES ($1, $2, $3, $4, $5, $6)
           ON CONFLICT ("channelId", type, "targetId")
           DO UPDATE SET allow = EXCLUDED.allow, deny = EXCLUDED.deny
           RETURNING *"#,
    )
    .bind(cuid())
    .bind(channel_id)
    .bind(ow_type)
    .bind(target_id)
    .bind(allow)
    .bind(deny)
    .fetch_one(&state.pool)
    .await?;
    Ok(row)
}

pub async fn delete_overwrite(
    state: &AppState,
    channel_id: &str,
    ow_type: &str,
    target_id: &str,
) -> AppResult<()> {
    sqlx::query(
        r#"DELETE FROM "ChannelOverwrite" WHERE "channelId" = $1 AND type = $2 AND "targetId" = $3"#,
    )
    .bind(channel_id)
    .bind(ow_type)
    .bind(target_id)
    .execute(&state.pool)
    .await?;
    Ok(())
}

/// Enforce a channel's slowmode for one member, consuming their allowance.
///
/// Distinct from services::rate_limit, which protects the server from abuse with
/// fixed windows and fails open. This is a *product* rule the moderator set, so
/// it uses a strict per-member cooldown key and, on a Redis outage, is skipped
/// rather than allowed to lock a channel down harder than configured.
///
/// Members who can manage the channel or its messages are exempt, matching
/// Discord: slowmode is a crowd-control tool, not a rule for the people holding
/// the tool.
pub async fn enforce_slowmode(
    state: &AppState,
    channel: &ChannelRow,
    user_id: &str,
) -> AppResult<()> {
    // DMs have no moderator to set slowmode in the first place.
    if channel.rate_limit_per_user <= 0 || channel.server_id.is_none() {
        return Ok(());
    }

    if let Some(perms) = membership::channel_permissions(state, &channel.id, user_id).await? {
        if permissions::has_permission(perms, MANAGE_MESSAGES)
            || permissions::has_permission(perms, MANAGE_CHANNELS)
        {
            return Ok(());
        }
    }

    let key = format!("slowmode:{}:{}", channel.id, user_id);
    let mut conn = state.redis.clone();
    // SET NX EX: the key's own expiry is the cooldown, so it cannot be refreshed
    // into a permanent block by repeated attempts.
    let acquired: Option<String> = redis::cmd("SET")
        .arg(&key)
        .arg("1")
        .arg("NX")
        .arg("EX")
        .arg(channel.rate_limit_per_user as i64)
        .query_async(&mut conn)
        .await
        .unwrap_or(Some("OK".into()));

    if acquired.is_some() {
        return Ok(());
    }
    let ttl: i64 = redis::cmd("TTL")
        .arg(&key)
        .query_async(&mut conn)
        .await
        .unwrap_or(channel.rate_limit_per_user as i64);
    // A negative TTL means the key vanished between the SET and the TTL; treat it
    // as a full wait rather than reporting a nonsensical countdown.
    let wait = if ttl > 0 {
        ttl as u64
    } else {
        channel.rate_limit_per_user.max(1) as u64
    };
    Err(AppError::TooManyRequests {
        message: format!("Slowmode is on: wait {wait}s before sending again"),
        retry_after: wait,
    })
}

/// Resolve a channel and confirm the user may access it. Mirrors requireChannelAccess.
pub async fn require_channel_access(
    state: &AppState,
    channel_id: &str,
    user_id: &str,
) -> AppResult<ChannelRow> {
    let channel = get_channel(state, channel_id)
        .await?
        .ok_or_else(|| AppError::Permission("Channel not found".into()))?;

    if channel.server_id.is_some() {
        match membership::channel_permissions(state, channel_id, user_id).await? {
            None => Err(AppError::Permission("Not a member of this server".into())),
            Some(perms) if !permissions::has_permission(perms, VIEW_CHANNEL) => {
                Err(AppError::Permission("You cannot view this channel".into()))
            }
            Some(_) => Ok(channel),
        }
    } else {
        let participant: Option<String> = sqlx::query_scalar(
            r#"SELECT id FROM "ChannelParticipant" WHERE "channelId" = $1 AND "userId" = $2"#,
        )
        .bind(channel_id)
        .bind(user_id)
        .fetch_optional(&state.pool)
        .await?;
        if participant.is_none() {
            return Err(AppError::Permission(
                "Not a participant in this conversation".into(),
            ));
        }
        Ok(channel)
    }
}
