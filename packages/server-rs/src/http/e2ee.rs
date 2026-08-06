use axum::extract::{Path, Query, State};
use axum::routing::{get, post};
use axum::{Json, Router};
use serde::Deserialize;
use serde_json::{json, Value};

use crate::error::{AppError, AppResult};
use crate::http::{bad_request, AuthUser, ClientIp};
use crate::http::auth::{store_email_login_code, verify_email_login_code};
use crate::models::UserRow;
use crate::services::{
    channel, e2ee, email, key_deletion, push, rate_limit, system_message, totp,
};
use crate::state::AppState;

pub fn routes() -> Router<AppState> {
    Router::new()
        .route("/e2ee/devices", get(my_devices).post(add_device))
        .route("/e2ee/devices/genesis", post(enroll_genesis))
        .route("/e2ee/devices/revoke", post(revoke_device))
        .route("/e2ee/devices/:deviceId/seen", post(seen))
        .route("/e2ee/users/:userId/devices", get(peer_devices))
        .route("/e2ee/transfers", post(new_transfer))
        .route("/e2ee/transfer-grant", post(transfer_grant))
        .route("/e2ee/transfer-grant/email-code", post(request_transfer_email_code))
        .route(
            "/e2ee/transfers/:transferId/blob",
            get(fetch_blob).post(store_blob),
        )
        // The last resort for an account whose every keyed device is gone. GET
        // on the cancel route because it is opened from an email.
        .route(
            "/e2ee/keys/deletion",
            get(deletion_status)
                .post(request_deletion)
                .delete(cancel_deletion),
        )
        .route("/e2ee/keys/deletion/cancel", get(cancel_deletion_link))
        .route("/e2ee/channels/:channelId/state", get(channel_state))
        .route("/e2ee/channels/:channelId/epochs", post(mint_epoch))
        .route("/e2ee/channels/:channelId/keys", get(epoch_keys))
}

#[derive(Deserialize)]
struct SinceQuery {
    #[serde(default)]
    since: Option<i32>,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct KeysQuery {
    device_id: String,
    #[serde(default)]
    since: Option<i32>,
}

async fn my_devices(
    State(state): State<AppState>,
    user: AuthUser,
    Query(q): Query<SinceQuery>,
) -> AppResult<Json<Value>> {
    let list = e2ee::list(&state, &user.user_id, q.since).await?;
    Ok(Json(json!(list)))
}

async fn peer_devices(
    State(state): State<AppState>,
    user: AuthUser,
    Path(user_id): Path<String>,
    Query(q): Query<SinceQuery>,
) -> AppResult<Json<Value>> {
    rate_limit::check(
        &state,
        "e2ee:peers",
        &user.user_id,
        rate_limit::E2EE_LOOKUP_PER_USER,
    )
    .await?;
    let list = e2ee::list(&state, &user_id, q.since).await?;
    Ok(Json(json!(list)))
}

async fn enroll_genesis(
    State(state): State<AppState>,
    user: AuthUser,
    Json(body): Json<e2ee::EnrollGenesisInput>,
) -> AppResult<Json<Value>> {
    rate_limit::check(
        &state,
        "e2ee:enroll",
        &user.user_id,
        rate_limit::E2EE_ENROL_PER_USER,
    )
    .await?;
    let device = e2ee::enroll_genesis(&state, &user.user_id, &body).await?;
    announce_device(&state, &user.user_id, &device).await;
    Ok(Json(json!(device)))
}

async fn add_device(
    State(state): State<AppState>,
    user: AuthUser,
    Json(body): Json<e2ee::AddDeviceInput>,
) -> AppResult<Json<Value>> {
    rate_limit::check(
        &state,
        "e2ee:enroll",
        &user.user_id,
        rate_limit::E2EE_ENROL_PER_USER,
    )
    .await?;
    let device = e2ee::add_device(&state, &user.user_id, &body).await?;
    announce_device(&state, &user.user_id, &device).await;
    Ok(Json(json!(device)))
}

async fn revoke_device(
    State(state): State<AppState>,
    user: AuthUser,
    Json(body): Json<e2ee::RevokeDeviceInput>,
) -> AppResult<Json<Value>> {
    rate_limit::check(
        &state,
        "e2ee:enroll",
        &user.user_id,
        rate_limit::E2EE_ENROL_PER_USER,
    )
    .await?;
    let device = e2ee::revoke_device(&state, &user.user_id, &body).await?;
    let payload = json!({ "userId": user.user_id, "deviceId": device.id });
    for room in e2ee::peer_rooms(&state, &user.user_id)
        .await
        .unwrap_or_default()
    {
        let _ = state.io().to(room).emit("e2ee:device:revoked", &payload);
    }
    Ok(Json(json!(device)))
}

async fn seen(
    State(state): State<AppState>,
    user: AuthUser,
    Path(device_id): Path<String>,
) -> AppResult<Json<Value>> {
    e2ee::touch(&state, &user.user_id, &device_id).await?;
    Ok(Json(json!({ "ok": true })))
}

/// A transfer id has to be unguessable, not merely unique: on the §4.3 relay
/// path it is the only thing between an on-path attacker and a fetch race. A
/// cuid embeds a counter and a timestamp, so the id is minted here from the
/// CSPRNG rather than by whichever client happens to be enrolling.
async fn new_transfer(State(state): State<AppState>, user: AuthUser) -> AppResult<Json<Value>> {
    rate_limit::check(
        &state,
        "e2ee:blob:mutate",
        &user.user_id,
        rate_limit::E2EE_TRANSFER_MUTATION_PER_USER,
    )
    .await?;
    Ok(Json(json!({ "transferId": e2ee::new_transfer_id() })))
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct TransferGrantBody {
    transfer_id: String,
    ik_sig_pub: String,
    ik_dh_pub: String,
    #[serde(default)]
    code: Option<String>,
    /// Set when the account has no authenticator: the one-time email code
    /// issued by /e2ee/transfer-grant/email-code and the token it came with.
    #[serde(default)]
    login_token: Option<String>,
}

/// The fallback for accounts without an authenticator app: mints a one-time
/// email code exactly like a sign-in code and hands back the opaque token the
/// client must present alongside the code. One code per account at a time; a
/// fresh request burns the previous one.
async fn request_transfer_email_code(
    State(state): State<AppState>,
    user: AuthUser,
) -> AppResult<Json<Value>> {
    rate_limit::check(
        &state,
        "e2ee:transfer-email-code",
        &user.user_id,
        rate_limit::EMAIL_CODE_PER_USER,
    )
    .await?;

    let row: UserRow = sqlx::query_as(r#"SELECT * FROM "User" WHERE id = $1"#)
        .bind(&user.user_id)
        .fetch_one(&state.pool)
        .await?;

    if row.lockdown_at.is_some() {
        return Err(AppError::Permission(
            "This account is locked down, so no new device can be added".into(),
        ));
    }
    // TOTP accounts never take this path - they already have a code they can
    // produce themselves - but refusing it here keeps the invariant that a
    // transfer grant is always gated by one of the two second factors.
    if row.totp_enabled {
        return Err(bad_request(
            "This account has two-factor authentication; enter a code from your authenticator app instead",
        ));
    }
    if e2ee::active_devices(&state, &user.user_id)
        .await?
        .is_empty()
    {
        return Err(bad_request(
            "This account has no encryption identity to add a device to",
        ));
    }

    let login_token = crate::auth_security::random_token();
    let code = crate::auth_security::random_email_code();
    store_email_login_code(&state, &row.id, &code, &login_token).await?;
    email::send_device_transfer_code(&state.config, &row.email, &code).await?;

    Ok(Json(json!({ "loginToken": login_token })))
}

#[derive(Deserialize)]
struct DeletionBody {
    #[serde(default)]
    code: Option<String>,
}

async fn deletion_status(State(state): State<AppState>, user: AuthUser) -> AppResult<Json<Value>> {
    let row = key_deletion::pending(&state, &user.user_id).await?;
    Ok(Json(match row {
        Some(p) => json!({
            "pending": true,
            "requestedAt": p.requested_at,
            "executeAfter": p.execute_after,
        }),
        None => json!({ "pending": false }),
    }))
}

/// Schedules the erasure. The 2FA check is not what authorizes the wipe - the
/// wait and the notifications are - but passing it is what earns the shorter
/// wait, because an attacker who cleared it had to beat more than a password.
async fn request_deletion(
    State(state): State<AppState>,
    ClientIp(ip): ClientIp,
    user: AuthUser,
    Json(body): Json<DeletionBody>,
) -> AppResult<Json<Value>> {
    rate_limit::check(
        &state,
        "e2ee:key-deletion",
        &user.user_id,
        rate_limit::E2EE_ENROL_PER_USER,
    )
    .await?;

    let row: UserRow = sqlx::query_as(r#"SELECT * FROM "User" WHERE id = $1"#)
        .bind(&user.user_id)
        .fetch_one(&state.pool)
        .await?;

    // Lockdown exists to freeze an account somebody else got into. Honouring a
    // request to destroy its keys while it is frozen would defeat the point.
    if row.lockdown_at.is_some() {
        return Err(AppError::Permission(
            "This account is locked down, so its keys cannot be erased".into(),
        ));
    }
    if e2ee::active_devices(&state, &user.user_id)
        .await?
        .is_empty()
    {
        return Err(bad_request(
            "This account has no encryption identity to erase",
        ));
    }

    // Offered rather than demanded: an account without 2FA is precisely the one
    // that cannot produce a code, and refusing it here would leave the people
    // this feature exists for with no way out at all. They wait longer instead.
    if row.totp_enabled {
        rate_limit::check(&state, "2fa", &user.user_id, rate_limit::TOTP_PER_USER).await?;
        let code = body.code.as_deref().unwrap_or_default();
        let secret = row.totp_secret.as_deref().unwrap_or_default();
        let ok = totp::verify_code(secret, &row.email, code)?
            || totp::consume_backup_code(&state, &row.id, code).await?;
        if !ok {
            return Err(bad_request("That code isn't right. Try the current one."));
        }
    }

    let pending = key_deletion::request(
        &state,
        &user.user_id,
        &row.email,
        row.totp_enabled,
        Some(ip.as_str()),
    )
    .await?;

    Ok(Json(json!({
        "pending": true,
        "requestedAt": pending.requested_at,
        "executeAfter": pending.execute_after,
    })))
}

async fn cancel_deletion(State(state): State<AppState>, user: AuthUser) -> AppResult<Json<Value>> {
    key_deletion::cancel_for_user(&state, &user.user_id).await?;
    Ok(Json(json!({ "pending": false })))
}

/// Opened straight from the warning email, so it authenticates on the token
/// alone - the recipient of that mailbox is exactly who this is for, and
/// requiring a login would lock out the person whose account was taken over.
async fn cancel_deletion_link(
    State(state): State<AppState>,
    Query(params): Query<std::collections::HashMap<String, String>>,
) -> AppResult<axum::response::Redirect> {
    let token = params.get("token").map(String::as_str).unwrap_or_default();
    key_deletion::cancel_by_token(&state, token).await?;
    Ok(axum::response::Redirect::to(&format!(
        "{}/?keyErasure=cancelled",
        state.config.client_origin.trim_end_matches('/')
    )))
}

/// The old device's half of §4.4: a fresh TOTP buys a 60-second grant bound to
/// the new device's keys. Policy only - the authorization signature is the
/// cryptographic authority, and nothing here can substitute for it.
async fn transfer_grant(
    State(state): State<AppState>,
    user: AuthUser,
    Json(body): Json<TransferGrantBody>,
) -> AppResult<Json<Value>> {
    rate_limit::check(&state, "2fa", &user.user_id, rate_limit::TOTP_PER_USER).await?;

    let row: UserRow = sqlx::query_as(r#"SELECT * FROM "User" WHERE id = $1"#)
        .bind(&user.user_id)
        .fetch_one(&state.pool)
        .await?;

    if row.lockdown_at.is_some() {
        return Err(AppError::Permission(
            "This account is locked down, so no new device can be added".into(),
        ));
    }

    // A fresh second-factor code, either kind: TOTP when the account has an
    // authenticator, otherwise the one-time email code this endpoint's sibling
    // issued. Neither is the authority - the old device's signature is - but
    // both stand between a stolen session and the device log.
    let code = body.code.as_deref().unwrap_or_default();
    if row.totp_enabled {
        let secret = row.totp_secret.as_deref().unwrap_or_default();
        let ok = totp::verify_code(secret, &row.email, code)?
            || totp::consume_backup_code(&state, &row.id, code).await?;
        if !ok {
            return Err(bad_request("That code isn't right. Try the current one."));
        }
    } else {
        let login_token = body.login_token.as_deref().unwrap_or_default();
        verify_email_login_code(&state, login_token, code).await.map_err(|_| {
            bad_request("That code isn't right. Request a fresh one and try again.")
        })?;
    }

    if e2ee::active_devices(&state, &user.user_id)
        .await?
        .is_empty()
    {
        return Err(bad_request(
            "This account has no encryption identity to add a device to",
        ));
    }

    let ik_sig_pub = base64_bytes("ikSigPub", &body.ik_sig_pub)?;
    let ik_dh_pub = base64_bytes("ikDhPub", &body.ik_dh_pub)?;
    let (grant, ttl) = e2ee::issue_transfer_grant(
        &state,
        &user.user_id,
        &body.transfer_id,
        &ik_sig_pub,
        &ik_dh_pub,
    )
    .await?;

    // A grant does not itself authorize a device (the old device's signature
    // does), but spending TOTP on one is still a security event. Record it in
    // structured logs and alert every registered endpoint plus email.
    tracing::info!(
        user_id = %user.user_id,
        transfer_id = %body.transfer_id,
        "e2ee transfer grant issued"
    );
    let notice_state = state.clone();
    let notice_user_id = user.user_id.clone();
    let notice_email = row.email.clone();
    tokio::spawn(async move {
        let payload = push::PushPayload {
            title: "Encryption device approved".into(),
            body: "A security code approved a new OrangChat encryption device.".into(),
            href: "/settings".into(),
            tag: "e2ee-device-transfer".into(),
            icon: None,
            channel_id: "account-security".into(),
            message_id: None,
            sender_id: notice_user_id.clone(),
            sender_name: "OrangChat Security".into(),
            is_group: false,
            kind: push::PushKind::Message,
            ciphertext: None,
            enc_epoch: None,
        };
        push::send_to_user(&notice_state, &notice_user_id, &payload).await;
        if notice_state.config.resend_api_key.is_some() {
            if let Err(error) =
                email::send_device_transfer_notice(&notice_state.config, &notice_email).await
            {
                tracing::warn!(%error, "could not email e2ee transfer notice");
            }
        }
    });

    Ok(Json(json!({
        "grant": grant,
        "transferId": body.transfer_id,
        "expiresIn": ttl,
    })))
}

#[derive(Deserialize)]
struct BlobBody {
    blob: String,
    /// "handshake" or "bundle"; see services::e2ee::valid_blob_slot.
    #[serde(default)]
    slot: Option<String>,
}

#[derive(Deserialize)]
struct SlotQuery {
    #[serde(default)]
    slot: Option<String>,
}

fn slot_of(value: Option<&str>) -> &str {
    value.unwrap_or("bundle")
}

async fn store_blob(
    State(state): State<AppState>,
    user: AuthUser,
    Path(transfer_id): Path<String>,
    Json(body): Json<BlobBody>,
) -> AppResult<Json<Value>> {
    rate_limit::check(
        &state,
        "e2ee:blob:mutate",
        &user.user_id,
        rate_limit::E2EE_TRANSFER_MUTATION_PER_USER,
    )
    .await?;
    let ttl = e2ee::put_transfer_blob(
        &state,
        &transfer_id,
        slot_of(body.slot.as_deref()),
        &body.blob,
    )
    .await?;
    Ok(Json(json!({ "expiresIn": ttl })))
}

/// Single-use by construction: the read deletes. A blob fetched by anyone but
/// the waiting device is a burned transfer the user must restart, which is loud
/// on purpose.
async fn fetch_blob(
    State(state): State<AppState>,
    user: AuthUser,
    Path(transfer_id): Path<String>,
    Query(q): Query<SlotQuery>,
) -> AppResult<Json<Value>> {
    rate_limit::check(
        &state,
        "e2ee:blob:poll",
        &user.user_id,
        rate_limit::E2EE_TRANSFER_POLL_PER_USER,
    )
    .await?;
    let blob = e2ee::take_transfer_blob(
        &state,
        &transfer_id,
        slot_of(q.slot.as_deref()),
        &user.user_id,
    )
    .await?;
    Ok(Json(json!({ "blob": blob })))
}

async fn channel_state(
    State(state): State<AppState>,
    user: AuthUser,
    Path(channel_id): Path<String>,
) -> AppResult<Json<Value>> {
    channel::require_channel_access(&state, &channel_id, &user.user_id).await?;
    let dto = e2ee::channel_state(&state, &channel_id).await?;
    Ok(Json(json!(dto)))
}

async fn mint_epoch(
    State(state): State<AppState>,
    user: AuthUser,
    Path(channel_id): Path<String>,
    Json(body): Json<e2ee::MintEpochInput>,
) -> AppResult<Json<Value>> {
    let channel = channel::require_channel_access(&state, &channel_id, &user.user_id).await?;
    if !matches!(channel.channel_type.as_str(), "dm" | "group_dm") {
        return Err(bad_request(
            "Only direct conversations can be end-to-end encrypted",
        ));
    }
    rate_limit::check(
        &state,
        "e2ee:epoch",
        &user.user_id,
        rate_limit::E2EE_EPOCH_PER_USER,
    )
    .await?;

    let epoch = e2ee::mint_epoch(&state, &channel_id, &user.user_id, &body).await?;
    if body.announce {
        system_message::announce(
            &state,
            &channel_id,
            &user.user_id,
            system_message::Notice::KeyReset,
        )
        .await;
    }
    let payload = json!({ "channelId": channel_id, "epoch": epoch });
    if let Ok(members) =
        crate::services::dm::get_conversation_participants(&state, &channel_id).await
    {
        for member in members {
            let _ = state
                .io()
                .to(format!("user:{member}"))
                .emit("e2ee:epoch", &payload);
        }
    }
    Ok(Json(json!(epoch)))
}

async fn epoch_keys(
    State(state): State<AppState>,
    user: AuthUser,
    Path(channel_id): Path<String>,
    Query(q): Query<KeysQuery>,
) -> AppResult<Json<Value>> {
    channel::require_channel_access(&state, &channel_id, &user.user_id).await?;
    let keys = e2ee::epoch_keys(&state, &channel_id, &user.user_id, &q.device_id, q.since).await?;
    Ok(Json(json!({ "keys": keys })))
}

fn base64_bytes(label: &str, value: &str) -> AppResult<Vec<u8>> {
    use base64::Engine;
    base64::engine::general_purpose::STANDARD
        .decode(value)
        .map_err(|_| bad_request(&format!("{label} is not valid base64")))
}

/// A new device on the account is a security event for every peer who might
/// wrap a key to it, and for the account's own other sessions. Peers hear about
/// it because it is what forces them to rotate the conversation key.
async fn announce_device(state: &AppState, user_id: &str, device: &crate::dto::DeviceDto) {
    let payload = json!({ "userId": user_id, "device": device });
    let rooms = e2ee::peer_rooms(state, user_id).await.unwrap_or_default();
    for room in rooms {
        let _ = state.io().to(room).emit("e2ee:device:added", &payload);
    }
}
