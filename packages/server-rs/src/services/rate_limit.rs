
use std::time::{SystemTime, UNIX_EPOCH};

use crate::error::{AppError, AppResult};
use crate::state::AppState;

#[derive(Clone, Copy)]
pub struct Quota {
    pub limit: u32,
    pub window_secs: u64,
}

impl Quota {
    pub const fn new(limit: u32, window_secs: u64) -> Self {
        Quota { limit, window_secs }
    }
}

const MINUTE: u64 = 60;
const HOUR: u64 = 3600;

pub const API_PER_IP: Quota = Quota::new(600, MINUTE);

pub const LOGIN_PER_IP: Quota = Quota::new(20, 5 * MINUTE);
pub const LOGIN_FAILURES_PER_ACCOUNT: Quota = Quota::new(8, 15 * MINUTE);
pub const SIGNUP_PER_IP: Quota = Quota::new(5, HOUR);
pub const INVITE_PREVIEW_PER_IP: Quota = Quota::new(120, MINUTE);
pub const REFRESH_PER_IP: Quota = Quota::new(60, 5 * MINUTE);
pub const QR_POLL_PER_IP: Quota = Quota::new(200, MINUTE);
pub const OAUTH_START_PER_IP: Quota = Quota::new(20, 5 * MINUTE);
pub const TOTP_PER_USER: Quota = Quota::new(10, 5 * MINUTE);
pub const EMAIL_PER_IP: Quota = Quota::new(3, MINUTE);
pub const EMAIL_2FA_PER_IP: Quota = Quota::new(10, 5 * MINUTE);
pub const EMAIL_CODE_PER_USER: Quota = Quota::new(3, MINUTE);

pub const E2EE_ENROL_PER_USER: Quota = Quota::new(10, HOUR);
pub const E2EE_LOOKUP_PER_USER: Quota = Quota::new(300, MINUTE);
pub const E2EE_EPOCH_PER_USER: Quota = Quota::new(120, 10 * MINUTE);
pub const E2EE_TRANSFER_MUTATION_PER_USER: Quota = Quota::new(20, 10 * MINUTE);
pub const E2EE_TRANSFER_POLL_PER_USER: Quota = Quota::new(240, 10 * MINUTE);

pub const UPLOAD_IMAGE_PER_USER: Quota = Quota::new(30, 10 * MINUTE);
pub const UPLOAD_ATTACHMENT_PER_USER: Quota = Quota::new(60, 10 * MINUTE);
pub const LINK_PREVIEW_PER_USER: Quota = Quota::new(30, MINUTE);

pub const MEDIA_SIGN_PER_USER: Quota = Quota::new(120, MINUTE);

pub const MEDIA_PROXY_PER_IP: Quota = Quota::new(600, MINUTE);

pub const SOUNDBOARD_PER_USER: Quota = Quota::new(6, 10);

pub const FRIEND_REQUEST_PER_USER: Quota = Quota::new(20, HOUR);
pub const DM_CREATE_PER_USER: Quota = Quota::new(20, 10 * MINUTE);
pub const SERVER_CREATE_PER_USER: Quota = Quota::new(10, HOUR);
pub const SEARCH_PER_USER: Quota = Quota::new(60, MINUTE);

pub const MESSAGE_SEND_PER_USER: Quota = Quota::new(10, 5);
pub const MESSAGE_SEND_PER_BOT: Quota = Quota::new(60, 5);
pub const MESSAGE_EDIT_PER_USER: Quota = Quota::new(20, 10);
pub const MESSAGE_REPORT_PER_USER: Quota = Quota::new(20, HOUR);
pub const REACTION_PER_USER: Quota = Quota::new(30, 10);
pub const TYPING_PER_USER: Quota = Quota::new(20, 10);
pub const CALL_START_PER_USER: Quota = Quota::new(15, 5 * MINUTE);
pub const GAME_ACTIVITY_PER_USER: Quota = Quota::new(40, MINUTE);

fn now_secs() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0)
}

fn window_key(bucket: &str, key: &str, quota: Quota, now: u64) -> String {
    let window = now / quota.window_secs;
    format!("rl:{bucket}:{key}:{window}")
}

fn retry_after(quota: Quota, now: u64) -> u64 {
    quota.window_secs - (now % quota.window_secs)
}

fn exceeded(bucket: &str, quota: Quota, now: u64) -> AppError {
    let retry_after = retry_after(quota, now);
    tracing::debug!("rate limit hit: {bucket}");
    AppError::TooManyRequests {
        message: format!("Too many requests. Try again in {retry_after}s."),
        retry_after,
    }
}

pub async fn check(state: &AppState, bucket: &str, key: &str, quota: Quota) -> AppResult<()> {
    let now = now_secs();
    let Some(count) = incr(state, &window_key(bucket, key, quota, now), quota).await else {
        return Ok(());
    };
    if count > quota.limit as u64 {
        return Err(exceeded(bucket, quota, now));
    }
    Ok(())
}

pub async fn peek(state: &AppState, bucket: &str, key: &str, quota: Quota) -> AppResult<()> {
    let now = now_secs();
    let mut con = state.rd();
    let count: Option<u64> = match redis::cmd("GET")
        .arg(window_key(bucket, key, quota, now))
        .query_async(&mut con)
        .await
    {
        Ok(c) => c,
        Err(e) => {
            tracing::warn!("rate limit peek failed, allowing: {e}");
            return Ok(());
        }
    };
    if count.unwrap_or(0) >= quota.limit as u64 {
        return Err(exceeded(bucket, quota, now));
    }
    Ok(())
}

pub async fn record(state: &AppState, bucket: &str, key: &str, quota: Quota) {
    let now = now_secs();
    incr(state, &window_key(bucket, key, quota, now), quota).await;
}

pub async fn current(state: &AppState, bucket: &str, key: &str, quota: Quota) -> u64 {
    let mut con = state.rd();
    redis::cmd("GET")
        .arg(window_key(bucket, key, quota, now_secs()))
        .query_async(&mut con)
        .await
        .unwrap_or(0)
}

pub async fn reset(state: &AppState, bucket: &str, key: &str, quota: Quota) {
    let mut con = state.rd();
    let key = window_key(bucket, key, quota, now_secs());
    let _: Result<u64, _> = redis::cmd("DEL").arg(key).query_async(&mut con).await;
}

async fn incr(state: &AppState, key: &str, quota: Quota) -> Option<u64> {
    let mut con = state.rd();
    let result: Result<(u64,), _> = redis::pipe()
        .atomic()
        .incr(key, 1)
        .expire(key, (quota.window_secs + 1) as i64)
        .ignore()
        .query_async(&mut con)
        .await;
    match result {
        Ok((count,)) => Some(count),
        Err(e) => {
            tracing::warn!("rate limit check failed, allowing: {e}");
            None
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const Q: Quota = Quota::new(5, 60);

    #[test]
    fn key_is_stable_within_a_window_and_rolls_over_after() {
        assert_eq!(window_key("b", "k", Q, 60), window_key("b", "k", Q, 119));
        assert_ne!(window_key("b", "k", Q, 119), window_key("b", "k", Q, 120));
    }

    #[test]
    fn retry_after_counts_down_to_the_window_edge() {
        assert_eq!(retry_after(Q, 120), 60);
        assert_eq!(retry_after(Q, 150), 30);
        assert_eq!(retry_after(Q, 179), 1);
    }

    #[test]
    fn e2ee_poll_budget_covers_a_complete_relay_transfer() {
        const MAX_NORMAL_POLLS: u32 = 45 * 3;
        const _: () = assert!(E2EE_TRANSFER_POLL_PER_USER.limit >= MAX_NORMAL_POLLS);
        const _: () =
            assert!(E2EE_TRANSFER_POLL_PER_USER.limit > E2EE_TRANSFER_MUTATION_PER_USER.limit);
    }
}
