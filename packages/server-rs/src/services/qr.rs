
use redis::AsyncCommands;
use serde::{Deserialize, Serialize};

use crate::error::{AppError, AppResult};
use crate::state::AppState;

const TTL_SECONDS: i64 = 120;

fn key(token: &str) -> String {
    format!("qrlogin:{token}")
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "lowercase")]
pub enum QrState {
    Pending,
    Scanned,
    Approved,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
struct QrRecord {
    state: QrState,
    user_id: Option<String>,
}

async fn read(state: &AppState, token: &str) -> AppResult<Option<QrRecord>> {
    let mut con = state.rd();
    let raw: Option<String> = con.get(key(token)).await?;
    Ok(raw.and_then(|r| serde_json::from_str(&r).ok()))
}

async fn write(state: &AppState, token: &str, record: &QrRecord) -> AppResult<()> {
    let mut con = state.rd();
    let ttl: i64 = con.ttl(key(token)).await?;
    if ttl <= 0 {
        return Err(AppError::NotFound("This code has expired".into()));
    }
    let encoded =
        serde_json::to_string(record).map_err(|e| AppError::Internal(format!("qr: {e}")))?;
    let _: () = con.set_ex(key(token), encoded, ttl as u64).await?;
    Ok(())
}

pub async fn start(state: &AppState) -> AppResult<(String, i64)> {
    let token = uuid::Uuid::new_v4().to_string();
    let record = QrRecord {
        state: QrState::Pending,
        user_id: None,
    };
    let encoded =
        serde_json::to_string(&record).map_err(|e| AppError::Internal(format!("qr: {e}")))?;
    let mut con = state.rd();
    let _: () = con.set_ex(key(&token), encoded, TTL_SECONDS as u64).await?;
    Ok((token, TTL_SECONDS))
}

pub async fn scan(state: &AppState, token: &str, user_id: &str) -> AppResult<()> {
    let record = read(state, token)
        .await?
        .ok_or_else(|| AppError::NotFound("This code has expired".into()))?;
    if record.state == QrState::Approved {
        return Err(AppError::Conflict("This code was already used".into()));
    }
    write(
        state,
        token,
        &QrRecord {
            state: QrState::Scanned,
            user_id: Some(user_id.to_string()),
        },
    )
    .await
}

pub async fn approve(state: &AppState, token: &str, user_id: &str) -> AppResult<()> {
    let record = read(state, token)
        .await?
        .ok_or_else(|| AppError::NotFound("This code has expired".into()))?;
    if record.state == QrState::Approved {
        return Err(AppError::Conflict("This code was already used".into()));
    }
    write(
        state,
        token,
        &QrRecord {
            state: QrState::Approved,
            user_id: Some(user_id.to_string()),
        },
    )
    .await
}

pub enum PollResult {
    Pending,
    Scanned,
    Approved(String),
    Expired,
}

pub async fn poll(state: &AppState, token: &str) -> AppResult<PollResult> {
    let Some(record) = read(state, token).await? else {
        return Ok(PollResult::Expired);
    };
    match record.state {
        QrState::Pending => Ok(PollResult::Pending),
        QrState::Scanned => Ok(PollResult::Scanned),
        QrState::Approved => {
            let user_id = record
                .user_id
                .ok_or_else(|| AppError::Internal("approved code without a user".into()))?;
            let mut con = state.rd();
            let _: () = con.del(key(token)).await?;
            Ok(PollResult::Approved(user_id))
        }
    }
}
