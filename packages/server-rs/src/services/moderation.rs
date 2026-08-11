
use crate::error::{AppError, AppResult};
use crate::ids::cuid;
use crate::models::{BanRow, UserRow};
use crate::services::membership;
use crate::state::AppState;

async fn assert_targetable(
    state: &AppState,
    server_id: &str,
    target_user_id: &str,
    actor_user_id: &str,
) -> AppResult<()> {
    let owner: Option<String> =
        sqlx::query_scalar(r#"SELECT "ownerId" FROM "Server" WHERE id = $1"#)
            .bind(server_id)
            .fetch_optional(&state.pool)
            .await?;
    let owner_id = owner.ok_or_else(|| AppError::Permission("Server not found".into()))?;
    if target_user_id == owner_id {
        return Err(AppError::Permission(
            "Cannot moderate the server owner".into(),
        ));
    }
    if target_user_id == actor_user_id {
        return Err(AppError::Permission("Cannot moderate yourself".into()));
    }
    membership::assert_outranks(state, server_id, actor_user_id, target_user_id).await?;
    Ok(())
}

pub async fn kick_member(
    state: &AppState,
    server_id: &str,
    target_user_id: &str,
    actor_user_id: &str,
) -> AppResult<()> {
    assert_targetable(state, server_id, target_user_id, actor_user_id).await?;
    let exists: Option<String> = sqlx::query_scalar(
        r#"SELECT id FROM "ServerMember" WHERE "serverId" = $1 AND "userId" = $2"#,
    )
    .bind(server_id)
    .bind(target_user_id)
    .fetch_optional(&state.pool)
    .await?;
    if exists.is_none() {
        return Err(AppError::Permission("Member not found".into()));
    }
    sqlx::query(r#"DELETE FROM "ServerMember" WHERE "serverId" = $1 AND "userId" = $2"#)
        .bind(server_id)
        .bind(target_user_id)
        .execute(&state.pool)
        .await?;
    Ok(())
}

pub async fn ban_member(
    state: &AppState,
    server_id: &str,
    target_user_id: &str,
    actor_user_id: &str,
    reason: Option<&str>,
) -> AppResult<()> {
    assert_targetable(state, server_id, target_user_id, actor_user_id).await?;
    let mut tx = state.pool.begin().await?;
    sqlx::query(
        r#"INSERT INTO "Ban" (id, "serverId", "userId", "bannedById", reason)
           VALUES ($1, $2, $3, $4, $5)
           ON CONFLICT ("serverId", "userId")
           DO UPDATE SET reason = EXCLUDED.reason, "bannedById" = EXCLUDED."bannedById""#,
    )
    .bind(cuid())
    .bind(server_id)
    .bind(target_user_id)
    .bind(actor_user_id)
    .bind(reason)
    .execute(&mut *tx)
    .await?;
    sqlx::query(r#"DELETE FROM "ServerMember" WHERE "serverId" = $1 AND "userId" = $2"#)
        .bind(server_id)
        .bind(target_user_id)
        .execute(&mut *tx)
        .await?;
    tx.commit().await?;
    Ok(())
}

pub async fn unban_member(
    state: &AppState,
    server_id: &str,
    target_user_id: &str,
) -> AppResult<()> {
    sqlx::query(r#"DELETE FROM "Ban" WHERE "serverId" = $1 AND "userId" = $2"#)
        .bind(server_id)
        .bind(target_user_id)
        .execute(&state.pool)
        .await?;
    Ok(())
}

pub async fn list_bans(state: &AppState, server_id: &str) -> AppResult<Vec<(BanRow, UserRow)>> {
    let bans: Vec<BanRow> =
        sqlx::query_as(r#"SELECT * FROM "Ban" WHERE "serverId" = $1 ORDER BY "createdAt" DESC"#)
            .bind(server_id)
            .fetch_all(&state.pool)
            .await?;
    let mut out = Vec::with_capacity(bans.len());
    for b in bans {
        let user: UserRow = sqlx::query_as(r#"SELECT * FROM "User" WHERE id = $1"#)
            .bind(&b.user_id)
            .fetch_one(&state.pool)
            .await?;
        out.push((b, user));
    }
    Ok(out)
}
