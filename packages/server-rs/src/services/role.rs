//! Role + membership-role + nickname persistence. Mirrors role-service.ts.

use sqlx::QueryBuilder;

use crate::error::{AppError, AppResult};
use crate::ids::cuid;
use crate::models::{RoleRow, ServerMemberRow, UserRow};
use crate::permissions::{self, MANAGE_ROLES, MODERATE_MEMBERS};
use crate::services::membership;
use crate::services::server::MemberDetail;
use crate::state::AppState;

pub struct NewRole {
    pub name: String,
    pub color: Option<i32>,
    pub permissions: Option<String>,
    pub hoist: Option<bool>,
    pub mentionable: Option<bool>,
}

#[derive(Default)]
pub struct RolePatch {
    pub name: Option<String>,
    pub color: Option<i32>,
    pub permissions: Option<String>,
    pub position: Option<i32>,
    pub hoist: Option<bool>,
    pub mentionable: Option<bool>,
}

/// New roles land at the bottom of the hierarchy (just above @everyone) and push
/// everything else up, as Discord does. Creating at the *top* would mint a role
/// outranking its own creator, which they then could not edit.
pub async fn create_role(
    state: &AppState,
    server_id: &str,
    actor_id: &str,
    input: NewRole,
) -> AppResult<RoleRow> {
    let perms = input
        .permissions
        .as_deref()
        .map(permissions::parse)
        .unwrap_or(0);
    let actor_perms = membership::require_permission(state, server_id, actor_id, MANAGE_ROLES).await?;
    membership::assert_no_escalation(actor_perms, 0, perms)?;

    let mut tx = state.pool.begin().await?;
    sqlx::query(r#"UPDATE "Role" SET position = position + 1 WHERE "serverId" = $1 AND position >= 1"#)
        .bind(server_id)
        .execute(&mut *tx)
        .await?;
    let row = sqlx::query_as::<_, RoleRow>(
        r#"INSERT INTO "Role" (id, "serverId", name, color, permissions, position, hoist, mentionable)
           VALUES ($1, $2, $3, $4, $5, 1, $6, $7) RETURNING *"#,
    )
    .bind(cuid())
    .bind(server_id)
    .bind(&input.name)
    .bind(input.color.unwrap_or(0))
    .bind(perms)
    .bind(input.hoist.unwrap_or(false))
    .bind(input.mentionable.unwrap_or(false))
    .fetch_one(&mut *tx)
    .await?;
    tx.commit().await?;
    Ok(row)
}

pub async fn update_role(
    state: &AppState,
    server_id: &str,
    role_id: &str,
    actor_id: &str,
    patch: RolePatch,
) -> AppResult<RoleRow> {
    let role: Option<RoleRow> = sqlx::query_as(r#"SELECT * FROM "Role" WHERE id = $1"#)
        .bind(role_id)
        .fetch_optional(&state.pool)
        .await?;
    let role = role.filter(|r| r.server_id == server_id);
    let role = role.ok_or_else(|| AppError::Permission("Role not found".into()))?;
    if role.position == 0 && patch.position.is_some() {
        return Err(AppError::Permission(
            "Cannot move the @everyone role".into(),
        ));
    }

    let actor_perms = membership::require_permission(state, server_id, actor_id, MANAGE_ROLES).await?;
    // @everyone (position 0) is below everyone by construction, so the hierarchy
    // check would reject every edit to it; its permissions are still guarded by
    // the escalation check below.
    if role.position != 0 {
        membership::assert_role_below(state, server_id, actor_id, role.position).await?;
    }
    if let Some(ref p) = patch.permissions {
        membership::assert_no_escalation(actor_perms, role.permissions, permissions::parse(p))?;
    }
    // Moving a role *to* a position must clear the same bar as editing one there,
    // or a moderator could hoist a role above themselves and then edit it freely.
    if let Some(pos) = patch.position {
        membership::assert_role_below(state, server_id, actor_id, pos).await?;
    }

    if patch.name.is_none()
        && patch.color.is_none()
        && patch.permissions.is_none()
        && patch.position.is_none()
        && patch.hoist.is_none()
        && patch.mentionable.is_none()
    {
        return Ok(role);
    }

    let mut qb: QueryBuilder<sqlx::Postgres> = QueryBuilder::new(r#"UPDATE "Role" SET "#);
    let mut sep = qb.separated(", ");
    if let Some(name) = patch.name {
        sep.push(r#"name = "#).push_bind_unseparated(name);
    }
    if let Some(color) = patch.color {
        sep.push(r#"color = "#).push_bind_unseparated(color);
    }
    if let Some(perms) = patch.permissions {
        sep.push(r#"permissions = "#)
            .push_bind_unseparated(permissions::parse(&perms));
    }
    if let Some(position) = patch.position {
        sep.push(r#"position = "#).push_bind_unseparated(position);
    }
    if let Some(hoist) = patch.hoist {
        sep.push(r#"hoist = "#).push_bind_unseparated(hoist);
    }
    if let Some(mentionable) = patch.mentionable {
        sep.push(r#"mentionable = "#).push_bind_unseparated(mentionable);
    }
    qb.push(r#" WHERE id = "#)
        .push_bind(role_id)
        .push(r#" RETURNING *"#);
    Ok(qb
        .build_query_as::<RoleRow>()
        .fetch_one(&state.pool)
        .await?)
}

pub async fn delete_role(
    state: &AppState,
    server_id: &str,
    role_id: &str,
    actor_id: &str,
) -> AppResult<()> {
    let role: Option<RoleRow> = sqlx::query_as(r#"SELECT * FROM "Role" WHERE id = $1"#)
        .bind(role_id)
        .fetch_optional(&state.pool)
        .await?;
    let role = role
        .filter(|r| r.server_id == server_id)
        .ok_or_else(|| AppError::Permission("Role not found".into()))?;
    if role.position == 0 {
        return Err(AppError::Permission(
            "Cannot delete the @everyone role".into(),
        ));
    }
    membership::require_permission(state, server_id, actor_id, MANAGE_ROLES).await?;
    membership::assert_role_below(state, server_id, actor_id, role.position).await?;
    sqlx::query(r#"DELETE FROM "Role" WHERE id = $1"#)
        .bind(role_id)
        .execute(&state.pool)
        .await?;
    Ok(())
}

/// Bulk reorder, for drag-and-drop role lists. Applied in one transaction so a
/// rejected entry cannot leave the hierarchy half-rewritten.
///
/// Both the old and new position of every moved role must sit below the actor:
/// checking only the destination would let someone drag a role down out of the
/// ranks above them and then edit it.
pub async fn reorder_roles(
    state: &AppState,
    server_id: &str,
    actor_id: &str,
    positions: &[(String, i32)],
) -> AppResult<Vec<RoleRow>> {
    membership::require_permission(state, server_id, actor_id, MANAGE_ROLES).await?;

    let existing: Vec<RoleRow> = sqlx::query_as(r#"SELECT * FROM "Role" WHERE "serverId" = $1"#)
        .bind(server_id)
        .fetch_all(&state.pool)
        .await?;

    for (role_id, new_pos) in positions {
        let role = existing
            .iter()
            .find(|r| &r.id == role_id)
            .ok_or_else(|| AppError::Permission("Role not found".into()))?;
        // Unchanged entries are skipped before the @everyone guard on purpose: a
        // drag-and-drop client naturally sends the whole list back, @everyone
        // included, and rejecting that would make the obvious payload unusable.
        if role.position == *new_pos {
            continue;
        }
        if role.position == 0 || *new_pos == 0 {
            return Err(AppError::Permission(
                "Cannot move the @everyone role".into(),
            ));
        }
        membership::assert_role_below(state, server_id, actor_id, role.position).await?;
        membership::assert_role_below(state, server_id, actor_id, *new_pos).await?;
    }

    let mut tx = state.pool.begin().await?;
    for (role_id, new_pos) in positions {
        sqlx::query(r#"UPDATE "Role" SET position = $1 WHERE id = $2 AND "serverId" = $3"#)
            .bind(new_pos)
            .bind(role_id)
            .bind(server_id)
            .execute(&mut *tx)
            .await?;
    }
    tx.commit().await?;

    Ok(
        sqlx::query_as(r#"SELECT * FROM "Role" WHERE "serverId" = $1 ORDER BY position DESC"#)
            .bind(server_id)
            .fetch_all(&state.pool)
            .await?,
    )
}

/// Shared gate for assign/unassign: the actor needs MANAGE_ROLES, must outrank
/// both the member and the role in question.
///
/// `granting` distinguishes the two directions deliberately:
///
/// - Granting additionally requires the role's permissions to be a subset of the
///   actor's. Without it the escalation guard on update_role is trivially
///   sidestepped — a moderator cannot *write* BAN_MEMBERS onto a role, but could
///   hand out an existing role that already has it, to a friend or to
///   themselves, and gain it by proxy.
/// - Revoking is not subject to that check. Taking a role away never grants
///   anything, and requiring BAN_MEMBERS in order to strip BAN_MEMBERS from a
///   compromised account is exactly backwards during an incident.
///
/// Self-targeting is allowed: `assert_outranks` would otherwise compare the actor
/// against themselves and refuse, leaving a non-owner admin unable to give
/// themselves so much as a colour role. The subset check above is what keeps
/// self-assignment from being an escalation.
async fn assert_can_change_member_role(
    state: &AppState,
    server_id: &str,
    actor_id: &str,
    target_id: &str,
    role: &RoleRow,
    granting: bool,
) -> AppResult<()> {
    let actor_perms = membership::require_permission(state, server_id, actor_id, MANAGE_ROLES).await?;
    if target_id != actor_id {
        membership::assert_outranks(state, server_id, actor_id, target_id).await?;
    }
    membership::assert_role_below(state, server_id, actor_id, role.position).await?;
    if role.position == 0 {
        return Err(AppError::Permission(
            "The @everyone role cannot be assigned".into(),
        ));
    }
    if granting {
        membership::assert_no_escalation(actor_perms, 0, role.permissions)?;
    }
    Ok(())
}

pub async fn load_server_role(
    state: &AppState,
    server_id: &str,
    role_id: &str,
) -> AppResult<RoleRow> {
    let role: Option<RoleRow> = sqlx::query_as(r#"SELECT * FROM "Role" WHERE id = $1"#)
        .bind(role_id)
        .fetch_optional(&state.pool)
        .await?;
    role.filter(|r| r.server_id == server_id)
        .ok_or_else(|| AppError::Permission("Role not found".into()))
}

pub async fn assign_role(
    state: &AppState,
    server_id: &str,
    user_id: &str,
    role_id: &str,
    actor_id: &str,
) -> AppResult<Option<MemberDetail>> {
    let member_id: Option<String> = sqlx::query_scalar(
        r#"SELECT id FROM "ServerMember" WHERE "serverId" = $1 AND "userId" = $2"#,
    )
    .bind(server_id)
    .bind(user_id)
    .fetch_optional(&state.pool)
    .await?;
    let member_id = member_id.ok_or_else(|| AppError::Permission("Member not found".into()))?;

    let role = load_server_role(state, server_id, role_id).await?;
    assert_can_change_member_role(state, server_id, actor_id, user_id, &role, true).await?;

    sqlx::query(
        r#"INSERT INTO "MemberRole" (id, "memberId", "roleId") VALUES ($1, $2, $3)
           ON CONFLICT ("memberId", "roleId") DO NOTHING"#,
    )
    .bind(cuid())
    .bind(&member_id)
    .bind(role_id)
    .execute(&state.pool)
    .await?;

    get_member_with_roles(state, server_id, user_id).await
}

pub async fn unassign_role(
    state: &AppState,
    server_id: &str,
    user_id: &str,
    role_id: &str,
    actor_id: &str,
) -> AppResult<Option<MemberDetail>> {
    let member_id: Option<String> = sqlx::query_scalar(
        r#"SELECT id FROM "ServerMember" WHERE "serverId" = $1 AND "userId" = $2"#,
    )
    .bind(server_id)
    .bind(user_id)
    .fetch_optional(&state.pool)
    .await?;
    let member_id = member_id.ok_or_else(|| AppError::Permission("Member not found".into()))?;

    let role = load_server_role(state, server_id, role_id).await?;
    assert_can_change_member_role(state, server_id, actor_id, user_id, &role, false).await?;

    sqlx::query(r#"DELETE FROM "MemberRole" WHERE "memberId" = $1 AND "roleId" = $2"#)
        .bind(&member_id)
        .bind(role_id)
        .execute(&state.pool)
        .await?;

    get_member_with_roles(state, server_id, user_id).await
}

pub async fn set_nickname(
    state: &AppState,
    server_id: &str,
    user_id: &str,
    nickname: Option<String>,
) -> AppResult<Option<MemberDetail>> {
    let member_id: Option<String> = sqlx::query_scalar(
        r#"SELECT id FROM "ServerMember" WHERE "serverId" = $1 AND "userId" = $2"#,
    )
    .bind(server_id)
    .bind(user_id)
    .fetch_optional(&state.pool)
    .await?;
    let Some(member_id) = member_id else {
        return Ok(None);
    };
    let value = nickname.filter(|n| !n.is_empty());
    sqlx::query(r#"UPDATE "ServerMember" SET nickname = $1 WHERE id = $2"#)
        .bind(&value)
        .bind(&member_id)
        .execute(&state.pool)
        .await?;
    get_member_with_roles(state, server_id, user_id).await
}

/// Time a member out, or lift it with `until = None`.
///
/// Gated on MODERATE_MEMBERS *and* hierarchy: the permission says you may time
/// people out, the hierarchy says whom. Admins are exempt as targets — a timeout
/// they could instantly lift is theatre — which also stops a moderator from
/// silencing the owner.
pub async fn set_timeout(
    state: &AppState,
    server_id: &str,
    user_id: &str,
    actor_id: &str,
    until: Option<chrono::NaiveDateTime>,
) -> AppResult<Option<MemberDetail>> {
    membership::require_permission(state, server_id, actor_id, MODERATE_MEMBERS).await?;
    if user_id == actor_id {
        return Err(AppError::Permission("You cannot time yourself out".into()));
    }
    membership::assert_outranks(state, server_id, actor_id, user_id).await?;

    let target_perms = membership::effective_permissions(state, server_id, user_id)
        .await?
        .ok_or_else(|| AppError::Permission("Member not found".into()))?;
    if permissions::has_permission(target_perms, permissions::ADMINISTRATOR) {
        return Err(AppError::Permission(
            "Cannot time out a member with ADMINISTRATOR".into(),
        ));
    }

    let updated = sqlx::query(
        r#"UPDATE "ServerMember" SET "timedOutUntil" = $1 WHERE "serverId" = $2 AND "userId" = $3"#,
    )
    .bind(until)
    .bind(server_id)
    .bind(user_id)
    .execute(&state.pool)
    .await?;
    if updated.rows_affected() == 0 {
        return Ok(None);
    }
    get_member_with_roles(state, server_id, user_id).await
}

pub async fn get_member_with_roles(
    state: &AppState,
    server_id: &str,
    user_id: &str,
) -> AppResult<Option<MemberDetail>> {
    let member: Option<ServerMemberRow> =
        sqlx::query_as(r#"SELECT * FROM "ServerMember" WHERE "serverId" = $1 AND "userId" = $2"#)
            .bind(server_id)
            .bind(user_id)
            .fetch_optional(&state.pool)
            .await?;
    let Some(member) = member else {
        return Ok(None);
    };
    let user: UserRow = sqlx::query_as(r#"SELECT * FROM "User" WHERE id = $1"#)
        .bind(user_id)
        .fetch_one(&state.pool)
        .await?;
    let role_ids: Vec<String> =
        sqlx::query_scalar(r#"SELECT "roleId" FROM "MemberRole" WHERE "memberId" = $1"#)
            .bind(&member.id)
            .fetch_all(&state.pool)
            .await?;
    Ok(Some(MemberDetail {
        member,
        user,
        role_ids,
    }))
}
