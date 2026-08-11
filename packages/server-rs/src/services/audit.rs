
use serde_json::{json, Value};

use crate::error::AppResult;
use crate::ids::cuid;
use crate::models::{AuditLogRow, UserRow};
use crate::state::AppState;

pub mod action {
    pub const SERVER_UPDATE: &str = "server.update";
    pub const CHANNEL_CREATE: &str = "channel.create";
    pub const CHANNEL_UPDATE: &str = "channel.update";
    pub const CHANNEL_DELETE: &str = "channel.delete";
    pub const ROLE_CREATE: &str = "role.create";
    pub const ROLE_UPDATE: &str = "role.update";
    pub const ROLE_DELETE: &str = "role.delete";
    pub const MEMBER_KICK: &str = "member.kick";
    pub const MEMBER_BAN: &str = "member.ban";
    pub const MEMBER_UNBAN: &str = "member.unban";
    pub const MEMBER_TIMEOUT: &str = "member.timeout";
    pub const MEMBER_ROLE_UPDATE: &str = "member.role_update";
    pub const BOT_ADD: &str = "bot.add";
    pub const BOT_REMOVE: &str = "bot.remove";
}

pub struct Entry<'a> {
    pub server_id: &'a str,
    pub actor_id: &'a str,
    pub action: &'a str,
    pub target_id: Option<&'a str>,
    pub target_type: Option<&'a str>,
    pub changes: Value,
    pub reason: Option<&'a str>,
}

pub async fn record(state: &AppState, entry: Entry<'_>) {
    let res = sqlx::query(
        r#"INSERT INTO "AuditLog" (id, "serverId", "actorId", action, "targetId", "targetType", changes, reason)
           VALUES ($1, $2, $3, $4, $5, $6, $7, $8)"#,
    )
    .bind(cuid())
    .bind(entry.server_id)
    .bind(entry.actor_id)
    .bind(entry.action)
    .bind(entry.target_id)
    .bind(entry.target_type)
    .bind(&entry.changes)
    .bind(entry.reason)
    .execute(&state.pool)
    .await;

    if let Err(e) = res {
        tracing::warn!(error = %e, action = entry.action, "audit log write failed");
    }
}

pub fn diff(pairs: Vec<(&str, Value, Value)>) -> Value {
    let mut out = serde_json::Map::new();
    for (field, old, new) in pairs {
        if old != new {
            out.insert(field.to_string(), json!({ "old": old, "new": new }));
        }
    }
    Value::Object(out)
}

pub async fn list(
    state: &AppState,
    server_id: &str,
    action_filter: Option<&str>,
    limit: i64,
    offset: i64,
) -> AppResult<Vec<(AuditLogRow, Option<UserRow>)>> {
    let rows: Vec<AuditLogRow> = match action_filter {
        Some(a) => {
            sqlx::query_as(
                r#"SELECT * FROM "AuditLog" WHERE "serverId" = $1 AND action = $2
                   ORDER BY "createdAt" DESC LIMIT $3 OFFSET $4"#,
            )
            .bind(server_id)
            .bind(a)
            .bind(limit)
            .bind(offset)
            .fetch_all(&state.pool)
            .await?
        }
        None => {
            sqlx::query_as(
                r#"SELECT * FROM "AuditLog" WHERE "serverId" = $1
                   ORDER BY "createdAt" DESC LIMIT $2 OFFSET $3"#,
            )
            .bind(server_id)
            .bind(limit)
            .bind(offset)
            .fetch_all(&state.pool)
            .await?
        }
    };

    let mut out = Vec::with_capacity(rows.len());
    for row in rows {
        let actor: Option<UserRow> = match row.actor_id {
            Some(ref id) => {
                sqlx::query_as(r#"SELECT * FROM "User" WHERE id = $1"#)
                    .bind(id)
                    .fetch_optional(&state.pool)
                    .await?
            }
            None => None,
        };
        out.push((row, actor));
    }
    Ok(out)
}
