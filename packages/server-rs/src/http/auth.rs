
use axum::extract::{Path, Query, State};
use axum::http::StatusCode;
use axum::response::{IntoResponse, Redirect};
use axum::routing::{get, post};
use axum::{Json, Router};
use axum_extra::extract::cookie::{Cookie, CookieJar, SameSite};
use serde_json::{json, Value};

use crate::auth::*;
use crate::auth_security::{
    captcha_required, random_email_code, random_token, token_hash, valid_email_code,
    verify_recaptcha,
};
use crate::dto::{to_self_user, to_user, SelfUserDto};
use crate::error::{AppError, AppResult};
use crate::http::{bad_request, valid_email, valid_username, AuthUser, ClientIp};
use crate::ids::cuid;
use crate::models::UserRow;
use crate::oauth;
use crate::services::{account, badge, passkey, presence, qr, rate_limit, totp, user};
use crate::state::AppState;

const OAUTH_STATE_COOKIE: &str = "oc_oauth_state";

pub fn routes() -> Router<AppState> {
    Router::new()
        .route("/signup", post(signup))
        .route("/login", post(login))
        .route("/login/email-2fa", post(verify_email_2fa))
        .route("/login/email-2fa/resend", post(resend_email_2fa))
        .route("/passkey/start", post(passkey_start))
        .route("/passkey/finish", post(passkey_finish))
        .route("/verify-email", get(verify_email))
        .route("/verify-email/resend", post(resend_verification))
        .route("/recaptcha/config", get(recaptcha_config))
        .route("/refresh", post(refresh))
        .route("/logout", post(logout))
        .route("/me", get(get_me).patch(patch_me))
        .route("/oauth/:provider", get(oauth_start))
        .route("/oauth/:provider/callback", get(oauth_callback))
        .route("/qr/start", post(qr_start))
        .route("/qr/poll", get(qr_poll))
        .route("/qr/scan", post(qr_scan))
        .route("/qr/approve", post(qr_approve))
        .route(
            "/sessions",
            get(list_sessions).delete(revoke_other_sessions),
        )
        .route("/sessions/:jti", axum::routing::delete(revoke_session))
        .route("/lockdown", post(set_lockdown))
}

async fn set_lockdown(
    State(state): State<AppState>,
    user: AuthUser,
    jar: CookieJar,
    Json(body): Json<Value>,
) -> AppResult<Json<Value>> {
    let on = body.get("on").and_then(Value::as_bool).unwrap_or(false);

    let row: UserRow = sqlx::query_as(r#"SELECT * FROM "User" WHERE id = $1"#)
        .bind(&user.user_id)
        .fetch_one(&state.pool)
        .await?;

    if !on {
        rate_limit::check(&state, "2fa", &user.user_id, rate_limit::TOTP_PER_USER).await?;
        if let Some(hash) = row.password_hash.as_deref() {
            let supplied = body.get("password").and_then(Value::as_str).unwrap_or("");
            if !verify_password(hash, supplied) {
                return Err(AppError::Unauthorized("Incorrect password".into()));
            }
        }
    }

    let current = current_jti(&state, &jar);
    let revoked = account::set_lockdown(&state, &user.user_id, on, current.as_deref()).await?;
    Ok(Json(json!({ "lockdown": on, "sessionsRevoked": revoked })))
}

async fn qr_start(State(state): State<AppState>, ClientIp(ip): ClientIp) -> AppResult<Json<Value>> {
    rate_limit::check(&state, "qr:start", &ip, rate_limit::LOGIN_PER_IP).await?;
    let (token, expires_in) = qr::start(&state).await?;
    Ok(Json(json!({ "token": token, "expiresIn": expires_in })))
}

async fn qr_poll(
    State(state): State<AppState>,
    headers: axum::http::HeaderMap,
    ClientIp(ip): ClientIp,
    jar: CookieJar,
    Query(params): Query<std::collections::HashMap<String, String>>,
) -> AppResult<impl IntoResponse> {
    rate_limit::check(&state, "qr:poll", &ip, rate_limit::QR_POLL_PER_IP).await?;
    let token = params.get("token").map(String::as_str).unwrap_or_default();
    match qr::poll(&state, token).await? {
        qr::PollResult::Pending => Ok((jar, Json(json!({ "status": "pending" }))).into_response()),
        qr::PollResult::Scanned => Ok((jar, Json(json!({ "status": "scanned" }))).into_response()),
        qr::PollResult::Expired => Ok((jar, Json(json!({ "status": "expired" }))).into_response()),
        qr::PollResult::Approved(user_id) => {
            let user: UserRow = sqlx::query_as(r#"SELECT * FROM "User" WHERE id = $1"#)
                .bind(&user_id)
                .fetch_one(&state.pool)
                .await?;
            if user.lockdown_at.is_some() || user.deleted_at.is_some() {
                return Ok((jar, Json(json!({ "status": "expired" }))).into_response());
            }
            let (result, cookie) =
                issue_session(&state, &user, DeviceInfo::new(&headers, &ip)).await?;
            Ok((
                jar.add(cookie),
                Json(json!({ "status": "approved", "session": result })),
            )
                .into_response())
        }
    }
}

async fn qr_scan(
    State(state): State<AppState>,
    user: AuthUser,
    Json(body): Json<Value>,
) -> AppResult<Json<Value>> {
    let token = body
        .get("token")
        .and_then(Value::as_str)
        .unwrap_or_default();
    qr::scan(&state, token, &user.user_id).await?;
    Ok(Json(json!({ "ok": true })))
}

async fn qr_approve(
    State(state): State<AppState>,
    user: AuthUser,
    Json(body): Json<Value>,
) -> AppResult<Json<Value>> {
    let token = body
        .get("token")
        .and_then(Value::as_str)
        .unwrap_or_default();
    qr::approve(&state, token, &user.user_id).await?;
    Ok(Json(json!({ "ok": true })))
}

fn current_jti(state: &AppState, jar: &CookieJar) -> Option<String> {
    let token = jar.get(REFRESH_COOKIE)?.value().to_string();
    verify_refresh_token(&state.config, &token)
        .ok()
        .map(|c| c.jti)
}

async fn list_sessions(
    State(state): State<AppState>,
    user: AuthUser,
    jar: CookieJar,
) -> AppResult<Json<Value>> {
    let current = current_jti(&state, &jar);
    let sessions: Vec<Value> = crate::auth::list_sessions(&state, &user.user_id)
        .await?
        .into_iter()
        .map(|(jti, s)| {
            json!({
                "id": jti,
                "current": Some(&jti) == current.as_ref(),
                "userAgent": s.user_agent,
                "ip": s.ip,
                "createdAt": s.created_at,
                "lastSeenAt": s.last_seen_at,
            })
        })
        .collect();
    Ok(Json(json!({ "sessions": sessions })))
}

async fn revoke_session(
    State(state): State<AppState>,
    user: AuthUser,
    Path(jti): Path<String>,
) -> AppResult<Json<Value>> {
    if !is_refresh_token_valid(&state, &jti, &user.user_id).await? {
        return Err(AppError::NotFound("No such session".into()));
    }
    revoke_refresh_token(&state, &jti, &user.user_id).await?;
    Ok(Json(json!({ "revoked": 1 })))
}

async fn revoke_other_sessions(
    State(state): State<AppState>,
    user: AuthUser,
    jar: CookieJar,
) -> AppResult<Json<Value>> {
    let current = current_jti(&state, &jar);
    let revoked = revoke_all_refresh_tokens(&state, &user.user_id, current.as_deref()).await?;
    Ok(Json(json!({ "revoked": revoked })))
}

#[derive(Default)]
struct DeviceInfo {
    user_agent: Option<String>,
    ip: Option<String>,
    created_at: Option<String>,
}

impl DeviceInfo {
    fn new(headers: &axum::http::HeaderMap, ip: &str) -> Self {
        Self {
            user_agent: headers
                .get(axum::http::header::USER_AGENT)
                .and_then(|v| v.to_str().ok())
                .map(|s| s.chars().take(400).collect()),
            ip: Some(ip.to_string()).filter(|s| s != "unknown"),
            created_at: None,
        }
    }

    fn inheriting(mut self, created_at: Option<String>) -> Self {
        self.created_at = created_at;
        self
    }
}

async fn issue_session(
    state: &AppState,
    user: &UserRow,
    device: DeviceInfo,
) -> AppResult<(Value, Cookie<'static>)> {
    let access = sign_access_token(&state.config, &user.id, &user.username)?;
    let (refresh, jti) = sign_refresh_token(&state.config, &user.id)?;
    register_refresh_token(
        state,
        &jti,
        &user.id,
        device.user_agent,
        device.ip,
        device.created_at,
    )
    .await?;
    let cookie = set_refresh_cookie(&state.config, refresh);
    let result = json!({
        "user": to_self_user(user),
        "tokens": { "accessToken": access, "expiresIn": state.config.access_ttl_seconds },
    });
    Ok((result, cookie))
}

async fn issue_email_verification(state: &AppState, user: &UserRow) -> AppResult<()> {
    let token = random_token();
    sqlx::query(r#"UPDATE "EmailVerification" SET "usedAt" = now() WHERE "userId" = $1 AND "usedAt" IS NULL"#)
        .bind(&user.id).execute(&state.pool).await?;
    sqlx::query(r#"INSERT INTO "EmailVerification" (id, "userId", "tokenHash", "expiresAt") VALUES ($1, $2, $3, now() + interval '24 hours')"#)
        .bind(cuid()).bind(&user.id).bind(token_hash(&token)).execute(&state.pool).await?;
    crate::services::email::send_verification(&state.config, &user.email, &token).await
}

pub(super) async fn store_email_login_code(
    state: &AppState,
    user_id: &str,
    code: &str,
    login_token: &str,
) -> AppResult<()> {
    sqlx::query(
        r#"UPDATE "EmailLoginCode" SET "usedAt" = now() WHERE "userId" = $1 AND "usedAt" IS NULL"#,
    )
    .bind(user_id)
    .execute(&state.pool)
    .await?;
    sqlx::query(r#"INSERT INTO "EmailLoginCode" (id, "userId", "codeHash", "loginTokenHash", "expiresAt") VALUES ($1, $2, $3, $4, now() + interval '10 minutes')"#)
        .bind(cuid()).bind(user_id).bind(hash_password(code)?).bind(token_hash(login_token)).execute(&state.pool).await?;
    Ok(())
}

pub(super) async fn issue_email_login_code(
    state: &AppState,
    user: &UserRow,
    login_token: &str,
) -> AppResult<()> {
    let code = random_email_code();
    store_email_login_code(state, &user.id, &code, login_token).await?;
    crate::services::email::send_login_code(&state.config, &user.email, &code).await
}

pub(super) async fn verify_email_login_code(
    state: &AppState,
    login_token: &str,
    code: &str,
) -> AppResult<()> {
    if !valid_email_code(code) || login_token.is_empty() {
        return Err(AppError::Unauthorized("Invalid or expired code".into()));
    }
    let row: Option<UserRow> = sqlx::query_as(r#"SELECT u.* FROM "User" u JOIN "EmailLoginCode" c ON c."userId" = u.id WHERE c."loginTokenHash" = $1 AND c."usedAt" IS NULL AND c."expiresAt" > now() AND c.attempts < 5 AND u."emailVerifiedAt" IS NOT NULL"#).bind(token_hash(login_token)).fetch_optional(&state.pool).await?;
    if row.is_none() {
        return Err(AppError::Unauthorized("Invalid or expired code".into()));
    }
    let hash: String = sqlx::query_scalar(
        r#"SELECT "codeHash" FROM "EmailLoginCode" WHERE "loginTokenHash" = $1"#,
    )
    .bind(token_hash(login_token))
    .fetch_one(&state.pool)
    .await?;
    if !verify_password(&hash, code) {
        sqlx::query(
            r#"UPDATE "EmailLoginCode" SET attempts = attempts + 1 WHERE "loginTokenHash" = $1"#,
        )
        .bind(token_hash(login_token))
        .execute(&state.pool)
        .await?;
        return Err(AppError::Unauthorized("Invalid or expired code".into()));
    }
    sqlx::query(r#"UPDATE "EmailLoginCode" SET "usedAt" = now() WHERE "loginTokenHash" = $1"#)
        .bind(token_hash(login_token))
        .execute(&state.pool)
        .await?;
    Ok(())
}

async fn recaptcha_config(State(state): State<AppState>) -> AppResult<Json<Value>> {
    let enabled =
        state.config.recaptcha_site_key.is_some() && state.config.recaptcha_secret_key.is_some();
    Ok(Json(json!({
        "enabled": enabled,
        "siteKey": enabled.then(|| state.config.recaptcha_site_key.clone()).flatten(),
    })))
}

async fn verify_email(
    State(state): State<AppState>,
    Query(params): Query<std::collections::HashMap<String, String>>,
) -> AppResult<Redirect> {
    let token = params.get("token").map(String::as_str).unwrap_or_default();
    let updated = sqlx::query(r#"UPDATE "User" SET "emailVerifiedAt" = COALESCE("emailVerifiedAt", now()) WHERE id = (SELECT "userId" FROM "EmailVerification" WHERE "tokenHash" = $1 AND "usedAt" IS NULL AND "expiresAt" > now())"#)
        .bind(token_hash(token)).execute(&state.pool).await?;
    if updated.rows_affected() == 0 {
        return Err(AppError::BadRequest(
            "This verification link is invalid or expired".into(),
        ));
    }
    sqlx::query(r#"UPDATE "EmailVerification" SET "usedAt" = now() WHERE "tokenHash" = $1"#)
        .bind(token_hash(token))
        .execute(&state.pool)
        .await?;
    Ok(Redirect::to(&format!(
        "{}/login?verified=1",
        state.config.client_origin.trim_end_matches('/')
    )))
}

async fn resend_verification(
    State(state): State<AppState>,
    ClientIp(ip): ClientIp,
    Json(body): Json<Value>,
) -> AppResult<Json<Value>> {
    rate_limit::check(&state, "verify:resend", &ip, rate_limit::EMAIL_PER_IP).await?;
    let email = body
        .get("email")
        .and_then(Value::as_str)
        .unwrap_or_default();
    if valid_email(email) {
        if let Some(user) = sqlx::query_as::<_, UserRow>(
            r#"SELECT * FROM "User" WHERE lower(email) = lower($1) AND "emailVerifiedAt" IS NULL"#,
        )
        .bind(email)
        .fetch_optional(&state.pool)
        .await?
        {
            issue_email_verification(&state, &user).await?;
        }
    }
    Ok(Json(json!({ "ok": true })))
}

async fn verify_email_2fa(
    State(state): State<AppState>,
    ClientIp(ip): ClientIp,
    headers: axum::http::HeaderMap,
    jar: CookieJar,
    Json(body): Json<Value>,
) -> AppResult<impl IntoResponse> {
    rate_limit::check(&state, "email-2fa", &ip, rate_limit::EMAIL_2FA_PER_IP).await?;
    let token = body
        .get("loginToken")
        .and_then(Value::as_str)
        .unwrap_or_default();
    let code = body.get("code").and_then(Value::as_str).unwrap_or_default();
    let row: Option<UserRow> = sqlx::query_as(r#"SELECT u.* FROM "User" u JOIN "EmailLoginCode" c ON c."userId" = u.id WHERE c."loginTokenHash" = $1 AND c."usedAt" IS NULL AND c."expiresAt" > now() AND c.attempts < 5 AND u."emailVerifiedAt" IS NOT NULL"#).bind(token_hash(token)).fetch_optional(&state.pool).await?;
    verify_email_login_code(&state, token, code).await?;
    let user = row.ok_or_else(|| AppError::Unauthorized("Invalid or expired code".into()))?;
    let (result, cookie) = issue_session(&state, &user, DeviceInfo::new(&headers, &ip)).await?;
    Ok((jar.add(cookie), Json(result)))
}

async fn resend_email_2fa(
    State(state): State<AppState>,
    ClientIp(ip): ClientIp,
    Json(body): Json<Value>,
) -> AppResult<Json<Value>> {
    rate_limit::check(&state, "email-2fa:resend", &ip, rate_limit::EMAIL_PER_IP).await?;
    let token = body
        .get("loginToken")
        .and_then(Value::as_str)
        .unwrap_or_default();
    if !token.is_empty() {
        if let Some(user) = sqlx::query_as::<_, UserRow>(r#"SELECT u.* FROM "User" u JOIN "EmailLoginCode" c ON c."userId" = u.id WHERE c."loginTokenHash" = $1 AND c."usedAt" IS NULL AND c."expiresAt" > now()"#).bind(token_hash(token)).fetch_optional(&state.pool).await? {
            issue_email_login_code(&state, &user, token).await?;
        }
    }
    Ok(Json(json!({ "ok": true })))
}

async fn signup(
    State(state): State<AppState>,
    ClientIp(ip): ClientIp,
    _headers: axum::http::HeaderMap,
    _jar: CookieJar,
    Json(body): Json<Value>,
) -> AppResult<impl IntoResponse> {
    rate_limit::check(&state, "signup", &ip, rate_limit::SIGNUP_PER_IP).await?;
    verify_recaptcha(
        &state.config,
        body.get("recaptchaToken").and_then(Value::as_str),
        &ip,
    )
    .await?;

    let email = body
        .get("email")
        .and_then(Value::as_str)
        .unwrap_or_default();
    let username = body
        .get("username")
        .and_then(Value::as_str)
        .unwrap_or_default();
    let password = body
        .get("password")
        .and_then(Value::as_str)
        .unwrap_or_default();
    let display_name = body.get("displayName").and_then(Value::as_str);

    if !valid_email(email)
        || !valid_username(username)
        || password.len() < 8
        || password.len() > 200
        || display_name
            .map(|d| d.is_empty() || d.len() > 64)
            .unwrap_or(false)
    {
        return Err(bad_request("Invalid input"));
    }

    let email = email.to_lowercase();

    let email_taken: Option<String> =
        sqlx::query_scalar(r#"SELECT id FROM "User" WHERE lower(email) = lower($1)"#)
            .bind(&email)
            .fetch_optional(&state.pool)
            .await?;
    if email_taken.is_some() {
        return Err(AppError::Conflict("Email already in use".into()));
    }
    let username_taken: Option<String> =
        sqlx::query_scalar(r#"SELECT id FROM "User" WHERE lower(username) = lower($1)"#)
            .bind(username)
            .fetch_optional(&state.pool)
            .await?;
    if username_taken.is_some() {
        return Err(AppError::Conflict("Username already taken".into()));
    }

    let hash = hash_password(password)?;
    let badges = badge::initial_badges();
    let user: UserRow = sqlx::query_as(
        r#"INSERT INTO "User" (id, email, username, "displayName", "passwordHash", badges, "updatedAt")
           VALUES ($1, $2, $3, $4, $5, $6, now()) RETURNING *"#,
    )
    .bind(cuid())
    .bind(&email)
    .bind(username)
    .bind(display_name.unwrap_or(username))
    .bind(&hash)
    .bind(&badges)
    .fetch_one(&state.pool)
    .await?;

    issue_email_verification(&state, &user).await?;
    Ok((
        StatusCode::CREATED,
        Json(json!({ "emailVerificationRequired": true })),
    ))
}

async fn login(
    State(state): State<AppState>,
    ClientIp(ip): ClientIp,
    headers: axum::http::HeaderMap,
    jar: CookieJar,
    Json(body): Json<Value>,
) -> AppResult<(CookieJar, Json<Value>)> {
    rate_limit::check(&state, "login", &ip, rate_limit::LOGIN_PER_IP).await?;

    let email = body
        .get("email")
        .and_then(Value::as_str)
        .unwrap_or_default();
    let password = body
        .get("password")
        .and_then(Value::as_str)
        .unwrap_or_default();
    if !valid_email(email) || password.is_empty() {
        return Err(bad_request("Invalid input"));
    }

    let user: Option<UserRow> =
        sqlx::query_as(r#"SELECT * FROM "User" WHERE lower(email) = lower($1)"#)
            .bind(email)
            .fetch_optional(&state.pool)
            .await?;

    if let Some(u) = &user {
        rate_limit::peek(
            &state,
            "login:fail",
            &u.id,
            rate_limit::LOGIN_FAILURES_PER_ACCOUNT,
        )
        .await?;
        if captcha_required(
            rate_limit::current(
                &state,
                "login:fail",
                &u.id,
                rate_limit::LOGIN_FAILURES_PER_ACCOUNT,
            )
            .await,
        ) {
            verify_recaptcha(
                &state.config,
                body.get("recaptchaToken").and_then(Value::as_str),
                &ip,
            )
            .await?;
        }
    }

    let valid = match &user {
        Some(u) if u.deleted_at.is_none() => match &u.password_hash {
            Some(h) => verify_password(h, password),
            None => false,
        },
        _ => false,
    };
    if !valid {
        if let Some(u) = &user {
            record_login_failure(&state, &u.id).await;
        }
        return Err(AppError::Unauthorized("Invalid email or password".into()));
    }

    let user = user.unwrap();

    if user.lockdown_at.is_some() {
        return Err(AppError::Unauthorized(
            "This account is locked down. Lift it from a device that's still signed in.".into(),
        ));
    }
    if user.email_verified_at.is_none() {
        return Err(AppError::Unauthorized(
            "Verify your email before signing in".into(),
        ));
    }


    let skip_passkey = body
        .get("skipPasskey")
        .and_then(Value::as_bool)
        .unwrap_or(false);
    if !skip_passkey && passkey::count(&state, &user.id).await? > 0 {
        let (challenge, ceremony) = passkey::start_known(&state, &user.id).await?;
        return Ok((
            jar,
            Json(json!({
                "passkeyRequired": true,
                "challenge": challenge,
                "ceremonyToken": ceremony,
            })),
        ));
    }

    let lost_authenticator = body
        .get("lostAuthenticator")
        .and_then(Value::as_bool)
        .unwrap_or(false);
    if user.totp_enabled && !lost_authenticator {
        let code = body
            .get("totpCode")
            .and_then(Value::as_str)
            .unwrap_or_default();
        if code.is_empty() {
            return Err(AppError::TwoFactorRequired(
                "Enter the code from your authenticator app".into(),
            ));
        }
        let secret = user.totp_secret.as_deref().unwrap_or_default();
        let ok = totp::verify_code(secret, &user.email, code)?
            || totp::consume_backup_code(&state, &user.id, code).await?;
        if !ok {
            record_login_failure(&state, &user.id).await;
            return Err(AppError::TwoFactorRequired(
                "That code isn't right. Try the current one.".into(),
            ));
        }
        reset_login_failures(&state, &user.id).await;
        let (result, cookie) = issue_session(&state, &user, DeviceInfo::new(&headers, &ip)).await?;
        return Ok((jar.add(cookie), Json(result)));
    }

    reset_login_failures(&state, &user.id).await;
    let login_token = random_token();
    issue_email_login_code(&state, &user, &login_token).await?;
    Ok((
        jar,
        Json(json!({ "email2faRequired": true, "loginToken": login_token })),
    ))
}

async fn passkey_start(
    State(state): State<AppState>,
    ClientIp(ip): ClientIp,
) -> AppResult<Json<Value>> {
    rate_limit::check(&state, "passkey", &ip, rate_limit::LOGIN_PER_IP).await?;
    let (challenge, token) = passkey::start_discoverable(&state).await?;
    Ok(Json(
        json!({ "challenge": challenge, "ceremonyToken": token }),
    ))
}

async fn passkey_finish(
    State(state): State<AppState>,
    ClientIp(ip): ClientIp,
    headers: axum::http::HeaderMap,
    jar: CookieJar,
    Json(body): Json<Value>,
) -> AppResult<impl IntoResponse> {
    rate_limit::check(&state, "passkey", &ip, rate_limit::LOGIN_PER_IP).await?;
    let token = body
        .get("ceremonyToken")
        .and_then(Value::as_str)
        .unwrap_or_default();
    let response = serde_json::from_value(
        body.get("response")
            .cloned()
            .ok_or_else(|| bad_request("Invalid input"))?,
    )
    .map_err(|_| bad_request("That passkey couldn't be read"))?;

    let user = passkey::finish_authentication(&state, token, response).await?;

    if user.lockdown_at.is_some() {
        return Err(AppError::Unauthorized(
            "This account is locked down. Lift it from a device that's still signed in.".into(),
        ));
    }
    if user.email_verified_at.is_none() {
        return Err(AppError::Unauthorized(
            "Verify your email before signing in".into(),
        ));
    }

    reset_login_failures(&state, &user.id).await;
    let (result, cookie) = issue_session(&state, &user, DeviceInfo::new(&headers, &ip)).await?;
    Ok((jar.add(cookie), Json(result)))
}

async fn reset_login_failures(state: &AppState, user_id: &str) {
    rate_limit::reset(
        state,
        "login:fail",
        user_id,
        rate_limit::LOGIN_FAILURES_PER_ACCOUNT,
    )
    .await;
}

async fn record_login_failure(state: &AppState, user_id: &str) {
    rate_limit::record(
        state,
        "login:fail",
        user_id,
        rate_limit::LOGIN_FAILURES_PER_ACCOUNT,
    )
    .await;
}

async fn refresh(
    State(state): State<AppState>,
    ClientIp(ip): ClientIp,
    headers: axum::http::HeaderMap,
    jar: CookieJar,
) -> Result<(CookieJar, Json<Value>), AppError> {
    rate_limit::check(&state, "refresh", &ip, rate_limit::REFRESH_PER_IP).await?;

    let token = jar
        .get(REFRESH_COOKIE)
        .map(|c| c.value().to_string())
        .ok_or_else(|| AppError::Unauthorized("No refresh token".into()))?;

    let claims = match verify_refresh_token(&state.config, &token) {
        Ok(c) => c,
        Err(_) => {
            return Err(AppError::Unauthorized("Invalid refresh token".into()));
        }
    };

    if !is_refresh_token_valid(&state, &claims.jti, &claims.sub).await? {
        return Err(AppError::Unauthorized(
            "Refresh token expired or revoked".into(),
        ));
    }

    let user: Option<UserRow> = sqlx::query_as(r#"SELECT * FROM "User" WHERE id = $1"#)
        .bind(&claims.sub)
        .fetch_optional(&state.pool)
        .await?;
    let Some(user) = user else {
        return Err(AppError::Unauthorized("User no longer exists".into()));
    };

    let inherited = read_session(&state, &claims.jti)
        .await?
        .and_then(|s| s.created_at);
    revoke_refresh_token(&state, &claims.jti, &claims.sub).await?;
    let (result, cookie) = issue_session(
        &state,
        &user,
        DeviceInfo::new(&headers, &ip).inheriting(inherited),
    )
    .await?;
    Ok((jar.add(cookie), Json(result)))
}

async fn logout(State(state): State<AppState>, jar: CookieJar) -> AppResult<impl IntoResponse> {
    if let Some(token) = jar.get(REFRESH_COOKIE).map(|c| c.value().to_string()) {
        if let Ok(claims) = verify_refresh_token(&state.config, &token) {
            let _ = revoke_refresh_token(&state, &claims.jti, &claims.sub).await;
        }
    }
    Ok((jar.add(clear_refresh_cookie()), Json(json!({ "ok": true }))))
}

async fn get_me(State(state): State<AppState>, user: AuthUser) -> AppResult<Json<SelfUserDto>> {
    let row: Option<UserRow> = sqlx::query_as(r#"SELECT * FROM "User" WHERE id = $1"#)
        .bind(&user.user_id)
        .fetch_optional(&state.pool)
        .await?;
    let row = row.ok_or_else(|| AppError::NotFound("User not found".into()))?;
    Ok(Json(to_self_user(&row)))
}

async fn patch_me(
    State(state): State<AppState>,
    user: AuthUser,
    Json(body): Json<Value>,
) -> AppResult<Json<SelfUserDto>> {
    let mut patch = user::UserPatch::default();
    let obj = body
        .as_object()
        .ok_or_else(|| bad_request("Invalid input"))?;
    if obj.is_empty() {
        return Err(bad_request("No fields to update"));
    }
    if let Some(v) = obj.get("username") {
        let u = v.as_str().ok_or_else(|| bad_request("Invalid input"))?;
        if !valid_username(u) {
            return Err(bad_request("Invalid input"));
        }
        patch.username = Some(u.to_string());
    }
    if let Some(v) = obj.get("displayName") {
        let d = v.as_str().ok_or_else(|| bad_request("Invalid input"))?;
        if d.is_empty() || d.len() > 64 {
            return Err(bad_request("Invalid input"));
        }
        patch.display_name = Some(d.to_string());
    }
    if let Some(v) = obj.get("avatarUrl") {
        patch.avatar_url = Some(match v {
            Value::Null => None,
            Value::String(s) => Some(s.clone()),
            _ => return Err(bad_request("Invalid input")),
        });
    }
    if let Some(v) = obj.get("status") {
        let s = v.as_str().ok_or_else(|| bad_request("Invalid input"))?;
        if !matches!(s, "online" | "idle" | "dnd" | "offline") {
            return Err(bad_request("Invalid input"));
        }
        patch.status = Some(s.to_string());
    }
    if let Some(v) = obj.get("bio") {
        patch.bio = Some(match v {
            Value::Null => None,
            Value::String(s) if s.len() <= 4000 => Some(s.clone()),
            _ => return Err(bad_request("Invalid input")),
        });
    }
    if let Some(v) = obj.get("appIconUrl") {
        patch.app_icon_url = Some(match v {
            Value::Null => None,
            Value::String(s) if s.is_empty() => None,
            Value::String(s) => Some(s.clone()),
            _ => return Err(bad_request("Invalid input")),
        });
    }
    if let Some(v) = obj.get("bannerUrl") {
        patch.banner_url = Some(match v {
            Value::Null => None,
            Value::String(s) => Some(s.clone()),
            _ => return Err(bad_request("Invalid input")),
        });
    }
    if let Some(v) = obj.get("accentColor") {
        patch.accent_color = Some(match v {
            Value::Null => None,
            Value::Number(n) => {
                let c = n.as_i64().ok_or_else(|| bad_request("Invalid input"))?;
                if !(0..=0xFFFFFF).contains(&c) {
                    return Err(bad_request("Invalid input"));
                }
                Some(c as i32)
            }
            _ => return Err(bad_request("Invalid input")),
        });
    }
    if let Some(v) = obj.get("pronouns") {
        patch.pronouns = Some(match v {
            Value::Null => None,
            Value::String(s) if s.len() <= 40 => Some(s.clone()),
            _ => return Err(bad_request("Invalid input")),
        });
    }
    if let Some(v) = obj.get("customCss") {
        patch.custom_css = Some(match v {
            Value::Null => None,
            Value::String(s) if s.len() <= 100_000 => Some(s.clone()),
            _ => return Err(bad_request("Invalid input")),
        });
    }
    if let Some(v) = obj.get("profileCss") {
        patch.profile_css = Some(match v {
            Value::Null => None,
            Value::String(s) if s.len() <= 100_000 => Some(s.clone()),
            _ => return Err(bad_request("Invalid input")),
        });
    }
    if let Some(v) = obj.get("dmPrivacy") {
        let s = v.as_str().ok_or_else(|| bad_request("Invalid input"))?;
        if !matches!(s, "everyone" | "friends" | "none") {
            return Err(bad_request("Invalid input"));
        }
        patch.dm_privacy = Some(s.to_string());
    }
    if let Some(v) = obj.get("friendRequestPrivacy") {
        let s = v.as_str().ok_or_else(|| bad_request("Invalid input"))?;
        if !matches!(s, "everyone" | "mutual" | "none") {
            return Err(bad_request("Invalid input"));
        }
        patch.friend_request_privacy = Some(s.to_string());
    }
    if let Some(v) = obj.get("typingIndicators") {
        patch.typing_indicators = Some(v.as_bool().ok_or_else(|| bad_request("Invalid input"))?);
    }
    for (key, field) in [
        ("notifyFriendRequests", 0usize),
        ("notifyFriendAccepted", 1),
        ("notifyFriendOnline", 2),
    ] {
        if let Some(v) = obj.get(key) {
            let b = v.as_bool().ok_or_else(|| bad_request("Invalid input"))?;
            match field {
                0 => patch.notify_friend_requests = Some(b),
                1 => patch.notify_friend_accepted = Some(b),
                _ => patch.notify_friend_online = Some(b),
            }
        }
    }
    if let Some(v) = obj.get("e2eeStrict") {
        patch.e2ee_strict = Some(v.as_bool().ok_or_else(|| bad_request("Invalid input"))?);
    }
    if let Some(v) = obj.get("gameActivity") {
        patch.game_activity = Some(v.as_bool().ok_or_else(|| bad_request("Invalid input"))?);
    }
    let disabling_game_activity = patch.game_activity == Some(false);

    let updated = user::update_profile(&state, &user.user_id, patch).await?;

    if disabling_game_activity
        && presence::set_activity(&state, &user.user_id, "game", None).await?
    {
        let status = presence::get_status(&state, &user.user_id).await?;
        crate::socket::broadcast_presence(state.io(), &state, &user.user_id, &status).await;
    }

    let rooms = user::get_profile_audience_rooms(&state, &user.user_id).await?;
    let public = to_user(&updated);
    for room in rooms {
        let _ = state.io().to(room).emit("user:updated", &public);
    }

    Ok(Json(to_self_user(&updated)))
}


async fn oauth_start(
    State(state): State<AppState>,
    ClientIp(ip): ClientIp,
    Path(provider): Path<String>,
    jar: CookieJar,
) -> Result<(CookieJar, Redirect), AppError> {
    rate_limit::check(&state, "oauth:start", &ip, rate_limit::OAUTH_START_PER_IP).await?;

    if !oauth::is_oauth_provider(&provider) {
        return Err(AppError::NotFound("Unknown provider".into()));
    }
    if !oauth::is_provider_configured(&state.config, &provider) {
        return Err(AppError::Internal(format!(
            "{provider} OAuth is not configured"
        )));
    }
    let csrf = uuid::Uuid::new_v4().to_string();
    let cookie = Cookie::build((OAUTH_STATE_COOKIE, csrf.clone()))
        .http_only(true)
        .secure(state.config.is_prod())
        .same_site(SameSite::Lax)
        .path("/api/auth")
        .max_age(time::Duration::seconds(600))
        .build();
    let url = oauth::authorization_url(&state.config, &provider, &csrf);
    Ok((jar.add(cookie), Redirect::to(&url)))
}

async fn oauth_callback(
    State(state): State<AppState>,
    Path(provider): Path<String>,
    Query(params): Query<std::collections::HashMap<String, String>>,
    ClientIp(ip): ClientIp,
    headers: axum::http::HeaderMap,
    jar: CookieJar,
) -> Result<(CookieJar, Redirect), AppError> {
    if !oauth::is_oauth_provider(&provider) {
        return Err(AppError::NotFound("Unknown provider".into()));
    }
    let code = params.get("code");
    let recv_state = params.get("state");
    let expected = jar.get(OAUTH_STATE_COOKIE).map(|c| c.value().to_string());
    let jar = jar.remove(Cookie::build((OAUTH_STATE_COOKIE, "")).path("/api/auth"));

    let origin = &state.config.client_origin;
    if code.is_none()
        || recv_state.is_none()
        || expected.is_none()
        || recv_state != expected.as_ref()
    {
        return Ok((
            jar,
            Redirect::to(&format!("{origin}/login?error=oauth_state")),
        ));
    }

    if !oauth::is_provider_configured(&state.config, &provider) {
        return Err(AppError::NotFound("Unknown provider".into()));
    }

    match oauth::exchange_code_for_profile(&state.config, &provider, code.unwrap()).await {
        Ok(profile) => match user::find_or_create_oauth_user(&state, &profile).await {
            Ok(user) => {
                if user.totp_enabled {
                    return Ok((
                        jar,
                        Redirect::to(&format!("{origin}/login?error=totp_required")),
                    ));
                }
                let (_result, cookie) =
                    issue_session(&state, &user, DeviceInfo::new(&headers, &ip)).await?;
                Ok((
                    jar.add(cookie),
                    Redirect::to(&format!("{origin}/auth/callback")),
                ))
            }
            Err(_) => Ok((
                jar,
                Redirect::to(&format!("{origin}/login?error=oauth_failed")),
            )),
        },
        Err(_) => Ok((
            jar,
            Redirect::to(&format!("{origin}/login?error=oauth_failed")),
        )),
    }
}
