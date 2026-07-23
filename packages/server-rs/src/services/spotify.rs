//! Spotify rich presence. Linked users are polled only while they have a live
//! OrangChat socket; tokens stay encrypted in Postgres and are never sent to a
//! client. The cached activity rides the ordinary presence fan-out.

use std::time::Duration as StdDuration;

use base64::engine::general_purpose::STANDARD;
use base64::Engine as _;
use chrono::{Duration, NaiveDateTime, Utc};
use redis::AsyncCommands;
use reqwest::StatusCode;
use serde_json::Value;
use sqlx::FromRow;

use crate::dto::ActivityDto;
use crate::error::{AppError, AppResult};
use crate::services::presence;
use crate::state::AppState;

const POLL_INTERVAL: StdDuration = StdDuration::from_secs(15);

#[derive(Debug, FromRow)]
struct SpotifyConnection {
    id: String,
    #[sqlx(rename = "userId")]
    user_id: String,
    #[sqlx(rename = "providerId")]
    provider_id: String,
    #[sqlx(rename = "oauthAccessToken")]
    access_token: Option<String>,
    #[sqlx(rename = "oauthRefreshToken")]
    refresh_token: Option<String>,
    #[sqlx(rename = "oauthExpiresAt")]
    expires_at: Option<NaiveDateTime>,
}

fn aad(row: &SpotifyConnection) -> String {
    format!("oauth:{}:spotify:{}", row.user_id, row.provider_id)
}

fn decrypt(state: &AppState, row: &SpotifyConnection, encoded: &str) -> AppResult<String> {
    let cipher = state
        .attachment_cipher
        .as_ref()
        .ok_or_else(|| AppError::Internal("OAuth token encryption is not configured".into()))?;
    let envelope = STANDARD
        .decode(encoded)
        .map_err(|_| AppError::Internal("Invalid encrypted OAuth token".into()))?;
    let plaintext = cipher.decrypt(&envelope, &aad(row))?;
    String::from_utf8(plaintext)
        .map_err(|_| AppError::Internal("Invalid OAuth token encoding".into()))
}

fn encrypt(state: &AppState, row: &SpotifyConnection, value: &str) -> AppResult<String> {
    let cipher = state
        .attachment_cipher
        .as_ref()
        .ok_or_else(|| AppError::Internal("OAuth token encryption is not configured".into()))?;
    Ok(STANDARD.encode(cipher.encrypt(value.as_bytes(), &aad(row))?))
}

async fn refresh_access_token(state: &AppState, row: &SpotifyConnection) -> AppResult<String> {
    let refresh = row
        .refresh_token
        .as_deref()
        .ok_or_else(|| AppError::BadRequest("Spotify needs to be linked again".into()))?;
    let refresh = decrypt(state, row, refresh)?;
    let client_id = state
        .config
        .spotify_client_id
        .as_deref()
        .unwrap_or_default();
    let client_secret = state
        .config
        .spotify_client_secret
        .as_deref()
        .unwrap_or_default();
    let response = reqwest::Client::new()
        .post("https://accounts.spotify.com/api/token")
        .basic_auth(client_id, Some(client_secret))
        .form(&[
            ("grant_type", "refresh_token"),
            ("refresh_token", refresh.as_str()),
        ])
        .send()
        .await
        .map_err(|e| AppError::Internal(format!("Spotify token refresh failed: {e}")))?;
    if !response.status().is_success() {
        return Err(AppError::Internal(format!(
            "Spotify token refresh failed: {}",
            response.status()
        )));
    }
    let body: Value = response
        .json()
        .await
        .map_err(|e| AppError::Internal(format!("Spotify token response failed: {e}")))?;
    let access = body
        .get("access_token")
        .and_then(Value::as_str)
        .ok_or_else(|| AppError::Internal("Spotify refresh omitted access_token".into()))?;
    let encrypted_access = encrypt(state, row, access)?;
    let expires_at = Utc::now()
        + Duration::seconds(
            body.get("expires_in")
                .and_then(Value::as_i64)
                .unwrap_or(3600),
        );
    let encrypted_refresh = body
        .get("refresh_token")
        .and_then(Value::as_str)
        .map(|value| encrypt(state, row, value))
        .transpose()?;
    sqlx::query(
        r#"UPDATE "Connection"
           SET "oauthAccessToken" = $2,
               "oauthRefreshToken" = COALESCE($3, "oauthRefreshToken"),
               "oauthExpiresAt" = $4
           WHERE id = $1"#,
    )
    .bind(&row.id)
    .bind(encrypted_access)
    .bind(encrypted_refresh)
    .bind(expires_at.naive_utc())
    .execute(&state.pool)
    .await?;
    Ok(access.to_string())
}

async fn access_token(state: &AppState, row: &SpotifyConnection) -> AppResult<String> {
    let valid = row
        .expires_at
        .map(|expiry| expiry > (Utc::now() + Duration::seconds(60)).naive_utc())
        .unwrap_or(false);
    if valid {
        if let Some(token) = row.access_token.as_deref() {
            return decrypt(state, row, token);
        }
    }
    refresh_access_token(state, row).await
}

fn text_at(value: &Value, pointer: &str) -> Option<String> {
    value
        .pointer(pointer)
        .and_then(Value::as_str)
        .map(str::to_string)
}

fn activity_from_playback(body: &Value) -> Option<ActivityDto> {
    if body.get("is_playing").and_then(Value::as_bool) != Some(true) {
        return None;
    }
    let item = body.get("item")?;
    let name = item.get("name")?.as_str()?.to_string();
    let item_type = item.get("type").and_then(Value::as_str).unwrap_or("track");
    let details = if item_type == "episode" {
        text_at(item, "/show/name")
    } else {
        item.get("artists")
            .and_then(Value::as_array)
            .map(|artists| {
                artists
                    .iter()
                    .filter_map(|artist| artist.get("name").and_then(Value::as_str))
                    .collect::<Vec<_>>()
                    .join(", ")
            })
            .filter(|artists| !artists.is_empty())
    };
    let image_url = text_at(item, "/album/images/0/url").or_else(|| text_at(item, "/images/0/url"));
    let url = text_at(item, "/external_urls/spotify");
    let timestamp = body.get("timestamp").and_then(Value::as_i64);
    let progress = body.get("progress_ms").and_then(Value::as_i64).unwrap_or(0);
    let duration = item.get("duration_ms").and_then(Value::as_i64);
    // Progress advances every poll; round the derived start to a second so
    // harmless API timing jitter does not broadcast an identical track again.
    let started_ms = timestamp
        .map(|value| value.saturating_sub(progress))
        .map(|value| value.div_euclid(1000) * 1000);
    let started_at = started_ms
        .and_then(chrono::DateTime::from_timestamp_millis)
        .map(|value| value.to_rfc3339());
    let ends_at = started_ms
        .zip(duration)
        .and_then(|(start, length)| {
            chrono::DateTime::from_timestamp_millis(start.saturating_add(length))
        })
        .map(|value| value.to_rfc3339());
    Some(ActivityDto {
        kind: "spotify".into(),
        name,
        details,
        url,
        image_url,
        started_at,
        ends_at,
    })
}

async fn fetch_playback(
    state: &AppState,
    row: &SpotifyConnection,
    token: &str,
) -> AppResult<Option<ActivityDto>> {
    let response = reqwest::Client::new()
        .get("https://api.spotify.com/v1/me/player/currently-playing?additional_types=episode")
        .bearer_auth(token)
        .send()
        .await
        .map_err(|e| AppError::Internal(format!("Spotify playback request failed: {e}")))?;
    if response.status() == StatusCode::NO_CONTENT {
        return Ok(None);
    }
    if response.status() == StatusCode::TOO_MANY_REQUESTS {
        let seconds = response
            .headers()
            .get("retry-after")
            .and_then(|value| value.to_str().ok())
            .and_then(|value| value.parse::<u64>().ok())
            .unwrap_or(30)
            .max(1);
        let mut redis = state.rd();
        let _: () = redis
            .set_ex(format!("spotify:backoff:{}", row.user_id), "1", seconds)
            .await?;
        return Err(AppError::Internal(
            "Spotify rate limited playback polling".into(),
        ));
    }
    if !response.status().is_success() {
        return Err(AppError::Internal(format!(
            "Spotify playback request failed: {}",
            response.status()
        )));
    }
    let body: Value = response
        .json()
        .await
        .map_err(|e| AppError::Internal(format!("Spotify playback response failed: {e}")))?;
    Ok(activity_from_playback(&body))
}

async fn poll_connection(state: &AppState, row: &SpotifyConnection) -> AppResult<()> {
    let mut redis = state.rd();
    let backed_off: bool = redis
        .exists(format!("spotify:backoff:{}", row.user_id))
        .await?;
    if backed_off {
        return Ok(());
    }
    let token = access_token(state, row).await?;
    let activity = match fetch_playback(state, row, &token).await {
        Err(error) if error.message().contains("401") => {
            let refreshed = refresh_access_token(state, row).await?;
            fetch_playback(state, row, &refreshed).await?
        }
        other => other?,
    };
    if presence::set_activity(state, &row.user_id, "spotify", activity).await? {
        let status = presence::get_status(state, &row.user_id).await?;
        crate::socket::broadcast_presence(state.io(), state, &row.user_id, &status).await;
    }
    Ok(())
}

pub async fn poll_once(state: &AppState) -> AppResult<usize> {
    let online = presence::online_user_ids(state).await?;
    if online.is_empty() {
        return Ok(0);
    }
    let rows = sqlx::query_as::<_, SpotifyConnection>(
        r#"SELECT id, "userId", "providerId", "oauthAccessToken", "oauthRefreshToken", "oauthExpiresAt"
           FROM "Connection"
           WHERE provider = 'spotify' AND "userId" = ANY($1) AND "oauthRefreshToken" IS NOT NULL"#,
    )
    .bind(&online)
    .fetch_all(&state.pool)
    .await?;
    for row in &rows {
        if let Err(error) = poll_connection(state, row).await {
            tracing::warn!(user_id = %row.user_id, "spotify presence poll failed: {}", error.message());
        }
    }
    Ok(rows.len())
}

pub async fn run_poll_loop(state: AppState) {
    let mut ticker = tokio::time::interval(POLL_INTERVAL);
    loop {
        ticker.tick().await;
        if let Err(error) = poll_once(&state).await {
            tracing::warn!("spotify presence cycle failed: {}", error.message());
        }
    }
}

#[cfg(test)]
mod tests {
    use super::activity_from_playback;
    use serde_json::json;

    #[test]
    fn maps_a_playing_track_to_public_activity() {
        let activity = activity_from_playback(&json!({
            "is_playing": true,
            "timestamp": 1_700_000_010_000_i64,
            "progress_ms": 10_000,
            "item": {
                "type": "track",
                "name": "Orange Sky",
                "duration_ms": 180_000,
                "artists": [{"name": "The Citrus"}],
                "album": {"images": [{"url": "https://i.scdn.co/cover"}]},
                "external_urls": {"spotify": "https://open.spotify.com/track/1"}
            }
        }))
        .unwrap();
        assert_eq!(activity.kind, "spotify");
        assert_eq!(activity.name, "Orange Sky");
        assert_eq!(activity.details.as_deref(), Some("The Citrus"));
        assert!(activity.started_at.is_some());
        assert!(activity.ends_at.is_some());
    }

    #[test]
    fn paused_playback_is_not_public() {
        assert!(activity_from_playback(&json!({"is_playing": false})).is_none());
    }
}
