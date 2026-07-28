//! Profile badges. The catalog mirrors `@orangchat/shared`'s badges.ts - the
//! slug is the contract, everything user-facing (label, artwork, copy) lives on
//! the client so renaming a badge never touches the database.
//!
//! Badges sit in a `TEXT[]` on the user row, so they ride along with every
//! existing `SELECT * FROM "User"` and the DTO mappers stay synchronous.

use std::collections::HashMap;

use crate::error::AppResult;
use crate::state::AppState;

pub const BETA: &str = "beta";
pub const FOUNDER: &str = "founder";
pub const DEVELOPER: &str = "developer";
pub const BUGHUNTER: &str = "bughunter";
pub const CONTRIBUTOR: &str = "contributor";
pub const BONFIRE: &str = "bonfire";
pub const BOT: &str = "bot";

pub const CATALOG: [&str; 7] = [
    BETA,
    FOUNDER,
    DEVELOPER,
    BUGHUNTER,
    CONTRIBUTOR,
    BONFIRE,
    BOT,
];

pub fn is_known(slug: &str) -> bool {
    CATALOG.contains(&slug)
}

/// Badges a brand-new account starts with.
///
/// `beta` is awarded to everyone for now - the app is still in beta, so every
/// signup counts as an early member. When that stops being true this becomes a
/// cutoff (a count/date gate) rather than an unconditional grant.
pub fn initial_badges() -> Vec<String> {
    vec![BETA.to_string()]
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

/// Reconciles the hand-awarded badges against the badges file at boot.
///
/// The file maps a badge slug to the user IDs that should hold it. For every
/// badge listed, the file is the whole truth: a user named in the list is
/// granted the badge, a holder not named loses it - so awards stay declarative
/// instead of accumulating in the database by hand. A badge whose key is absent
/// from the file (or a file that doesn't exist) is left untouched.
///
/// `beta` is deliberately excluded: it's earned automatically at signup, and
/// reconciling it here would strip it from everyone not named in the file.
///
/// Returns (granted, revoked) counts for the startup log.
pub async fn sync_from_file(state: &AppState) -> AppResult<(usize, usize)> {
    let Some(configured) = load_file(&state.config.badges_file) else {
        return Ok((0, 0));
    };

    let mut granted = 0;
    let mut revoked = 0;
    for (slug, ids) in configured {
        // Unknown keys (including any comment fields) and the auto-earned
        // beta badge are not file-managed.
        if !is_known(&slug) || slug == BETA {
            continue;
        }

        let holders: Vec<(String,)> =
            sqlx::query_as(r#"SELECT id FROM "User" WHERE badges @> ARRAY[$1]"#)
                .bind(&slug)
                .fetch_all(&state.pool)
                .await?;
        let holders: Vec<String> = holders.into_iter().map(|(id,)| id).collect();

        for id in ids.iter().filter(|id| !holders.contains(id)) {
            grant(state, id, &slug).await?;
            granted += 1;
        }
        for id in holders.iter().filter(|id| !ids.contains(id)) {
            revoke(state, id, &slug).await?;
            revoked += 1;
        }
    }
    Ok((granted, revoked))
}

/// Reads and parses the badges file. Returns `None` when the file is absent or
/// unreadable/unparseable - all of which mean "leave every badge alone" rather
/// than "revoke everything", so a typo can't wipe the roster on the next boot.
fn load_file(path: &str) -> Option<HashMap<String, Vec<String>>> {
    let raw = std::fs::read_to_string(path).ok()?;
    match serde_json::from_str::<HashMap<String, Vec<String>>>(&raw) {
        Ok(map) => Some(map),
        Err(err) => {
            tracing::warn!(%path, %err, "badges file is not valid JSON; skipping badge sync");
            None
        }
    }
}

async fn current(state: &AppState, user_id: &str) -> AppResult<Vec<String>> {
    let row: Option<(Vec<String>,)> = sqlx::query_as(r#"SELECT badges FROM "User" WHERE id = $1"#)
        .bind(user_id)
        .fetch_optional(&state.pool)
        .await?;
    Ok(row.map(|(b,)| b).unwrap_or_default())
}
