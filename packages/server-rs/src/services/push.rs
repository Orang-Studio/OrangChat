
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
use crate::services::presence;
use crate::state::AppState;

const TTL_SECONDS: u32 = 60 * 60 * 24;

const FCM_SCOPE: &str = "https://www.googleapis.com/auth/firebase.messaging";
const GOOGLE_TOKEN_URI: &str = "https://oauth2.googleapis.com/token";

#[derive(Debug, Clone, FromRow)]
pub struct Subscription {
    pub id: String,
    pub kind: String,
    pub endpoint: String,
    pub p256dh: Option<String>,
    pub auth: Option<String>,
}

#[derive(Debug, Deserialize)]
pub struct NewSubscription {
    pub kind: String,
    pub endpoint: String,
    pub p256dh: Option<String>,
    pub auth: Option<String>,
    pub label: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PushPayload {
    pub title: String,
    pub body: String,
    pub href: String,
    pub tag: String,
    pub icon: Option<String>,
    pub channel_id: String,
    pub message_id: Option<String>,
    pub sender_id: String,
    pub sender_name: String,
    pub is_group: bool,
    pub kind: PushKind,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub ciphertext: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub enc_epoch: Option<i32>,
}

const MAX_PUSH_CIPHERTEXT_CHARS: usize = 2600;

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "lowercase")]
pub enum PushKind {
    Message,
    Call,
    Read,
    Security,
}


struct WebPush {
    private_key: String,
    subject: String,
}


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
    token: RwLock<Option<(String, u64)>>,
}


pub struct Push {
    web: Option<WebPush>,
    fcm: Option<Fcm>,
    client: reqwest::Client,
}

impl Push {
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

async fn forget(state: &AppState, id: &str) {
    let _ = sqlx::query(r#"DELETE FROM "PushSubscription" WHERE "id" = $1"#)
        .bind(id)
        .execute(&state.pool)
        .await;
}


pub async fn send_to_user(state: &AppState, user_id: &str, payload: &PushPayload) {
    send_to(state, user_id, payload, Audience::Every).await;
}

#[derive(Clone, Copy, PartialEq, Eq)]
enum Audience {
    Every,
    Phones,
    NotPhones,
}

impl Audience {
    fn admits(self, kind: &str) -> bool {
        match self {
            Audience::Every => true,
            Audience::Phones => kind == "fcm",
            Audience::NotPhones => kind != "fcm",
        }
    }
}

async fn send_to(state: &AppState, user_id: &str, payload: &PushPayload, audience: Audience) {
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
        if !audience.admits(&sub.kind) {
            continue;
        }
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

enum Delivery {
    Gone,
    Failed(String),
}

async fn send_web(push: &Push, sub: &Subscription, payload: &[u8]) -> Result<(), Delivery> {
    let Some(web) = &push.web else {
        return Err(Delivery::Failed("Web Push is not configured".into()));
    };
    let (Some(p256dh), Some(auth)) = (&sub.p256dh, &sub.auth) else {
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
                "avatarUrl": payload.icon.clone().unwrap_or_default(),
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
    if status == reqwest::StatusCode::NOT_FOUND
        || (status == reqwest::StatusCode::BAD_REQUEST && text.contains("registration token"))
    {
        return Err(Delivery::Gone);
    }
    Err(Delivery::Failed(format!("{status}: {}", text.trim())))
}

async fn fcm_access_token(push: &Push, fcm: &Fcm) -> Result<String, String> {
    if let Some((token, exp)) = fcm.token.read().await.as_ref() {
        if now_secs() + 60 < *exp {
            return Ok(token.clone());
        }
    }

    let mut slot = fcm.token.write().await;
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


fn pending_key(user_id: &str) -> String {
    format!("push:pending:{user_id}")
}

async fn mark_pending(state: &AppState, user_id: &str, channel_id: &str) {
    let mut con = state.rd();
    let key = pending_key(user_id);
    let _: Result<(), _> = con.sadd(&key, channel_id).await;
    let _: Result<(), _> = con.expire(&key, TTL_SECONDS as i64).await;
}

async fn clear_pending(state: &AppState, user_id: &str, channel_id: &str) -> bool {
    let mut con = state.rd();
    let removed: i64 = con
        .srem(pending_key(user_id), channel_id)
        .await
        .unwrap_or(0);
    removed > 0
}


const DEFERRED_KEY: &str = "push:deferred:mobile";

pub const DEFER_TO_MOBILE_SECONDS: i64 = 300;

#[derive(Serialize, Deserialize)]
struct Deferred {
    user_id: String,
    payload: PushPayload,
}

async fn defer_to_mobile(state: &AppState, user_id: &str, payload: &PushPayload) {
    let entry = Deferred {
        user_id: user_id.to_string(),
        payload: payload.clone(),
    };
    let Ok(raw) = serde_json::to_string(&entry) else {
        return;
    };
    let due = (now_secs() as i64 + DEFER_TO_MOBILE_SECONDS) as f64;
    let mut con = state.rd();
    let _: Result<(), _> = con.zadd(DEFERRED_KEY, raw, due).await;
    let _: Result<(), _> = con.expire(DEFERRED_KEY, TTL_SECONDS as i64).await;
}

pub async fn flush_deferred(state: &AppState) -> AppResult<usize> {
    if state.push.is_none() {
        return Ok(0);
    }
    let due: Vec<String> = {
        let mut con = state.rd();
        con.zrangebyscore_limit(DEFERRED_KEY, 0f64, now_secs() as f64, 0, 200)
            .await?
    };

    let mut sent = 0;
    for raw in due {
        let claimed: i64 = {
            let mut con = state.rd();
            con.zrem(DEFERRED_KEY, &raw).await.unwrap_or(0)
        };
        if claimed == 0 {
            continue;
        }
        let Ok(entry) = serde_json::from_str::<Deferred>(&raw) else {
            continue;
        };
        let Some(message_id) = entry.payload.message_id.clone() else {
            continue;
        };
        if !crate::services::read_state::is_message_unread(state, &entry.user_id, &message_id)
            .await?
        {
            continue;
        }
        let active = presence::active_devices(state, &entry.user_id)
            .await
            .unwrap_or_default();
        if active.iter().any(|kind| kind == "mobile") {
            continue;
        }

        send_to(state, &entry.user_id, &entry.payload, Audience::Phones).await;
        mark_pending(state, &entry.user_id, &entry.payload.channel_id).await;
        sent += 1;
    }
    Ok(sent)
}


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
    ciphertext: Option<&str>,
    enc_epoch: Option<i32>,
) {
    if state.push.is_none() {
        return;
    }
    let is_dm = server_id.is_none();

    let targets = recipients.to_vec();

    let href = match server_id {
        Some(server_id) => format!("/servers/{server_id}/channels/{channel_id}"),
        None => format!("/dms/{channel_id}"),
    };
    let sealed = ciphertext.filter(|c| c.len() <= MAX_PUSH_CIPHERTEXT_CHARS);
    let encrypted = ciphertext.is_some();

    let payload = PushPayload {
        title: if is_dm {
            author_name.to_string()
        } else {
            format!("{author_name} mentioned you")
        },
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
        let active = presence::active_devices(state, &target)
            .await
            .unwrap_or_default();
        let on_phone = active.iter().any(|kind| kind == "mobile");
        let at_computer = active
            .iter()
            .any(|kind| kind == "browser" || kind == "desktop");

        if on_phone {
            send_to(state, &target, &payload, Audience::NotPhones).await;
        } else if at_computer {
            send_to(state, &target, &payload, Audience::NotPhones).await;
            defer_to_mobile(state, &target, &payload).await;
        } else {
            send_to_user(state, &target, &payload).await;
        }
        mark_pending(state, &target, channel_id).await;
    }
}

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

pub fn web_public_key(state: &AppState) -> Option<String> {
    state.push.as_ref()?.web.as_ref()?;
    state.config.vapid_public_key.clone()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn only_fcm_counts_as_a_phone() {
        assert!(Audience::Phones.admits("fcm"));
        assert!(!Audience::Phones.admits("webpush"));
        assert!(!Audience::NotPhones.admits("fcm"));
        assert!(Audience::NotPhones.admits("webpush"));
        for kind in ["fcm", "webpush"] {
            assert!(Audience::Every.admits(kind));
        }
    }

    #[test]
    fn a_held_payload_survives_being_parked() {
        let payload = PushPayload {
            title: "Kim".into(),
            body: String::new(),
            href: "/dms/c1".into(),
            tag: "c1".into(),
            icon: None,
            channel_id: "c1".into(),
            message_id: Some("m1".into()),
            sender_id: "u1".into(),
            sender_name: "Kim".into(),
            is_group: false,
            kind: PushKind::Message,
            ciphertext: Some("sealed".into()),
            enc_epoch: Some(3),
        };
        let raw = serde_json::to_string(&Deferred {
            user_id: "u2".into(),
            payload,
        })
        .unwrap();
        let back: Deferred = serde_json::from_str(&raw).unwrap();

        assert_eq!(back.user_id, "u2");
        assert_eq!(back.payload.message_id.as_deref(), Some("m1"));
        assert_eq!(back.payload.ciphertext.as_deref(), Some("sealed"));
        assert_eq!(back.payload.enc_epoch, Some(3));
        assert_eq!(back.payload.kind, PushKind::Message);
    }

    #[test]
    fn a_plaintext_payload_parks_without_its_absent_fields() {
        let raw = r#"{"user_id":"u2","payload":{"title":"Kim","body":"hi","href":"/dms/c1",
            "tag":"c1","icon":null,"channel_id":"c1","message_id":"m1","sender_id":"u1",
            "sender_name":"Kim","is_group":false,"kind":"message"}}"#;
        let back: Deferred = serde_json::from_str(raw).unwrap();
        assert!(back.payload.ciphertext.is_none());
        assert!(back.payload.enc_epoch.is_none());
    }
}
