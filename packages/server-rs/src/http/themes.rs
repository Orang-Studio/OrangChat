//! Theme marketplace REST, mounted under /api/themes.
//!
//! Public routes (auth required) let anyone browse published themes, manage
//! their own, and submit one for review. The admin routes - the review queue,
//! approve, reject, and a tiny HTML panel - are gated by [`LocalOnly`], so they
//! only answer to requests made on the server host itself. There is no admin
//! account: "in the server, not outside" is enforced by the network, not a role.

use axum::extract::{Path, State};
use axum::response::Html;
use axum::routing::{get, post};
use axum::{Json, Router};
use serde_json::{json, Value};

use crate::error::{AppResult, AppError};
use crate::http::{AuthUser, LocalOnly};
use crate::services::theme;
use crate::state::AppState;

pub fn routes() -> Router<AppState> {
    Router::new()
        // Host-only admin surface. Registered before the ":id" routes so the
        // literal "admin" segment wins the match.
        .route("/themes/admin", get(admin_panel))
        .route("/themes/admin/pending", get(admin_pending))
        .route("/themes/admin/:id/approve", post(admin_approve))
        .route("/themes/admin/:id/reject", post(admin_reject))
        // Public marketplace.
        .route("/themes", get(list).post(create))
        .route("/themes/mine", get(list_mine))
        .route("/themes/:id", axum::routing::patch(update).delete(delete))
        .route("/themes/:id/install", post(install))
}

async fn list(State(state): State<AppState>, _user: AuthUser) -> AppResult<Json<Value>> {
    Ok(Json(json!({ "themes": theme::list_published(&state).await? })))
}

async fn list_mine(State(state): State<AppState>, user: AuthUser) -> AppResult<Json<Value>> {
    Ok(Json(json!({ "themes": theme::list_mine(&state, &user.user_id).await? })))
}

async fn create(
    State(state): State<AppState>,
    user: AuthUser,
    Json(body): Json<Value>,
) -> AppResult<Json<Value>> {
    let name = body.get("name").and_then(Value::as_str).unwrap_or_default();
    let vars = body.get("vars").cloned().unwrap_or_else(|| json!({}));
    let submitted = body.get("submitted").and_then(Value::as_bool).unwrap_or(false);
    Ok(Json(json!(
        theme::create(&state, &user.user_id, name, &vars, submitted).await?
    )))
}

async fn update(
    State(state): State<AppState>,
    user: AuthUser,
    Path(id): Path<String>,
    Json(body): Json<Value>,
) -> AppResult<Json<Value>> {
    let name = body.get("name").and_then(Value::as_str);
    let submitted = body.get("submitted").and_then(Value::as_bool);
    Ok(Json(json!(
        theme::update_own(&state, &user.user_id, &id, name, submitted).await?
    )))
}

async fn delete(
    State(state): State<AppState>,
    user: AuthUser,
    Path(id): Path<String>,
) -> AppResult<Json<Value>> {
    theme::delete(&state, &user.user_id, &id).await?;
    Ok(Json(json!({ "deleted": true })))
}

async fn install(
    State(state): State<AppState>,
    user: AuthUser,
    Path(id): Path<String>,
) -> AppResult<Json<Value>> {
    Ok(Json(json!(theme::install(&state, &user.user_id, &id).await?)))
}

// ── Host-only admin ─────────────────────────────────────

async fn admin_pending(State(state): State<AppState>, _local: LocalOnly) -> AppResult<Json<Value>> {
    Ok(Json(json!({ "themes": theme::list_pending(&state).await? })))
}

async fn admin_approve(
    State(state): State<AppState>,
    _local: LocalOnly,
    Path(id): Path<String>,
) -> AppResult<Json<Value>> {
    Ok(Json(json!(theme::review(&state, &id, true).await?)))
}

async fn admin_reject(
    State(state): State<AppState>,
    _local: LocalOnly,
    Path(id): Path<String>,
) -> AppResult<Json<Value>> {
    Ok(Json(json!(theme::review(&state, &id, false).await?)))
}

/// A self-contained review panel, served only to host-local requests. No build
/// step, no external assets - it talks to the admin JSON routes above, which are
/// behind the same guard, so opening it over an SSH tunnel is enough to moderate.
async fn admin_panel(_local: LocalOnly) -> Result<Html<&'static str>, AppError> {
    Ok(Html(ADMIN_PANEL_HTML))
}

const ADMIN_PANEL_HTML: &str = r##"<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>OrangChat — Theme review</title>
<style>
  :root { color-scheme: dark; }
  body { margin: 0; background: #0e0f13; color: #f3f4f7;
    font: 15px/1.5 system-ui, sans-serif; padding: 24px; }
  h1 { font-size: 18px; margin: 0 0 4px; }
  p.sub { color: #71747f; margin: 0 0 20px; }
  .theme { border: 1px solid #21232c; border-radius: 10px; padding: 14px; margin-bottom: 12px; }
  .head { display: flex; justify-content: space-between; gap: 12px; align-items: baseline; }
  .name { font-weight: 600; }
  .by { color: #71747f; font-size: 13px; }
  .swatches { display: flex; flex-wrap: wrap; gap: 6px; margin: 10px 0; }
  .sw { width: 26px; height: 26px; border-radius: 6px; border: 1px solid #353845; }
  .actions { display: flex; gap: 8px; }
  button { border: 0; border-radius: 8px; padding: 7px 14px; font-weight: 600; cursor: pointer; }
  .approve { background: #ff6a1a; color: #1c0e02; }
  .reject { background: #24262f; color: #f3f4f7; }
  .empty, .err { color: #71747f; }
  .err { color: #e2574c; }
</style>
</head>
<body>
<h1>Theme review</h1>
<p class="sub">Submissions awaiting approval. This panel is reachable only from the server host.</p>
<div id="list"><p class="empty">Loading…</p></div>
<script>
const listEl = document.getElementById('list');

function swatches(vars) {
  return Object.values(vars || {}).slice(0, 12)
    .map(c => `<span class="sw" style="background:${String(c).replace(/[^#a-zA-Z0-9(),.%/ -]/g,'')}"></span>`)
    .join('');
}

async function load() {
  try {
    const res = await fetch('/api/themes/admin/pending');
    if (!res.ok) throw new Error('Request failed ('+res.status+')');
    const { themes } = await res.json();
    if (!themes.length) { listEl.innerHTML = '<p class="empty">Nothing pending.</p>'; return; }
    listEl.innerHTML = themes.map(t => `
      <div class="theme" data-id="${t.id}">
        <div class="head">
          <span class="name"></span>
          <span class="by"></span>
        </div>
        <div class="swatches">${swatches(t.vars)}</div>
        <div class="actions">
          <button class="approve">Approve</button>
          <button class="reject">Reject</button>
        </div>
      </div>`).join('');
    // Fill text via textContent so a theme name can't inject markup.
    themes.forEach(t => {
      const el = listEl.querySelector(`[data-id="${t.id}"]`);
      el.querySelector('.name').textContent = t.name;
      el.querySelector('.by').textContent = 'by ' + (t.authorName || 'unknown');
      el.querySelector('.approve').onclick = () => decide(t.id, 'approve');
      el.querySelector('.reject').onclick = () => decide(t.id, 'reject');
    });
  } catch (e) {
    listEl.innerHTML = '<p class="err">'+e.message+'</p>';
  }
}

async function decide(id, action) {
  await fetch(`/api/themes/admin/${id}/${action}`, { method: 'POST' });
  load();
}

load();
</script>
</body>
</html>"##;
