//! Profile badges. The catalog mirrors `@orangchat/shared`'s badges.ts — the
//! slug is the contract, everything user-facing (label, colour, copy) lives on
//! the client so renaming a badge never touches the database.
//!
//! Badges sit in a `TEXT[]` on the user row, so they ride along with every
//! existing `SELECT * FROM "User"` and the DTO mappers stay synchronous.

use crate::error::AppResult;
use crate::state::AppState;

pub const EARLY_DEVELOPER: &str = "early_developer";
pub const EARLY_MEMBER: &str = "early_member";
pub const BONFIRE: &str = "bonfire";

pub const CATALOG: [&str; 3] = [EARLY_DEVELOPER, EARLY_MEMBER, BONFIRE];

pub fn is_known(slug: &str) -> bool {
    CATALOG.contains(&slug)
}

/// Badges a brand-new account starts with.
///
/// `early_member` is awarded while the user table is still under
/// `EARLY_MEMBER_LIMIT`. The count races with concurrent signups, which is
/// harmless: the limit is a soft cutoff for a cosmetic badge, not a quota.
pub async fn initial_badges(state: &AppState) -> AppResult<Vec<String>> {
    let limit = state.config.early_member_limit;
    if limit == 0 {
        return Ok(Vec::new());
    }
    let (count,): (i64,) = sqlx::query_as(r#"SELECT count(*) FROM "User""#)
        .fetch_one(&state.pool)
        .await?;
    Ok(if count < limit {
        vec![EARLY_MEMBER.to_string()]
    } else {
        Vec::new()
    })
}

/// Adds a badge, ignoring unknown slugs and ones the user already has.
/// Returns the user's badge list as it stands afterwards.
pub async fn grant(state: &AppState, user_id: &str, slug: &str) -> AppResult<Vec<String>> {
    if !is_known(slug) {
        return current(state, user_id).await;
    }
    let updated: Option<(Vec<String>,)> = sqlx::query_as(
        r#"UPDATE "User"
           SET badges = array_append(badges, $2), "updatedAt" = now()
           WHERE id = $1 AND NOT (badges @> ARRAY[$2])
           RETURNING badges"#,
    )
    .bind(user_id)
    .bind(slug)
    .fetch_optional(&state.pool)
    .await?;

    match updated {
        Some((badges,)) => Ok(badges),
        // No row updated → the user already had it (or doesn't exist).
        None => current(state, user_id).await,
    }
}

/// Removes a badge. No-op when the user doesn't have it.
pub async fn revoke(state: &AppState, user_id: &str, slug: &str) -> AppResult<Vec<String>> {
    sqlx::query(
        r#"UPDATE "User"
           SET badges = array_remove(badges, $2), "updatedAt" = now()
           WHERE id = $1"#,
    )
    .bind(user_id)
    .bind(slug)
    .execute(&state.pool)
    .await?;
    current(state, user_id).await
}

/// Reconciles the hand-awarded badges against config at boot.
///
/// `early_member` is excluded — it's earned at signup, and folding it in here
/// would strip it from everyone not named in the env. For the two manual badges
/// the env list is the whole truth: a user dropped from it loses the badge, so
/// awards stay declarative instead of accumulating in the database by hand.
///
/// Returns (granted, revoked) counts for the startup log.
pub async fn sync_configured(state: &AppState) -> AppResult<(usize, usize)> {
    let configured = [
        (EARLY_DEVELOPER, &state.config.early_developer_emails),
        (BONFIRE, &state.config.bonfire_emails),
    ];

    let mut granted = 0;
    let mut revoked = 0;
    for (slug, emails) in configured {
        // Untouched when the env var is absent — an unset list means "leave this
        // badge alone", not "revoke it from everyone".
        let Some(emails) = emails else { continue };

        let holders: Vec<(String,)> =
            sqlx::query_as(r#"SELECT id FROM "User" WHERE badges @> ARRAY[$1]"#)
                .bind(slug)
                .fetch_all(&state.pool)
                .await?;
        let intended: Vec<(String,)> =
            sqlx::query_as(r#"SELECT id FROM "User" WHERE lower(email) = ANY($1)"#)
                .bind(emails)
                .fetch_all(&state.pool)
                .await?;

        let holders: Vec<String> = holders.into_iter().map(|(id,)| id).collect();
        let intended: Vec<String> = intended.into_iter().map(|(id,)| id).collect();

        for id in intended.iter().filter(|id| !holders.contains(id)) {
            grant(state, id, slug).await?;
            granted += 1;
        }
        for id in holders.iter().filter(|id| !intended.contains(id)) {
            revoke(state, id, slug).await?;
            revoked += 1;
        }
    }
    Ok((granted, revoked))
}

async fn current(state: &AppState, user_id: &str) -> AppResult<Vec<String>> {
    let row: Option<(Vec<String>,)> = sqlx::query_as(r#"SELECT badges FROM "User" WHERE id = $1"#)
        .bind(user_id)
        .fetch_optional(&state.pool)
        .await?;
    Ok(row.map(|(b,)| b).unwrap_or_default())
}
