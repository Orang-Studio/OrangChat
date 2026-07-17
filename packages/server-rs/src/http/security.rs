//! Account-security REST (2FA), mounted under /api/security. Requires auth.

use axum::extract::State;
use axum::routing::{get, post};
use axum::{Json, Router};
use serde_json::{json, Value};

use crate::auth::verify_password;
use crate::error::{AppError, AppResult};
use crate::http::{bad_request, AuthUser};
use crate::models::UserRow;
use crate::services::{rate_limit, totp};
use crate::state::AppState;

pub fn routes() -> Router<AppState> {
    Router::new()
        .route("/2fa", get(status))
        .route("/2fa/setup", post(setup))
        .route("/2fa/enable", post(enable))
        .route("/2fa/disable", post(disable))
        .route("/2fa/backup-codes", post(regenerate_backup_codes))
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
/// codes from it — a mis-scanned QR must never lock someone out.
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
