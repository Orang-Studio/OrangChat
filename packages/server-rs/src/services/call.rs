
use std::time::Duration;

use chrono::Utc;
use redis::AsyncCommands;
use serde::{Deserialize, Serialize};

use crate::dto::{to_user, UserDto};
use crate::error::{AppError, AppResult};
use crate::models::UserRow;
use crate::services::{channel, dm, system_message};
use crate::state::AppState;

pub const RING_TIMEOUT: Duration = Duration::from_secs(30);

const CALL_TTL_SECS: u64 = 6 * 3600;

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct CallState {
    pub channel_id: String,
    pub caller_id: String,
    pub ringing: Vec<String>,
    pub participants: Vec<String>,
    pub video: bool,
    pub started_at: String,
    #[serde(default)]
    pub joined: Vec<String>,
    #[serde(default)]
    pub message_id: Option<String>,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct CallPayload {
    pub channel_id: String,
    pub caller_id: String,
    pub caller: UserDto,
    pub ringing: Vec<String>,
    pub participants: Vec<String>,
    pub video: bool,
    pub started_at: String,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct CallEndedPayload {
    pub channel_id: String,
    pub user_id: String,
    pub reason: &'static str,
    pub call_over: bool,
}

fn call_key(channel_id: &str) -> String {
    format!("call:{channel_id}")
}
fn user_key(user_id: &str) -> String {
    format!("call:user:{user_id}")
}


pub async fn load(state: &AppState, channel_id: &str) -> AppResult<Option<CallState>> {
    let mut con = state.rd();
    let raw: Option<String> = con.get(call_key(channel_id)).await?;
    Ok(raw.and_then(|r| serde_json::from_str(&r).ok()))
}

async fn save(state: &AppState, call: &CallState) -> AppResult<()> {
    let mut con = state.rd();
    let json = serde_json::to_string(call).unwrap();
    let _: () = con
        .set_ex(call_key(&call.channel_id), json, CALL_TTL_SECS)
        .await?;
    Ok(())
}

async fn discard(state: &AppState, call: &CallState) -> AppResult<()> {
    let mut con = state.rd();
    let _: () = con.del(call_key(&call.channel_id)).await?;
    for uid in call.ringing.iter().chain(call.participants.iter()) {
        let _: () = con.del(user_key(uid)).await?;
    }
    Ok(())
}

async fn bind_user(state: &AppState, user_id: &str, channel_id: &str) -> AppResult<()> {
    let mut con = state.rd();
    let _: () = con
        .set_ex(user_key(user_id), channel_id, CALL_TTL_SECS)
        .await?;
    Ok(())
}

async fn unbind_user(state: &AppState, user_id: &str) -> AppResult<()> {
    let mut con = state.rd();
    let _: () = con.del(user_key(user_id)).await?;
    Ok(())
}

pub async fn active_call_of(state: &AppState, user_id: &str) -> AppResult<Option<String>> {
    let mut con = state.rd();
    Ok(con.get(user_key(user_id)).await?)
}

async fn load_user(state: &AppState, user_id: &str) -> AppResult<UserRow> {
    sqlx::query_as(r#"SELECT * FROM "User" WHERE id = $1"#)
        .bind(user_id)
        .fetch_optional(&state.pool)
        .await?
        .ok_or_else(|| AppError::Internal(format!("call references missing user {user_id}")))
}

pub async fn payload(state: &AppState, call: &CallState) -> AppResult<CallPayload> {
    let caller = load_user(state, &call.caller_id).await?;
    Ok(CallPayload {
        channel_id: call.channel_id.clone(),
        caller_id: call.caller_id.clone(),
        caller: to_user(&caller),
        ringing: call.ringing.clone(),
        participants: call.participants.clone(),
        video: call.video,
        started_at: call.started_at.clone(),
    })
}


fn now_iso() -> String {
    Utc::now().format("%Y-%m-%dT%H:%M:%S%.3fZ").to_string()
}

fn elapsed_secs(from: &str, to: &str) -> Option<i64> {
    let start = chrono::DateTime::parse_from_rfc3339(from).ok()?;
    let end = chrono::DateTime::parse_from_rfc3339(to).ok()?;
    Some((end - start).num_seconds().max(0))
}

async fn sync_card(state: &AppState, call: &CallState) {
    let Some(message_id) = call.message_id.as_deref() else {
        return;
    };
    let card = system_message::CallCard {
        caller_id: &call.caller_id,
        video: call.video,
        started_at: &call.started_at,
        ended_at: None,
        joined: &call.joined,
        ringing: &call.ringing,
        duration_sec: None,
    };
    system_message::update_call(state, &call.channel_id, message_id, &card).await;
}

async fn finish_card(state: &AppState, call: &CallState) {
    let Some(message_id) = call.message_id.as_deref() else {
        return;
    };
    let ended_at = now_iso();
    let duration = elapsed_secs(&call.started_at, &ended_at);
    let card = system_message::CallCard {
        caller_id: &call.caller_id,
        video: call.video,
        started_at: &call.started_at,
        ended_at: Some(&ended_at),
        joined: &call.joined,
        ringing: &[],
        duration_sec: if call.joined.len() > 1 { duration } else { None },
    };
    system_message::update_call(state, &call.channel_id, message_id, &card).await;
}


pub struct StartOutcome {
    pub call: CallState,
    pub busy: Vec<String>,
    pub created: bool,
}

pub async fn start(
    state: &AppState,
    channel_id: &str,
    caller_id: &str,
    video: bool,
) -> AppResult<StartOutcome> {
    let ch = channel::require_channel_access(state, channel_id, caller_id).await?;
    if !matches!(ch.channel_type.as_str(), "dm" | "group_dm") {
        return Err(AppError::BadRequest(
            "Calls are only available in direct messages".into(),
        ));
    }

    if let Some(existing) = active_call_of(state, caller_id).await? {
        if existing != channel_id {
            return Err(AppError::Conflict("You are already on a call".into()));
        }
    }

    if let Some(mut call) = load(state, channel_id).await? {
        if !call.participants.contains(&caller_id.to_string()) {
            call.participants.push(caller_id.to_string());
            call.ringing.retain(|u| u != caller_id);
            if !call.joined.iter().any(|u| u == caller_id) {
                call.joined.push(caller_id.to_string());
            }
            save(state, &call).await?;
            bind_user(state, caller_id, channel_id).await?;
            sync_card(state, &call).await;
        }
        return Ok(StartOutcome {
            call,
            busy: Vec::new(),
            created: false,
        });
    }

    let members = dm::get_conversation_participants(state, channel_id).await?;
    let mut ringing = Vec::new();
    let mut busy = Vec::new();
    for uid in members.into_iter().filter(|u| u != caller_id) {
        if active_call_of(state, &uid).await?.is_some() {
            busy.push(uid);
        } else {
            ringing.push(uid);
        }
    }
    if ringing.is_empty() {
        return Err(if busy.is_empty() {
            AppError::BadRequest("Nobody else is in this conversation".into())
        } else {
            AppError::Conflict("Everyone else is already on a call".into())
        });
    }

    let mut call = CallState {
        channel_id: channel_id.to_string(),
        caller_id: caller_id.to_string(),
        ringing: ringing.clone(),
        participants: vec![caller_id.to_string()],
        video,
        started_at: now_iso(),
        joined: vec![caller_id.to_string()],
        message_id: None,
    };
    let card = system_message::CallCard {
        caller_id: &call.caller_id,
        video: call.video,
        started_at: &call.started_at,
        ended_at: None,
        joined: &call.joined,
        ringing: &call.ringing,
        duration_sec: None,
    };
    call.message_id = system_message::open_call(state, channel_id, &card).await;
    save(state, &call).await?;
    bind_user(state, caller_id, channel_id).await?;
    for uid in &ringing {
        bind_user(state, uid, channel_id).await?;
    }

    Ok(StartOutcome {
        call,
        busy,
        created: true,
    })
}

pub async fn accept(state: &AppState, channel_id: &str, user_id: &str) -> AppResult<CallState> {
    let mut call = load(state, channel_id)
        .await?
        .ok_or_else(|| AppError::NotFound("That call has already ended".into()))?;
    if !call.ringing.contains(&user_id.to_string()) {
        return Err(AppError::BadRequest(
            "You are not being rung for this call".into(),
        ));
    }
    call.ringing.retain(|u| u != user_id);
    call.participants.push(user_id.to_string());
    if !call.joined.iter().any(|u| u == user_id) {
        call.joined.push(user_id.to_string());
    }
    save(state, &call).await?;
    bind_user(state, user_id, channel_id).await?;
    sync_card(state, &call).await;
    Ok(call)
}

pub async fn leave(state: &AppState, channel_id: &str, user_id: &str) -> AppResult<Option<bool>> {
    let Some(mut call) = load(state, channel_id).await? else {
        unbind_user(state, user_id).await?;
        return Ok(None);
    };
    let was_ringing = call.ringing.iter().any(|u| u == user_id);
    let was_participant = call.participants.iter().any(|u| u == user_id);
    if !was_ringing && !was_participant {
        return Ok(None);
    }
    call.ringing.retain(|u| u != user_id);
    call.participants.retain(|u| u != user_id);
    unbind_user(state, user_id).await?;

    if call.participants.is_empty() {
        discard(state, &call).await?;
        finish_card(state, &call).await;
        return Ok(Some(true));
    }
    save(state, &call).await?;
    sync_card(state, &call).await;
    Ok(Some(false))
}

pub async fn expire_ringing(
    state: &AppState,
    channel_id: &str,
    started_at: &str,
) -> AppResult<(Vec<String>, bool)> {
    let Some(mut call) = load(state, channel_id).await? else {
        return Ok((Vec::new(), false));
    };
    if call.started_at != started_at || call.ringing.is_empty() {
        return Ok((Vec::new(), false));
    }
    let timed_out: Vec<String> = std::mem::take(&mut call.ringing);
    for uid in &timed_out {
        unbind_user(state, uid).await?;
    }
    let over = call.participants.len() <= 1;
    if over {
        discard(state, &call).await?;
        finish_card(state, &call).await;
    } else {
        save(state, &call).await?;
        sync_card(state, &call).await;
    }
    Ok((timed_out, over))
}
