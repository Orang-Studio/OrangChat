//! Bot accounts and their API tokens.
//!
//! A bot is a `User` row with `isBot = true` and an `ownerId`, not a separate
//! entity. Message authorship, server membership, roles and the permission
//! bitfield all key on `User`; giving bots their own table would mean a parallel
//! nullable column on every one of those and a branch at every read. The cost of
//! this choice is that a bot row has to be kept out of the human account
//! surfaces deliberately - see `http::deny_bots` and the notes below.

use base64::Engine;
use rand::RngCore;
use sha2::{Digest, Sha256};

use crate::error::{AppError, AppResult};
use crate::ids::cuid;
use crate::state::AppState;

use super::badge;

/// How many bots one account may own. Each one is a real user row that can be
/// invited into servers, so this is a spam ceiling, not a licensing decision.
const MAX_BOTS_PER_OWNER: i64 = 25;

/// Tokens per bot. More than one so a token can be rotated without downtime:
/// mint the new one, deploy it, then revoke the old.
const MAX_TOKENS_PER_BOT: i64 = 5;

pub struct BotRow {
    pub id: String,
    pub username: String,
    pub display_name: String,
    pub avatar_url: Option<String>,
    pub created_at: String,
}

/// A freshly minted token. The plaintext exists only in this struct, on the way
/// to the one response that will ever contain it.
pub struct MintedToken {
    pub id: String,
    pub token: String,
    pub hint: String,
}

fn hash_token(token: &str) -> String {
    hex::encode(Sha256::digest(token.as_bytes()))
}

/// `<base64url(bot id)>.<32 random bytes, base64url>`.
///
/// The id prefix is not a security property - it is there so the server can find
/// the right row without scanning, and so a leaked token is traceable to its bot.
/// The entropy is entirely in the second half.
fn generate_token(bot_id: &str) -> String {
    let mut secret = [0u8; 32];
    rand::thread_rng().fill_bytes(&mut secret);
    let engine = base64::engine::general_purpose::URL_SAFE_NO_PAD;
    format!("{}.{}", engine.encode(bot_id), engine.encode(secret))
}

/// The bot id a token claims to belong to. Claims, not proves - the caller still
/// has to match the digest. Returning it early just avoids a full-table scan.
fn claimed_bot_id(token: &str) -> Option<String> {
    let prefix = token.split('.').next()?;
    let raw = base64::engine::general_purpose::URL_SAFE_NO_PAD
        .decode(prefix)
        .ok()?;
    String::from_utf8(raw).ok()
}

/// Resolves a bot token to its bot id, or `None` if it matches nothing.
///
/// The digest is compared in the database by equality on a unique index. That is
/// not a constant-time comparison, but it does not need to be: the value being
/// compared is a SHA-256 of the secret, so timing tells an attacker only about
/// digests, and finding a token whose digest is close to a target is exactly the
/// preimage problem.
pub async fn authenticate(state: &AppState, token: &str) -> AppResult<Option<String>> {
    let Some(bot_id) = claimed_bot_id(token) else {
        return Ok(None);
    };
    let hash = hash_token(token);
    let found: Option<(String,)> = sqlx::query_as(
        r#"SELECT t."botId" FROM "BotToken" t
           JOIN "User" u ON u.id = t."botId"
           WHERE t."tokenHash" = $1 AND t."botId" = $2
             AND u."isBot" = true AND u."deletedAt" IS NULL"#,
    )
    .bind(&hash)
    .bind(&bot_id)
    .fetch_optional(&state.pool)
    .await?;

    let Some((id,)) = found else {
        return Ok(None);
    };

    // Best-effort: a failed touch must not fail the request it was observing.
    let _ = sqlx::query(r#"UPDATE "BotToken" SET "lastUsedAt" = now() WHERE "tokenHash" = $1"#)
        .bind(&hash)
        .execute(&state.pool)
        .await;

    Ok(Some(id))
}

pub async fn is_bot(state: &AppState, user_id: &str) -> AppResult<bool> {
    let row: Option<(bool,)> = sqlx::query_as(r#"SELECT "isBot" FROM "User" WHERE id = $1"#)
        .bind(user_id)
        .fetch_optional(&state.pool)
        .await?;
    Ok(row.is_some_and(|r| r.0))
}

pub async fn create(
    state: &AppState,
    owner_id: &str,
    username: &str,
    display_name: &str,
) -> AppResult<BotRow> {
    if !crate::http::valid_username(username) {
        return Err(AppError::BadRequest(
            "A bot username is 2-32 characters: lowercase letters, digits, _ and .".into(),
        ));
    }
    let display_name = display_name.trim();
    if display_name.is_empty() || display_name.chars().count() > 32 {
        return Err(AppError::BadRequest(
            "A bot needs a display name of 32 characters or fewer".into(),
        ));
    }

    let owned: (i64,) =
        sqlx::query_as(r#"SELECT count(*) FROM "User" WHERE "ownerId" = $1 AND "isBot" = true"#)
            .bind(owner_id)
            .fetch_one(&state.pool)
            .await?;
    if owned.0 >= MAX_BOTS_PER_OWNER {
        return Err(AppError::BadRequest(format!(
            "You can own at most {MAX_BOTS_PER_OWNER} bots"
        )));
    }

    let taken: Option<(String,)> =
        sqlx::query_as(r#"SELECT id FROM "User" WHERE lower(username) = lower($1)"#)
            .bind(username)
            .fetch_optional(&state.pool)
            .await?;
    if taken.is_some() {
        return Err(AppError::BadRequest("That username is taken".into()));
    }

    let id = cuid();
    // The email is unique and non-null on User, so a bot needs one it can never
    // receive at. `.invalid` is reserved by RFC 2606 and cannot resolve.
    let email = format!("bot+{id}@bots.orangchat.invalid");

    // `emailVerifiedAt` is not cosmetic here: AuthUser rejects any account
    // without it, so an unverified bot would 401 on every request it ever made.
    sqlx::query(
        // "updatedAt" is set explicitly: Prisma's @updatedAt is applied by the
        // Prisma client, not by a database default, so a raw INSERT that omits
        // it violates the NOT NULL constraint.
        r#"INSERT INTO "User"
           (id, email, username, "displayName", "isBot", "ownerId",
            "emailVerifiedAt", "passwordHash", "updatedAt")
           VALUES ($1, $2, $3, $4, true, $5, now(), NULL, now())"#,
    )
    .bind(&id)
    .bind(&email)
    .bind(username)
    .bind(display_name)
    .bind(owner_id)
    .execute(&state.pool)
    .await?;

    let _ = badge::grant(state, &id, badge::BOT).await;

    get(state, owner_id, &id)
        .await?
        .ok_or_else(|| AppError::Internal("bot vanished after creation".into()))
}

pub async fn list(state: &AppState, owner_id: &str) -> AppResult<Vec<BotRow>> {
    let rows: Vec<(String, String, String, Option<String>, chrono::NaiveDateTime)> =
        sqlx::query_as(
            r#"SELECT id, username, "displayName", "avatarUrl", "createdAt"
               FROM "User"
               WHERE "ownerId" = $1 AND "isBot" = true AND "deletedAt" IS NULL
               ORDER BY "createdAt" DESC"#,
        )
        .bind(owner_id)
        .fetch_all(&state.pool)
        .await?;

    Ok(rows
        .into_iter()
        .map(|(id, username, display_name, avatar_url, created_at)| BotRow {
            id,
            username,
            display_name,
            avatar_url,
            created_at: crate::timefmt::iso(created_at),
        })
        .collect())
}

/// A bot, but only for the account that owns it. Every mutating route funnels
/// through this, so ownership is checked in exactly one place.
pub async fn get(state: &AppState, owner_id: &str, bot_id: &str) -> AppResult<Option<BotRow>> {
    let row: Option<(String, String, String, Option<String>, chrono::NaiveDateTime)> =
        sqlx::query_as(
            r#"SELECT id, username, "displayName", "avatarUrl", "createdAt"
               FROM "User"
               WHERE id = $1 AND "ownerId" = $2 AND "isBot" = true AND "deletedAt" IS NULL"#,
        )
        .bind(bot_id)
        .bind(owner_id)
        .fetch_optional(&state.pool)
        .await?;

    Ok(
        row.map(
            |(id, username, display_name, avatar_url, created_at)| BotRow {
                id,
                username,
                display_name,
                avatar_url,
                created_at: crate::timefmt::iso(created_at),
            },
        ),
    )
}

async fn require_owned(state: &AppState, owner_id: &str, bot_id: &str) -> AppResult<BotRow> {
    get(state, owner_id, bot_id)
        .await?
        .ok_or_else(|| AppError::NotFound("Bot not found".into()))
}

pub async fn update(
    state: &AppState,
    owner_id: &str,
    bot_id: &str,
    display_name: Option<&str>,
    avatar_url: Option<Option<&str>>,
) -> AppResult<BotRow> {
    require_owned(state, owner_id, bot_id).await?;

    if let Some(name) = display_name {
        let name = name.trim();
        if name.is_empty() || name.chars().count() > 32 {
            return Err(AppError::BadRequest(
                "A bot needs a display name of 32 characters or fewer".into(),
            ));
        }
        sqlx::query(r#"UPDATE "User" SET "displayName" = $2, "updatedAt" = now() WHERE id = $1"#)
            .bind(bot_id)
            .bind(name)
            .execute(&state.pool)
            .await?;
    }

    if let Some(url) = avatar_url {
        sqlx::query(r#"UPDATE "User" SET "avatarUrl" = $2, "updatedAt" = now() WHERE id = $1"#)
            .bind(bot_id)
            .bind(url)
            .execute(&state.pool)
            .await?;
    }

    require_owned(state, owner_id, bot_id).await
}

/// Deletes the bot outright rather than tombstoning it the way `account` does
/// for people. A bot has no conversations of its own to keep readable, and its
/// messages survive on their channels regardless; leaving a live login behind
/// for something the owner asked to be gone would be the worse default.
pub async fn delete(state: &AppState, owner_id: &str, bot_id: &str) -> AppResult<()> {
    require_owned(state, owner_id, bot_id).await?;
    sqlx::query(r#"DELETE FROM "User" WHERE id = $1 AND "ownerId" = $2 AND "isBot" = true"#)
        .bind(bot_id)
        .bind(owner_id)
        .execute(&state.pool)
        .await?;
    Ok(())
}

pub async fn mint_token(
    state: &AppState,
    owner_id: &str,
    bot_id: &str,
) -> AppResult<MintedToken> {
    require_owned(state, owner_id, bot_id).await?;

    let count: (i64,) = sqlx::query_as(r#"SELECT count(*) FROM "BotToken" WHERE "botId" = $1"#)
        .bind(bot_id)
        .fetch_one(&state.pool)
        .await?;
    if count.0 >= MAX_TOKENS_PER_BOT {
        return Err(AppError::BadRequest(format!(
            "A bot can hold at most {MAX_TOKENS_PER_BOT} tokens - revoke one first"
        )));
    }

    let token = generate_token(bot_id);
    let hint = token.chars().rev().take(4).collect::<Vec<_>>().into_iter().rev().collect();
    let id = cuid();

    sqlx::query(r#"INSERT INTO "BotToken" (id, "botId", "tokenHash", hint) VALUES ($1, $2, $3, $4)"#)
        .bind(&id)
        .bind(bot_id)
        .bind(hash_token(&token))
        .bind(&hint)
        .execute(&state.pool)
        .await?;

    Ok(MintedToken { id, token, hint })
}

pub struct TokenRow {
    pub id: String,
    pub hint: String,
    pub created_at: String,
    pub last_used_at: Option<String>,
}

pub async fn list_tokens(
    state: &AppState,
    owner_id: &str,
    bot_id: &str,
) -> AppResult<Vec<TokenRow>> {
    require_owned(state, owner_id, bot_id).await?;
    let rows: Vec<(String, String, chrono::NaiveDateTime, Option<chrono::NaiveDateTime>)> =
        sqlx::query_as(
            r#"SELECT id, hint, "createdAt", "lastUsedAt" FROM "BotToken"
               WHERE "botId" = $1 ORDER BY "createdAt" DESC"#,
        )
        .bind(bot_id)
        .fetch_all(&state.pool)
        .await?;

    Ok(rows
        .into_iter()
        .map(|(id, hint, created_at, last_used_at)| TokenRow {
            id,
            hint,
            created_at: crate::timefmt::iso(created_at),
            last_used_at: last_used_at.map(crate::timefmt::iso),
        })
        .collect())
}

pub async fn revoke_token(
    state: &AppState,
    owner_id: &str,
    bot_id: &str,
    token_id: &str,
) -> AppResult<()> {
    require_owned(state, owner_id, bot_id).await?;
    sqlx::query(r#"DELETE FROM "BotToken" WHERE id = $1 AND "botId" = $2"#)
        .bind(token_id)
        .bind(bot_id)
        .execute(&state.pool)
        .await?;
    Ok(())
}

/// Adds a bot to a server with a dedicated role carrying `permissions`.
///
/// The requested bitfield is intersected against what the *inviter* actually
/// holds. Without that, inviting a bot is a privilege-escalation path: any
/// member with MANAGE_SERVER could mint a bot, invite it with ADMINISTRATOR, and
/// drive the server through it. This codebase has closed that class of bug once
/// already for roles - `membership::assert_no_escalation` is the same guard.
///
/// The bot gets its own role rather than inheriting @everyone so its powers are
/// visible in the role list and can be edited or removed like anything else.
pub async fn invite_to_server(
    state: &AppState,
    server_id: &str,
    inviter_id: &str,
    bot_id: &str,
    requested_permissions: i64,
) -> AppResult<()> {
    use crate::permissions::MANAGE_SERVER;
    use crate::services::{audit, membership, role};

    let inviter_perms =
        membership::require_permission(state, server_id, inviter_id, MANAGE_SERVER).await?;
    membership::assert_no_escalation(inviter_perms, 0, requested_permissions)?;

    let target: Option<(bool, String)> =
        sqlx::query_as(r#"SELECT "isBot", "displayName" FROM "User" WHERE id = $1"#)
            .bind(bot_id)
            .fetch_optional(&state.pool)
            .await?;
    let Some((is_bot_row, display_name)) = target else {
        return Err(AppError::NotFound("Bot not found".into()));
    };
    if !is_bot_row {
        return Err(AppError::BadRequest("That account is not a bot".into()));
    }

    let already: Option<(String,)> = sqlx::query_as(
        r#"SELECT id FROM "ServerMember" WHERE "serverId" = $1 AND "userId" = $2"#,
    )
    .bind(server_id)
    .bind(bot_id)
    .fetch_optional(&state.pool)
    .await?;
    if already.is_some() {
        return Err(AppError::Conflict("That bot is already in this server".into()));
    }

    let created_role = role::create_role(
        state,
        server_id,
        inviter_id,
        role::NewRole {
            name: display_name.clone(),
            color: None,
            permissions: Some(requested_permissions.to_string()),
            hoist: Some(false),
            mentionable: Some(false),
        },
    )
    .await?;

    let member_id = cuid();
    let mut tx = state.pool.begin().await?;
    sqlx::query(r#"INSERT INTO "ServerMember" (id, "serverId", "userId") VALUES ($1, $2, $3)"#)
        .bind(&member_id)
        .bind(server_id)
        .bind(bot_id)
        .execute(&mut *tx)
        .await?;
    sqlx::query(r#"INSERT INTO "MemberRole" (id, "memberId", "roleId") VALUES ($1, $2, $3)"#)
        .bind(cuid())
        .bind(&member_id)
        .bind(&created_role.id)
        .execute(&mut *tx)
        .await?;
    tx.commit().await?;

    audit::record(
        state,
        audit::Entry {
            server_id,
            actor_id: inviter_id,
            action: audit::action::BOT_ADD,
            target_id: Some(bot_id),
            target_type: Some("user"),
            changes: serde_json::json!({
                "permissions": { "new": requested_permissions.to_string() },
            }),
            reason: None,
        },
    )
    .await;

    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_token_carries_its_bot_id() {
        let token = generate_token("bot_abc123");
        assert_eq!(claimed_bot_id(&token).as_deref(), Some("bot_abc123"));
    }

    #[test]
    fn tokens_are_unique_per_mint() {
        let a = generate_token("bot_abc123");
        let b = generate_token("bot_abc123");
        assert_ne!(a, b, "two mints must never produce the same secret");
    }

    #[test]
    fn hashing_is_stable_and_hides_the_token() {
        let token = generate_token("bot_abc123");
        assert_eq!(hash_token(&token), hash_token(&token));
        assert!(!hash_token(&token).contains(&token));
        assert_eq!(hash_token(&token).len(), 64);
    }

    /// Garbage must read as "no such token", never as a panic or a match.
    #[test]
    fn malformed_tokens_claim_nothing() {
        assert_eq!(claimed_bot_id(""), Some(String::new()));
        assert_eq!(claimed_bot_id("!!!not base64!!!.xyz"), None);
        assert_eq!(claimed_bot_id("no-dot-at-all"), None);
    }
}
