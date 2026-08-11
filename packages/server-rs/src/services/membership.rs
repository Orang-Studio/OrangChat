
use crate::error::{AppError, AppResult};
use crate::models::ChannelOverwriteRow;
use crate::permissions::{self, ALL_PERMISSIONS, DEFAULT_EVERYONE_PERMISSIONS};
use crate::state::AppState;

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
        return Ok(Some(base));
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

    let role_id_set: std::collections::HashSet<&String> = role_ids.iter().collect();
    Ok(Some(apply_overwrites(
        base,
        overwrites.iter(),
        &role_id_set,
        everyone_id.as_ref(),
        user_id,
    )))
}

fn apply_overwrites<'a>(
    base: i64,
    mut overwrites: impl Iterator<Item = &'a ChannelOverwriteRow> + Clone,
    role_id_set: &std::collections::HashSet<&String>,
    everyone_id: Option<&String>,
    user_id: &str,
) -> i64 {
    let mut perms = base;

    if let Some(eid) = everyone_id {
        if let Some(ow) = overwrites
            .clone()
            .find(|o| o.ow_type == "role" && &o.target_id == eid)
        {
            perms = (perms & !ow.deny) | ow.allow;
        }
    }

    let mut role_allow = 0i64;
    let mut role_deny = 0i64;
    for ow in overwrites.clone() {
        if ow.ow_type == "role"
            && Some(&ow.target_id) != everyone_id
            && role_id_set.contains(&ow.target_id)
        {
            role_allow |= ow.allow;
            role_deny |= ow.deny;
        }
    }
    perms = (perms & !role_deny) | role_allow;

    if let Some(ow) = overwrites.find(|o| o.ow_type == "member" && o.target_id == user_id) {
        perms = (perms & !ow.deny) | ow.allow;
    }

    perms
}

pub async fn viewable_text_channels(
    state: &AppState,
    server_id: &str,
    user_id: &str,
) -> AppResult<Vec<String>> {
    let Some(base) = effective_permissions(state, server_id, user_id).await? else {
        return Ok(vec![]);
    };

    let channels: Vec<String> =
        sqlx::query_scalar(r#"SELECT id FROM "Channel" WHERE "serverId" = $1 AND type = 'text'"#)
            .bind(server_id)
            .fetch_all(&state.pool)
            .await?;

    if permissions::has_permission(base, ALL_PERMISSIONS) {
        return Ok(channels);
    }

    let overwrites: Vec<ChannelOverwriteRow> = sqlx::query_as(
        r#"SELECT o.* FROM "ChannelOverwrite" o
           JOIN "Channel" c ON c.id = o."channelId"
           WHERE c."serverId" = $1"#,
    )
    .bind(server_id)
    .fetch_all(&state.pool)
    .await?;

    let role_ids: Vec<String> = sqlx::query_scalar(
        r#"SELECT mr."roleId" FROM "MemberRole" mr
           JOIN "ServerMember" sm ON sm.id = mr."memberId"
           WHERE sm."serverId" = $1 AND sm."userId" = $2"#,
    )
    .bind(server_id)
    .bind(user_id)
    .fetch_all(&state.pool)
    .await?;

    let everyone_id: Option<String> = sqlx::query_scalar(
        r#"SELECT id FROM "Role" WHERE "serverId" = $1 AND position = 0 LIMIT 1"#,
    )
    .bind(server_id)
    .fetch_optional(&state.pool)
    .await?;

    let role_id_set: std::collections::HashSet<&String> = role_ids.iter().collect();
    let mut by_channel: std::collections::HashMap<&str, Vec<&ChannelOverwriteRow>> =
        std::collections::HashMap::new();
    for ow in &overwrites {
        by_channel
            .entry(ow.channel_id.as_str())
            .or_default()
            .push(ow);
    }

    Ok(channels
        .into_iter()
        .filter(|cid| {
            let empty: Vec<&ChannelOverwriteRow> = Vec::new();
            let ows = by_channel.get(cid.as_str()).unwrap_or(&empty);
            let perms = apply_overwrites(
                base,
                ows.iter().copied(),
                &role_id_set,
                everyone_id.as_ref(),
                user_id,
            );
            permissions::has_permission(perms, permissions::VIEW_CHANNEL)
        })
        .collect())
}

pub async fn is_server_member(state: &AppState, server_id: &str, user_id: &str) -> AppResult<bool> {
    Ok(effective_permissions(state, server_id, user_id)
        .await?
        .is_some())
}


pub const OWNER_POSITION: i32 = i32::MAX;

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
    use crate::permissions::{
        ADMINISTRATOR, BAN_MEMBERS, KICK_MEMBERS, MANAGE_ROLES, SEND_MESSAGES,
    };

    #[test]
    fn grants_only_permissions_the_actor_holds() {
        let actor = MANAGE_ROLES | KICK_MEMBERS;
        assert!(assert_no_escalation(actor, 0, KICK_MEMBERS).is_ok());
        assert!(assert_no_escalation(actor, 0, BAN_MEMBERS).is_err());
    }

    #[test]
    fn blocks_self_promotion_to_administrator() {
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
        let actor = MANAGE_ROLES;
        assert!(assert_no_escalation(actor, BAN_MEMBERS, BAN_MEMBERS).is_ok());
    }

    #[test]
    fn revoking_a_permission_the_actor_lacks_is_blocked() {
        let actor = MANAGE_ROLES;
        assert!(assert_no_escalation(actor, BAN_MEMBERS, 0).is_err());
    }

    #[test]
    fn owner_position_outranks_every_real_role() {
        const {
            assert!(OWNER_POSITION > i32::MAX - 1);
            assert!(OWNER_POSITION > 100_000);
        }
    }
}
