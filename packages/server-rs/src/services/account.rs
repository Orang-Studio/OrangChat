
use crate::auth;
use crate::error::{AppError, AppResult};
use crate::ids::cuid;
use crate::state::AppState;

pub async fn owned_server_names(state: &AppState, user_id: &str) -> AppResult<Vec<String>> {
    let rows: Vec<(String,)> =
        sqlx::query_as(r#"SELECT name FROM "Server" WHERE "ownerId" = $1 ORDER BY name"#)
            .bind(user_id)
            .fetch_all(&state.pool)
            .await?;
    Ok(rows.into_iter().map(|(n,)| n).collect())
}

#[derive(Debug, Clone, serde::Serialize)]
#[serde(rename_all = "camelCase")]
pub struct StandingEntry {
    pub kind: String,
    pub server_id: String,
    pub server_name: String,
    pub reason: Option<String>,
    pub expires_at: Option<String>,
    pub created_at: Option<String>,
}

pub async fn standing(state: &AppState, user_id: &str) -> AppResult<Vec<StandingEntry>> {
    let bans: Vec<(String, String, Option<String>, chrono::NaiveDateTime)> = sqlx::query_as(
        r#"SELECT s.id, s.name, b.reason, b."createdAt"
           FROM "Ban" b JOIN "Server" s ON s.id = b."serverId"
           WHERE b."userId" = $1
           ORDER BY b."createdAt" DESC"#,
    )
    .bind(user_id)
    .fetch_all(&state.pool)
    .await?;

    let timeouts: Vec<(String, String, chrono::NaiveDateTime)> = sqlx::query_as(
        r#"SELECT s.id, s.name, m."timedOutUntil"
           FROM "ServerMember" m JOIN "Server" s ON s.id = m."serverId"
           WHERE m."userId" = $1 AND m."timedOutUntil" > now()
           ORDER BY m."timedOutUntil" DESC"#,
    )
    .bind(user_id)
    .fetch_all(&state.pool)
    .await?;

    let mut out: Vec<StandingEntry> = bans
        .into_iter()
        .map(
            |(server_id, server_name, reason, created_at)| StandingEntry {
                kind: "ban".into(),
                server_id,
                server_name,
                reason,
                expires_at: None,
                created_at: Some(crate::timefmt::iso(created_at)),
            },
        )
        .collect();

    out.extend(
        timeouts
            .into_iter()
            .map(|(server_id, server_name, until)| StandingEntry {
                kind: "timeout".into(),
                server_id,
                server_name,
                reason: None,
                expires_at: Some(crate::timefmt::iso(until)),
                created_at: None,
            }),
    );

    Ok(out)
}

pub async fn set_lockdown(
    state: &AppState,
    user_id: &str,
    on: bool,
    keep_jti: Option<&str>,
) -> AppResult<usize> {
    sqlx::query(
        r#"UPDATE "User" SET "lockdownAt" = CASE WHEN $2 THEN now() ELSE NULL END,
             "updatedAt" = now()
           WHERE id = $1"#,
    )
    .bind(user_id)
    .bind(on)
    .execute(&state.pool)
    .await?;

    if on {
        auth::revoke_all_refresh_tokens(state, user_id, keep_jti).await
    } else {
        Ok(0)
    }
}

pub async fn delete_all_messages(state: &AppState, user_id: &str) -> AppResult<u64> {
    let deleted = sqlx::query(r#"DELETE FROM "Message" WHERE "authorId" = $1"#)
        .bind(user_id)
        .execute(&state.pool)
        .await?;
    Ok(deleted.rows_affected())
}

pub async fn delete_account(state: &AppState, user_id: &str) -> AppResult<()> {
    let owned = owned_server_names(state, user_id).await?;
    if !owned.is_empty() {
        return Err(AppError::Conflict(format!(
            "Transfer or delete these servers first: {}",
            owned.join(", ")
        )));
    }

    let suffix = cuid();
    let placeholder_username = format!("deleted_{}", &suffix[suffix.len().saturating_sub(12)..]);
    let placeholder_email = format!("{placeholder_username}@deleted.orangchat.local");

    let mut tx = state.pool.begin().await?;

    sqlx::query(
        r#"UPDATE "User" SET
             email = $2,
             username = $3,
             "displayName" = 'Deleted User',
             "passwordHash" = NULL,
             "avatarUrl" = NULL,
             "bannerUrl" = NULL,
             bio = NULL,
             pronouns = NULL,
             "accentColor" = NULL,
             "customCss" = NULL,
             "profileCss" = NULL,
             badges = '{}',
             "totpSecret" = NULL,
             "totpEnabled" = false,
             "deletedAt" = now(),
             "updatedAt" = now()
           WHERE id = $1"#,
    )
    .bind(user_id)
    .bind(&placeholder_email)
    .bind(&placeholder_username)
    .execute(&mut *tx)
    .await?;

    for stmt in [
        r#"DELETE FROM "Connection" WHERE "userId" = $1"#,
        r#"DELETE FROM "OAuthAccount" WHERE "userId" = $1"#,
        r#"DELETE FROM "PushSubscription" WHERE "userId" = $1"#,
        r#"DELETE FROM "TotpBackupCode" WHERE "userId" = $1"#,
        r#"DELETE FROM "ReadState" WHERE "userId" = $1"#,
        r#"DELETE FROM "Friendship" WHERE "requesterId" = $1 OR "addresseeId" = $1"#,
        r#"DELETE FROM "ServerMember" WHERE "userId" = $1"#,
        r#"DELETE FROM "ChannelParticipant" WHERE "userId" = $1"#,
        r#"DELETE FROM "Invite" WHERE "inviterId" = $1"#,
        r#"DELETE FROM "PendingAttachment" WHERE "uploaderId" = $1"#,
    ] {
        sqlx::query(stmt).bind(user_id).execute(&mut *tx).await?;
    }

    let (has_drafts,): (bool,) =
        sqlx::query_as(r#"SELECT to_regclass('public."Draft"') IS NOT NULL"#)
            .fetch_one(&mut *tx)
            .await?;
    if has_drafts {
        sqlx::query(r#"DELETE FROM "Draft" WHERE "userId" = $1"#)
            .bind(user_id)
            .execute(&mut *tx)
            .await?;
    }

    tx.commit().await?;

    auth::revoke_all_refresh_tokens(state, user_id, None).await?;

    Ok(())
}
