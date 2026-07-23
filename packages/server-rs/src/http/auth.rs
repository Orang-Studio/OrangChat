//! Auth routes, mounted under /api/auth. Mirrors routes/auth.ts.

use axum::extract::{Path, Query, State};
use axum::http::StatusCode;
use axum::response::{IntoResponse, Redirect};
use axum::routing::{get, post};
use axum::{Json, Router};
use axum_extra::extract::cookie::{Cookie, CookieJar, SameSite};
use serde_json::{json, Value};

use crate::auth::*;
use crate::dto::{to_self_user, to_user, SelfUserDto};
use crate::error::{AppError, AppResult};
use crate::http::{bad_request, valid_email, valid_username, AuthUser, ClientIp};
use crate::ids::cuid;
use crate::models::UserRow;
use crate::oauth;
use crate::services::{badge, rate_limit, totp, user};
use crate::state::AppState;

const OAUTH_STATE_COOKIE: &str = "oc_oauth_state";

pub fn routes() -> Router<AppState> {
    Router::new()
        .route("/signup", post(signup))
        .route("/login", post(login))
        .route("/refresh", post(refresh))
        .route("/logout", post(logout))
        .route("/me", get(get_me).patch(patch_me))
        .route("/oauth/:provider", get(oauth_start))
        .route("/oauth/:provider/callback", get(oauth_callback))
}

/// Mint an access token + rotating refresh cookie for a user. Mirrors issueSession.
async fn issue_session(state: &AppState, user: &UserRow) -> AppResult<(Value, Cookie<'static>)> {
    let access = sign_access_token(&state.config, &user.id, &user.username)?;
    let (refresh, jti) = sign_refresh_token(&state.config, &user.id)?;
    register_refresh_token(state, &jti, &user.id).await?;
    let cookie = set_refresh_cookie(&state.config, refresh);
    let result = json!({
        "user": to_self_user(user),
        "tokens": { "accessToken": access, "expiresIn": state.config.access_ttl_seconds },
    });
    Ok((result, cookie))
}

async fn signup(
    State(state): State<AppState>,
    ClientIp(ip): ClientIp,
    jar: CookieJar,
    Json(body): Json<Value>,
) -> AppResult<impl IntoResponse> {
    rate_limit::check(&state, "signup", &ip, rate_limit::SIGNUP_PER_IP).await?;

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

    // Case is not meaningful in an address, and no mail provider treats it as
    // such, so the canonical form is what gets stored - otherwise the row and
    // the lower(email) index disagree about what "taken" means.
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
    let badges = badge::initial_badges(&state).await?;
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

    let (result, cookie) = issue_session(&state, &user).await?;
    Ok((StatusCode::CREATED, jar.add(cookie), Json(result)))
}

async fn login(
    State(state): State<AppState>,
    ClientIp(ip): ClientIp,
    jar: CookieJar,
    Json(body): Json<Value>,
) -> AppResult<impl IntoResponse> {
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

    // Signing in must not care how the address was capitalised - a phone that
    // auto-capitalises the first letter would otherwise lock someone out of the
    // account they just created. See the lower(email) unique index.
    let user: Option<UserRow> =
        sqlx::query_as(r#"SELECT * FROM "User" WHERE lower(email) = lower($1)"#)
            .bind(email)
            .fetch_optional(&state.pool)
            .await?;

    // Budget guesses per account, keyed by id rather than the submitted email so
    // no address lands in a Redis key. An unknown address gets no bucket of its
    // own - the per-IP quota above is what covers enumeration. Checked before
    // verify_password because argon2 is deliberately expensive: unbounded
    // attempts are a CPU exhaustion vector, not just a guessing one.
    if let Some(u) = &user {
        rate_limit::peek(
            &state,
            "login:fail",
            &u.id,
            rate_limit::LOGIN_FAILURES_PER_ACCOUNT,
        )
        .await?;
    }

    let valid = match &user {
        Some(u) => match &u.password_hash {
            Some(h) => verify_password(h, password),
            None => false,
        },
        None => false,
    };
    if !valid {
        if let Some(u) = &user {
            record_login_failure(&state, &u.id).await;
        }
        return Err(AppError::Unauthorized("Invalid email or password".into()));
    }

    // Password checked out; the account may still owe us a second factor.
    let user = user.unwrap();
    if user.totp_enabled {
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
            // A stolen password plus unlimited tries would walk a 6-digit code,
            // so wrong second factors spend the same budget as wrong passwords.
            record_login_failure(&state, &user.id).await;
            return Err(AppError::TwoFactorRequired(
                "That code isn't right. Try the current one.".into(),
            ));
        }
    }

    rate_limit::reset(
        &state,
        "login:fail",
        &user.id,
        rate_limit::LOGIN_FAILURES_PER_ACCOUNT,
    )
    .await;
    let (result, cookie) = issue_session(&state, &user).await?;
    Ok((jar.add(cookie), Json(result)))
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

    // Single-use: burn the old token, issue a fresh pair.
    revoke_refresh_token(&state, &claims.jti, &claims.sub).await?;
    let (result, cookie) = issue_session(&state, &user).await?;
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
    // ── Profile fields (all nullable: JSON null clears them) ──
    if let Some(v) = obj.get("bio") {
        patch.bio = Some(match v {
            Value::Null => None,
            Value::String(s) if s.len() <= 4000 => Some(s.clone()),
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
    // ── Privacy ──
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

    let updated = user::update_profile(&state, &user.user_id, patch).await?;

    // Fan the public profile out to everyone rendering it: shared servers,
    // friends, DM partners, and the user's own other sessions.
    let rooms = user::get_profile_audience_rooms(&state, &user.user_id).await?;
    let public = to_user(&updated);
    for room in rooms {
        let _ = state.io().to(room).emit("user:updated", &public);
    }

    Ok(Json(to_self_user(&updated)))
}

// ── OAuth ───────────────────────────────────────────────

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
            ":provider OAuth is not configured"
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
                // The password path owes a second factor (see `login`), so this
                // one does too - a provider redirect must not be a way around
                // 2FA the account holder switched on. There is nowhere to prompt
                // for a code mid-redirect, so 2FA accounts finish at the form.
                // TODO: hand off a short-lived pending token to a /login/2fa
                // step so OAuth + 2FA can coexist before OAuth is enabled.
                if user.totp_enabled {
                    return Ok((
                        jar,
                        Redirect::to(&format!("{origin}/login?error=totp_required")),
                    ));
                }
                let (_result, cookie) = issue_session(&state, &user).await?;
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
