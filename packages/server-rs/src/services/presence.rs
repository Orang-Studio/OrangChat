//! Presence counters in Redis. Mirrors presence-service.ts.

use std::collections::HashMap;

use redis::AsyncCommands;

use crate::error::AppResult;
use crate::state::AppState;

const TTL: i64 = 60 * 60 * 24;

fn count_key(user_id: &str) -> String {
    format!("presence:count:{user_id}")
}
fn status_key(user_id: &str) -> String {
    format!("presence:status:{user_id}")
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

    for pattern in ["presence:count:*", "presence:status:*"] {
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
pub async fn add_socket(state: &AppState, user_id: &str) -> AppResult<bool> {
    let mut con = state.rd();
    let count: i64 = con.incr(count_key(user_id), 1).await?;
    let _: () = con.expire(count_key(user_id), TTL).await?;
    Ok(count == 1)
}

/// Deregister a socket. Returns true if it was their last (went offline).
pub async fn remove_socket(state: &AppState, user_id: &str) -> AppResult<bool> {
    let mut con = state.rd();
    let count: i64 = con.decr(count_key(user_id), 1).await?;
    if count <= 0 {
        let _: () = con.del(&[count_key(user_id), status_key(user_id)]).await?;
        Ok(true)
    } else {
        Ok(false)
    }
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
