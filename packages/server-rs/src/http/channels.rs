//! Channel-scoped REST, mounted under /api. Mirrors routes/channels.ts. Requires auth.

use axum::extract::{Path, Query, State};
use axum::http::StatusCode;
use axum::response::IntoResponse;
use axum::routing::{get, post};
use axum::{Json, Router};
use serde_json::{json, Value};
use std::collections::HashMap;

use crate::dto::{to_channel, to_channel_overwrite};
use crate::error::{AppError, AppResult};
use crate::http::{bad_request, AuthUser};
use crate::permissions::{MANAGE_CHANNELS, MANAGE_MESSAGES, MANAGE_ROLES};
use crate::services::{audit, channel, membership, message, rate_limit, read_state, voice};
use crate::state::AppState;

pub fn routes() -> Router<AppState> {
    Router::new()
        .route(
            "/channels/:channelId/messages",
            get(history).post(post_message),
        )
        .route("/channels/:channelId/read", post(mark_read))
        .route("/channels/:channelId/unread", post(mark_unread))
        .route("/me/unreads", get(unreads))
        .route(
            "/channels/:channelId",
            get(get_channel).patch(patch_channel).delete(delete_channel),
        )
        .route("/channels/:channelId/permissions", get(list_permissions))
        .route(
            "/channels/:channelId/permissions/:targetId",
            axum::routing::put(put_permission).delete(delete_permission),
        )
        .route("/channels/:channelId/pins", get(list_pins))
        .route(
            "/channels/:channelId/pins/:messageId",
            axum::routing::put(pin_message).delete(unpin_message),
        )
        .route("/channels/:channelId/voice", get(voice_participants))
}

async fn mark_read(
    State(state): State<AppState>,
    user: AuthUser,
    Path(channel_id): Path<String>,
) -> AppResult<Json<Value>> {
    channel::require_channel_access(&state, &channel_id, &user.user_id).await?;
    read_state::mark_read(&state, &user.user_id, &channel_id).await?;
    let _ = state
        .io()
        .to(format!("user:{}", user.user_id))
        .emit("read:state", &json!({ "channelId": channel_id }));
    // Take back any notification this channel left on the user's other devices.
    crate::services::push::notify_read(&state, &user.user_id, &channel_id).await;
    Ok(Json(json!({ "ok": true })))
}

async fn mark_unread(
    State(state): State<AppState>,
    user: AuthUser,
    Path(channel_id): Path<String>,
    Json(body): Json<MarkUnreadBody>,
) -> AppResult<Json<Value>> {
    channel::require_channel_access(&state, &channel_id, &user.user_id).await?;
    let owned: Option<String> =
        sqlx::query_scalar(r#"SELECT id FROM "Message" WHERE id = $1 AND "channelId" = $2"#)
            .bind(&body.message_id)
            .bind(&channel_id)
            .fetch_optional(&state.pool)
            .await?;
    if owned.is_none() {
        return Err(bad_request("Unknown message"));
    }

    read_state::mark_unread(&state, &user.user_id, &channel_id, &body.message_id).await?;
    let unread = read_state::get_channel_unread(&state, &user.user_id, &channel_id).await?;
    let wire = json!({
        "channelId": unread.channel_id,
        "serverId": unread.server_id,
        "unread": unread.unread,
        "unreadCount": unread.unread_count,
        "mentionCount": unread.mention_count,
    });
    let _ = state
        .io()
        .to(format!("user:{}", user.user_id))
        .emit("unread:state", &wire);
    Ok(Json(wire))
}

#[derive(serde::Deserialize)]
#[serde(rename_all = "camelCase")]
struct MarkUnreadBody {
    message_id: String,
}

#[derive(serde::Deserialize)]
#[serde(rename_all = "camelCase")]
struct SendMessageBody {
    content: String,
    #[serde(default)]
    reply_to_id: Option<String>,
    #[serde(default)]
    attachment_ids: Vec<String>,
    #[serde(default)]
    spoiler_attachment_ids: Vec<String>,
    /// docs/E2EE.md §2. A quick reply into an encrypted conversation seals on
    /// the device exactly as the app does; the server refuses plaintext into a
    /// latched channel either way, so this is what makes the path work rather
    /// than what makes it safe.
    #[serde(default)]
    ciphertext: Option<String>,
    #[serde(default)]
    enc_epoch: Option<i32>,
    #[serde(default)]
    enc_version: Option<i32>,
}

/// REST twin of the socket `message:send`. Exists for callers with no live
/// socket - chiefly the Android notification quick-reply, which posts here from
/// a background broadcast rather than spinning up a connection. Runs the same
/// guards and the same `deliver_message` fan-out, so a reply sent this way is
/// indistinguishable from one typed in the app.
async fn post_message(
    State(state): State<AppState>,
    user: AuthUser,
    Path(channel_id): Path<String>,
    Json(body): Json<SendMessageBody>,
) -> AppResult<Json<Value>> {
    rate_limit::check(
        &state,
        "msg:send",
        &user.user_id,
        rate_limit::MESSAGE_SEND_PER_USER,
    )
    .await?;
    let ch = channel::require_channel_access(&state, &channel_id, &user.user_id).await?;
    channel::enforce_slowmode(&state, &ch, &user.user_id).await?;
    membership::assert_not_timed_out(&state, &ch, &user.user_id).await?;
    let sealed = message::parse_sealed(
        &ch,
        body.ciphertext.as_deref(),
        body.enc_epoch,
        body.enc_version,
    )?;
    let msg = message::send_message(
        &state,
        &channel_id,
        &user.user_id,
        &body.content,
        body.reply_to_id.as_deref(),
        &body.attachment_ids,
        &body.spoiler_attachment_ids,
        sealed.as_ref(),
    )
    .await?;
    // No originating socket: this sender was answered over HTTP, so everyone in
    // the channel needs the broadcast.
    crate::socket::deliver_message(
        state.io(),
        &state,
        &ch,
        &msg,
        &user.user_id,
        &body.content,
        None,
    )
    .await;
    Ok(Json(json!(msg)))
}

async fn unreads(State(state): State<AppState>, user: AuthUser) -> AppResult<Json<Value>> {
    let items = read_state::get_unreads(&state, &user.user_id).await?;
    let wire: Vec<Value> = items
        .into_iter()
        .map(|u| {
            json!({
                "channelId": u.channel_id,
                "serverId": u.server_id,
                "unread": u.unread,
                "unreadCount": u.unread_count,
                "mentionCount": u.mention_count,
            })
        })
        .collect();
    Ok(Json(json!(wire)))
}

async fn history(
    State(state): State<AppState>,
    user: AuthUser,
    Path(channel_id): Path<String>,
    Query(q): Query<HashMap<String, String>>,
) -> AppResult<Json<Value>> {
    channel::require_channel_access(&state, &channel_id, &user.user_id).await?;
    let before = q.get("before").map(String::as_str);
    let limit: i64 = q.get("limit").and_then(|l| l.parse().ok()).unwrap_or(50);
    if !(1..=100).contains(&limit) {
        return Err(bad_request("Invalid query"));
    }
    let page = message::get_history(&state, &channel_id, &user.user_id, before, limit).await?;
    Ok(Json(json!(page)))
}

async fn get_channel(
    State(state): State<AppState>,
    user: AuthUser,
    Path(channel_id): Path<String>,
) -> AppResult<Json<Value>> {
    let ch = channel::require_channel_access(&state, &channel_id, &user.user_id).await?;
    Ok(Json(json!(to_channel(&ch))))
}

async fn patch_channel(
    State(state): State<AppState>,
    user: AuthUser,
    Path(channel_id): Path<String>,
    Json(body): Json<Value>,
) -> AppResult<Json<Value>> {
    let ch = channel::get_channel(&state, &channel_id)
        .await?
        .ok_or_else(|| AppError::NotFound("Channel not found".into()))?;
    let Some(server_id) = ch.server_id.clone() else {
        return Err(bad_request("Cannot edit a DM channel"));
    };

    let obj = body
        .as_object()
        .ok_or_else(|| bad_request("Invalid input"))?;
    if obj.is_empty() {
        return Err(bad_request("Invalid input"));
    }
    let mut patch = channel::ChannelPatch::default();
    if let Some(v) = obj.get("name") {
        let n = v.as_str().ok_or_else(|| bad_request("Invalid input"))?;
        if n.is_empty() || n.len() > 100 {
            return Err(bad_request("Invalid input"));
        }
        patch.name = Some(n.to_string());
    }
    if let Some(v) = obj.get("topic") {
        patch.topic = Some(match v {
            Value::Null => None,
            Value::String(s) => Some(s.clone()),
            _ => return Err(bad_request("Invalid input")),
        });
    }
    if let Some(v) = obj.get("position") {
        patch.position = Some(v.as_i64().ok_or_else(|| bad_request("Invalid input"))? as i32);
    }
    if let Some(v) = obj.get("parentCategoryId") {
        patch.parent_category_id = Some(match v {
            Value::Null => None,
            Value::String(s) => Some(s.clone()),
            _ => return Err(bad_request("Invalid input")),
        });
    }
    if let Some(v) = obj.get("nsfw") {
        patch.nsfw = Some(v.as_bool().ok_or_else(|| bad_request("Invalid input"))?);
    }
    if let Some(v) = obj.get("rateLimitPerUser") {
        let r = v.as_i64().ok_or_else(|| bad_request("Invalid input"))?;
        // 0 = off, 6h ceiling, as Discord.
        if !(0..=21600).contains(&r) {
            return Err(bad_request("rateLimitPerUser must be 0-21600 seconds"));
        }
        if ch.channel_type != "text" {
            return Err(bad_request("Slowmode applies only to text channels"));
        }
        patch.rate_limit_per_user = Some(r as i32);
    }
    if let Some(v) = obj.get("userLimit") {
        let l = v.as_i64().ok_or_else(|| bad_request("Invalid input"))?;
        if !(0..=99).contains(&l) {
            return Err(bad_request("userLimit must be 0-99"));
        }
        if ch.channel_type != "voice" {
            return Err(bad_request("userLimit applies only to voice channels"));
        }
        patch.user_limit = Some(l as i32);
    }
    if let Some(v) = obj.get("bitrate") {
        let b = v.as_i64().ok_or_else(|| bad_request("Invalid input"))?;
        if !(8000..=384_000).contains(&b) {
            return Err(bad_request("bitrate must be 8000-384000"));
        }
        if ch.channel_type != "voice" {
            return Err(bad_request("bitrate applies only to voice channels"));
        }
        patch.bitrate = Some(b as i32);
    }

    membership::require_permission(&state, &server_id, &user.user_id, MANAGE_CHANNELS).await?;
    let updated = channel::update_channel(&state, &channel_id, patch).await?;
    let changes = audit::diff(vec![
        ("name", json!(ch.name), json!(updated.name)),
        ("topic", json!(ch.topic), json!(updated.topic)),
        ("position", json!(ch.position), json!(updated.position)),
        (
            "parentCategoryId",
            json!(ch.parent_category_id),
            json!(updated.parent_category_id),
        ),
        ("nsfw", json!(ch.nsfw), json!(updated.nsfw)),
        (
            "rateLimitPerUser",
            json!(ch.rate_limit_per_user),
            json!(updated.rate_limit_per_user),
        ),
        ("userLimit", json!(ch.user_limit), json!(updated.user_limit)),
        ("bitrate", json!(ch.bitrate), json!(updated.bitrate)),
    ]);
    audit::record(
        &state,
        audit::Entry {
            server_id: &server_id,
            actor_id: &user.user_id,
            action: audit::action::CHANNEL_UPDATE,
            target_id: Some(&channel_id),
            target_type: Some("channel"),
            changes,
            reason: None,
        },
    )
    .await;
    let dto = to_channel(&updated);
    let _ = state
        .io()
        .to(format!("server:{server_id}"))
        .emit("channel:updated", &dto);
    Ok(Json(json!(dto)))
}

async fn delete_channel(
    State(state): State<AppState>,
    user: AuthUser,
    Path(channel_id): Path<String>,
) -> AppResult<impl IntoResponse> {
    let ch = channel::get_channel(&state, &channel_id)
        .await?
        .ok_or_else(|| AppError::NotFound("Channel not found".into()))?;
    let Some(server_id) = ch.server_id.clone() else {
        return Err(bad_request("Cannot delete a DM channel"));
    };
    membership::require_permission(&state, &server_id, &user.user_id, MANAGE_CHANNELS).await?;
    channel::delete_channel(&state, &channel_id).await?;
    audit::record(
        &state,
        audit::Entry {
            server_id: &server_id,
            actor_id: &user.user_id,
            action: audit::action::CHANNEL_DELETE,
            target_id: Some(&channel_id),
            target_type: Some("channel"),
            changes: json!({ "name": { "old": ch.name } }),
            reason: None,
        },
    )
    .await;
    let _ = state.io().to(format!("server:{server_id}")).emit(
        "channel:deleted",
        &json!({ "serverId": server_id, "channelId": channel_id }),
    );
    Ok(StatusCode::NO_CONTENT)
}

async fn list_permissions(
    State(state): State<AppState>,
    user: AuthUser,
    Path(channel_id): Path<String>,
) -> AppResult<Json<Value>> {
    channel::require_channel_access(&state, &channel_id, &user.user_id).await?;
    let overwrites = channel::list_overwrites(&state, &channel_id).await?;
    Ok(Json(json!(overwrites
        .iter()
        .map(to_channel_overwrite)
        .collect::<Vec<_>>())))
}

async fn put_permission(
    State(state): State<AppState>,
    user: AuthUser,
    Path((channel_id, target_id)): Path<(String, String)>,
    Json(body): Json<Value>,
) -> AppResult<Json<Value>> {
    let ch = channel::get_channel(&state, &channel_id)
        .await?
        .ok_or_else(|| AppError::NotFound("Channel not found".into()))?;
    let Some(server_id) = ch.server_id.clone() else {
        return Err(bad_request("DM channels have no overwrites"));
    };

    let ow_type = body.get("type").and_then(Value::as_str).unwrap_or_default();
    if !matches!(ow_type, "role" | "member") {
        return Err(bad_request("Invalid input"));
    }
    let allow = body.get("allow").and_then(Value::as_str).unwrap_or("0");
    let deny = body.get("deny").and_then(Value::as_str).unwrap_or("0");
    if !allow.chars().all(|c| c.is_ascii_digit()) || !deny.chars().all(|c| c.is_ascii_digit()) {
        return Err(bad_request("Invalid input"));
    }

    membership::require_permission(&state, &server_id, &user.user_id, MANAGE_ROLES).await?;
    let ow = channel::upsert_overwrite(
        &state,
        &channel_id,
        ow_type,
        &target_id,
        crate::permissions::parse(allow),
        crate::permissions::parse(deny),
    )
    .await?;

    let overwrites = channel::list_overwrites(&state, &channel_id).await?;
    let _ = state.io().to(format!("server:{server_id}")).emit(
        "channel:overwrites",
        &json!({
            "channelId": channel_id,
            "overwrites": overwrites.iter().map(to_channel_overwrite).collect::<Vec<_>>(),
        }),
    );
    Ok(Json(json!(to_channel_overwrite(&ow))))
}

async fn delete_permission(
    State(state): State<AppState>,
    user: AuthUser,
    Path((channel_id, target_id)): Path<(String, String)>,
    Query(q): Query<HashMap<String, String>>,
) -> AppResult<impl IntoResponse> {
    let ch = channel::get_channel(&state, &channel_id)
        .await?
        .ok_or_else(|| AppError::NotFound("Channel not found".into()))?;
    let Some(server_id) = ch.server_id.clone() else {
        return Err(bad_request("DM channels have no overwrites"));
    };
    let ow_type = q.get("type").map(String::as_str).unwrap_or_default();
    if !matches!(ow_type, "role" | "member") {
        return Err(bad_request("Missing or invalid ?type"));
    }
    membership::require_permission(&state, &server_id, &user.user_id, MANAGE_ROLES).await?;
    channel::delete_overwrite(&state, &channel_id, ow_type, &target_id).await?;

    let overwrites = channel::list_overwrites(&state, &channel_id).await?;
    let _ = state.io().to(format!("server:{server_id}")).emit(
        "channel:overwrites",
        &json!({
            "channelId": channel_id,
            "overwrites": overwrites.iter().map(to_channel_overwrite).collect::<Vec<_>>(),
        }),
    );
    Ok(StatusCode::NO_CONTENT)
}

/// Anyone who can see a channel can read its pins; changing them needs
/// MANAGE_MESSAGES. In a DM there is no such role, so any participant may pin -
/// require_channel_access has already established they are one.
async fn require_pin_permission(
    state: &AppState,
    channel_id: &str,
    user_id: &str,
) -> AppResult<()> {
    let ch = channel::require_channel_access(state, channel_id, user_id).await?;
    if let Some(server_id) = ch.server_id {
        membership::require_permission(state, &server_id, user_id, MANAGE_MESSAGES).await?;
    }
    Ok(())
}

async fn list_pins(
    State(state): State<AppState>,
    user: AuthUser,
    Path(channel_id): Path<String>,
) -> AppResult<Json<Value>> {
    channel::require_channel_access(&state, &channel_id, &user.user_id).await?;
    let pins = message::list_pins(&state, &channel_id, &user.user_id).await?;
    Ok(Json(json!(pins)))
}

async fn pin_message(
    State(state): State<AppState>,
    user: AuthUser,
    Path((channel_id, message_id)): Path<(String, String)>,
) -> AppResult<Json<Value>> {
    require_pin_permission(&state, &channel_id, &user.user_id).await?;
    let msg = message::set_pinned(&state, &channel_id, &message_id, true).await?;
    let _ = state.io().to(format!("channel:{channel_id}")).emit(
        "message:pins",
        &json!({ "channelId": channel_id, "messageId": msg.id, "pinned": true }),
    );
    Ok(Json(json!({ "ok": true })))
}

async fn unpin_message(
    State(state): State<AppState>,
    user: AuthUser,
    Path((channel_id, message_id)): Path<(String, String)>,
) -> AppResult<impl IntoResponse> {
    require_pin_permission(&state, &channel_id, &user.user_id).await?;
    message::set_pinned(&state, &channel_id, &message_id, false).await?;
    let _ = state.io().to(format!("channel:{channel_id}")).emit(
        "message:pins",
        &json!({ "channelId": channel_id, "messageId": message_id, "pinned": false }),
    );
    Ok(StatusCode::NO_CONTENT)
}

async fn voice_participants(
    State(state): State<AppState>,
    user: AuthUser,
    Path(channel_id): Path<String>,
) -> AppResult<Json<Value>> {
    channel::require_channel_access(&state, &channel_id, &user.user_id).await?;
    let list = voice::list_voice_participants(&state, &channel_id).await?;
    Ok(Json(json!(list)))
}
