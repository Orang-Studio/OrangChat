//! Account-security REST (2FA), mounted under /api/security. Requires auth.

use axum::extract::State;
use axum::routing::{delete, get, post};
use axum::{Json, Router};
use serde_json::{json, Value};

use crate::auth::verify_password;
use crate::error::{AppError, AppResult};
use crate::http::{bad_request, valid_email, AuthUser};
use crate::models::UserRow;
use crate::services::{account, rate_limit, totp};
use crate::state::AppState;

pub fn routes() -> Router<AppState> {
    Router::new()
        .route("/2fa", get(status))
        .route("/2fa/setup", post(setup))
        .route("/2fa/enable", post(enable))
        .route("/2fa/disable", post(disable))
        .route("/2fa/backup-codes", post(regenerate_backup_codes))
        .route("/password", post(change_password))
        .route("/email", post(change_email))
        .route("/account", delete(delete_account))
}

/// Tombstones the account. Gated on the password, a 2FA code when enabled, and
/// the username typed back - deletion is irreversible, so it asks for something
/// no accidental click supplies.
async fn delete_account(
    State(state): State<AppState>,
    user: AuthUser,
    Json(body): Json<Value>,
) -> AppResult<Json<Value>> {
    limit(&state, &user.user_id).await?;
    let row = fetch_user(&state, &user.user_id).await?;
    if row.deleted_at.is_some() {
        return Err(bad_request("This account is already deleted"));
    }

    if field(&body, "username").unwrap_or_default() != row.username {
        return Err(bad_request("Type your username exactly to confirm"));
    }
    check_password(&row, &body)?;
    check_totp(&state, &row, &body).await?;

    account::delete_account(&state, &user.user_id).await?;
    Ok(Json(json!({ "deleted": true })))
}

/// A live 6-digit code (or a backup code), required alongside the password on
/// credential changes whenever 2FA is on. Without it, a stolen password alone
/// would be enough to take the account over.
async fn check_totp(state: &AppState, row: &UserRow, body: &Value) -> AppResult<()> {
    if !row.totp_enabled {
        return Ok(());
    }
    let code = field(body, "code").unwrap_or_default();
    let secret = row.totp_secret.as_deref().unwrap_or_default();
    let ok = totp::verify_code(secret, &row.email, code)?
        || totp::consume_backup_code(state, &row.id, code).await?;
    if !ok {
        return Err(bad_request("That code isn't right. Try the current one."));
    }
    Ok(())
}

/// Sets or replaces the password. OAuth-only accounts have none to confirm, so
/// for them this is "set a password" and the session is the only proof.
async fn change_password(
    State(state): State<AppState>,
    user: AuthUser,
    Json(body): Json<Value>,
) -> AppResult<Json<Value>> {
    limit(&state, &user.user_id).await?;
    let row = fetch_user(&state, &user.user_id).await?;

    let new_password = field(&body, "newPassword").unwrap_or_default();
    if new_password.len() < 8 || new_password.len() > 200 {
        return Err(bad_request("Password must be 8-200 characters"));
    }

    check_password(&row, &body)?;
    check_totp(&state, &row, &body).await?;

    let hash = crate::auth::hash_password(new_password)?;
    sqlx::query(r#"UPDATE "User" SET "passwordHash" = $2, "updatedAt" = now() WHERE id = $1"#)
        .bind(&user.user_id)
        .bind(&hash)
        .execute(&state.pool)
        .await?;

    // Anyone signed in with the old password keeps a working refresh token
    // otherwise, which would make the change cosmetic.
    let revoked = crate::auth::revoke_all_refresh_tokens(&state, &user.user_id, None).await?;

    Ok(Json(json!({ "ok": true, "sessionsRevoked": revoked })))
}

/// Changes the address on the account. There's no mail transport in this
/// deployment, so the new address can't be proven by a confirmation link -
/// password (plus 2FA when enabled) is the whole gate.
async fn change_email(
    State(state): State<AppState>,
    user: AuthUser,
    Json(body): Json<Value>,
) -> AppResult<Json<Value>> {
    limit(&state, &user.user_id).await?;
    let row = fetch_user(&state, &user.user_id).await?;

    let email = field(&body, "email").unwrap_or_default();
    if !valid_email(email) {
        return Err(bad_request("That doesn't look like an email address"));
    }
    // Canonical lowercase, to agree with the lower(email) index - see signup.
    let email = email.to_lowercase();

    check_password(&row, &body)?;
    check_totp(&state, &row, &body).await?;

    if email == row.email.to_lowercase() {
        return Ok(Json(json!({ "email": row.email })));
    }

    let taken: Option<String> =
        sqlx::query_scalar(r#"SELECT id FROM "User" WHERE lower(email) = $1 AND id <> $2"#)
            .bind(&email)
            .bind(&user.user_id)
            .fetch_optional(&state.pool)
            .await?;
    if taken.is_some() {
        return Err(AppError::Conflict("Email already in use".into()));
    }

    sqlx::query(r#"UPDATE "User" SET email = $2, "updatedAt" = now() WHERE id = $1"#)
        .bind(&user.user_id)
        .bind(&email)
        .execute(&state.pool)
        .await?;

    Ok(Json(json!({ "email": email })))
}

/// Every route here verifies a password or a 6-digit code, so they all share one
/// per-user budget rather than each getting its own.
async fn limit(state: &AppState, user_id: &str) -> AppResult<()> {
    rate_limit::check(state, "2fa", user_id, rate_limit::TOTP_PER_USER).await
}

async fn fetch_user(state: &AppState, user_id: &str) -> AppResult<UserRow> {
    sqlx::query_as(r#"SELECT * FROM "User" WHERE id = $1"#)
        .bind(user_id)
        .fetch_one(&state.pool)
        .await
        .map_err(Into::into)
}

fn field<'a>(body: &'a Value, key: &str) -> Option<&'a str> {
    body.get(key).and_then(Value::as_str)
}

/// OAuth-only accounts have no password, so the session is the only proof there is.
fn check_password(user: &UserRow, body: &Value) -> AppResult<()> {
    let Some(hash) = user.password_hash.as_deref() else {
        return Ok(());
    };
    if !verify_password(hash, field(body, "password").unwrap_or_default()) {
        return Err(AppError::Unauthorized("Incorrect password".into()));
    }
    Ok(())
}

async fn status(State(state): State<AppState>, user: AuthUser) -> AppResult<Json<Value>> {
    let row = fetch_user(&state, &user.user_id).await?;
    Ok(Json(json!({
        "enabled": row.totp_enabled,
        "backupCodesRemaining": totp::count_unused_backup_codes(&state, &user.user_id).await?,
    })))
}

/// Stashes a secret but leaves 2FA off until `enable` proves the user can read
/// codes from it - a mis-scanned QR must never lock someone out.
async fn setup(
    State(state): State<AppState>,
    user: AuthUser,
    body: Option<Json<Value>>,
) -> AppResult<Json<Value>> {
    limit(&state, &user.user_id).await?;
    let row = fetch_user(&state, &user.user_id).await?;
    if row.totp_enabled {
        return Err(AppError::Conflict(
            "Two-factor authentication is already enabled".into(),
        ));
    }
    check_password(&row, &body.map(|Json(v)| v).unwrap_or(json!({})))?;

    let secret = totp::generate_secret();
    let uri = totp::provisioning_uri(&secret, &row.email)?;
    sqlx::query(r#"UPDATE "User" SET "totpSecret" = $1, "updatedAt" = now() WHERE id = $2"#)
        .bind(&secret)
        .bind(&user.user_id)
        .execute(&state.pool)
        .await?;

    Ok(Json(json!({ "secret": secret, "otpauthUrl": uri })))
}

async fn enable(
    State(state): State<AppState>,
    user: AuthUser,
    Json(body): Json<Value>,
) -> AppResult<Json<Value>> {
    limit(&state, &user.user_id).await?;
    let row = fetch_user(&state, &user.user_id).await?;
    if row.totp_enabled {
        return Err(AppError::Conflict(
            "Two-factor authentication is already enabled".into(),
        ));
    }
    let secret = row
        .totp_secret
        .as_deref()
        .ok_or_else(|| bad_request("Start setup before enabling two-factor authentication"))?;

    let code = field(&body, "code").ok_or_else(|| bad_request("Invalid input"))?;
    if !totp::verify_code(secret, &row.email, code)? {
        return Err(bad_request("That code isn't right. Try the current one."));
    }

    sqlx::query(r#"UPDATE "User" SET "totpEnabled" = true, "updatedAt" = now() WHERE id = $1"#)
        .bind(&user.user_id)
        .execute(&state.pool)
        .await?;
    let codes = totp::regenerate_backup_codes(&state, &user.user_id).await?;

    Ok(Json(json!({ "enabled": true, "backupCodes": codes })))
}

/// Password *and* a live code, so a hijacked session can't strip the second factor.
async fn disable(
    State(state): State<AppState>,
    user: AuthUser,
    Json(body): Json<Value>,
) -> AppResult<Json<Value>> {
    limit(&state, &user.user_id).await?;
    let row = fetch_user(&state, &user.user_id).await?;
    if !row.totp_enabled {
        return Ok(Json(json!({ "enabled": false })));
    }
    check_password(&row, &body)?;

    let code = field(&body, "code").unwrap_or_default();
    let secret = row.totp_secret.as_deref().unwrap_or_default();
    let ok = totp::verify_code(secret, &row.email, code)?
        || totp::consume_backup_code(&state, &user.user_id, code).await?;
    if !ok {
        return Err(bad_request("That code isn't right. Try the current one."));
    }

    sqlx::query(
        r#"UPDATE "User" SET "totpEnabled" = false, "totpSecret" = NULL, "updatedAt" = now()
           WHERE id = $1"#,
    )
    .bind(&user.user_id)
    .execute(&state.pool)
    .await?;
    totp::clear_backup_codes(&state, &user.user_id).await?;

    Ok(Json(json!({ "enabled": false })))
}

async fn regenerate_backup_codes(
    State(state): State<AppState>,
    user: AuthUser,
    Json(body): Json<Value>,
) -> AppResult<Json<Value>> {
    limit(&state, &user.user_id).await?;
    let row = fetch_user(&state, &user.user_id).await?;
    if !row.totp_enabled {
        return Err(bad_request("Two-factor authentication isn't enabled"));
    }
    check_password(&row, &body)?;

    let code = field(&body, "code").unwrap_or_default();
    let secret = row.totp_secret.as_deref().unwrap_or_default();
    if !totp::verify_code(secret, &row.email, code)? {
        return Err(bad_request("That code isn't right. Try the current one."));
    }

    Ok(Json(
        json!({ "backupCodes": totp::regenerate_backup_codes(&state, &user.user_id).await? }),
    ))
}
