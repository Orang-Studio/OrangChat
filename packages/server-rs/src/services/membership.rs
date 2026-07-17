//! Effective-permission computation, mirroring membership-service.ts.

use crate::error::{AppError, AppResult};
use crate::models::ChannelOverwriteRow;
use crate::permissions::{self, ALL_PERMISSIONS, DEFAULT_EVERYONE_PERMISSIONS};
use crate::state::AppState;

/// Effective server-wide permission bitfield for a member, or None if not a member.
pub async fn effective_permissions(
    state: &AppState,
    server_id: &str,
    user_id: &str,
) -> AppResult<Option<i64>> {
    let owner: Option<String> =
        sqlx::query_scalar(r#"SELECT "ownerId" FROM "Server" WHERE id = $1"#)
            .bind(server_id)
            .fetch_optional(&state.pool)
            .await?;
    let Some(owner_id) = owner else {
        return Ok(None);
    };
    if owner_id == user_id {
        return Ok(Some(ALL_PERMISSIONS));
    }

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

    let role_perms: Vec<i64> = sqlx::query_scalar(
        r#"SELECT r.permissions FROM "MemberRole" mr
           JOIN "Role" r ON r.id = mr."roleId"
           WHERE mr."memberId" = $1"#,
    )
    .bind(&member_id)
    .fetch_all(&state.pool)
    .await?;

    let everyone: Option<i64> = sqlx::query_scalar(
        r#"SELECT permissions FROM "Role" WHERE "serverId" = $1 AND position = 0 LIMIT 1"#,
    )
    .bind(server_id)
    .fetch_optional(&state.pool)
    .await?;

    let mut bitfields = vec![everyone.unwrap_or(DEFAULT_EVERYONE_PERMISSIONS)];
    bitfields.extend(role_perms);
    Ok(Some(permissions::combine(&bitfields)))
}

/// Channel-scoped effective permissions with overwrites layered on, Discord-style.
pub async fn channel_permissions(
    state: &AppState,
    channel_id: &str,
    user_id: &str,
) -> AppResult<Option<i64>> {
    let server_id: Option<String> =
        sqlx::query_scalar(r#"SELECT "serverId" FROM "Channel" WHERE id = $1"#)
            .bind(channel_id)
            .fetch_optional(&state.pool)
            .await?
            .flatten();
    let Some(server_id) = server_id else {
        return Ok(None);
    };

    let base = match effective_permissions(state, &server_id, user_id).await? {
        Some(b) => b,
        None => return Ok(None),
    };
    if permissions::has_permission(base, ALL_PERMISSIONS) {
        return Ok(Some(base)); // admin/owner bypass
    }

    let overwrites: Vec<ChannelOverwriteRow> =
        sqlx::query_as(r#"SELECT * FROM "ChannelOverwrite" WHERE "channelId" = $1"#)
            .bind(channel_id)
            .fetch_all(&state.pool)
            .await?;

    let role_ids: Vec<String> = sqlx::query_scalar(
        r#"SELECT mr."roleId" FROM "MemberRole" mr
           JOIN "ServerMember" sm ON sm.id = mr."memberId"
           WHERE sm."serverId" = $1 AND sm."userId" = $2"#,
    )
    .bind(&server_id)
    .bind(user_id)
    .fetch_all(&state.pool)
    .await?;

    let everyone_id: Option<String> = sqlx::query_scalar(
        r#"SELECT id FROM "Role" WHERE "serverId" = $1 AND position = 0 LIMIT 1"#,
    )
    .bind(&server_id)
    .fetch_optional(&state.pool)
    .await?;

    let mut perms = base;
    let role_id_set: std::collections::HashSet<&String> = role_ids.iter().collect();

    // 1. @everyone role overwrite.
    if let Some(ref eid) = everyone_id {
        if let Some(ow) = overwrites
            .iter()
            .find(|o| o.ow_type == "role" && &o.target_id == eid)
        {
            perms = (perms & !ow.deny) | ow.allow;
        }
    }

    // 2. Union of the member's other role overwrites.
    let mut role_allow = 0i64;
    let mut role_deny = 0i64;
    for ow in &overwrites {
        if ow.ow_type == "role"
            && Some(&ow.target_id) != everyone_id.as_ref()
            && role_id_set.contains(&ow.target_id)
        {
            role_allow |= ow.allow;
            role_deny |= ow.deny;
        }
    }
    perms = (perms & !role_deny) | role_allow;

    // 3. Member-specific overwrite.
    if let Some(ow) = overwrites
        .iter()
        .find(|o| o.ow_type == "member" && o.target_id == user_id)
    {
        perms = (perms & !ow.deny) | ow.allow;
    }

    Ok(Some(perms))
}

pub async fn is_server_member(state: &AppState, server_id: &str, user_id: &str) -> AppResult<bool> {
    Ok(effective_permissions(state, server_id, user_id)
        .await?
        .is_some())
}

// ── Hierarchy ───────────────────────────────────────────
//
// MANAGE_ROLES / KICK_MEMBERS / BAN_MEMBERS say *whether* someone may act, never
// *on whom*. Without a second axis any moderator can edit the owner's roles or
// grant themselves ADMINISTRATOR, so every mutation below is additionally gated
// on position: you may only act strictly below your own highest role.

pub const OWNER_POSITION: i32 = i32::MAX;

/// `OWNER_POSITION` for the owner, None if not a member. Roleless members sit at
/// @everyone's 0.
pub async fn highest_role_position(
    state: &AppState,
    server_id: &str,
    user_id: &str,
) -> AppResult<Option<i32>> {
    let owner: Option<String> =
        sqlx::query_scalar(r#"SELECT "ownerId" FROM "Server" WHERE id = $1"#)
            .bind(server_id)
            .fetch_optional(&state.pool)
            .await?;
    let Some(owner_id) = owner else {
        return Ok(None);
    };
    if owner_id == user_id {
        return Ok(Some(OWNER_POSITION));
    }

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

    let highest: Option<i32> = sqlx::query_scalar(
        r#"SELECT MAX(r.position) FROM "MemberRole" mr
           JOIN "Role" r ON r.id = mr."roleId"
           WHERE mr."memberId" = $1"#,
    )
    .bind(&member_id)
    .fetch_one(&state.pool)
    .await?;
    Ok(Some(highest.unwrap_or(0)))
}

/// Assert the actor may act on a role at `role_position`. Strict inequality:
/// holding a role does not let you edit it, matching Discord.
pub async fn assert_role_below(
    state: &AppState,
    server_id: &str,
    actor_id: &str,
    role_position: i32,
) -> AppResult<()> {
    let actor = highest_role_position(state, server_id, actor_id)
        .await?
        .ok_or_else(|| AppError::Permission("Not a member of this server".into()))?;
    if actor <= role_position {
        return Err(AppError::Permission(
            "You cannot manage a role at or above your highest role".into(),
        ));
    }
    Ok(())
}

/// Assert the actor may moderate `target_id` (kick/ban/timeout/nickname/roles).
pub async fn assert_outranks(
    state: &AppState,
    server_id: &str,
    actor_id: &str,
    target_id: &str,
) -> AppResult<()> {
    let actor = highest_role_position(state, server_id, actor_id)
        .await?
        .ok_or_else(|| AppError::Permission("Not a member of this server".into()))?;
    if actor == OWNER_POSITION {
        return Ok(());
    }
    // A non-member has no roles to outrank. Bans legitimately target people who
    // were never here (pre-emptive bans); callers that require membership, like
    // kick, check for it themselves.
    let Some(target) = highest_role_position(state, server_id, target_id).await? else {
        return Ok(());
    };
    if actor <= target {
        return Err(AppError::Permission(
            "You cannot moderate a member with an equal or higher role".into(),
        ));
    }
    Ok(())
}

/// Reject the action if the member is timed out. Timeouts are server-wide, so DM
/// channels are never affected.
pub async fn assert_not_timed_out(
    state: &AppState,
    channel: &crate::models::ChannelRow,
    user_id: &str,
) -> AppResult<()> {
    let Some(ref server_id) = channel.server_id else {
        return Ok(());
    };
    let until: Option<Option<chrono::NaiveDateTime>> = sqlx::query_scalar(
        r#"SELECT "timedOutUntil" FROM "ServerMember" WHERE "serverId" = $1 AND "userId" = $2"#,
    )
    .bind(server_id)
    .bind(user_id)
    .fetch_optional(&state.pool)
    .await?;

    if let Some(Some(until)) = until {
        if until > chrono::Utc::now().naive_utc() {
            return Err(AppError::Permission(format!(
                "You are timed out in this server until {}",
                crate::timefmt::iso(until)
            )));
        }
    }
    Ok(())
}

/// Assert the actor is not handing out permissions they do not hold themselves.
///
/// Compares the *delta*, not the new value: a moderator may rename a role that
/// already carries BAN_MEMBERS without holding it, but may neither add nor strip
/// that bit. ADMINISTRATOR implies every bit, so admins pass unconditionally.
pub fn assert_no_escalation(actor_perms: i64, old_perms: i64, new_perms: i64) -> AppResult<()> {
    if permissions::has_permission(actor_perms, permissions::ADMINISTRATOR) {
        return Ok(());
    }
    let changed = old_perms ^ new_perms;
    if changed & !actor_perms != 0 {
        return Err(AppError::Permission(
            "You cannot grant or revoke permissions you do not have".into(),
        ));
    }
    Ok(())
}

/// Assert `required` permissions; returns the effective bitfield or a 403.
pub async fn require_permission(
    state: &AppState,
    server_id: &str,
    user_id: &str,
    required: i64,
) -> AppResult<i64> {
    match effective_permissions(state, server_id, user_id).await? {
        None => Err(AppError::Permission("Not a member of this server".into())),
        Some(perms) if !permissions::has_permission(perms, required) => {
            Err(AppError::Permission("Missing permission".into()))
        }
        Some(perms) => Ok(perms),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::permissions::{ADMINISTRATOR, BAN_MEMBERS, KICK_MEMBERS, MANAGE_ROLES, SEND_MESSAGES};

    #[test]
    fn grants_only_permissions_the_actor_holds() {
        let actor = MANAGE_ROLES | KICK_MEMBERS;
        assert!(assert_no_escalation(actor, 0, KICK_MEMBERS).is_ok());
        // BAN_MEMBERS is not the actor's to give.
        assert!(assert_no_escalation(actor, 0, BAN_MEMBERS).is_err());
    }

    #[test]
    fn blocks_self_promotion_to_administrator() {
        // The bug this guard exists for: MANAGE_ROLES alone used to be enough to
        // write ADMINISTRATOR onto any role, including one's own.
        let actor = MANAGE_ROLES;
        assert!(assert_no_escalation(actor, 0, ADMINISTRATOR).is_err());
        assert!(assert_no_escalation(actor, SEND_MESSAGES, SEND_MESSAGES | ADMINISTRATOR).is_err());
    }

    #[test]
    fn administrator_may_grant_anything() {
        assert!(assert_no_escalation(ADMINISTRATOR, 0, ALL_PERMISSIONS).is_ok());
    }

    #[test]
    fn untouched_bits_the_actor_lacks_are_allowed() {
        // Renaming a role that already carries BAN_MEMBERS must not require
        // holding BAN_MEMBERS -- only the delta is checked.
        let actor = MANAGE_ROLES;
        assert!(assert_no_escalation(actor, BAN_MEMBERS, BAN_MEMBERS).is_ok());
    }

    #[test]
    fn revoking_a_permission_the_actor_lacks_is_blocked() {
        // Stripping is as much a privilege as granting: it must clear the same bar.
        let actor = MANAGE_ROLES;
        assert!(assert_no_escalation(actor, BAN_MEMBERS, 0).is_err());
    }

    #[test]
    fn owner_position_outranks_every_real_role() {
        assert!(OWNER_POSITION > i32::MAX - 1);
        assert!(OWNER_POSITION > 100_000);
    }
}
