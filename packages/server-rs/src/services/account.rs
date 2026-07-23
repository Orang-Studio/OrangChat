//! Account deletion.
//!
//! Deleting the row is not an option: `Message.authorId` is ON DELETE CASCADE,
//! so it would pull the user's entire history out of conversations other people
//! are still reading. Instead the account is tombstoned - everything
//! identifying is scrubbed, the username is freed for reuse, personal side
//! tables are emptied, and `deletedAt` marks the row so nothing can sign in as
//! it again. What survives is the authorship link on messages, which is the
//! whole point.

use crate::auth;
use crate::error::{AppError, AppResult};
use crate::ids::cuid;
use crate::state::AppState;

/// Servers the user owns. Ownership is ON DELETE RESTRICT, and inheriting or
/// destroying a community as a side effect of one person leaving is not a
/// decision this endpoint should make - so it refuses and names them instead.
pub async fn owned_server_names(state: &AppState, user_id: &str) -> AppResult<Vec<String>> {
    let rows: Vec<(String,)> =
        sqlx::query_as(r#"SELECT name FROM "Server" WHERE "ownerId" = $1 ORDER BY name"#)
            .bind(user_id)
            .fetch_all(&state.pool)
            .await?;
    Ok(rows.into_iter().map(|(n,)| n).collect())
}

/// Tombstones the account. Idempotent-ish: a second call on an already-deleted
/// account is refused by the caller's `deletedAt` check.
pub async fn delete_account(state: &AppState, user_id: &str) -> AppResult<()> {
    let owned = owned_server_names(state, user_id).await?;
    if !owned.is_empty() {
        return Err(AppError::Conflict(format!(
            "Transfer or delete these servers first: {}",
            owned.join(", ")
        )));
    }

    // A freed username has to stay unique, and the placeholder email has to
    // survive the lower(email) unique index, so both get a cuid suffix.
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

    // Everything here is personal to the account and meaningless once it's gone.
    // Messages, reactions and audit entries are deliberately absent: those are
    // part of other people's conversations or of the moderation record.
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

    // Drafts ship in a migration that isn't applied everywhere yet. Their FK
    // cascade never fires here (the row survives), so they have to be cleared
    // explicitly - but only where the table actually exists.
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

    // Outside the transaction: Redis isn't part of it, and a token left live
    // after a committed deletion is worse than one revoked before a rollback.
    auth::revoke_all_refresh_tokens(state, user_id, None).await?;

    Ok(())
}
