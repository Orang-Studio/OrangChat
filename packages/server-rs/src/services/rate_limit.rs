//! Redis fixed-window rate limiting, shared by the HTTP routes and the
//! Socket.IO handlers.
//!
//! Counters are keyed by window index, so a key is only ever written during the
//! window it belongs to and expires on its own afterwards - no sweeping, and no
//! risk of a refreshed TTL pinning someone at the limit forever.
//!
//! Every check fails **open**: if Redis is unreachable we log and allow. Redis
//! already holds the refresh-token store and presence, so an outage breaks auth
//! long before rate limiting matters, and 500ing every request would be worse
//! than briefly not counting them.

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

/// Backstop against a single address hammering the API. Well above what the app
/// itself asks for - the realtime path is a socket, not polling.
pub const API_PER_IP: Quota = Quota::new(600, MINUTE);

pub const LOGIN_PER_IP: Quota = Quota::new(20, 5 * MINUTE);
/// Counted on failure only, so a legitimate user behind a shared address can
/// always get in; it's the guessing that's budgeted.
pub const LOGIN_FAILURES_PER_ACCOUNT: Quota = Quota::new(8, 15 * MINUTE);
pub const SIGNUP_PER_IP: Quota = Quota::new(5, HOUR);
/// Readable without an account, so it is budgeted by address. Generous enough
/// for a chat full of invite embeds to resolve, far too tight to sweep the
/// 48-bit code space.
pub const INVITE_PREVIEW_PER_IP: Quota = Quota::new(120, MINUTE);
pub const REFRESH_PER_IP: Quota = Quota::new(60, 5 * MINUTE);
/// QR sign-in polls run ~every 2s for up to 2 min per attempt; keep it roomy.
pub const QR_POLL_PER_IP: Quota = Quota::new(200, MINUTE);
pub const OAUTH_START_PER_IP: Quota = Quota::new(20, 5 * MINUTE);
pub const TOTP_PER_USER: Quota = Quota::new(10, 5 * MINUTE);
pub const EMAIL_PER_IP: Quota = Quota::new(3, MINUTE);
pub const EMAIL_2FA_PER_IP: Quota = Quota::new(10, 5 * MINUTE);
/// Requesting a one-time email code for a device-transfer grant. Three per
/// minute mirrors the login resend budget; a fresh code invalidates the
/// previous one, so more than that is retry churn, not progress.
pub const EMAIL_CODE_PER_USER: Quota = Quota::new(3, MINUTE);

/// Enrolling, authorizing or revoking a device. Rare by nature, and each one is
/// a security event, so the budget is tight enough that a stolen session cannot
/// churn the device log.
pub const E2EE_ENROL_PER_USER: Quota = Quota::new(10, HOUR);
/// Reading someone's published device log. Public key material by design, but
/// budgeted so it is not a cheap way to sweep the user table.
pub const E2EE_LOOKUP_PER_USER: Quota = Quota::new(300, MINUTE);
/// Minting an epoch. Rotation is driven by membership and device changes, and a
/// busy group still needs far fewer than this.
pub const E2EE_EPOCH_PER_USER: Quota = Quota::new(120, 10 * MINUTE);
/// Creating a transfer or publishing one of its three single-use relay blobs.
/// These are genuine mutations and a normal transfer needs only a handful.
pub const E2EE_TRANSFER_MUTATION_PER_USER: Quota = Quota::new(20, 10 * MINUTE);
/// Both devices poll two-second relay slots while a person scans the QR,
/// compares the SAS and enters TOTP. A worst-case successful transfer makes
/// roughly 90 reads, so reads must not share the mutation budget.
pub const E2EE_TRANSFER_POLL_PER_USER: Quota = Quota::new(240, 10 * MINUTE);

pub const UPLOAD_IMAGE_PER_USER: Quota = Quota::new(30, 10 * MINUTE);
pub const UPLOAD_ATTACHMENT_PER_USER: Quota = Quota::new(60, 10 * MINUTE);
/// Fetches an arbitrary third-party URL, so this one guards other people's
/// servers as much as ours.
pub const LINK_PREVIEW_PER_USER: Quota = Quota::new(30, MINUTE);

/// Minting a signed proxy URL is cheap, but it's the gate on how much media a
/// single account can route through us; kept well above a busy channel's needs.
pub const MEDIA_SIGN_PER_USER: Quota = Quota::new(120, MINUTE);

/// The proxy fetch itself, keyed by client address since the signed URL carries
/// no session. A video streams in ranged chunks, so this sits high enough that
/// seeking a clip doesn't trip it while still capping open-proxy abuse.
pub const MEDIA_PROXY_PER_IP: Quota = Quota::new(600, MINUTE);

/// A soundboard plays out of everyone's speakers at once, which makes spamming
/// it the point rather than a side effect. Loose enough for a punchline, tight
/// enough that it cannot be held down.
pub const SOUNDBOARD_PER_USER: Quota = Quota::new(6, 10);

pub const FRIEND_REQUEST_PER_USER: Quota = Quota::new(20, HOUR);
pub const DM_CREATE_PER_USER: Quota = Quota::new(20, 10 * MINUTE);
pub const SERVER_CREATE_PER_USER: Quota = Quota::new(10, HOUR);
pub const SEARCH_PER_USER: Quota = Quota::new(60, MINUTE);

pub const MESSAGE_SEND_PER_USER: Quota = Quota::new(10, 5);
/// Bots answer commands in bursts - several replies to one trigger is normal
/// traffic, not abuse - so their ceiling is higher than a person's. It is still
/// a ceiling: a runaway loop is the failure mode this exists to contain.
///
/// Note that the coarse `API_PER_IP` (600/min) still applies to a bot's REST
/// calls. That is deliberate: bucketing on the credential instead would mean
/// trusting an unverified header to pick the bucket, which is a bypass.
pub const MESSAGE_SEND_PER_BOT: Quota = Quota::new(60, 5);
pub const MESSAGE_EDIT_PER_USER: Quota = Quota::new(20, 10);
/// Reporting is intentionally rare and stores disclosed plaintext. A tight
/// ceiling limits storage abuse without making a genuine burst impossible.
pub const MESSAGE_REPORT_PER_USER: Quota = Quota::new(20, HOUR);
pub const REACTION_PER_USER: Quota = Quota::new(30, 10);
pub const TYPING_PER_USER: Quota = Quota::new(20, 10);
pub const CALL_START_PER_USER: Quota = Quota::new(15, 5 * MINUTE);
/// The desktop client polls every 15s and only reports on a change, so a busy
/// alt-tabber costs about four of these a minute. The ceiling is here to bound a
/// client that reports on every poll, not to shape normal use.
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

/// Count this hit and reject it if the window is already full.
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

/// Reject if the window is already full, without counting this hit. Pair with
/// [`record`] when only some outcomes should consume the budget.
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

/// Count a hit without rejecting. See [`peek`].
pub async fn record(state: &AppState, bucket: &str, key: &str, quota: Quota) {
    let now = now_secs();
    incr(state, &window_key(bucket, key, quota, now), quota).await;
}

/// Read the current count without recording a request. Redis failures are
/// deliberately fail-open, matching the rest of this module.
pub async fn current(state: &AppState, bucket: &str, key: &str, quota: Quota) -> u64 {
    let mut con = state.rd();
    redis::cmd("GET")
        .arg(window_key(bucket, key, quota, now_secs()))
        .query_async(&mut con)
        .await
        .unwrap_or(0)
}

/// Clear a bucket, e.g. once a login succeeds. Best-effort.
pub async fn reset(state: &AppState, bucket: &str, key: &str, quota: Quota) {
    let mut con = state.rd();
    let key = window_key(bucket, key, quota, now_secs());
    let _: Result<u64, _> = redis::cmd("DEL").arg(key).query_async(&mut con).await;
}

/// Returns the post-increment count, or None if Redis is unavailable.
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
        // Desktop may wait 45 times for the phone's hello and the phone may
        // wait 45 times each for handshake and bundle. Leave room for retries.
        const MAX_NORMAL_POLLS: u32 = 45 * 3;
        const _: () = assert!(E2EE_TRANSFER_POLL_PER_USER.limit >= MAX_NORMAL_POLLS);
        const _: () =
            assert!(E2EE_TRANSFER_POLL_PER_USER.limit > E2EE_TRANSFER_MUTATION_PER_USER.limit);
    }
}
