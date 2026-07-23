//! JWT signing/verification, argon2 password hashing, and the Redis
//! refresh-token rotation store. Mirrors src/auth/* in the TS server.

use argon2::password_hash::rand_core::OsRng;
use argon2::password_hash::{PasswordHash, PasswordHasher, PasswordVerifier, SaltString};
use argon2::{Algorithm, Argon2, Params, Version};
use axum_extra::extract::cookie::{Cookie, SameSite};
use chrono::Utc;
use jsonwebtoken::{decode, encode, DecodingKey, EncodingKey, Header, Validation};
use redis::AsyncCommands;
use serde::{Deserialize, Serialize};

use crate::config::Config;
use crate::error::{AppError, AppResult};
use crate::state::AppState;

const ISSUER: &str = "orangchat";
pub const REFRESH_COOKIE: &str = "oc_refresh";
const COOKIE_PATH: &str = "/api/auth";

// ── Password (argon2id) ─────────────────────────────────

fn argon2() -> Argon2<'static> {
    // memoryCost 19456 KiB, timeCost 2, parallelism 1 - matches password.ts.
    let params = Params::new(19_456, 2, 1, None).expect("valid argon2 params");
    Argon2::new(Algorithm::Argon2id, Version::V0x13, params)
}

pub fn hash_password(plain: &str) -> AppResult<String> {
    let salt = SaltString::generate(&mut OsRng);
    argon2()
        .hash_password(plain.as_bytes(), &salt)
        .map(|h| h.to_string())
        .map_err(|e| AppError::Internal(format!("hash error: {e}")))
}

pub fn verify_password(hash: &str, plain: &str) -> bool {
    match PasswordHash::new(hash) {
        Ok(parsed) => Argon2::default()
            .verify_password(plain.as_bytes(), &parsed)
            .is_ok(),
        Err(_) => false,
    }
}

// ── JWT ─────────────────────────────────────────────────

#[derive(Debug, Serialize, Deserialize)]
pub struct AccessClaims {
    pub sub: String,
    pub username: String,
    pub iss: String,
    pub iat: i64,
    pub exp: i64,
}

#[derive(Debug, Serialize, Deserialize)]
pub struct RefreshClaims {
    pub sub: String,
    pub jti: String,
    pub iss: String,
    pub iat: i64,
    pub exp: i64,
}

fn validation() -> Validation {
    let mut v = Validation::new(jsonwebtoken::Algorithm::HS256);
    v.set_issuer(&[ISSUER]);
    v.validate_aud = false;
    v
}

pub fn sign_access_token(cfg: &Config, user_id: &str, username: &str) -> AppResult<String> {
    let now = Utc::now().timestamp();
    let claims = AccessClaims {
        sub: user_id.to_string(),
        username: username.to_string(),
        iss: ISSUER.to_string(),
        iat: now,
        exp: now + cfg.access_ttl_seconds,
    };
    encode(
        &Header::default(),
        &claims,
        &EncodingKey::from_secret(cfg.jwt_access_secret.as_bytes()),
    )
    .map_err(|e| AppError::Internal(format!("jwt sign: {e}")))
}

/// Returns (token, jti) so the caller can register it in the rotation store.
pub fn sign_refresh_token(cfg: &Config, user_id: &str) -> AppResult<(String, String)> {
    let now = Utc::now().timestamp();
    let jti = uuid::Uuid::new_v4().to_string();
    let claims = RefreshClaims {
        sub: user_id.to_string(),
        jti: jti.clone(),
        iss: ISSUER.to_string(),
        iat: now,
        exp: now + cfg.refresh_ttl_seconds,
    };
    let token = encode(
        &Header::default(),
        &claims,
        &EncodingKey::from_secret(cfg.jwt_refresh_secret.as_bytes()),
    )
    .map_err(|e| AppError::Internal(format!("jwt sign: {e}")))?;
    Ok((token, jti))
}

pub fn verify_access_token(cfg: &Config, token: &str) -> AppResult<AccessClaims> {
    decode::<AccessClaims>(
        token,
        &DecodingKey::from_secret(cfg.jwt_access_secret.as_bytes()),
        &validation(),
    )
    .map(|d| d.claims)
    .map_err(|_| AppError::Unauthorized("Invalid or expired access token".into()))
}

pub fn verify_refresh_token(cfg: &Config, token: &str) -> AppResult<RefreshClaims> {
    decode::<RefreshClaims>(
        token,
        &DecodingKey::from_secret(cfg.jwt_refresh_secret.as_bytes()),
        &validation(),
    )
    .map(|d| d.claims)
    .map_err(|_| AppError::Unauthorized("Invalid refresh token".into()))
}

// ── Refresh-token rotation store (Redis) ────────────────

fn key(jti: &str) -> String {
    format!("refresh:{jti}")
}
fn user_set(user_id: &str) -> String {
    format!("refresh:user:{user_id}")
}

/// What a live session records about the device holding it. Stored as JSON at
/// `refresh:{jti}` so the sessions screen has something to show beyond an
/// opaque id.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SessionRecord {
    pub user_id: String,
    /// Raw User-Agent, kept verbatim; the client turns it into a device name.
    #[serde(default)]
    pub user_agent: Option<String>,
    #[serde(default)]
    pub ip: Option<String>,
    #[serde(default)]
    pub created_at: Option<String>,
    #[serde(default)]
    pub last_seen_at: Option<String>,
}

/// Reads a session record, tolerating the bare-user-id values written before
/// sessions carried metadata. Those pre-date this format and would otherwise
/// have to be revoked on deploy, signing everyone out for a cosmetic change.
pub async fn read_session(state: &AppState, jti: &str) -> AppResult<Option<SessionRecord>> {
    let mut con = state.rd();
    let stored: Option<String> = con.get(key(jti)).await?;
    Ok(stored.map(|raw| {
        serde_json::from_str::<SessionRecord>(&raw).unwrap_or(SessionRecord {
            user_id: raw,
            user_agent: None,
            ip: None,
            created_at: None,
            last_seen_at: None,
        })
    }))
}

/// `created_at` carries the original sign-in time across refresh rotation.
/// Refresh burns the old jti and mints a new one every time the access token
/// expires, so without this every device would look like it appeared minutes
/// ago and "signed in since" would be meaningless.
pub async fn register_refresh_token(
    state: &AppState,
    jti: &str,
    user_id: &str,
    user_agent: Option<String>,
    ip: Option<String>,
    created_at: Option<String>,
) -> AppResult<()> {
    let ttl = state.config.refresh_ttl_seconds;
    let now = Utc::now().to_rfc3339();
    let record = SessionRecord {
        user_id: user_id.to_string(),
        user_agent,
        ip,
        created_at: Some(created_at.unwrap_or_else(|| now.clone())),
        last_seen_at: Some(now),
    };
    let encoded =
        serde_json::to_string(&record).map_err(|e| AppError::Internal(format!("session: {e}")))?;

    let mut con = state.rd();
    let _: () = con.set_ex(key(jti), encoded, ttl as u64).await?;
    let _: () = con.sadd(user_set(user_id), jti).await?;
    let _: () = con.expire(user_set(user_id), ttl).await?;
    Ok(())
}

pub async fn is_refresh_token_valid(state: &AppState, jti: &str, user_id: &str) -> AppResult<bool> {
    Ok(read_session(state, jti)
        .await?
        .is_some_and(|s| s.user_id == user_id))
}

/// Every live session for a user, newest first, paired with its jti.
///
/// The set can outlive its entries - a key expires on its own TTL while the id
/// lingers in the set - so misses are swept as they're found rather than left
/// to show up as phantom devices.
pub async fn list_sessions(
    state: &AppState,
    user_id: &str,
) -> AppResult<Vec<(String, SessionRecord)>> {
    let jtis: Vec<String> = {
        let mut con = state.rd();
        con.smembers(user_set(user_id)).await?
    };

    let mut out = Vec::new();
    for jti in jtis {
        match read_session(state, &jti).await? {
            Some(record) if record.user_id == user_id => out.push((jti, record)),
            Some(_) => {}
            None => {
                let mut con = state.rd();
                let _: () = con.srem(user_set(user_id), &jti).await?;
            }
        }
    }
    out.sort_by(|a, b| b.1.created_at.cmp(&a.1.created_at));
    Ok(out)
}

pub async fn revoke_refresh_token(state: &AppState, jti: &str, user_id: &str) -> AppResult<()> {
    let mut con = state.rd();
    let _: () = con.del(key(jti)).await?;
    let _: () = con.srem(user_set(user_id), jti).await?;
    Ok(())
}

/// Signs every session out, optionally sparing the one making the request.
///
/// Used when a credential changes: whoever knew the old password may still hold
/// a live refresh token, and rotating the secret is pointless if their session
/// survives it. Returns how many were revoked.
pub async fn revoke_all_refresh_tokens(
    state: &AppState,
    user_id: &str,
    except_jti: Option<&str>,
) -> AppResult<usize> {
    let mut con = state.rd();
    let jtis: Vec<String> = con.smembers(user_set(user_id)).await?;

    let mut revoked = 0;
    for jti in jtis {
        if Some(jti.as_str()) == except_jti {
            continue;
        }
        let _: () = con.del(key(&jti)).await?;
        let _: () = con.srem(user_set(user_id), &jti).await?;
        revoked += 1;
    }
    Ok(revoked)
}

// ── Refresh cookie ──────────────────────────────────────

pub fn set_refresh_cookie(cfg: &Config, token: String) -> Cookie<'static> {
    Cookie::build((REFRESH_COOKIE, token))
        .http_only(true)
        .secure(cfg.is_prod())
        .same_site(SameSite::Lax)
        .path(COOKIE_PATH)
        .max_age(time::Duration::seconds(cfg.refresh_ttl_seconds))
        .build()
}

pub fn clear_refresh_cookie() -> Cookie<'static> {
    Cookie::build((REFRESH_COOKIE, ""))
        .path(COOKIE_PATH)
        .max_age(time::Duration::seconds(0))
        .build()
}
