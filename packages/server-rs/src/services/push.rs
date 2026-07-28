//! Push notifications that survive the app being closed.
//!
//! The socket path (see socket.rs `unread:activity`) can only reach a client
//! that is currently connected, which by definition excludes the case people
//! actually mean by "notify me": the tab shut, the phone asleep. This module is
//! the other half. The transports - Web Push for browsers, FCM for Android -
//! keep their own connection to the device and wake it on our behalf, so a send
//! here lands whether or not OrangChat is running.
//!
//! Both transports are independent and optional, mirroring Cloudinary/OpenAI: an
//! unconfigured one is simply never dispatched to, and the client is told not to
//! offer subscribing. Sends also fail open - a push outage must never take the
//! message path down with it, since the message itself already committed.
//!
//! ## Payloads are data-only on purpose
//!
//! FCM will happily render a `notification` block itself, but only the app can
//! decide what a message *means* - whether the channel is already on screen,
//! which notification channel it belongs to, whether it's a call that needs a
//! full-screen intent. So both transports carry data only, and each client
//! renders it (NotificationHelper on Android, the service worker on web).

use std::time::{Duration, SystemTime, UNIX_EPOCH};

use redis::AsyncCommands;
use serde::{Deserialize, Serialize};
use serde_json::json;
use sqlx::FromRow;
use tokio::sync::RwLock;
use web_push::{
    ContentEncoding, SubscriptionInfo, Urgency, VapidSignatureBuilder, WebPushMessageBuilder,
};

use crate::config::Config;
use crate::error::AppResult;
use crate::state::AppState;

/// How long a push service should hold a message for a device that is offline.
/// A day: long enough to cover a phone left on a charger overnight, short enough
/// that nobody is woken by a message that has since been read elsewhere.
const TTL_SECONDS: u32 = 60 * 60 * 24;

/// Google's OAuth2 scope for the FCM HTTP v1 API.
const FCM_SCOPE: &str = "https://www.googleapis.com/auth/firebase.messaging";
const GOOGLE_TOKEN_URI: &str = "https://oauth2.googleapis.com/token";

/// A registered device, straight out of the `PushSubscription` table.
#[derive(Debug, Clone, FromRow)]
pub struct Subscription {
    pub id: String,
    pub kind: String,
    pub endpoint: String,
    pub p256dh: Option<String>,
    pub auth: Option<String>,
}

/// What a client hands us when it registers. `p256dh`/`auth` are present only
/// for Web Push; an FCM registration arrives with just its token in `endpoint`.
#[derive(Debug, Deserialize)]
pub struct NewSubscription {
    pub kind: String,
    pub endpoint: String,
    pub p256dh: Option<String>,
    pub auth: Option<String>,
    pub label: Option<String>,
}

/// The notification itself, as both clients receive it. Mirrored by the
/// `PushPayload` interface in the service worker and `PushPayload` in Kotlin.
#[derive(Debug, Clone, Serialize)]
pub struct PushPayload {
    pub title: String,
    pub body: String,
    /// Route to open on tap, e.g. `/dms/<id>`.
    pub href: String,
    /// Collapse key: a later notification for the same channel replaces the
    /// earlier one rather than stacking a second copy of the same conversation.
    pub tag: String,
    pub icon: Option<String>,
    pub channel_id: String,
    pub message_id: Option<String>,
    pub sender_id: String,
    pub sender_name: String,
    pub is_group: bool,
    /// Distinguishes a ring from a message so the client can route it to the
    /// calls channel and take over the screen.
    pub kind: PushKind,
    /// docs/E2EE.md §8: for an encrypted conversation there is no body to
    /// compose here, so the envelope travels instead and the client decrypts it
    /// in its own notification path. Absent when the conversation is plaintext,
    /// or when the ciphertext would not fit inside the transport's payload
    /// ceiling - in which case the client shows a placeholder rather than
    /// nothing, because a truncated GCM ciphertext cannot be opened at all.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub ciphertext: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub enc_epoch: Option<i32>,
}

/// Web Push caps a payload at about 4 KB once encrypted, and the rest of the
/// notification has to fit alongside. Anything larger is sent without its
/// ciphertext.
const MAX_PUSH_CIPHERTEXT_CHARS: usize = 2600;

#[derive(Debug, Clone, Copy, Serialize, PartialEq, Eq)]
#[serde(rename_all = "lowercase")]
pub enum PushKind {
    Message,
    Call,
    /// Not a notification to show but one to *take back*: the channel was read
    /// on another device, so any lingering notification for it should be
    /// dismissed. Carries no title/body - the client just cancels by channel.
    Read,
    /// Something happened to the account itself rather than to a conversation.
    /// Shown insistently: these are the ones where reading it late is the same
    /// as not reading it at all.
    Security,
}

// ── Web Push ─────────────────────────────────────────────

struct WebPush {
    private_key: String,
    subject: String,
}

// ── FCM ──────────────────────────────────────────────────

/// The fields we need out of a Google service-account JSON.
#[derive(Debug, Deserialize)]
struct ServiceAccount {
    project_id: String,
    client_email: String,
    private_key: String,
}

#[derive(Debug, Serialize)]
struct TokenClaims<'a> {
    iss: &'a str,
    scope: &'a str,
    aud: &'a str,
    exp: u64,
    iat: u64,
}

#[derive(Debug, Deserialize)]
struct TokenResponse {
    access_token: String,
    expires_in: u64,
}

struct Fcm {
    account: ServiceAccount,
    /// Cached OAuth2 access token and its expiry (unix seconds). Google's are
    /// good for an hour, so minting one per notification would be absurd.
    token: RwLock<Option<(String, u64)>>,
}

// ── Service ──────────────────────────────────────────────

pub struct Push {
    web: Option<WebPush>,
    fcm: Option<Fcm>,
    client: reqwest::Client,
}

impl Push {
    /// None when neither transport is configured - push is then entirely off and
    /// nothing else in the process needs to know.
    pub fn from_config(config: &Config) -> Option<Push> {
        let web = match (&config.vapid_private_key, &config.vapid_subject) {
            (Some(private_key), Some(subject)) => Some(WebPush {
                private_key: private_key.clone(),
                subject: subject.clone(),
            }),
            _ => None,
        };

        let fcm = config
            .fcm_service_account
            .as_deref()
            .and_then(load_service_account)
            .map(|account| Fcm {
                account,
                token: RwLock::new(None),
            });

        if web.is_none() && fcm.is_none() {
            return None;
        }
        if web.is_some() {
            tracing::info!("Web Push enabled");
        }
        if let Some(f) = &fcm {
            tracing::info!(project = %f.account.project_id, "FCM enabled");
        }

        Some(Push {
            web,
            fcm,
            client: reqwest::Client::builder()
                .timeout(Duration::from_secs(10))
                .build()
                .unwrap_or_default(),
        })
    }

    pub fn fcm_enabled(&self) -> bool {
        self.fcm.is_some()
    }
}

/// Accepts either the service-account JSON itself or a path to it, because the
/// two deployments want different things: systemd units pass a path, containers
/// tend to inject the blob straight into the environment.
fn load_service_account(raw: &str) -> Option<ServiceAccount> {
    let trimmed = raw.trim();
    let json = if trimmed.starts_with('{') {
        trimmed.to_string()
    } else {
        match std::fs::read_to_string(trimmed) {
            Ok(contents) => contents,
            Err(e) => {
                tracing::error!(path = %trimmed, error = %e, "FCM_SERVICE_ACCOUNT unreadable; FCM disabled");
                return None;
            }
        }
    };
    match serde_json::from_str::<ServiceAccount>(&json) {
        Ok(account) => Some(account),
        Err(e) => {
            tracing::error!(error = %e, "FCM_SERVICE_ACCOUNT is not a valid service-account JSON; FCM disabled");
            None
        }
    }
}

fn now_secs() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or_default()
}

// ── Subscription storage ─────────────────────────────────

/// Register (or re-register) a device.
///
/// Upserting on `endpoint` rather than inserting is what keeps the table from
/// growing without bound: browsers hand back the same endpoint every load, and
/// FCM the same token, so a fresh row per sign-in would fan every notification
/// out N times to one device. The conflict clause also re-points the row at the
/// current user, which is what should happen when a shared device changes hands.
pub async fn save_subscription(
    state: &AppState,
    user_id: &str,
    sub: &NewSubscription,
) -> AppResult<()> {
    sqlx::query(
        r#"
        INSERT INTO "PushSubscription" ("id", "userId", "kind", "endpoint", "p256dh", "auth", "label", "lastSeenAt")
        VALUES ($1, $2, $3, $4, $5, $6, $7, NOW())
        ON CONFLICT ("endpoint") DO UPDATE
          SET "userId" = EXCLUDED."userId",
              "p256dh" = EXCLUDED."p256dh",
              "auth" = EXCLUDED."auth",
              "label" = EXCLUDED."label",
              "lastSeenAt" = NOW()
        "#,
    )
    .bind(crate::ids::cuid())
    .bind(user_id)
    .bind(&sub.kind)
    .bind(&sub.endpoint)
    .bind(&sub.p256dh)
    .bind(&sub.auth)
    .bind(&sub.label)
    .execute(&state.pool)
    .await?;
    Ok(())
}

/// Scoped to the owner: an endpoint is a bearer capability to notify someone, so
/// letting any authenticated caller delete an arbitrary one would be a free
/// "silence this user" button.
pub async fn delete_subscription(state: &AppState, user_id: &str, endpoint: &str) -> AppResult<()> {
    sqlx::query(r#"DELETE FROM "PushSubscription" WHERE "endpoint" = $1 AND "userId" = $2"#)
        .bind(endpoint)
        .bind(user_id)
        .execute(&state.pool)
        .await?;
    Ok(())
}

async fn subscriptions_for(state: &AppState, user_id: &str) -> AppResult<Vec<Subscription>> {
    let rows = sqlx::query_as::<_, Subscription>(
        r#"SELECT "id", "kind", "endpoint", "p256dh", "auth" FROM "PushSubscription" WHERE "userId" = $1"#,
    )
    .bind(user_id)
    .fetch_all(&state.pool)
    .await?;
    Ok(rows)
}

/// Drop a device the transport has told us is gone for good.
async fn forget(state: &AppState, id: &str) {
    let _ = sqlx::query(r#"DELETE FROM "PushSubscription" WHERE "id" = $1"#)
        .bind(id)
        .execute(&state.pool)
        .await;
}

// ── Sending ──────────────────────────────────────────────

/// Fan a payload out to every device a user has registered.
///
/// Errors are logged, never returned: callers are on the message-send path and
/// the message has already been persisted and delivered over the socket by the
/// time we get here. A device the transport reports as permanently gone is
/// pruned, which is the only way these rows ever get cleaned up.
pub async fn send_to_user(state: &AppState, user_id: &str, payload: &PushPayload) {
    let Some(push) = state.push.clone() else {
        return;
    };
    let subs = match subscriptions_for(state, user_id).await {
        Ok(subs) => subs,
        Err(e) => {
            tracing::warn!(user = %user_id, error = %e, "could not load push subscriptions");
            return;
        }
    };
    if subs.is_empty() {
        return;
    }

    let encoded = match serde_json::to_vec(payload) {
        Ok(bytes) => bytes,
        Err(e) => {
            tracing::error!(error = %e, "push payload would not serialize");
            return;
        }
    };

    for sub in subs {
        let outcome = match sub.kind.as_str() {
            "webpush" => send_web(&push, &sub, &encoded).await,
            "fcm" => send_fcm(&push, &sub, payload).await,
            other => {
                tracing::warn!(kind = %other, "unknown push subscription kind");
                continue;
            }
        };
        match outcome {
            Ok(()) => {}
            Err(Delivery::Gone) => {
                tracing::debug!(kind = %sub.kind, "pruning dead push subscription");
                forget(state, &sub.id).await;
            }
            Err(Delivery::Failed(e)) => {
                tracing::warn!(kind = %sub.kind, error = %e, "push delivery failed");
            }
        }
    }
}

/// Separates "this device is permanently gone, delete it" from "this send did
/// not work, try again next time" - conflating them either leaks dead rows
/// forever or unsubscribes people over a transient 500.
enum Delivery {
    Gone,
    Failed(String),
}

async fn send_web(push: &Push, sub: &Subscription, payload: &[u8]) -> Result<(), Delivery> {
    let Some(web) = &push.web else {
        return Err(Delivery::Failed("Web Push is not configured".into()));
    };
    let (Some(p256dh), Some(auth)) = (&sub.p256dh, &sub.auth) else {
        // Keys are what the payload is encrypted to; without them the row is
        // unusable and always will be.
        return Err(Delivery::Gone);
    };

    let info = SubscriptionInfo::new(sub.endpoint.clone(), p256dh.clone(), auth.clone());

    let message = (|| {
        let mut sig = VapidSignatureBuilder::from_base64(&web.private_key, &info)?;
        sig.add_claim("sub", web.subject.as_str());
        let signature = sig.build()?;

        let mut builder = WebPushMessageBuilder::new(&info);
        builder.set_payload(ContentEncoding::Aes128Gcm, payload);
        builder.set_vapid_signature(signature);
        builder.set_ttl(TTL_SECONDS);
        builder.set_urgency(Urgency::High);
        builder.build()
    })()
    .map_err(|e| Delivery::Failed(format!("{e}")))?;

    let mut request = push
        .client
        .post(message.endpoint.to_string())
        .header("TTL", message.ttl);
    if let Some(urgency) = message.urgency {
        request = request.header("Urgency", urgency.to_string());
    }
    if let Some(topic) = message.topic {
        request = request.header("Topic", topic);
    }
    if let Some(payload) = message.payload {
        request = request
            .header("Content-Encoding", payload.content_encoding.to_str())
            .header("Content-Type", "application/octet-stream");
        for (name, value) in payload.crypto_headers {
            request = request.header(name, value);
        }
        request = request.body(payload.content);
    }

    let res = request
        .send()
        .await
        .map_err(|e| Delivery::Failed(format!("{e}")))?;

    let status = res.status();
    if status.is_success() {
        return Ok(());
    }
    // 404/410 is the push service telling us the subscription was revoked -
    // the tab's permission was withdrawn, or the browser profile is gone.
    if status == reqwest::StatusCode::NOT_FOUND || status == reqwest::StatusCode::GONE {
        return Err(Delivery::Gone);
    }
    let body = res.text().await.unwrap_or_default();
    Err(Delivery::Failed(format!("{status}: {}", body.trim())))
}

async fn send_fcm(push: &Push, sub: &Subscription, payload: &PushPayload) -> Result<(), Delivery> {
    let Some(fcm) = &push.fcm else {
        return Err(Delivery::Failed("FCM is not configured".into()));
    };
    let token = fcm_access_token(push, fcm)
        .await
        .map_err(Delivery::Failed)?;

    // Data-only (see the module note), and every value must be a string: FCM's
    // data map is typed `map<string, string>` and rejects anything else.
    let body = json!({
        "message": {
            "token": sub.endpoint,
            "data": {
                "title": payload.title,
                "body": payload.body,
                "href": payload.href,
                "tag": payload.tag,
                "channelId": payload.channel_id,
                "messageId": payload.message_id.clone().unwrap_or_default(),
                "senderId": payload.sender_id,
                "senderName": payload.sender_name,
                "isGroup": payload.is_group.to_string(),
                "icon": payload.icon.clone().unwrap_or_default(),
                "kind": match payload.kind {
                    PushKind::Call => "call",
                    PushKind::Read => "read",
                    PushKind::Security => "security",
                    PushKind::Message => "message",
                },
                "ciphertext": payload.ciphertext.clone().unwrap_or_default(),
                "encEpoch": payload.enc_epoch.map(|value| value.to_string()).unwrap_or_default(),
            },
            "android": {
                // Anything less can be held until the device next wakes, which
                // for a chat notification is indistinguishable from dropping it.
                "priority": "high",
                "ttl": format!("{TTL_SECONDS}s"),
            },
        }
    });

    let url = format!(
        "https://fcm.googleapis.com/v1/projects/{}/messages:send",
        fcm.account.project_id
    );
    let res = push
        .client
        .post(&url)
        .bearer_auth(token)
        .json(&body)
        .send()
        .await
        .map_err(|e| Delivery::Failed(format!("{e}")))?;

    let status = res.status();
    if status.is_success() {
        return Ok(());
    }
    let text = res.text().await.unwrap_or_default();
    // UNREGISTERED (404) is an uninstalled app or a rotated token. A 400
    // INVALID_ARGUMENT naming the token is a malformed one that will never work;
    // other 400s are our own payload's fault and must not prune the device.
    if status == reqwest::StatusCode::NOT_FOUND
        || (status == reqwest::StatusCode::BAD_REQUEST && text.contains("registration token"))
    {
        return Err(Delivery::Gone);
    }
    Err(Delivery::Failed(format!("{status}: {}", text.trim())))
}

/// Mint (or reuse) an OAuth2 access token for FCM.
///
/// The service-account flow: self-sign a JWT asserting the scope, hand it to
/// Google, get a bearer token back. Refreshed a minute before expiry so a token
/// can't die mid-flight.
async fn fcm_access_token(push: &Push, fcm: &Fcm) -> Result<String, String> {
    if let Some((token, exp)) = fcm.token.read().await.as_ref() {
        if now_secs() + 60 < *exp {
            return Ok(token.clone());
        }
    }

    let mut slot = fcm.token.write().await;
    // Another task may have refreshed it while we waited for the write lock.
    if let Some((token, exp)) = slot.as_ref() {
        if now_secs() + 60 < *exp {
            return Ok(token.clone());
        }
    }

    let iat = now_secs();
    let claims = TokenClaims {
        iss: &fcm.account.client_email,
        scope: FCM_SCOPE,
        aud: GOOGLE_TOKEN_URI,
        exp: iat + 3600,
        iat,
    };
    let key = jsonwebtoken::EncodingKey::from_rsa_pem(fcm.account.private_key.as_bytes())
        .map_err(|e| format!("service-account private key is unusable: {e}"))?;
    let assertion = jsonwebtoken::encode(
        &jsonwebtoken::Header::new(jsonwebtoken::Algorithm::RS256),
        &claims,
        &key,
    )
    .map_err(|e| format!("could not sign FCM token request: {e}"))?;

    let res = push
        .client
        .post(GOOGLE_TOKEN_URI)
        .form(&[
            ("grant_type", "urn:ietf:params:oauth:grant-type:jwt-bearer"),
            ("assertion", &assertion),
        ])
        .send()
        .await
        .map_err(|e| format!("{e}"))?;

    if !res.status().is_success() {
        let status = res.status();
        let body = res.text().await.unwrap_or_default();
        return Err(format!(
            "FCM token exchange failed ({status}): {}",
            body.trim()
        ));
    }

    let token: TokenResponse = res.json().await.map_err(|e| format!("{e}"))?;
    *slot = Some((token.access_token.clone(), iat + token.expires_in));
    Ok(token.access_token)
}

// ── Pending-notification tracking ────────────────────────
//
// A notification, once delivered, lives on the device until something cancels
// it - and only the app can, by running code. So a message read on another
// device can't clear a phone's notification unless we wake that phone and tell
// it to. We only ever want to do that for a channel that actually has a
// notification outstanding, so we remember, per user, which channels we've
// pushed for and haven't yet seen read. `notify_read` fires a dismissal only
// when the channel is in that set, which keeps reads that clear nothing (every
// channel switch, most sends) from waking a device for no reason.

/// Channels we've pushed a message for and not yet seen read, per user. Expires
/// with the push TTL: a notification the transport would no longer deliver is
/// not one we still need to chase down.
fn pending_key(user_id: &str) -> String {
    format!("push:pending:{user_id}")
}

/// Remember that `user_id` now has an outstanding notification for `channel_id`.
async fn mark_pending(state: &AppState, user_id: &str, channel_id: &str) {
    let mut con = state.rd();
    let key = pending_key(user_id);
    let _: Result<(), _> = con.sadd(&key, channel_id).await;
    let _: Result<(), _> = con.expire(&key, TTL_SECONDS as i64).await;
}

/// Drop a channel from the pending set, reporting whether it was there - i.e.
/// whether a notification for it is plausibly still on a device.
async fn clear_pending(state: &AppState, user_id: &str, channel_id: &str) -> bool {
    let mut con = state.rd();
    let removed: i64 = con
        .srem(pending_key(user_id), channel_id)
        .await
        .unwrap_or(0);
    removed > 0
}

// ── Fan-out helpers ──────────────────────────────────────

/// Push a new message to everyone it should reach, skipping the author.
///
/// Deliberately narrower than the unread bookkeeping next to it at the call
/// site: a DM or an @mention is worth waking a phone for, an ordinary message
/// in a busy server channel is not, and that distinction is the difference
/// between notifications people keep on and notifications people turn off. It
/// matches what the web client already chose to surface locally.
#[allow(clippy::too_many_arguments)]
pub async fn notify_message(
    state: &AppState,
    message_id: &str,
    channel_id: &str,
    server_id: Option<&str>,
    author_id: &str,
    author_name: &str,
    author_avatar: Option<&str>,
    preview: &str,
    recipients: &[String],
    // `ciphertext` is the base64 message envelope for an encrypted conversation;
    // see docs/E2EE.md §8. None for a plaintext one.
    ciphertext: Option<&str>,
    enc_epoch: Option<i32>,
) {
    if state.push.is_none() {
        return;
    }
    let is_dm = server_id.is_none();

    // The socket handler already resolved the authoritative recipients.
    let targets = recipients.to_vec();

    let href = match server_id {
        Some(server_id) => format!("/servers/{server_id}/channels/{channel_id}"),
        None => format!("/dms/{channel_id}"),
    };
    // An encrypted conversation has no body here to compose from: `content` is
    // the empty string by construction. The envelope goes instead and the client
    // opens it; a ciphertext too large for the transport is dropped rather than
    // truncated, because half a GCM ciphertext decrypts to nothing at all.
    let sealed = ciphertext.filter(|c| c.len() <= MAX_PUSH_CIPHERTEXT_CHARS);
    let encrypted = ciphertext.is_some();

    let payload = PushPayload {
        title: if is_dm {
            author_name.to_string()
        } else {
            format!("{author_name} mentioned you")
        },
        // Matches the 140 the web client truncates its local notification to,
        // and keeps us clear of the 4 KB Web Push payload ceiling.
        body: if encrypted {
            String::new()
        } else {
            preview.chars().take(140).collect()
        },
        href,
        tag: channel_id.to_string(),
        icon: author_avatar.map(str::to_string),
        channel_id: channel_id.to_string(),
        message_id: Some(message_id.to_string()),
        sender_id: author_id.to_string(),
        sender_name: author_name.to_string(),
        is_group: !is_dm,
        kind: PushKind::Message,
        ciphertext: sealed.map(str::to_string),
        enc_epoch: sealed.and(enc_epoch),
    };

    for target in targets {
        if target == author_id {
            continue;
        }
        send_to_user(state, &target, &payload).await;
        // Record the outstanding notification so a later read on any of this
        // user's devices can reach out and dismiss it (see `notify_read`).
        mark_pending(state, &target, channel_id).await;
    }
}

/// Dismiss a user's outstanding notification for a channel they just read
/// elsewhere. A no-op unless we actually pushed for that channel, so ordinary
/// channel opens don't wake the user's other devices. The payload carries only
/// the channel and `PushKind::Read`; each client cancels by channel and shows
/// nothing new (NotificationHelper on Android, the service worker on web).
pub async fn notify_read(state: &AppState, user_id: &str, channel_id: &str) {
    if state.push.is_none() {
        return;
    }
    if !clear_pending(state, user_id, channel_id).await {
        return;
    }
    let payload = PushPayload {
        title: String::new(),
        body: String::new(),
        href: String::new(),
        tag: channel_id.to_string(),
        icon: None,
        channel_id: channel_id.to_string(),
        message_id: None,
        sender_id: String::new(),
        sender_name: String::new(),
        is_group: false,
        kind: PushKind::Read,
        ciphertext: None,
        enc_epoch: None,
    };
    send_to_user(state, user_id, &payload).await;
}

/// Tell every device something happened to the account. No channel, no sender,
/// no collapsing against conversation notifications - `tag` is the event so a
/// second security notice never silently replaces the first.
pub async fn notify_security(
    state: &AppState,
    user_id: &str,
    tag: &str,
    title: &str,
    body: &str,
    href: &str,
) {
    let payload = PushPayload {
        title: title.to_string(),
        body: body.to_string(),
        href: href.to_string(),
        tag: format!("security:{tag}"),
        icon: None,
        channel_id: String::new(),
        message_id: None,
        sender_id: String::new(),
        sender_name: String::new(),
        is_group: false,
        kind: PushKind::Security,
        ciphertext: None,
        enc_epoch: None,
    };
    send_to_user(state, user_id, &payload).await;
}

/// Ring a callee's devices. Unlike a message this is worth interrupting for, so
/// the client escalates it to a full-screen intent.
pub async fn notify_call(
    state: &AppState,
    channel_id: &str,
    caller_name: &str,
    caller_avatar: Option<&str>,
    video: bool,
    targets: &[String],
) {
    if state.push.is_none() {
        return;
    }
    let kind = if video { "video call" } else { "voice call" };
    let payload = PushPayload {
        title: format!("Incoming {kind}"),
        body: format!("{caller_name} is calling"),
        href: format!("/dms/{channel_id}"),
        tag: format!("call:{channel_id}"),
        icon: caller_avatar.map(str::to_string),
        channel_id: channel_id.to_string(),
        message_id: None,
        sender_id: caller_name.to_string(),
        sender_name: caller_name.to_string(),
        is_group: targets.len() > 1,
        kind: PushKind::Call,
        ciphertext: None,
        enc_epoch: None,
    };
    for target in targets {
        send_to_user(state, target, &payload).await;
    }
}

/// The VAPID public key the browser needs to subscribe, if Web Push is on.
pub fn web_public_key(state: &AppState) -> Option<String> {
    state.push.as_ref()?.web.as_ref()?;
    state.config.vapid_public_key.clone()
}
