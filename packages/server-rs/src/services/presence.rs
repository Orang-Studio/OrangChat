//! Presence counters in Redis. Mirrors presence-service.ts.

use std::collections::HashMap;

use redis::AsyncCommands;

use crate::dto::ActivityDto;
use crate::error::AppResult;
use crate::state::AppState;

const TTL: i64 = 60 * 60 * 24;

fn count_key(user_id: &str) -> String {
    format!("presence:count:{user_id}")
}
fn status_key(user_id: &str) -> String {
    format!("presence:status:{user_id}")
}
fn devices_key(user_id: &str) -> String {
    format!("presence:devices:{user_id}")
}
fn activities_key(user_id: &str) -> String {
    format!("presence:activities:{user_id}")
}

/// Remove presence left by a previous server process.
///
/// Socket.IO connections are process-local and cannot survive a backend
/// restart, but their Redis counters can. Without this startup reconciliation,
/// every restart can leave users falsely online until the 24-hour TTL expires.
/// This server is deployed as a single Socket.IO process, so before its listener
/// opens there cannot be a valid presence key to preserve.
pub async fn clear_stale_startup_presence(state: &AppState) -> AppResult<u64> {
    let mut con = state.rd();
    let mut stale_keys = Vec::new();

    for pattern in [
        "presence:count:*",
        "presence:status:*",
        "presence:devices:*",
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

/// Register a socket. Returns true if this is the user's first (went online).
pub async fn add_socket(state: &AppState, user_id: &str, device: &str) -> AppResult<bool> {
    let mut con = state.rd();
    let count: i64 = con.incr(count_key(user_id), 1).await?;
    let _: i64 = con.hincr(devices_key(user_id), device, 1).await?;
    let _: () = con.expire(count_key(user_id), TTL).await?;
    let _: () = con.expire(devices_key(user_id), TTL).await?;
    Ok(count == 1)
}

/// Deregister a socket. Returns true if it was their last (went offline).
pub async fn remove_socket(state: &AppState, user_id: &str, device: &str) -> AppResult<bool> {
    let mut con = state.rd();
    let count: i64 = con.decr(count_key(user_id), 1).await?;
    if count <= 0 {
        let _: () = con
            .del(&[
                count_key(user_id),
                status_key(user_id),
                devices_key(user_id),
                activities_key(user_id),
            ])
            .await?;
        Ok(true)
    } else {
        let device_count: i64 = con.hincr(devices_key(user_id), device, -1).await?;
        if device_count <= 0 {
            let _: () = con.hdel(devices_key(user_id), device).await?;
        }
        Ok(false)
    }
}

pub async fn get_activities(state: &AppState, user_id: &str) -> AppResult<Vec<ActivityDto>> {
    let mut con = state.rd();
    let online: bool = con.exists(count_key(user_id)).await?;
    if !online {
        return Ok(Vec::new());
    }
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
            .set_ex(activities_key(user_id), encoded, TTL as u64)
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
    let mut cursor = 0_u64;
    let mut users = Vec::new();
    loop {
        let (next, keys): (u64, Vec<String>) = redis::cmd("SCAN")
            .arg(cursor)
            .arg("MATCH")
            .arg("presence:count:*")
            .arg("COUNT")
            .arg(100)
            .query_async(&mut con)
            .await?;
        users.extend(
            keys.into_iter()
                .filter_map(|key| key.strip_prefix("presence:count:").map(str::to_string)),
        );
        cursor = next;
        if cursor == 0 {
            break;
        }
    }
    Ok(users)
}

pub async fn set_status(state: &AppState, user_id: &str, status: &str) -> AppResult<()> {
    let mut con = state.rd();
    let _: () = con.set_ex(status_key(user_id), status, TTL as u64).await?;
    Ok(())
}

pub async fn get_status(state: &AppState, user_id: &str) -> AppResult<String> {
    let mut con = state.rd();
    let online: bool = con.exists(count_key(user_id)).await?;
    if !online {
        return Ok("offline".into());
    }
    let status: Option<String> = con.get(status_key(user_id)).await?;
    Ok(status.unwrap_or_else(|| "online".into()))
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

/// Distinct client kinds with at least one connected socket, in stable UI order.
pub async fn get_devices(state: &AppState, user_id: &str) -> AppResult<Vec<String>> {
    let mut con = state.rd();
    let online: bool = con.exists(count_key(user_id)).await?;
    if !online {
        return Ok(Vec::new());
    }
    let counts: HashMap<String, i64> = con.hgetall(devices_key(user_id)).await?;
    Ok(["desktop", "browser", "mobile"]
        .into_iter()
        .filter(|kind| counts.get(*kind).copied().unwrap_or(0) > 0)
        .map(str::to_string)
        .collect())
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
