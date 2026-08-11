
use chrono::Utc;
use jsonwebtoken::{encode, EncodingKey, Header};
use redis::AsyncCommands;
use serde::{Deserialize, Serialize};

use crate::config::Config;
use crate::error::{AppError, AppResult};
use crate::permissions::{has_permission, SCREEN_SHARE, SPEAK, VIDEO};
use crate::state::AppState;

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct VoiceStatePayload {
    pub channel_id: String,
    pub user_id: String,
    pub joined: bool,
    pub muted: bool,
    pub deafened: bool,
    pub video: bool,
    pub screen_sharing: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct StoredState {
    user_id: String,
    muted: bool,
    deafened: bool,
    video: bool,
    screen_sharing: bool,
}

#[derive(Default)]
pub struct VoicePatch {
    pub muted: Option<bool>,
    pub deafened: Option<bool>,
    pub video: Option<bool>,
    pub screen_sharing: Option<bool>,
}

pub fn is_voice_configured(cfg: &Config) -> bool {
    cfg.livekit_url.is_some() && cfg.livekit_api_key.is_some() && cfg.livekit_api_secret.is_some()
}

fn room_name(channel_id: &str) -> String {
    format!("voice_{channel_id}")
}
fn state_key(channel_id: &str) -> String {
    format!("voice:state:{channel_id}")
}

fn devices_key(user_id: &str) -> String {
    format!("voice:devices:{user_id}")
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct DeviceSession {
    pub session_id: String,
    pub channel_id: String,
}

pub async fn add_device(
    state: &AppState,
    user_id: &str,
    session_id: &str,
    channel_id: &str,
) -> AppResult<()> {
    let mut con = state.rd();
    let _: () = con
        .hset(devices_key(user_id), session_id, channel_id)
        .await?;
    Ok(())
}

pub async fn remove_device(state: &AppState, user_id: &str, session_id: &str) -> AppResult<()> {
    let mut con = state.rd();
    let _: () = con.hdel(devices_key(user_id), session_id).await?;
    Ok(())
}

pub async fn list_devices(state: &AppState, user_id: &str) -> AppResult<Vec<DeviceSession>> {
    let mut con = state.rd();
    let all: std::collections::HashMap<String, String> = con.hgetall(devices_key(user_id)).await?;
    Ok(all
        .into_iter()
        .map(|(session_id, channel_id)| DeviceSession {
            session_id,
            channel_id,
        })
        .collect())
}

pub async fn has_other_device_in(
    state: &AppState,
    user_id: &str,
    channel_id: &str,
    except_session: &str,
) -> AppResult<bool> {
    Ok(list_devices(state, user_id)
        .await?
        .iter()
        .any(|d| d.channel_id == channel_id && d.session_id != except_session))
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct VideoGrant {
    room_join: bool,
    room: String,
    can_publish: bool,
    can_subscribe: bool,
    can_publish_data: bool,
    #[serde(skip_serializing_if = "Vec::is_empty")]
    can_publish_sources: Vec<String>,
}

#[derive(Serialize)]
struct LiveKitClaims {
    iss: String,
    sub: String,
    jti: String,
    name: String,
    nbf: i64,
    exp: i64,
    video: VideoGrant,
}

pub fn publish_sources(perms: i64) -> Vec<String> {
    let mut sources = Vec::new();
    if has_permission(perms, SPEAK) {
        sources.push("microphone".to_string());
    }
    if has_permission(perms, VIDEO) {
        sources.push("camera".to_string());
    }
    if has_permission(perms, SCREEN_SHARE) {
        sources.push("screen_share".to_string());
        sources.push("screen_share_audio".to_string());
    }
    sources
}

pub fn mint_voice_token(
    cfg: &Config,
    channel_id: &str,
    user_id: &str,
    username: &str,
    sources: Option<Vec<String>>,
) -> AppResult<(String, String)> {
    if !is_voice_configured(cfg) {
        return Err(AppError::Permission(
            "Voice is not configured on this server".into(),
        ));
    }
    let (can_publish, can_publish_sources) = match sources {
        None => (true, Vec::new()),
        Some(list) => (!list.is_empty(), list),
    };
    let now = Utc::now().timestamp();
    let claims = LiveKitClaims {
        iss: cfg.livekit_api_key.clone().unwrap(),
        sub: user_id.to_string(),
        jti: user_id.to_string(),
        name: username.to_string(),
        nbf: now,
        exp: now + 7200,
        video: VideoGrant {
            room_join: true,
            room: room_name(channel_id),
            can_publish,
            can_subscribe: true,
            can_publish_data: true,
            can_publish_sources,
        },
    };
    let secret = cfg.livekit_api_secret.clone().unwrap();
    let token = encode(
        &Header::default(),
        &claims,
        &EncodingKey::from_secret(secret.as_bytes()),
    )
    .map_err(|e| AppError::Internal(format!("livekit token: {e}")))?;
    Ok((token, cfg.livekit_url.clone().unwrap()))
}

pub async fn set_voice_state(
    state: &AppState,
    channel_id: &str,
    user_id: &str,
    patch: VoicePatch,
) -> AppResult<VoiceStatePayload> {
    apply_voice_patch(state, channel_id, user_id, patch, false).await
}

pub async fn patch_voice_state(
    state: &AppState,
    channel_id: &str,
    user_id: &str,
    patch: VoicePatch,
) -> AppResult<VoiceStatePayload> {
    apply_voice_patch(state, channel_id, user_id, patch, true).await
}

async fn apply_voice_patch(
    state: &AppState,
    channel_id: &str,
    user_id: &str,
    patch: VoicePatch,
    require_existing: bool,
) -> AppResult<VoiceStatePayload> {
    let mut con = state.rd();
    let raw: Option<String> = con.hget(state_key(channel_id), user_id).await?;
    let existing: Option<StoredState> = raw.and_then(|r| serde_json::from_str(&r).ok());
    if require_existing && existing.is_none() {
        return Err(AppError::Permission(
            "Not connected to this voice channel".into(),
        ));
    }
    let mut current: StoredState = existing.unwrap_or(StoredState {
        user_id: user_id.to_string(),
        muted: false,
        deafened: false,
        video: false,
        screen_sharing: false,
    });
    if let Some(v) = patch.muted {
        current.muted = v;
    }
    if let Some(v) = patch.deafened {
        current.deafened = v;
    }
    if let Some(v) = patch.video {
        current.video = v;
    }
    if let Some(v) = patch.screen_sharing {
        current.screen_sharing = v;
    }
    current.user_id = user_id.to_string();

    let json = serde_json::to_string(&current).unwrap();
    let _: () = con.hset(state_key(channel_id), user_id, json).await?;

    Ok(VoiceStatePayload {
        channel_id: channel_id.to_string(),
        user_id: current.user_id,
        joined: true,
        muted: current.muted,
        deafened: current.deafened,
        video: current.video,
        screen_sharing: current.screen_sharing,
    })
}

pub async fn clear_voice_state(state: &AppState, channel_id: &str, user_id: &str) -> AppResult<()> {
    let mut con = state.rd();
    let _: () = con.hdel(state_key(channel_id), user_id).await?;
    Ok(())
}

pub async fn list_voice_participants(
    state: &AppState,
    channel_id: &str,
) -> AppResult<Vec<VoiceStatePayload>> {
    let mut con = state.rd();
    let all: std::collections::HashMap<String, String> = con.hgetall(state_key(channel_id)).await?;
    let mut out = Vec::new();
    for raw in all.values() {
        if let Ok(s) = serde_json::from_str::<StoredState>(raw) {
            out.push(VoiceStatePayload {
                channel_id: channel_id.to_string(),
                user_id: s.user_id,
                joined: true,
                muted: s.muted,
                deafened: s.deafened,
                video: s.video,
                screen_sharing: s.screen_sharing,
            });
        }
    }
    Ok(out)
}

pub async fn assert_capacity(
    state: &AppState,
    channel: &crate::models::ChannelRow,
    user_id: &str,
    perms: i64,
) -> AppResult<()> {
    if channel.user_limit <= 0 || channel.channel_type != "voice" {
        return Ok(());
    }
    if crate::permissions::has_permission(perms, crate::permissions::MANAGE_CHANNELS) {
        return Ok(());
    }
    let current = list_voice_participants(state, &channel.id).await?;
    if current.iter().any(|p| p.user_id == user_id) {
        return Ok(());
    }
    if current.len() as i32 >= channel.user_limit {
        return Err(AppError::Permission("This voice channel is full".into()));
    }
    Ok(())
}

pub fn left_payload(channel_id: &str, user_id: &str) -> VoiceStatePayload {
    VoiceStatePayload {
        channel_id: channel_id.to_string(),
        user_id: user_id.to_string(),
        joined: false,
        muted: false,
        deafened: false,
        video: false,
        screen_sharing: false,
    }
}
