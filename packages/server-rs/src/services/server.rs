//! Server / invite persistence. Mirrors server-service.ts.

use std::collections::HashMap;

use sqlx::QueryBuilder;

use crate::error::{AppError, AppResult};
use crate::ids::{cuid, invite_code};
use crate::models::{
    ChannelOverwriteRow, ChannelRow, InviteRow, RoleRow, ServerMemberRow, ServerRow, UserRow,
};
use crate::permissions::DEFAULT_EVERYONE_PERMISSIONS;
use crate::state::AppState;

pub struct MemberDetail {
    pub member: ServerMemberRow,
    pub user: UserRow,
    pub role_ids: Vec<String>,
}

pub struct ServerDetail {
    pub server: ServerRow,
    pub channels: Vec<ChannelRow>,
    pub roles: Vec<RoleRow>,
    pub members: Vec<MemberDetail>,
    pub overwrites: Vec<ChannelOverwriteRow>,
}

#[derive(Default)]
pub struct ServerPatch {
    pub name: Option<String>,
    pub icon_url: Option<Option<String>>,
    pub description: Option<Option<String>>,
    pub banner_url: Option<Option<String>>,
    pub system_channel_id: Option<Option<String>>,
    pub afk_channel_id: Option<Option<String>>,
    pub afk_timeout: Option<i32>,
    pub default_message_notifications: Option<String>,
}

pub struct NewInvite {
    pub expires_in_seconds: Option<i64>,
    pub max_uses: Option<i32>,
}

/// Why an invite can't be used, or that it can.
///
/// The preview and the join share this so a card can never offer a Join button
/// for an invite the join would reject.
#[derive(Clone, Copy, PartialEq, Eq)]
pub enum InviteStatus {
    Ok,
    Expired,
    Exhausted,
    Banned,
    /// Already in - the join is a no-op, so clients jump straight to the server.
    AlreadyMember,
}

impl InviteStatus {
    pub fn as_str(self) -> &'static str {
        match self {
            InviteStatus::Ok => "ok",
            InviteStatus::Expired => "expired",
            InviteStatus::Exhausted => "exhausted",
            InviteStatus::Banned => "banned",
            InviteStatus::AlreadyMember => "alreadyMember",
        }
    }

    /// The message a rejected join fails with.
    fn reject(self) -> Option<AppError> {
        match self {
            InviteStatus::Ok | InviteStatus::AlreadyMember => None,
            InviteStatus::Expired => Some(AppError::Permission("Invite has expired".into())),
            InviteStatus::Exhausted => Some(AppError::Permission(
                "Invite has reached its use limit".into(),
            )),
            InviteStatus::Banned => Some(AppError::Permission(
                "You are banned from this server".into(),
            )),
        }
    }
}

/// What an invite link resolves to before anyone commits to joining.
pub struct InvitePreview {
    pub invite: InviteRow,
    pub server: ServerRow,
    pub inviter: Option<UserRow>,
    pub member_count: i64,
    pub status: InviteStatus,
}

async fn get_invite(state: &AppState, code: &str) -> AppResult<InviteRow> {
    sqlx::query_as(r#"SELECT * FROM "Invite" WHERE code = $1"#)
        .bind(code)
        .fetch_optional(&state.pool)
        .await?
        .ok_or_else(|| AppError::NotFound("Invalid invite".into()))
}

/// Ban and membership are per-viewer, so an anonymous viewer only ever learns
/// whether the invite itself is still live.
async fn invite_status(
    state: &AppState,
    invite: &InviteRow,
    viewer_id: Option<&str>,
) -> AppResult<InviteStatus> {
    if let Some(user_id) = viewer_id {
        let banned: Option<String> =
            sqlx::query_scalar(r#"SELECT id FROM "Ban" WHERE "serverId" = $1 AND "userId" = $2"#)
                .bind(&invite.server_id)
                .bind(user_id)
                .fetch_optional(&state.pool)
                .await?;
        if banned.is_some() {
            return Ok(InviteStatus::Banned);
        }

        // Checked before expiry: an existing member following a stale link
        // should land in the server, not be told the link is dead.
        let member: Option<String> = sqlx::query_scalar(
            r#"SELECT id FROM "ServerMember" WHERE "serverId" = $1 AND "userId" = $2"#,
        )
        .bind(&invite.server_id)
        .bind(user_id)
        .fetch_optional(&state.pool)
        .await?;
        if member.is_some() {
            return Ok(InviteStatus::AlreadyMember);
        }
    }

    if let Some(exp) = invite.expires_at {
        if exp < chrono::Utc::now().naive_utc() {
            return Ok(InviteStatus::Expired);
        }
    }
    if let Some(max) = invite.max_uses {
        if invite.uses >= max {
            return Ok(InviteStatus::Exhausted);
        }
    }
    Ok(InviteStatus::Ok)
}

/// Resolve an invite code to the server behind it, without joining anything.
pub async fn get_invite_preview(
    state: &AppState,
    code: &str,
    viewer_id: Option<&str>,
) -> AppResult<InvitePreview> {
    let invite = get_invite(state, code).await?;
    let server = get_server(state, &invite.server_id)
        .await?
        .ok_or_else(|| AppError::NotFound("Invalid invite".into()))?;
    let status = invite_status(state, &invite, viewer_id).await?;

    let inviter: Option<UserRow> = sqlx::query_as(r#"SELECT * FROM "User" WHERE id = $1"#)
        .bind(&invite.inviter_id)
        .fetch_optional(&state.pool)
        .await?;
    let member_count: i64 =
        sqlx::query_scalar(r#"SELECT count(*) FROM "ServerMember" WHERE "serverId" = $1"#)
            .bind(&invite.server_id)
            .fetch_one(&state.pool)
            .await?;

    Ok(InvitePreview {
        invite,
        server,
        inviter,
        member_count,
        status,
    })
}

pub async fn create_server(
    state: &AppState,
    owner_id: &str,
    name: &str,
    icon_url: Option<&str>,
) -> AppResult<ServerRow> {
    let mut tx = state.pool.begin().await?;

    let server: ServerRow = sqlx::query_as(
        r#"INSERT INTO "Server" (id, name, "iconUrl", "ownerId", "updatedAt")
           VALUES ($1, $2, $3, $4, now()) RETURNING *"#,
    )
    .bind(cuid())
    .bind(name)
    .bind(icon_url)
    .bind(owner_id)
    .fetch_one(&mut *tx)
    .await?;

    sqlx::query(
        r#"INSERT INTO "Role" (id, "serverId", name, position, permissions)
           VALUES ($1, $2, '@everyone', 0, $3)"#,
    )
    .bind(cuid())
    .bind(&server.id)
    .bind(DEFAULT_EVERYONE_PERMISSIONS)
    .execute(&mut *tx)
    .await?;

    sqlx::query(r#"INSERT INTO "ServerMember" (id, "serverId", "userId") VALUES ($1, $2, $3)"#)
        .bind(cuid())
        .bind(&server.id)
        .bind(owner_id)
        .execute(&mut *tx)
        .await?;

    sqlx::query(
        r#"INSERT INTO "Channel" (id, "serverId", name, type, position, "updatedAt")
           VALUES ($1, $2, 'general', 'text', 0, now())"#,
    )
    .bind(cuid())
    .bind(&server.id)
    .execute(&mut *tx)
    .await?;

    tx.commit().await?;
    Ok(server)
}

pub async fn get_user_servers(state: &AppState, user_id: &str) -> AppResult<Vec<ServerRow>> {
    Ok(sqlx::query_as(
        r#"SELECT s.* FROM "Server" s
           JOIN "ServerMember" m ON m."serverId" = s.id
           WHERE m."userId" = $1
           ORDER BY s."createdAt" ASC"#,
    )
    .bind(user_id)
    .fetch_all(&state.pool)
    .await?)
}

pub async fn get_server(state: &AppState, server_id: &str) -> AppResult<Option<ServerRow>> {
    Ok(sqlx::query_as(r#"SELECT * FROM "Server" WHERE id = $1"#)
        .bind(server_id)
        .fetch_optional(&state.pool)
        .await?)
}

pub async fn get_server_detail(
    state: &AppState,
    server_id: &str,
) -> AppResult<Option<ServerDetail>> {
    let Some(server) = get_server(state, server_id).await? else {
        return Ok(None);
    };

    let channels: Vec<ChannelRow> =
        sqlx::query_as(r#"SELECT * FROM "Channel" WHERE "serverId" = $1 ORDER BY position ASC"#)
            .bind(server_id)
            .fetch_all(&state.pool)
            .await?;
    let roles: Vec<RoleRow> =
        sqlx::query_as(r#"SELECT * FROM "Role" WHERE "serverId" = $1 ORDER BY position ASC"#)
            .bind(server_id)
            .fetch_all(&state.pool)
            .await?;
    let member_rows: Vec<ServerMemberRow> =
        sqlx::query_as(r#"SELECT * FROM "ServerMember" WHERE "serverId" = $1"#)
            .bind(server_id)
            .fetch_all(&state.pool)
            .await?;
    let overwrites: Vec<ChannelOverwriteRow> = sqlx::query_as(
        r#"SELECT o.* FROM "ChannelOverwrite" o
           JOIN "Channel" c ON c.id = o."channelId"
           WHERE c."serverId" = $1"#,
    )
    .bind(server_id)
    .fetch_all(&state.pool)
    .await?;

    let user_ids: Vec<String> = member_rows.iter().map(|m| m.user_id.clone()).collect();
    let users: Vec<UserRow> = sqlx::query_as(r#"SELECT * FROM "User" WHERE id = ANY($1)"#)
        .bind(&user_ids)
        .fetch_all(&state.pool)
        .await?;
    let user_map: HashMap<String, UserRow> = users.into_iter().map(|u| (u.id.clone(), u)).collect();

    let member_ids: Vec<String> = member_rows.iter().map(|m| m.id.clone()).collect();
    let mr_rows: Vec<(String, String)> = sqlx::query_as(
        r#"SELECT "memberId", "roleId" FROM "MemberRole" WHERE "memberId" = ANY($1)"#,
    )
    .bind(&member_ids)
    .fetch_all(&state.pool)
    .await?;
    let mut roles_by_member: HashMap<String, Vec<String>> = HashMap::new();
    for (mid, rid) in mr_rows {
        roles_by_member.entry(mid).or_default().push(rid);
    }

    let members = member_rows
        .into_iter()
        .filter_map(|m| {
            let user = user_map.get(&m.user_id).cloned()?;
            let role_ids = roles_by_member.get(&m.id).cloned().unwrap_or_default();
            Some(MemberDetail {
                member: m,
                user,
                role_ids,
            })
        })
        .collect();

    Ok(Some(ServerDetail {
        server,
        channels,
        roles,
        members,
        overwrites,
    }))
}

/// Confirm a channel setting points at a channel of `expected_type` belonging to
/// this server. Without the serverId check a caller could aim their system
/// channel at someone else's channel and have notices posted there.
async fn assert_channel_of_type(
    state: &AppState,
    server_id: &str,
    channel_id: &str,
    expected_type: &str,
) -> AppResult<()> {
    let row: Option<(Option<String>, String)> =
        sqlx::query_as(r#"SELECT "serverId", type FROM "Channel" WHERE id = $1"#)
            .bind(channel_id)
            .fetch_optional(&state.pool)
            .await?;
    match row {
        Some((Some(sid), ctype)) if sid == server_id && ctype == expected_type => Ok(()),
        Some((Some(sid), _)) if sid == server_id => Err(AppError::BadRequest(format!(
            "Channel must be a {expected_type} channel"
        ))),
        _ => Err(AppError::BadRequest(
            "Channel not found in this server".into(),
        )),
    }
}

pub async fn update_server(
    state: &AppState,
    server_id: &str,
    patch: ServerPatch,
) -> AppResult<ServerRow> {
    if let Some(Some(ref cid)) = patch.system_channel_id {
        assert_channel_of_type(state, server_id, cid, "text").await?;
    }
    if let Some(Some(ref cid)) = patch.afk_channel_id {
        assert_channel_of_type(state, server_id, cid, "voice").await?;
    }

    let mut qb: QueryBuilder<sqlx::Postgres> = QueryBuilder::new(r#"UPDATE "Server" SET "#);
    let mut sep = qb.separated(", ");
    if let Some(name) = patch.name {
        sep.push(r#"name = "#).push_bind_unseparated(name);
    }
    if let Some(icon) = patch.icon_url {
        sep.push(r#""iconUrl" = "#).push_bind_unseparated(icon);
    }
    if let Some(description) = patch.description {
        sep.push(r#"description = "#)
            .push_bind_unseparated(description);
    }
    if let Some(banner) = patch.banner_url {
        sep.push(r#""bannerUrl" = "#).push_bind_unseparated(banner);
    }
    if let Some(cid) = patch.system_channel_id {
        sep.push(r#""systemChannelId" = "#)
            .push_bind_unseparated(cid);
    }
    if let Some(cid) = patch.afk_channel_id {
        sep.push(r#""afkChannelId" = "#).push_bind_unseparated(cid);
    }
    if let Some(timeout) = patch.afk_timeout {
        sep.push(r#""afkTimeout" = "#)
            .push_bind_unseparated(timeout);
    }
    if let Some(notif) = patch.default_message_notifications {
        sep.push(r#""defaultMessageNotifications" = "#)
            .push_bind_unseparated(notif);
    }
    qb.push(r#" WHERE id = "#)
        .push_bind(server_id)
        .push(r#" RETURNING *"#);
    Ok(qb
        .build_query_as::<ServerRow>()
        .fetch_one(&state.pool)
        .await?)
}

/// Leave a server under your own steam.
///
/// Distinct from kick_member, which refuses self-targeting and demands
/// KICK_MEMBERS - leaving needs neither. The owner cannot leave, since that
/// would strand the server without one; they delete it instead.
pub async fn leave_server(state: &AppState, server_id: &str, user_id: &str) -> AppResult<()> {
    let owner: Option<String> =
        sqlx::query_scalar(r#"SELECT "ownerId" FROM "Server" WHERE id = $1"#)
            .bind(server_id)
            .fetch_optional(&state.pool)
            .await?;
    let owner_id = owner.ok_or_else(|| AppError::NotFound("Server not found".into()))?;
    if owner_id == user_id {
        return Err(AppError::Permission(
            "The owner cannot leave their own server - delete it instead".into(),
        ));
    }

    let deleted =
        sqlx::query(r#"DELETE FROM "ServerMember" WHERE "serverId" = $1 AND "userId" = $2"#)
            .bind(server_id)
            .bind(user_id)
            .execute(&state.pool)
            .await?;
    if deleted.rows_affected() == 0 {
        return Err(AppError::NotFound(
            "You are not a member of this server".into(),
        ));
    }
    Ok(())
}

/// Leaves every server the user is in but does not own, in one statement.
///
/// Owned servers are skipped rather than refused: the point of the button is
/// "get me out of everyone else's servers", and an owner leaving would strand
/// theirs without one - same rule `leave_server` enforces individually.
///
/// Returns the ids left, so the caller can drop the sockets out of those rooms.
pub async fn leave_all_non_owned(state: &AppState, user_id: &str) -> AppResult<Vec<String>> {
    let rows: Vec<(String,)> = sqlx::query_as(
        r#"DELETE FROM "ServerMember" m
           USING "Server" s
           WHERE m."serverId" = s.id
             AND m."userId" = $1
             AND s."ownerId" <> $1
           RETURNING m."serverId""#,
    )
    .bind(user_id)
    .fetch_all(&state.pool)
    .await?;
    Ok(rows.into_iter().map(|(id,)| id).collect())
}

pub async fn delete_server(state: &AppState, server_id: &str, user_id: &str) -> AppResult<()> {
    let owner: Option<String> =
        sqlx::query_scalar(r#"SELECT "ownerId" FROM "Server" WHERE id = $1"#)
            .bind(server_id)
            .fetch_optional(&state.pool)
            .await?;
    let owner_id = owner.ok_or_else(|| AppError::Permission("Server not found".into()))?;
    if owner_id != user_id {
        return Err(AppError::Permission(
            "Only the owner can delete this server".into(),
        ));
    }
    sqlx::query(r#"DELETE FROM "Server" WHERE id = $1"#)
        .bind(server_id)
        .execute(&state.pool)
        .await?;
    Ok(())
}

pub async fn create_invite(
    state: &AppState,
    server_id: &str,
    inviter_id: &str,
    opts: NewInvite,
) -> AppResult<InviteRow> {
    let expires_at = opts
        .expires_in_seconds
        .map(|s| chrono::Utc::now().naive_utc() + chrono::Duration::seconds(s));

    for _ in 0..5 {
        let res = sqlx::query_as::<_, InviteRow>(
            r#"INSERT INTO "Invite" (code, "serverId", "inviterId", "expiresAt", "maxUses")
               VALUES ($1, $2, $3, $4, $5) RETURNING *"#,
        )
        .bind(invite_code())
        .bind(server_id)
        .bind(inviter_id)
        .bind(expires_at)
        .bind(opts.max_uses)
        .fetch_one(&state.pool)
        .await;
        if let Ok(row) = res {
            return Ok(row);
        }
    }
    Err(AppError::Internal(
        "Could not generate a unique invite code".into(),
    ))
}

pub async fn join_via_invite(
    state: &AppState,
    code: &str,
    user_id: &str,
) -> AppResult<Option<ServerRow>> {
    let invite = get_invite(state, code).await?;
    let status = invite_status(state, &invite, Some(user_id)).await?;
    if let Some(err) = status.reject() {
        return Err(err);
    }

    if status != InviteStatus::AlreadyMember {
        let mut tx = state.pool.begin().await?;
        sqlx::query(r#"INSERT INTO "ServerMember" (id, "serverId", "userId") VALUES ($1, $2, $3)"#)
            .bind(cuid())
            .bind(&invite.server_id)
            .bind(user_id)
            .execute(&mut *tx)
            .await?;
        sqlx::query(r#"UPDATE "Invite" SET uses = uses + 1 WHERE code = $1"#)
            .bind(code)
            .execute(&mut *tx)
            .await?;
        tx.commit().await?;
    }

    get_server(state, &invite.server_id).await
}
