//! Presence leases in Redis.
//!
//! Every current client refreshes its Socket.IO connection's five-minute lease
//! once a minute. Aggregate presence is derived from the unexpired leases
//! instead of a counter, so a missed disconnect cannot leave a user online
//! indefinitely. A new connection starts with a compatibility lease so clients
//! installed before heartbeats were introduced do not disappear immediately.

use std::collections::{HashMap, HashSet};

use chrono::Utc;
use redis::{AsyncCommands, Script};

use crate::dto::ActivityDto;
use crate::error::AppResult;
use crate::state::AppState;

const DATA_TTL: i64 = 60 * 60 * 24;
pub const LEASE_TTL_SECONDS: i64 = 5 * 60;

fn leases_key(user_id: &str) -> String {
    format!("presence:leases:{user_id}")
}
fn status_key(user_id: &str) -> String {
    format!("presence:status:{user_id}")
}
fn devices_key(user_id: &str) -> String {
    format!("presence:devices:{user_id}")
}
fn lifecycle_key(user_id: &str) -> String {
    format!("presence:lifecycle:{user_id}")
}
fn activities_key(user_id: &str) -> String {
    format!("presence:activities:{user_id}")
}
fn users_key() -> &'static str {
    "presence:users"
}

fn now_ms() -> i64 {
    Utc::now().timestamp_millis()
}

fn lease_deadline_ms(ttl_seconds: i64) -> i64 {
    now_ms() + ttl_seconds * 1_000
}

/// Remove presence left by a previous server process.
///
/// Socket.IO connections are process-local and cannot survive a backend
/// restart, while Redis does. This server is deployed as a single Socket.IO
/// process, so no lease can still be valid before the new listener opens.
pub async fn clear_stale_startup_presence(state: &AppState) -> AppResult<u64> {
    let mut con = state.rd();
    let mut stale_keys = Vec::new();

    for pattern in [
        "presence:count:*", // Legacy counter keys from before socket leases.
        "presence:leases:*",
        "presence:status:*",
        "presence:devices:*",
        "presence:lifecycle:*",
        "presence:activities:*",
    ] {
        let mut cursor = 0_u64;
        loop {
            let (next, keys): (u64, Vec<String>) = redis::cmd("SCAN")
                .arg(cursor)
                .arg("MATCH")
                .arg(pattern)
                .arg("COUNT")
                .arg(100)
                .query_async(&mut con)
                .await?;
            stale_keys.extend(keys);
            cursor = next;
            if cursor == 0 {
                break;
            }
        }
    }
    stale_keys.push(users_key().to_string());

    // Mutating the keyspace during SCAN can cause entries to be skipped, so
    // collect the full snapshot first and only then delete it in bounded batches.
    let mut deleted = 0_u64;
    for keys in stale_keys.chunks(100) {
        deleted += redis::cmd("DEL")
            .arg(keys)
            .query_async::<u64>(&mut con)
            .await?;
    }

    Ok(deleted)
}

async fn upsert_socket(
    state: &AppState,
    user_id: &str,
    socket_id: &str,
    device: &str,
    ttl_seconds: i64,
) -> AppResult<bool> {
    let mut con = state.rd();
    let first: i64 = Script::new(
        r#"
        local expired = redis.call('ZRANGEBYSCORE', KEYS[1], '-inf', ARGV[1])
        for _, sid in ipairs(expired) do
            redis.call('HDEL', KEYS[2], sid)
            redis.call('HDEL', KEYS[3], sid)
        end
        redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', ARGV[1])
        local first = redis.call('ZCARD', KEYS[1]) == 0
        redis.call('ZADD', KEYS[1], ARGV[2], ARGV[3])
        redis.call('HSET', KEYS[2], ARGV[3], ARGV[4])
        redis.call('HSETNX', KEYS[3], ARGV[3], 'online')
        redis.call('SADD', KEYS[4], ARGV[5])
        redis.call('EXPIRE', KEYS[1], ARGV[6])
        redis.call('EXPIRE', KEYS[2], ARGV[6])
        redis.call('EXPIRE', KEYS[3], ARGV[6])
        return first and 1 or 0
        "#,
    )
    .key(leases_key(user_id))
    .key(devices_key(user_id))
    .key(lifecycle_key(user_id))
    .key(users_key())
    .arg(now_ms())
    .arg(lease_deadline_ms(ttl_seconds))
    .arg(socket_id)
    .arg(device)
    .arg(user_id)
    .arg(DATA_TTL)
    .invoke_async(&mut con)
    .await?;
    Ok(first == 1)
}

/// Register a newly-connected socket. The first lease lasts for the legacy
/// data TTL; current clients immediately replace it with a five-minute lease on
/// their first heartbeat.
pub async fn add_socket(
    state: &AppState,
    user_id: &str,
    socket_id: &str,
    device: &str,
) -> AppResult<bool> {
    upsert_socket(state, user_id, socket_id, device, DATA_TTL).await
}

/// Renew one socket's five-minute lease. Returns true when every prior lease
/// expired and this heartbeat brought the user back online.
pub async fn refresh_socket(
    state: &AppState,
    user_id: &str,
    socket_id: &str,
    device: &str,
) -> AppResult<bool> {
    upsert_socket(state, user_id, socket_id, device, LEASE_TTL_SECONDS).await
}

/// Deregister one socket. Returns true if it was the user's final live lease.
pub async fn remove_socket(state: &AppState, user_id: &str, socket_id: &str) -> AppResult<bool> {
    let mut con = state.rd();
    let last: i64 = Script::new(
        r#"
        redis.call('ZREM', KEYS[1], ARGV[1])
        redis.call('HDEL', KEYS[2], ARGV[1])
        redis.call('HDEL', KEYS[3], ARGV[1])
        local expired = redis.call('ZRANGEBYSCORE', KEYS[1], '-inf', ARGV[2])
        for _, sid in ipairs(expired) do
            redis.call('HDEL', KEYS[2], sid)
            redis.call('HDEL', KEYS[3], sid)
        end
        redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', ARGV[2])
        if redis.call('ZCARD', KEYS[1]) == 0 then
            redis.call('DEL', KEYS[1], KEYS[2], KEYS[3], KEYS[5], KEYS[6])
            redis.call('SREM', KEYS[4], ARGV[3])
            return 1
        end
        return 0
        "#,
    )
    .key(leases_key(user_id))
    .key(devices_key(user_id))
    .key(lifecycle_key(user_id))
    .key(users_key())
    .key(status_key(user_id))
    .key(activities_key(user_id))
    .arg(socket_id)
    .arg(now_ms())
    .arg(user_id)
    .invoke_async(&mut con)
    .await?;
    Ok(last == 1)
}

/// Prune expired socket ids for one user. The second return value is true only
/// for the sweep that observed an online -> offline transition.
async fn prune_user(state: &AppState, user_id: &str) -> AppResult<(bool, bool)> {
    let mut con = state.rd();
    let result: Vec<i64> = Script::new(
        r#"
        local was_online = redis.call('SISMEMBER', KEYS[4], ARGV[2])
        local expired = redis.call('ZRANGEBYSCORE', KEYS[1], '-inf', ARGV[1])
        for _, sid in ipairs(expired) do
            redis.call('HDEL', KEYS[2], sid)
            redis.call('HDEL', KEYS[3], sid)
        end
        redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', ARGV[1])
        if redis.call('ZCARD', KEYS[1]) == 0 then
            redis.call('DEL', KEYS[1], KEYS[2], KEYS[3], KEYS[5], KEYS[6])
            redis.call('SREM', KEYS[4], ARGV[2])
            return {0, was_online}
        end
        return {1, 0}
        "#,
    )
    .key(leases_key(user_id))
    .key(devices_key(user_id))
    .key(lifecycle_key(user_id))
    .key(users_key())
    .key(status_key(user_id))
    .key(activities_key(user_id))
    .arg(now_ms())
    .arg(user_id)
    .invoke_async(&mut con)
    .await?;
    Ok((result.first() == Some(&1), result.get(1) == Some(&1)))
}

/// Remove expired leases and return the users that need one offline broadcast.
pub async fn sweep_expired_leases(state: &AppState) -> AppResult<Vec<String>> {
    let mut con = state.rd();
    let users: Vec<String> = con.smembers(users_key()).await?;
    let mut offline = Vec::new();
    for user_id in users {
        let (_, went_offline) = prune_user(state, &user_id).await?;
        if went_offline {
            offline.push(user_id);
        }
    }
    Ok(offline)
}

pub async fn get_activities(state: &AppState, user_id: &str) -> AppResult<Vec<ActivityDto>> {
    let (online, _) = prune_user(state, user_id).await?;
    if !online {
        return Ok(Vec::new());
    }
    let mut con = state.rd();
    let raw: Option<String> = con.get(activities_key(user_id)).await?;
    Ok(raw
        .and_then(|value| serde_json::from_str(&value).ok())
        .unwrap_or_default())
}

/// Replace one provider's activity while preserving future activity providers.
/// Returns true only when the public value changed and needs broadcasting.
pub async fn set_activity(
    state: &AppState,
    user_id: &str,
    kind: &str,
    activity: Option<ActivityDto>,
) -> AppResult<bool> {
    let mut activities = get_activities(state, user_id).await?;
    activities.retain(|item| item.kind != kind);
    if let Some(activity) = activity {
        activities.push(activity);
    }
    let encoded = serde_json::to_string(&activities)
        .map_err(|e| crate::error::AppError::Internal(format!("activity encode: {e}")))?;
    let mut con = state.rd();
    let previous: Option<String> = con.get(activities_key(user_id)).await?;
    if previous.as_deref() == Some(&encoded) {
        return Ok(false);
    }
    if activities.is_empty() {
        let _: () = con.del(activities_key(user_id)).await?;
    } else {
        let _: () = con
            .set_ex(activities_key(user_id), encoded, DATA_TTL as u64)
            .await?;
    }
    Ok(true)
}

pub async fn get_activity_sets(
    state: &AppState,
    user_ids: &[String],
) -> AppResult<HashMap<String, Vec<ActivityDto>>> {
    let mut result = HashMap::new();
    for id in user_ids {
        result.insert(id.clone(), get_activities(state, id).await?);
    }
    Ok(result)
}

pub async fn online_user_ids(state: &AppState) -> AppResult<Vec<String>> {
    let mut con = state.rd();
    let users: Vec<String> = con.smembers(users_key()).await?;
    let mut online = Vec::new();
    for user_id in users {
        if prune_user(state, &user_id).await?.0 {
            online.push(user_id);
        }
    }
    Ok(online)
}

pub fn valid_status(status: &str) -> bool {
    matches!(status, "online" | "idle" | "dnd" | "offline")
}

fn effective_status(base: Option<String>, lifecycle: Vec<String>) -> String {
    let base = base.unwrap_or_else(|| "online".into());
    if base != "online" {
        return base;
    }
    if lifecycle.is_empty() || lifecycle.iter().any(|status| status == "online") {
        "online".into()
    } else {
        "idle".into()
    }
}

/// Update foreground/background state for one socket without overwriting the
/// user's explicit status selection on their other clients.
pub async fn set_socket_lifecycle(
    state: &AppState,
    user_id: &str,
    socket_id: &str,
    status: &str,
) -> AppResult<bool> {
    if !matches!(status, "online" | "idle") {
        return Ok(false);
    }
    let (online, _) = prune_user(state, user_id).await?;
    if !online {
        return Ok(false);
    }
    let mut con = state.rd();
    let lease_is_live: bool = con
        .zscore::<_, _, Option<f64>>(leases_key(user_id), socket_id)
        .await?
        .is_some();
    if !lease_is_live {
        return Ok(false);
    }
    let _: () = con.hset(lifecycle_key(user_id), socket_id, status).await?;
    Ok(true)
}

pub async fn set_status(state: &AppState, user_id: &str, status: &str) -> AppResult<()> {
    if !valid_status(status) {
        return Ok(());
    }
    let mut con = state.rd();
    let _: () = con
        .set_ex(status_key(user_id), status, DATA_TTL as u64)
        .await?;
    Ok(())
}

pub async fn get_status(state: &AppState, user_id: &str) -> AppResult<String> {
    let (online, _) = prune_user(state, user_id).await?;
    if !online {
        return Ok("offline".into());
    }
    let mut con = state.rd();
    let base: Option<String> = con.get(status_key(user_id)).await?;
    let lifecycle: Vec<String> = con.hvals(lifecycle_key(user_id)).await?;
    Ok(effective_status(base, lifecycle))
}

pub async fn get_statuses(
    state: &AppState,
    user_ids: &[String],
) -> AppResult<HashMap<String, String>> {
    let mut result = HashMap::new();
    for id in user_ids {
        result.insert(id.clone(), get_status(state, id).await?);
    }
    Ok(result)
}

/// Distinct client kinds with at least one live socket, in stable UI order.
pub async fn get_devices(state: &AppState, user_id: &str) -> AppResult<Vec<String>> {
    let (online, _) = prune_user(state, user_id).await?;
    if !online {
        return Ok(Vec::new());
    }
    let mut con = state.rd();
    let devices: Vec<String> = con.hvals(devices_key(user_id)).await?;
    let devices: HashSet<String> = devices.into_iter().collect();
    Ok(["desktop", "browser", "mobile"]
        .into_iter()
        .filter(|kind| devices.contains(*kind))
        .map(str::to_string)
        .collect())
}

/// The kinds in [`get_devices`] order, keeping only sockets that last reported
/// themselves foreground.
fn foreground_kinds(
    devices: HashMap<String, String>,
    lifecycle: &HashMap<String, String>,
) -> Vec<String> {
    let active: HashSet<String> = devices
        .into_iter()
        // A socket with no lifecycle entry is treated as foreground: the upsert
        // seeds 'online' and only a client that has since backgrounded itself
        // ever writes 'idle'.
        .filter(|(socket_id, _)| lifecycle.get(socket_id).map(String::as_str) != Some("idle"))
        .map(|(_, kind)| kind)
        .collect();
    ["desktop", "browser", "mobile"]
        .into_iter()
        .filter(|kind| active.contains(*kind))
        .map(str::to_string)
        .collect()
}

/// Which kinds of client the user is actually sitting in front of.
///
/// [`get_devices`] answers "where is this account signed in", which is the wrong
/// question for a notification: a phone holding a socket open in the background
/// is precisely the phone that still needs waking. This keeps only the sockets
/// whose client reported itself foreground, so "mobile" here means the app is
/// open in front of the user and "browser"/"desktop" mean they are at their
/// computer rather than merely logged in on it.
pub async fn active_devices(state: &AppState, user_id: &str) -> AppResult<Vec<String>> {
    let (online, _) = prune_user(state, user_id).await?;
    if !online {
        return Ok(Vec::new());
    }
    let mut con = state.rd();
    let devices: HashMap<String, String> = con.hgetall(devices_key(user_id)).await?;
    let lifecycle: HashMap<String, String> = con.hgetall(lifecycle_key(user_id)).await?;
    Ok(foreground_kinds(devices, &lifecycle))
}

pub async fn get_device_sets(
    state: &AppState,
    user_ids: &[String],
) -> AppResult<HashMap<String, Vec<String>>> {
    let mut result = HashMap::new();
    for id in user_ids {
        result.insert(id.clone(), get_devices(state, id).await?);
    }
    Ok(result)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn lease_is_five_minutes() {
        assert_eq!(LEASE_TTL_SECONDS, 300);
    }

    #[test]
    fn only_public_presence_statuses_are_accepted() {
        for status in ["online", "idle", "dnd", "offline"] {
            assert!(valid_status(status));
        }
        assert!(!valid_status(""));
        assert!(!valid_status("invisible-but-online"));
    }

    fn map(pairs: &[(&str, &str)]) -> HashMap<String, String> {
        pairs
            .iter()
            .map(|(k, v)| (k.to_string(), v.to_string()))
            .collect()
    }

    #[test]
    fn a_backgrounded_phone_is_not_a_phone_you_are_looking_at() {
        let devices = map(&[("s1", "mobile"), ("s2", "browser")]);
        let lifecycle = map(&[("s1", "idle"), ("s2", "online")]);
        assert_eq!(foreground_kinds(devices, &lifecycle), vec!["browser"]);
    }

    #[test]
    fn a_socket_that_never_reported_itself_counts_as_foreground() {
        let devices = map(&[("s1", "mobile")]);
        assert_eq!(
            foreground_kinds(devices, &HashMap::new()),
            vec!["mobile".to_string()]
        );
    }

    #[test]
    fn one_live_tab_keeps_the_kind_active_however_many_are_idle() {
        let devices = map(&[("s1", "browser"), ("s2", "browser")]);
        let lifecycle = map(&[("s1", "idle"), ("s2", "online")]);
        assert_eq!(
            foreground_kinds(devices, &lifecycle),
            vec!["browser".to_string()]
        );
    }

    #[test]
    fn explicit_status_wins_and_online_lifecycle_beats_idle() {
        assert_eq!(effective_status(None, vec!["idle".into()]), "idle");
        assert_eq!(
            effective_status(Some("online".into()), vec!["idle".into(), "online".into()]),
            "online"
        );
        assert_eq!(
            effective_status(Some("dnd".into()), vec!["online".into()]),
            "dnd"
        );
    }
}
