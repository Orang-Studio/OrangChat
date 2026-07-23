//! Storage and lifecycle for profile connections (see crate::connections for
//! the provider definitions and remote calls).
//!
//! The OAuth `state` here does more work than in the login flow. Login can keep
//! its CSRF token in a cookie because the browser is anonymous at that point;
//! linking is different - the callback arrives as a plain top-level navigation
//! with no Authorization header, so it must tell us *which* logged-in user is
//! linking. We stash that binding in Redis under the state token and look it up
//! on the way back, which keeps the user id out of the URL where the remote
//! platform (and the user) could tamper with it.

use redis::AsyncCommands;
use base64::engine::general_purpose::STANDARD;
use base64::Engine as _;
use chrono::{Duration, Utc};
use serde::{Deserialize, Serialize};

use crate::connections::{self, ConnectionProfile, OAuthGrant};
use crate::error::{AppError, AppResult};
use crate::ids::cuid;
use crate::models::ConnectionRow;
use crate::state::AppState;

/// Long enough to finish a login on the remote site, short enough that an
/// abandoned attempt cannot be replayed later.
const STATE_TTL_SECS: u64 = 600;

/// Keeps any one profile card from turning into a link farm.
const MAX_CONNECTIONS: i64 = 25;

#[derive(Serialize, Deserialize)]
struct PendingLink {
    user_id: String,
    provider: String,
    /// PKCE verifier; empty for providers that don't use it.
    verifier: String,
}

fn state_key(token: &str) -> String {
    format!("conn:state:{token}")
}

/// Mint a state token bound to this user and remember it for the callback.
pub async fn begin_link(
    state: &AppState,
    user_id: &str,
    provider: &str,
) -> AppResult<(String, String)> {
    let token = uuid::Uuid::new_v4().to_string();
    let verifier = uuid::Uuid::new_v4().simple().to_string();
    let pending = PendingLink {
        user_id: user_id.to_string(),
        provider: provider.to_string(),
        verifier: verifier.clone(),
    };
    let mut rd = state.rd();
    let _: () = rd
        .set_ex(
            state_key(&token),
            serde_json::to_string(&pending).unwrap(),
            STATE_TTL_SECS,
        )
        .await
        .map_err(|e| AppError::Internal(format!("redis set: {e}")))?;
    Ok((token, verifier))
}

/// Redeem a state token. Single-use: a replayed callback finds nothing.
async fn consume_state(state: &AppState, token: &str, provider: &str) -> AppResult<PendingLink> {
    let mut rd = state.rd();
    let raw: Option<String> = rd
        .get(state_key(token))
        .await
        .map_err(|e| AppError::Internal(format!("redis get: {e}")))?;
    let raw = raw.ok_or_else(|| AppError::BadRequest("Link request expired".into()))?;
    let _: () = rd
        .del(state_key(token))
        .await
        .map_err(|e| AppError::Internal(format!("redis del: {e}")))?;

    let pending: PendingLink =
        serde_json::from_str(&raw).map_err(|_| AppError::Internal("corrupt link state".into()))?;
    // A state minted for GitHub must not be redeemable at Spotify's callback.
    if pending.provider != provider {
        return Err(AppError::BadRequest("Link request mismatch".into()));
    }
    Ok(pending)
}

pub async fn list_for_user(state: &AppState, user_id: &str) -> AppResult<Vec<ConnectionRow>> {
    let rows = sqlx::query_as::<_, ConnectionRow>(
        r#"SELECT * FROM "Connection" WHERE "userId" = $1 ORDER BY position ASC, "createdAt" ASC"#,
    )
    .bind(user_id)
    .fetch_all(&state.pool)
    .await?;
    Ok(rows)
}

/// What another user may see: hidden connections are simply absent.
pub async fn list_visible(state: &AppState, user_id: &str) -> AppResult<Vec<ConnectionRow>> {
    let rows = sqlx::query_as::<_, ConnectionRow>(
        r#"SELECT * FROM "Connection"
           WHERE "userId" = $1 AND visible = true
           ORDER BY position ASC, "createdAt" ASC"#,
    )
    .bind(user_id)
    .fetch_all(&state.pool)
    .await?;
    Ok(rows)
}

async fn count_for_user(state: &AppState, user_id: &str) -> AppResult<i64> {
    let n: i64 = sqlx::query_scalar(r#"SELECT count(*) FROM "Connection" WHERE "userId" = $1"#)
        .bind(user_id)
        .fetch_one(&state.pool)
        .await?;
    Ok(n)
}

/// Insert the freshly authorized account, or refresh the handle if this user
/// already linked it (people rename themselves upstream and relink to update).
pub async fn upsert_verified(
    state: &AppState,
    user_id: &str,
    profile: &ConnectionProfile,
    grant: Option<&OAuthGrant>,
) -> AppResult<ConnectionRow> {
    let existing: Option<String> = if profile.provider == "spotify" {
        // One Spotify grant powers one presence. This also upgrades rows linked
        // before Spotify introduced account_id without creating a duplicate.
        sqlx::query_scalar(
            r#"SELECT id FROM "Connection"
               WHERE "userId" = $1 AND provider = 'spotify'
               ORDER BY "createdAt" ASC LIMIT 1"#,
        )
        .bind(user_id)
        .fetch_optional(&state.pool)
        .await?
    } else {
        sqlx::query_scalar(
            r#"SELECT id FROM "Connection"
               WHERE "userId" = $1 AND provider = $2 AND "providerId" = $3"#,
        )
        .bind(user_id)
        .bind(&profile.provider)
        .bind(&profile.provider_id)
        .fetch_optional(&state.pool)
        .await?
    };

    if existing.is_none() && count_for_user(state, user_id).await? >= MAX_CONNECTIONS {
        return Err(AppError::BadRequest(format!(
            "You can link at most {MAX_CONNECTIONS} accounts"
        )));
    }

    let aad = format!("oauth:{user_id}:{}:{}", profile.provider, profile.provider_id);
    let encrypt = |value: &str| -> AppResult<String> {
        let cipher = state.attachment_cipher.as_ref().ok_or_else(|| {
            AppError::Internal("OAuth token encryption is not configured".into())
        })?;
        Ok(STANDARD.encode(cipher.encrypt(value.as_bytes(), &aad)?))
    };
    let access_token = grant.map(|g| encrypt(&g.access_token)).transpose()?;
    let refresh_token = grant.and_then(|g| g.refresh_token.as_deref()).map(encrypt).transpose()?;
    let expires_at = grant.map(|g| (Utc::now() + Duration::seconds(g.expires_in)).naive_utc());
    let scope = grant.and_then(|g| g.scope.clone());

    let row = if let Some(existing_id) = existing {
        sqlx::query_as::<_, ConnectionRow>(
            r#"UPDATE "Connection"
               SET "providerId" = $2, name = $3, "profileUrl" = $4, verified = true,
                   "oauthAccessToken" = COALESCE($5, "oauthAccessToken"),
                   "oauthRefreshToken" = COALESCE($6, "oauthRefreshToken"),
                   "oauthExpiresAt" = COALESCE($7, "oauthExpiresAt"),
                   "oauthScope" = COALESCE($8, "oauthScope")
               WHERE id = $1 RETURNING *"#,
        )
        .bind(existing_id)
        .bind(&profile.provider_id)
        .bind(&profile.name)
        .bind(&profile.profile_url)
        .bind(access_token)
        .bind(refresh_token)
        .bind(expires_at)
        .bind(scope)
        .fetch_one(&state.pool)
        .await?
    } else {
        sqlx::query_as::<_, ConnectionRow>(
        r#"INSERT INTO "Connection" (id, "userId", provider, "providerId", name, "profileUrl", verified, position,
                                      "oauthAccessToken", "oauthRefreshToken", "oauthExpiresAt", "oauthScope")
           VALUES ($1, $2, $3, $4, $5, $6, true,
                   COALESCE((SELECT max(position) + 1 FROM "Connection" WHERE "userId" = $2), 0),
                   $7, $8, $9, $10)
           ON CONFLICT ("userId", provider, "providerId")
           DO UPDATE SET name = EXCLUDED.name, "profileUrl" = EXCLUDED."profileUrl", verified = true,
                         "oauthAccessToken" = COALESCE(EXCLUDED."oauthAccessToken", "Connection"."oauthAccessToken"),
                         "oauthRefreshToken" = COALESCE(EXCLUDED."oauthRefreshToken", "Connection"."oauthRefreshToken"),
                         "oauthExpiresAt" = COALESCE(EXCLUDED."oauthExpiresAt", "Connection"."oauthExpiresAt"),
                         "oauthScope" = COALESCE(EXCLUDED."oauthScope", "Connection"."oauthScope")
           RETURNING *"#,
        )
        .bind(cuid())
        .bind(user_id)
        .bind(&profile.provider)
        .bind(&profile.provider_id)
        .bind(&profile.name)
        .bind(&profile.profile_url)
        .bind(access_token)
        .bind(refresh_token)
        .bind(expires_at)
        .bind(scope)
        .fetch_one(&state.pool)
        .await?
    };
    Ok(row)
}

/// Hand-entered link. Never verified - the user typed a URL, which proves
/// nothing about who owns it, and the profile card says so.
pub async fn add_custom(
    state: &AppState,
    user_id: &str,
    name: &str,
    url: &str,
) -> AppResult<ConnectionRow> {
    if count_for_user(state, user_id).await? >= MAX_CONNECTIONS {
        return Err(AppError::BadRequest(format!(
            "You can link at most {MAX_CONNECTIONS} accounts"
        )));
    }
    let row = sqlx::query_as::<_, ConnectionRow>(
        r#"INSERT INTO "Connection" (id, "userId", provider, "providerId", name, "profileUrl", verified, position)
           VALUES ($1, $2, $3, $4, $5, $4, false,
                   COALESCE((SELECT max(position) + 1 FROM "Connection" WHERE "userId" = $2), 0))
           ON CONFLICT ("userId", provider, "providerId")
           DO UPDATE SET name = EXCLUDED.name
           RETURNING *"#,
    )
    .bind(cuid())
    .bind(user_id)
    .bind(connections::CUSTOM)
    .bind(url)
    .bind(name)
    .fetch_one(&state.pool)
    .await?;
    Ok(row)
}

pub async fn set_visible(
    state: &AppState,
    user_id: &str,
    id: &str,
    visible: bool,
) -> AppResult<ConnectionRow> {
    let row = sqlx::query_as::<_, ConnectionRow>(
        r#"UPDATE "Connection" SET visible = $3 WHERE id = $1 AND "userId" = $2 RETURNING *"#,
    )
    .bind(id)
    .bind(user_id)
    .bind(visible)
    .fetch_optional(&state.pool)
    .await?;
    row.ok_or_else(|| AppError::NotFound("Connection not found".into()))
}

pub async fn remove(state: &AppState, user_id: &str, id: &str) -> AppResult<String> {
    let provider: Option<String> = sqlx::query_scalar(
        r#"DELETE FROM "Connection" WHERE id = $1 AND "userId" = $2 RETURNING provider"#,
    )
        .bind(id)
        .bind(user_id)
        .fetch_optional(&state.pool)
        .await?;
    provider.ok_or_else(|| AppError::NotFound("Connection not found".into()))
}

/// Finish an OAuth 2.0 link: verify state, trade the code, store the handle.
pub async fn complete_oauth(
    state: &AppState,
    provider_key: &str,
    code: &str,
    state_token: &str,
) -> AppResult<ConnectionRow> {
    let provider = connections::find(provider_key)
        .ok_or_else(|| AppError::NotFound("Unknown provider".into()))?;
    let pending = consume_state(state, state_token, provider_key).await?;
    let authorized =
        connections::exchange_code_for_profile(&state.config, provider, code, &pending.verifier)
            .await?;
    let grant = (provider_key == "spotify").then_some(&authorized.grant);
    upsert_verified(state, &pending.user_id, &authorized.profile, grant).await
}

/// Finish a Steam OpenID link.
pub async fn complete_steam(
    state: &AppState,
    params: &std::collections::HashMap<String, String>,
) -> AppResult<ConnectionRow> {
    let token = params
        .get("state")
        .ok_or_else(|| AppError::BadRequest("Missing state".into()))?;
    let pending = consume_state(state, token, "steam").await?;
    let profile = connections::verify_steam_callback(&state.config, params).await?;
    upsert_verified(state, &pending.user_id, &profile, None).await
}
