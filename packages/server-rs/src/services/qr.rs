//! QR sign-in: a logged-out web client shows a code, a signed-in phone scans and
//! approves it, and the web client is then signed in as that account. The
//! WhatsApp / Discord-web pattern.
//!
//! The whole exchange rides on one short-lived, single-use token in Redis. The
//! web client only ever holds the token; the account it resolves to is set by
//! the phone, which is already authenticated. Nothing issues a session until the
//! phone has explicitly approved, and the token is burned the moment it does, so
//! a scanned-but-unapproved or replayed code is worth nothing.

use redis::AsyncCommands;
use serde::{Deserialize, Serialize};

use crate::error::{AppError, AppResult};
use crate::state::AppState;

/// How long a code is good for once shown. Long enough to find your phone,
/// short enough that a shoulder-surfed code expires before it's useful.
const TTL_SECONDS: i64 = 120;

fn key(token: &str) -> String {
    format!("qrlogin:{token}")
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "lowercase")]
pub enum QrState {
    /// Shown on the web, not yet seen by a phone.
    Pending,
    /// A phone has scanned it and is deciding.
    Scanned,
    /// The phone said yes; `user_id` is who to sign the web client in as.
    Approved,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
struct QrRecord {
    state: QrState,
    /// Set once a phone scans; the account the session will belong to.
    user_id: Option<String>,
}

async fn read(state: &AppState, token: &str) -> AppResult<Option<QrRecord>> {
    let mut con = state.rd();
    let raw: Option<String> = con.get(key(token)).await?;
    Ok(raw.and_then(|r| serde_json::from_str(&r).ok()))
}

/// Preserve the key's remaining life on write: the code's clock starts when it's
/// shown and must not be extended by a scan, or a slow approval could outlast
/// the window a bystander saw.
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

/// Mint a fresh code. The token is a v4 UUID: unguessable and single-purpose.
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

/// The phone reports that it scanned this code, moving it out of Pending so the
/// web client can show "found your phone" before the tap. Idempotent-ish: a
/// second scan of an already-approved code is refused.
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

/// The phone approves. The approving account is pinned here and nowhere else, so
/// the web client can never influence which account it becomes.
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

/// What the web client's poll sees. Returns the approved account exactly once:
/// on Approved it deletes the token and hands back the user id, so the same code
/// can't mint a second session.
pub enum PollResult {
    Pending,
    Scanned,
    /// Consume this into a session; the token is already gone.
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
            // Burn on read: single-use is what stops a replayed poll.
            let mut con = state.rd();
            let _: () = con.del(key(token)).await?;
            Ok(PollResult::Approved(user_id))
        }
    }
}
