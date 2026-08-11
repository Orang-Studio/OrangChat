
use axum::extract::{Path, State};
use axum::http::StatusCode;
use axum::response::IntoResponse;
use axum::routing::{delete, patch, post};
use axum::{Json, Router};
use serde_json::{json, Value};

use crate::dto::{to_role, to_server_member, to_user};
use crate::error::{AppError, AppResult};
use crate::http::{bad_request, AuthUser};
use crate::permissions::{BAN_MEMBERS, KICK_MEMBERS, MANAGE_NICKNAMES};
use crate::services::server::MemberDetail;
use crate::services::{audit, bot, membership, moderation, role};
use crate::state::AppState;

pub fn routes() -> Router<AppState> {
    Router::new()
        .route(
            "/servers/:serverId/roles",
            post(create_role).patch(reorder_roles),
        )
        .route(
            "/servers/:serverId/roles/:roleId",
            patch(update_role).delete(delete_role),
        )
        .route(
            "/servers/:serverId/members/:userId/roles/:roleId",
            axum::routing::put(assign_role).delete(unassign_role),
        )
        .route(
            "/servers/:serverId/members/:userId/nickname",
            patch(set_nickname),
        )
        .route(
            "/servers/:serverId/members/:userId/timeout",
            patch(set_timeout),
        )
        .route("/servers/:serverId/members/:userId", delete(kick_member))
        .route(
            "/servers/:serverId/bans/:userId",
            post(ban_member).delete(unban_member),
        )
        .route("/servers/:serverId/bans", axum::routing::get(list_bans))
}

fn emit_member_updated(state: &AppState, server_id: &str, member: &MemberDetail) {
    let dto = to_server_member(&member.member, &member.user, member.role_ids.clone());
    let _ = state.io().to(format!("server:{server_id}")).emit(
        "member:updated",
        &json!({ "serverId": server_id, "member": dto }),
    );
}

async fn create_role(
    State(state): State<AppState>,
    user: AuthUser,
    Path(server_id): Path<String>,
    Json(body): Json<Value>,
) -> AppResult<impl IntoResponse> {
    let name = body.get("name").and_then(Value::as_str).unwrap_or_default();
    if name.is_empty() || name.len() > 100 {
        return Err(bad_request("Invalid input"));
    }
    let color = body.get("color").and_then(Value::as_i64).map(|c| c as i32);
    let permissions = body
        .get("permissions")
        .and_then(Value::as_str)
        .map(String::from);
    if let Some(ref p) = permissions {
        if !p.chars().all(|c| c.is_ascii_digit()) {
            return Err(bad_request("Invalid input"));
        }
    }
    let hoist = body.get("hoist").and_then(Value::as_bool);
    let mentionable = body.get("mentionable").and_then(Value::as_bool);

    let r = role::create_role(
        &state,
        &server_id,
        &user.user_id,
        role::NewRole {
            name: name.to_string(),
            color,
            permissions,
            hoist,
            mentionable,
        },
    )
    .await?;
    audit::record(
        &state,
        audit::Entry {
            server_id: &server_id,
            actor_id: &user.user_id,
            action: audit::action::ROLE_CREATE,
            target_id: Some(&r.id),
            target_type: Some("role"),
            changes: json!({ "name": { "new": r.name }, "permissions": { "new": r.permissions.to_string() } }),
            reason: None,
        },
    )
    .await;
    let dto = to_role(&r);
    let _ = state
        .io()
        .to(format!("server:{server_id}"))
        .emit("role:created", &dto);
    Ok((StatusCode::CREATED, Json(json!(dto))))
}

async fn update_role(
    State(state): State<AppState>,
    user: AuthUser,
    Path((server_id, role_id)): Path<(String, String)>,
    Json(body): Json<Value>,
) -> AppResult<Json<Value>> {
    let mut patch = role::RolePatch::default();
    if let Some(v) = body.get("name") {
        let n = v.as_str().ok_or_else(|| bad_request("Invalid input"))?;
        if n.is_empty() || n.len() > 100 {
            return Err(bad_request("Invalid input"));
        }
        patch.name = Some(n.to_string());
    }
    if let Some(v) = body.get("color") {
        patch.color = Some(v.as_i64().ok_or_else(|| bad_request("Invalid input"))? as i32);
    }
    if let Some(v) = body.get("permissions") {
        let p = v.as_str().ok_or_else(|| bad_request("Invalid input"))?;
        if !p.chars().all(|c| c.is_ascii_digit()) {
            return Err(bad_request("Invalid input"));
        }
        patch.permissions = Some(p.to_string());
    }
    if let Some(v) = body.get("position") {
        let pos = v.as_i64().ok_or_else(|| bad_request("Invalid input"))? as i32;
        if pos < 1 {
            return Err(bad_request("Invalid input"));
        }
        patch.position = Some(pos);
    }
    if let Some(v) = body.get("hoist") {
        patch.hoist = Some(v.as_bool().ok_or_else(|| bad_request("Invalid input"))?);
    }
    if let Some(v) = body.get("mentionable") {
        patch.mentionable = Some(v.as_bool().ok_or_else(|| bad_request("Invalid input"))?);
    }

    let before = role::load_server_role(&state, &server_id, &role_id).await?;
    let r = role::update_role(&state, &server_id, &role_id, &user.user_id, patch).await?;
    let changes = audit::diff(vec![
        ("name", json!(before.name), json!(r.name)),
        ("color", json!(before.color), json!(r.color)),
        (
            "permissions",
            json!(before.permissions.to_string()),
            json!(r.permissions.to_string()),
        ),
        ("position", json!(before.position), json!(r.position)),
        ("hoist", json!(before.hoist), json!(r.hoist)),
        (
            "mentionable",
            json!(before.mentionable),
            json!(r.mentionable),
        ),
    ]);
    audit::record(
        &state,
        audit::Entry {
            server_id: &server_id,
            actor_id: &user.user_id,
            action: audit::action::ROLE_UPDATE,
            target_id: Some(&r.id),
            target_type: Some("role"),
            changes,
            reason: None,
        },
    )
    .await;
    let dto = to_role(&r);
    let _ = state
        .io()
        .to(format!("server:{server_id}"))
        .emit("role:updated", &dto);
    Ok(Json(json!(dto)))
}

async fn reorder_roles(
    State(state): State<AppState>,
    user: AuthUser,
    Path(server_id): Path<String>,
    Json(body): Json<Value>,
) -> AppResult<Json<Value>> {
    let items = body
        .as_array()
        .ok_or_else(|| bad_request("Expected an array of { id, position }"))?;
    if items.is_empty() || items.len() > 250 {
        return Err(bad_request("Invalid input"));
    }

    let mut positions: Vec<(String, i32)> = Vec::with_capacity(items.len());
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
        positions.push((id.to_string(), pos as i32));
    }
    let mut seen: Vec<&str> = positions.iter().map(|(id, _)| id.as_str()).collect();
    seen.sort_unstable();
    let unique = seen.len();
    seen.dedup();
    if seen.len() != unique {
        return Err(bad_request("Duplicate role id"));
    }

    let roles = role::reorder_roles(&state, &server_id, &user.user_id, &positions).await?;
    let dtos: Vec<_> = roles.iter().map(to_role).collect();
    let _ = state.io().to(format!("server:{server_id}")).emit(
        "roles:reordered",
        &json!({ "serverId": server_id, "roles": dtos }),
    );
    Ok(Json(json!(dtos)))
}

async fn delete_role(
    State(state): State<AppState>,
    user: AuthUser,
    Path((server_id, role_id)): Path<(String, String)>,
) -> AppResult<impl IntoResponse> {
    let before = role::load_server_role(&state, &server_id, &role_id).await?;
    role::delete_role(&state, &server_id, &role_id, &user.user_id).await?;
    audit::record(
        &state,
        audit::Entry {
            server_id: &server_id,
            actor_id: &user.user_id,
            action: audit::action::ROLE_DELETE,
            target_id: Some(&role_id),
            target_type: Some("role"),
            changes: json!({ "name": { "old": before.name } }),
            reason: None,
        },
    )
    .await;
    let _ = state.io().to(format!("server:{server_id}")).emit(
        "role:deleted",
        &json!({ "serverId": server_id, "roleId": role_id }),
    );
    Ok(StatusCode::NO_CONTENT)
}

async fn assign_role(
    State(state): State<AppState>,
    user: AuthUser,
    Path((server_id, target_id, role_id)): Path<(String, String, String)>,
) -> AppResult<Json<Value>> {
    let member = role::assign_role(&state, &server_id, &target_id, &role_id, &user.user_id)
        .await?
        .ok_or_else(|| AppError::NotFound("Member not found".into()))?;
    audit::record(
        &state,
        audit::Entry {
            server_id: &server_id,
            actor_id: &user.user_id,
            action: audit::action::MEMBER_ROLE_UPDATE,
            target_id: Some(&target_id),
            target_type: Some("member"),
            changes: json!({ "roleAdded": { "new": role_id } }),
            reason: None,
        },
    )
    .await;
    emit_member_updated(&state, &server_id, &member);
    Ok(Json(json!(to_server_member(
        &member.member,
        &member.user,
        member.role_ids.clone()
    ))))
}

async fn unassign_role(
    State(state): State<AppState>,
    user: AuthUser,
    Path((server_id, target_id, role_id)): Path<(String, String, String)>,
) -> AppResult<Json<Value>> {
    let member = role::unassign_role(&state, &server_id, &target_id, &role_id, &user.user_id)
        .await?
        .ok_or_else(|| AppError::NotFound("Member not found".into()))?;
    audit::record(
        &state,
        audit::Entry {
            server_id: &server_id,
            actor_id: &user.user_id,
            action: audit::action::MEMBER_ROLE_UPDATE,
            target_id: Some(&target_id),
            target_type: Some("member"),
            changes: json!({ "roleRemoved": { "old": role_id } }),
            reason: None,
        },
    )
    .await;
    emit_member_updated(&state, &server_id, &member);
    Ok(Json(json!(to_server_member(
        &member.member,
        &member.user,
        member.role_ids.clone()
    ))))
}

async fn set_nickname(
    State(state): State<AppState>,
    user: AuthUser,
    Path((server_id, target_param)): Path<(String, String)>,
    Json(body): Json<Value>,
) -> AppResult<Json<Value>> {
    let caller_id = user.user_id.clone();
    let target_id = if target_param == "@me" {
        caller_id.clone()
    } else {
        target_param
    };

    if !body
        .as_object()
        .map(|o| o.contains_key("nickname"))
        .unwrap_or(false)
    {
        return Err(bad_request("Invalid input"));
    }
    let nickname = match body.get("nickname") {
        Some(Value::Null) => None,
        Some(Value::String(s)) if s.len() <= 32 => Some(s.clone()),
        _ => return Err(bad_request("Invalid input")),
    };

    if target_id == caller_id {
        if !membership::is_server_member(&state, &server_id, &caller_id).await? {
            return Err(AppError::Permission("Not a member of this server".into()));
        }
    } else {
        membership::require_permission(&state, &server_id, &caller_id, MANAGE_NICKNAMES).await?;
        membership::assert_outranks(&state, &server_id, &caller_id, &target_id).await?;
    }

    let member = role::set_nickname(&state, &server_id, &target_id, nickname)
        .await?
        .ok_or_else(|| AppError::NotFound("Member not found".into()))?;
    emit_member_updated(&state, &server_id, &member);
    Ok(Json(json!(to_server_member(
        &member.member,
        &member.user,
        member.role_ids.clone()
    ))))
}

async fn set_timeout(
    State(state): State<AppState>,
    user: AuthUser,
    Path((server_id, target_id)): Path<(String, String)>,
    Json(body): Json<Value>,
) -> AppResult<Json<Value>> {
    if !body
        .as_object()
        .map(|o| o.contains_key("durationSeconds"))
        .unwrap_or(false)
    {
        return Err(bad_request("durationSeconds is required"));
    }
    const MAX_TIMEOUT_SECONDS: i64 = 28 * 24 * 3600;
    let until = match body.get("durationSeconds") {
        Some(Value::Null) => None,
        Some(v) => {
            let secs = v.as_i64().ok_or_else(|| bad_request("Invalid input"))?;
            if !(1..=MAX_TIMEOUT_SECONDS).contains(&secs) {
                return Err(bad_request("durationSeconds must be 1-2419200, or null"));
            }
            Some((chrono::Utc::now() + chrono::Duration::seconds(secs)).naive_utc())
        }
        None => return Err(bad_request("Invalid input")),
    };

    let member = role::set_timeout(&state, &server_id, &target_id, &user.user_id, until)
        .await?
        .ok_or_else(|| AppError::NotFound("Member not found".into()))?;
    audit::record(
        &state,
        audit::Entry {
            server_id: &server_id,
            actor_id: &user.user_id,
            action: audit::action::MEMBER_TIMEOUT,
            target_id: Some(&target_id),
            target_type: Some("member"),
            changes: json!({ "timedOutUntil": { "new": until.map(crate::timefmt::iso) } }),
            reason: None,
        },
    )
    .await;
    emit_member_updated(&state, &server_id, &member);
    Ok(Json(json!(to_server_member(
        &member.member,
        &member.user,
        member.role_ids.clone()
    ))))
}

async fn kick_member(
    State(state): State<AppState>,
    user: AuthUser,
    Path((server_id, target_id)): Path<(String, String)>,
) -> AppResult<impl IntoResponse> {
    membership::require_permission(&state, &server_id, &user.user_id, KICK_MEMBERS).await?;
    moderation::kick_member(&state, &server_id, &target_id, &user.user_id).await?;
    let (action, target_type) = if bot::is_bot(&state, &target_id).await? {
        (audit::action::BOT_REMOVE, "user")
    } else {
        (audit::action::MEMBER_KICK, "member")
    };
    audit::record(
        &state,
        audit::Entry {
            server_id: &server_id,
            actor_id: &user.user_id,
            action,
            target_id: Some(&target_id),
            target_type: Some(target_type),
            changes: json!({}),
            reason: None,
        },
    )
    .await;
    let _ = state.io().to(format!("server:{server_id}")).emit(
        "member:left",
        &json!({ "serverId": server_id, "userId": target_id }),
    );
    let _ = state
        .io()
        .to(format!("user:{target_id}"))
        .leave(format!("server:{server_id}"));
    Ok(StatusCode::NO_CONTENT)
}

async fn ban_member(
    State(state): State<AppState>,
    user: AuthUser,
    Path((server_id, target_id)): Path<(String, String)>,
    body: Option<Json<Value>>,
) -> AppResult<impl IntoResponse> {
    let reason = body
        .as_ref()
        .and_then(|b| b.get("reason").and_then(Value::as_str))
        .map(String::from);
    if let Some(ref r) = reason {
        if r.len() > 512 {
            return Err(bad_request("Invalid input"));
        }
    }
    membership::require_permission(&state, &server_id, &user.user_id, BAN_MEMBERS).await?;
    moderation::ban_member(
        &state,
        &server_id,
        &target_id,
        &user.user_id,
        reason.as_deref(),
    )
    .await?;
    audit::record(
        &state,
        audit::Entry {
            server_id: &server_id,
            actor_id: &user.user_id,
            action: audit::action::MEMBER_BAN,
            target_id: Some(&target_id),
            target_type: Some("member"),
            changes: json!({}),
            reason: reason.as_deref(),
        },
    )
    .await;
    let _ = state.io().to(format!("server:{server_id}")).emit(
        "member:left",
        &json!({ "serverId": server_id, "userId": target_id }),
    );
    let _ = state
        .io()
        .to(format!("user:{target_id}"))
        .leave(format!("server:{server_id}"));
    Ok(StatusCode::NO_CONTENT)
}

async fn unban_member(
    State(state): State<AppState>,
    user: AuthUser,
    Path((server_id, target_id)): Path<(String, String)>,
) -> AppResult<impl IntoResponse> {
    membership::require_permission(&state, &server_id, &user.user_id, BAN_MEMBERS).await?;
    moderation::unban_member(&state, &server_id, &target_id).await?;
    audit::record(
        &state,
        audit::Entry {
            server_id: &server_id,
            actor_id: &user.user_id,
            action: audit::action::MEMBER_UNBAN,
            target_id: Some(&target_id),
            target_type: Some("member"),
            changes: json!({}),
            reason: None,
        },
    )
    .await;
    Ok(StatusCode::NO_CONTENT)
}

async fn list_bans(
    State(state): State<AppState>,
    user: AuthUser,
    Path(server_id): Path<String>,
) -> AppResult<Json<Value>> {
    membership::require_permission(&state, &server_id, &user.user_id, BAN_MEMBERS).await?;
    let bans = moderation::list_bans(&state, &server_id).await?;
    let out: Vec<Value> = bans
        .iter()
        .map(|(b, u)| {
            json!({
                "user": to_user(u),
                "reason": b.reason,
                "bannedById": b.banned_by_id,
                "createdAt": crate::timefmt::iso(b.created_at),
            })
        })
        .collect();
    Ok(Json(json!(out)))
}
