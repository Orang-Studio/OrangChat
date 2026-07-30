//! Server / channel / invite REST, mounted under /api. Mirrors routes/servers.ts.

use axum::extract::{Path, Query, State};
use axum::http::StatusCode;
use axum::response::IntoResponse;
use axum::routing::{get, post};
use axum::{Json, Router};
use serde_json::{json, Value};
use std::collections::HashMap;

use crate::dto::*;
use crate::error::{AppError, AppResult};
use crate::http::{bad_request, AuthUser, ClientIp, OptionalAuthUser};
use crate::permissions::{self, MANAGE_CHANNELS, MANAGE_INVITES, MANAGE_SERVER, VIEW_AUDIT_LOG};
use crate::services::{
    account, audit, bot, channel, membership, message, presence, rate_limit, server,
};
use crate::state::AppState;

pub fn routes() -> Router<AppState> {
    Router::new()
        .route("/servers", post(create_server).get(list_servers))
        // Before /servers/:serverId so the literal segment wins the match.
        .route("/servers/leave-all", post(leave_all_servers))
        .route(
            "/servers/:serverId",
            get(get_server).patch(update_server).delete(delete_server),
        )
        .route(
            "/servers/:serverId/channels",
            post(create_channel).patch(reorder_channels),
        )
        .route("/servers/:serverId/invites", post(create_invite))
        .route("/servers/:serverId/leave", post(leave_server))
        .route("/servers/:serverId/search", get(search))
        .route("/servers/:serverId/audit-log", get(audit_log))
        .route("/servers/:serverId/bots", post(add_bot))
        .route("/invites/:code", get(invite_preview).post(join_invite))
        .route("/servers/:serverId/me/permissions", get(my_permissions))
}

async fn search(
    State(state): State<AppState>,
    user: AuthUser,
    Path(server_id): Path<String>,
    Query(q): Query<HashMap<String, String>>,
) -> AppResult<Json<Value>> {
    rate_limit::check(&state, "search", &user.user_id, rate_limit::SEARCH_PER_USER).await?;

    if !membership::is_server_member(&state, &server_id, &user.user_id).await? {
        return Err(AppError::Permission("Not a member of this server".into()));
    }
    let query = q.get("q").map(|s| s.trim()).unwrap_or_default();
    if query.is_empty() || query.len() > 200 {
        return Err(bad_request("Search query must be 1-200 characters"));
    }
    let channel_id = q
        .get("channelId")
        .map(String::as_str)
        .filter(|s| !s.is_empty());
    let author_id = q
        .get("authorId")
        .map(String::as_str)
        .filter(|s| !s.is_empty());
    let limit: i64 = q.get("limit").and_then(|l| l.parse().ok()).unwrap_or(25);
    if !(1..=50).contains(&limit) {
        return Err(bad_request("Invalid limit"));
    }
    let offset: i64 = q.get("offset").and_then(|o| o.parse().ok()).unwrap_or(0);
    if offset < 0 {
        return Err(bad_request("Invalid offset"));
    }
    let page = message::search_messages(
        &state,
        &server_id,
        &user.user_id,
        query,
        channel_id,
        author_id,
        limit,
        offset,
    )
    .await?;
    Ok(Json(json!(page)))
}

async fn audit_log(
    State(state): State<AppState>,
    user: AuthUser,
    Path(server_id): Path<String>,
    Query(q): Query<HashMap<String, String>>,
) -> AppResult<Json<Value>> {
    membership::require_permission(&state, &server_id, &user.user_id, VIEW_AUDIT_LOG).await?;

    let limit: i64 = q.get("limit").and_then(|l| l.parse().ok()).unwrap_or(50);
    if !(1..=100).contains(&limit) {
        return Err(bad_request("Invalid limit"));
    }
    let offset: i64 = q.get("offset").and_then(|o| o.parse().ok()).unwrap_or(0);
    if offset < 0 {
        return Err(bad_request("Invalid offset"));
    }
    let action = q
        .get("action")
        .map(String::as_str)
        .filter(|s| !s.is_empty());

    let entries = audit::list(&state, &server_id, action, limit + 1, offset).await?;
    let has_more = entries.len() as i64 > limit;
    let page = &entries[..entries.len().min(limit as usize)];

    let items: Vec<Value> = page
        .iter()
        .map(|(e, actor)| {
            json!({
                "id": e.id,
                "action": e.action,
                "targetId": e.target_id,
                "targetType": e.target_type,
                "changes": e.changes,
                "reason": e.reason,
                "createdAt": crate::timefmt::iso(e.created_at),
                "actor": actor.as_ref().map(to_user),
            })
        })
        .collect();

    Ok(Json(json!({
        "items": items,
        "nextCursor": if has_more { Some((offset + limit).to_string()) } else { None },
    })))
}

#[derive(serde::Deserialize)]
#[serde(rename_all = "camelCase")]
struct AddBotBody {
    bot_id: String,
    /// Decimal string, matching how permissions travel everywhere else (JSON
    /// has no BigInt). Omitted means the server's @everyone defaults.
    permissions: Option<String>,
}

/// Invite a bot into this server. The permission ceiling is enforced in the
/// service - a caller cannot grant a bot anything they do not hold themselves.
async fn add_bot(
    State(state): State<AppState>,
    user: AuthUser,
    Path(server_id): Path<String>,
    Json(body): Json<AddBotBody>,
) -> AppResult<Json<Value>> {
    let requested = body
        .permissions
        .as_deref()
        .map(crate::permissions::parse)
        .unwrap_or(crate::permissions::DEFAULT_EVERYONE_PERMISSIONS);

    bot::invite_to_server(&state, &server_id, &user.user_id, &body.bot_id, requested).await?;
    Ok(Json(json!({ "ok": true })))
}

async fn create_server(
    State(state): State<AppState>,
    user: AuthUser,
    Json(body): Json<Value>,
) -> AppResult<impl IntoResponse> {
    rate_limit::check(
        &state,
        "server:create",
        &user.user_id,
        rate_limit::SERVER_CREATE_PER_USER,
    )
    .await?;

    let name = body.get("name").and_then(Value::as_str).unwrap_or_default();
    if name.is_empty() || name.len() > 100 {
        return Err(bad_request("Invalid input"));
    }
    let icon_url = body.get("iconUrl").and_then(Value::as_str);
    let srv = server::create_server(&state, &user.user_id, name, icon_url).await?;
    // Live sockets only join server rooms at connect; add the creator's now.
    let _ = state
        .io()
        .to(format!("user:{}", user.user_id))
        .join(format!("server:{}", srv.id));
    Ok((StatusCode::CREATED, Json(json!(to_server(&srv)))))
}

async fn list_servers(State(state): State<AppState>, user: AuthUser) -> AppResult<Json<Value>> {
    let servers = server::get_user_servers(&state, &user.user_id).await?;
    Ok(Json(json!(servers
        .iter()
        .map(to_server)
        .collect::<Vec<_>>())))
}

async fn get_server(
    State(state): State<AppState>,
    user: AuthUser,
    Path(server_id): Path<String>,
) -> AppResult<Json<Value>> {
    if !membership::is_server_member(&state, &server_id, &user.user_id).await? {
        return Err(AppError::Permission("Not a member of this server".into()));
    }
    let detail = server::get_server_detail(&state, &server_id)
        .await?
        .ok_or_else(|| AppError::NotFound("Server not found".into()))?;

    let user_ids: Vec<String> = detail
        .members
        .iter()
        .map(|m| m.member.user_id.clone())
        .collect();
    let statuses = presence::get_statuses(&state, &user_ids).await?;
    let devices = presence::get_device_sets(&state, &user_ids).await?;
    let activities = presence::get_activity_sets(&state, &user_ids).await?;
    let members: Vec<ServerMemberDto> = detail
        .members
        .iter()
        .map(|m| {
            let mut dto = to_server_member(&m.member, &m.user, m.role_ids.clone());
            dto.user.status = statuses
                .get(&m.member.user_id)
                .cloned()
                .unwrap_or_else(|| "offline".into());
            dto.user.devices = devices.get(&m.member.user_id).cloned().unwrap_or_default();
            dto.user.activities = activities
                .get(&m.member.user_id)
                .cloned()
                .unwrap_or_default();
            dto
        })
        .collect();

    Ok(Json(json!({
        "server": to_server(&detail.server),
        "channels": detail.channels.iter().map(to_channel).collect::<Vec<_>>(),
        "roles": detail.roles.iter().map(to_role).collect::<Vec<_>>(),
        "members": members,
        "overwrites": detail.overwrites.iter().map(to_channel_overwrite).collect::<Vec<_>>(),
    })))
}

async fn update_server(
    State(state): State<AppState>,
    user: AuthUser,
    Path(server_id): Path<String>,
    Json(body): Json<Value>,
) -> AppResult<Json<Value>> {
    let obj = body
        .as_object()
        .ok_or_else(|| bad_request("Invalid input"))?;
    if obj.is_empty() {
        return Err(bad_request("No fields to update"));
    }
    let mut patch = server::ServerPatch::default();
    if let Some(v) = obj.get("name") {
        let n = v.as_str().ok_or_else(|| bad_request("Invalid input"))?;
        if n.is_empty() || n.len() > 100 {
            return Err(bad_request("Invalid input"));
        }
        patch.name = Some(n.to_string());
    }
    // Empty string clears, same as JSON null. The Android client serialises with
    // `explicitNulls = false`, so a null field never reaches the wire at all -
    // "" is the only clear signal it can send.
    if let Some(v) = obj.get("iconUrl") {
        patch.icon_url = Some(match v {
            Value::Null => None,
            Value::String(s) if s.is_empty() => None,
            Value::String(s) => Some(s.clone()),
            _ => return Err(bad_request("Invalid input")),
        });
    }
    if let Some(v) = obj.get("description") {
        patch.description = Some(match v {
            Value::Null => None,
            Value::String(s) if s.is_empty() => None,
            Value::String(s) if s.len() <= 1024 => Some(s.clone()),
            _ => return Err(bad_request("Description must be 1024 characters or fewer")),
        });
    }
    if let Some(v) = obj.get("bannerUrl") {
        patch.banner_url = Some(match v {
            Value::Null => None,
            Value::String(s) => Some(s.clone()),
            _ => return Err(bad_request("Invalid input")),
        });
    }
    if let Some(v) = obj.get("systemChannelId") {
        patch.system_channel_id = Some(match v {
            Value::Null => None,
            Value::String(s) => Some(s.clone()),
            _ => return Err(bad_request("Invalid input")),
        });
    }
    if let Some(v) = obj.get("afkChannelId") {
        patch.afk_channel_id = Some(match v {
            Value::Null => None,
            Value::String(s) => Some(s.clone()),
            _ => return Err(bad_request("Invalid input")),
        });
    }
    if let Some(v) = obj.get("afkTimeout") {
        let t = v.as_i64().ok_or_else(|| bad_request("Invalid input"))?;
        // Discord's fixed ladder. An arbitrary number here would be a setting the
        // voice layer silently rounds off anyway.
        if !matches!(t, 60 | 300 | 900 | 1800 | 3600) {
            return Err(bad_request(
                "afkTimeout must be one of 60, 300, 900, 1800, 3600",
            ));
        }
        patch.afk_timeout = Some(t as i32);
    }
    if let Some(v) = obj.get("defaultMessageNotifications") {
        let n = v.as_str().ok_or_else(|| bad_request("Invalid input"))?;
        if !matches!(n, "all" | "mentions") {
            return Err(bad_request(
                "defaultMessageNotifications must be \"all\" or \"mentions\"",
            ));
        }
        patch.default_message_notifications = Some(n.to_string());
    }

    membership::require_permission(&state, &server_id, &user.user_id, MANAGE_SERVER).await?;
    let before = server::get_server(&state, &server_id)
        .await?
        .ok_or_else(|| AppError::NotFound("Server not found".into()))?;
    let srv = server::update_server(&state, &server_id, patch).await?;
    let changes = audit::diff(vec![
        ("name", json!(before.name), json!(srv.name)),
        ("iconUrl", json!(before.icon_url), json!(srv.icon_url)),
        (
            "description",
            json!(before.description),
            json!(srv.description),
        ),
        ("bannerUrl", json!(before.banner_url), json!(srv.banner_url)),
        (
            "systemChannelId",
            json!(before.system_channel_id),
            json!(srv.system_channel_id),
        ),
        (
            "afkChannelId",
            json!(before.afk_channel_id),
            json!(srv.afk_channel_id),
        ),
        (
            "afkTimeout",
            json!(before.afk_timeout),
            json!(srv.afk_timeout),
        ),
        (
            "defaultMessageNotifications",
            json!(before.default_message_notifications),
            json!(srv.default_message_notifications),
        ),
    ]);
    audit::record(
        &state,
        audit::Entry {
            server_id: &server_id,
            actor_id: &user.user_id,
            action: audit::action::SERVER_UPDATE,
            target_id: Some(&server_id),
            target_type: Some("server"),
            changes,
            reason: None,
        },
    )
    .await;
    let dto = to_server(&srv);
    let _ = state
        .io()
        .to(format!("server:{server_id}"))
        .emit("server:updated", &dto);
    Ok(Json(json!(dto)))
}

async fn delete_server(
    State(state): State<AppState>,
    user: AuthUser,
    Path(server_id): Path<String>,
) -> AppResult<impl IntoResponse> {
    server::delete_server(&state, &server_id, &user.user_id).await?;
    let _ = state
        .io()
        .to(format!("server:{server_id}"))
        .emit("server:deleted", &json!({ "serverId": server_id }));
    let _ = state
        .io()
        .to(format!("server:{server_id}"))
        .leave(format!("server:{server_id}"));
    Ok(StatusCode::NO_CONTENT)
}

async fn leave_server(
    State(state): State<AppState>,
    user: AuthUser,
    Path(server_id): Path<String>,
) -> AppResult<impl IntoResponse> {
    server::leave_server(&state, &server_id, &user.user_id).await?;
    let user_id = &user.user_id;
    // Tell the server we are gone, then drop our sockets out of its room so we
    // stop receiving its traffic.
    let _ = state.io().to(format!("server:{server_id}")).emit(
        "member:left",
        &json!({ "serverId": server_id, "userId": user_id }),
    );
    let _ = state
        .io()
        .to(format!("user:{user_id}"))
        .leave(format!("server:{server_id}"));
    Ok(StatusCode::NO_CONTENT)
}

/// Leaves every server the user doesn't own. Servers they own are left alone -
/// an owner can't leave without stranding it, so those are reported back rather
/// than silently skipped.
async fn leave_all_servers(
    State(state): State<AppState>,
    user: AuthUser,
) -> AppResult<Json<Value>> {
    let left = server::leave_all_non_owned(&state, &user.user_id).await?;
    let user_id = &user.user_id;

    for server_id in &left {
        let _ = state.io().to(format!("server:{server_id}")).emit(
            "member:left",
            &json!({ "serverId": server_id, "userId": user_id }),
        );
        let _ = state
            .io()
            .to(format!("user:{user_id}"))
            .leave(format!("server:{server_id}"));
    }

    let kept = account::owned_server_names(&state, user_id).await?;
    Ok(Json(json!({ "left": left.len(), "keptOwned": kept })))
}

async fn create_channel(
    State(state): State<AppState>,
    user: AuthUser,
    Path(server_id): Path<String>,
    Json(body): Json<Value>,
) -> AppResult<impl IntoResponse> {
    let name = body.get("name").and_then(Value::as_str).unwrap_or_default();
    if name.is_empty() || name.len() > 100 {
        return Err(bad_request("Invalid input"));
    }
    let ctype = body.get("type").and_then(Value::as_str).unwrap_or("text");
    if !matches!(ctype, "text" | "voice" | "category") {
        return Err(bad_request("Invalid input"));
    }
    let topic = body.get("topic").and_then(Value::as_str).map(String::from);
    let parent = body
        .get("parentCategoryId")
        .and_then(Value::as_str)
        .map(String::from);

    membership::require_permission(&state, &server_id, &user.user_id, MANAGE_CHANNELS).await?;
    let ch = channel::create_channel(
        &state,
        &server_id,
        channel::NewChannel {
            name: name.to_string(),
            channel_type: ctype.to_string(),
            topic,
            parent_category_id: parent,
        },
    )
    .await?;
    audit::record(
        &state,
        audit::Entry {
            server_id: &server_id,
            actor_id: &user.user_id,
            action: audit::action::CHANNEL_CREATE,
            target_id: Some(&ch.id),
            target_type: Some("channel"),
            changes: json!({ "name": { "new": ch.name }, "type": { "new": ch.channel_type } }),
            reason: None,
        },
    )
    .await;
    let dto = to_channel(&ch);
    let _ = state
        .io()
        .to(format!("server:{server_id}"))
        .emit("channel:created", &dto);
    Ok((StatusCode::CREATED, Json(json!(dto))))
}

/// Bulk reorder: `[{ id, position, parentCategoryId? }, …]`. Omitting
/// `parentCategoryId` leaves the channel where it is; sending null un-parents it.
async fn reorder_channels(
    State(state): State<AppState>,
    user: AuthUser,
    Path(server_id): Path<String>,
    Json(body): Json<Value>,
) -> AppResult<Json<Value>> {
    let items = body
        .as_array()
        .ok_or_else(|| bad_request("Expected an array of { id, position }"))?;
    if items.is_empty() || items.len() > 500 {
        return Err(bad_request("Invalid input"));
    }

    let mut parsed: Vec<(String, i32, Option<Option<String>>)> = Vec::with_capacity(items.len());
    for item in items {
        let id = item
            .get("id")
            .and_then(Value::as_str)
            .ok_or_else(|| bad_request("Invalid input"))?;
        let pos = item
            .get("position")
            .and_then(Value::as_i64)
            .ok_or_else(|| bad_request("Invalid input"))?;
        if !(0..=100_000).contains(&pos) {
            return Err(bad_request("Invalid position"));
        }
        let parent = match item.get("parentCategoryId") {
            None => None,
            Some(Value::Null) => Some(None),
            Some(Value::String(s)) => Some(Some(s.clone())),
            Some(_) => return Err(bad_request("Invalid parentCategoryId")),
        };
        parsed.push((id.to_string(), pos as i32, parent));
    }
    let mut seen: Vec<&str> = parsed.iter().map(|(id, _, _)| id.as_str()).collect();
    seen.sort_unstable();
    let unique = seen.len();
    seen.dedup();
    if seen.len() != unique {
        return Err(bad_request("Duplicate channel id"));
    }

    membership::require_permission(&state, &server_id, &user.user_id, MANAGE_CHANNELS).await?;
    let channels = channel::reorder_channels(&state, &server_id, &parsed).await?;
    let dtos: Vec<_> = channels.iter().map(to_channel).collect();
    let _ = state.io().to(format!("server:{server_id}")).emit(
        "channels:reordered",
        &json!({ "serverId": server_id, "channels": dtos }),
    );
    Ok(Json(json!(dtos)))
}

async fn create_invite(
    State(state): State<AppState>,
    user: AuthUser,
    Path(server_id): Path<String>,
    Json(body): Json<Value>,
) -> AppResult<impl IntoResponse> {
    let expires_in_seconds = body.get("expiresInSeconds").and_then(Value::as_i64);
    let max_uses = body
        .get("maxUses")
        .and_then(Value::as_i64)
        .map(|v| v as i32);
    if expires_in_seconds.map(|v| v <= 0).unwrap_or(false)
        || max_uses.map(|v| v <= 0).unwrap_or(false)
    {
        return Err(bad_request("Invalid input"));
    }

    membership::require_permission(&state, &server_id, &user.user_id, MANAGE_INVITES).await?;
    let invite = server::create_invite(
        &state,
        &server_id,
        &user.user_id,
        server::NewInvite {
            expires_in_seconds,
            max_uses,
        },
    )
    .await?;
    Ok((StatusCode::CREATED, Json(json!(to_invite(&invite)))))
}

/// Resolve an invite link. Open to signed-out visitors: the invite code is
/// itself the secret, and a link that can only say what it leads to *after* you
/// have an account is a link nobody follows. Signing in adds the viewer-specific
/// part of the answer - banned, or already a member.
async fn invite_preview(
    State(state): State<AppState>,
    ClientIp(ip): ClientIp,
    OptionalAuthUser(viewer): OptionalAuthUser,
    Path(code): Path<String>,
) -> AppResult<Json<Value>> {
    rate_limit::check(
        &state,
        "invite-preview",
        &ip,
        rate_limit::INVITE_PREVIEW_PER_IP,
    )
    .await?;
    let preview = server::get_invite_preview(&state, &code, viewer.as_deref()).await?;
    Ok(Json(json!(to_invite_preview(&preview))))
}

async fn join_invite(
    State(state): State<AppState>,
    user: AuthUser,
    Path(code): Path<String>,
) -> AppResult<Json<Value>> {
    let srv = server::join_via_invite(&state, &code, &user.user_id).await?;
    let Some(srv) = srv else {
        return Err(AppError::NotFound("Server not found".into()));
    };

    let _ = state
        .io()
        .to(format!("user:{}", user.user_id))
        .join(format!("server:{}", srv.id));

    if let Some(detail) = server::get_server_detail(&state, &srv.id).await? {
        if let Some(me) = detail
            .members
            .iter()
            .find(|m| m.member.user_id == user.user_id)
        {
            let mut dto = to_server_member(&me.member, &me.user, me.role_ids.clone());
            dto.user.status = presence::get_status(&state, &user.user_id).await?;
            dto.user.devices = presence::get_devices(&state, &user.user_id).await?;
            dto.user.activities = presence::get_activities(&state, &user.user_id).await?;
            let _ = state.io().to(format!("server:{}", srv.id)).emit(
                "member:joined",
                &json!({ "serverId": srv.id, "member": dto }),
            );
        }
    }
    Ok(Json(json!(to_server(&srv))))
}

async fn my_permissions(
    State(state): State<AppState>,
    user: AuthUser,
    Path(server_id): Path<String>,
) -> AppResult<Json<Value>> {
    match membership::effective_permissions(&state, &server_id, &user.user_id).await? {
        None => Err(AppError::Permission("Not a member".into())),
        Some(perms) => Ok(Json(
            json!({ "permissions": permissions::serialize(perms) }),
        )),
    }
}
