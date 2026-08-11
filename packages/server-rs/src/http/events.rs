
use axum::extract::{Path, State};
use axum::http::StatusCode;
use axum::response::IntoResponse;
use axum::routing::{get, put};
use axum::{Json, Router};
use chrono::{DateTime, NaiveDateTime, Utc};
use serde::Deserialize;
use serde_json::json;

use crate::dto::{to_scheduled_event, ScheduledEventDto};
use crate::error::AppResult;
use crate::http::{bad_request, AuthUser};
use crate::permissions::MANAGE_EVENTS;
use crate::services::{event, membership};
use crate::state::AppState;

pub fn routes() -> Router<AppState> {
    Router::new()
        .route("/servers/:serverId/events", get(list).post(create))
        .route(
            "/events/:eventId",
            axum::routing::patch(update).delete(remove),
        )
        .route(
            "/events/:eventId/interest",
            put(interest).delete(uninterest),
        )
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct EventBody {
    name: String,
    #[serde(default)]
    description: Option<String>,
    #[serde(default)]
    location: Option<String>,
    #[serde(default)]
    channel_id: Option<String>,
    starts_at: String,
    #[serde(default)]
    ends_at: Option<String>,
}

fn parse_time(raw: &str) -> AppResult<NaiveDateTime> {
    DateTime::parse_from_rfc3339(raw)
        .map(|t| t.with_timezone(&Utc).naive_utc())
        .map_err(|_| bad_request("Invalid date"))
}

impl EventBody {
    fn into_input(self) -> AppResult<event::EventInput> {
        let starts_at = parse_time(&self.starts_at)?;
        let ends_at = match self.ends_at.as_deref() {
            Some(raw) if !raw.is_empty() => Some(parse_time(raw)?),
            _ => None,
        };
        Ok(event::EventInput {
            name: self.name,
            description: self.description.filter(|d| !d.trim().is_empty()),
            location: self.location.filter(|l| !l.trim().is_empty()),
            channel_id: self.channel_id.filter(|c| !c.is_empty()),
            starts_at,
            ends_at,
        })
    }
}

async fn list(
    user: AuthUser,
    Path(server_id): Path<String>,
    State(state): State<AppState>,
) -> AppResult<Json<Vec<ScheduledEventDto>>> {
    membership::require_permission(&state, &server_id, &user.user_id, 0).await?;
    let rows = event::list_events(&state, &server_id, &user.user_id).await?;
    Ok(Json(rows.iter().map(to_scheduled_event).collect()))
}

async fn create(
    user: AuthUser,
    Path(server_id): Path<String>,
    State(state): State<AppState>,
    Json(body): Json<EventBody>,
) -> AppResult<Json<ScheduledEventDto>> {
    membership::require_permission(&state, &server_id, &user.user_id, MANAGE_EVENTS).await?;
    let row = event::create_event(&state, &server_id, &user.user_id, body.into_input()?).await?;
    let dto = to_scheduled_event(&row);
    let _ = state
        .io()
        .to(format!("server:{server_id}"))
        .emit("event:created", &json!(dto));
    Ok(Json(dto))
}

async fn update(
    user: AuthUser,
    Path(event_id): Path<String>,
    State(state): State<AppState>,
    Json(body): Json<EventBody>,
) -> AppResult<Json<ScheduledEventDto>> {
    let server_id = event::event_server(&state, &event_id).await?;
    membership::require_permission(&state, &server_id, &user.user_id, MANAGE_EVENTS).await?;
    let row = event::update_event(&state, &event_id, &user.user_id, body.into_input()?).await?;
    let dto = to_scheduled_event(&row);
    let _ = state
        .io()
        .to(format!("server:{server_id}"))
        .emit("event:updated", &json!(dto));
    Ok(Json(dto))
}

async fn remove(
    user: AuthUser,
    Path(event_id): Path<String>,
    State(state): State<AppState>,
) -> AppResult<impl IntoResponse> {
    let server_id = event::event_server(&state, &event_id).await?;
    membership::require_permission(&state, &server_id, &user.user_id, MANAGE_EVENTS).await?;
    event::delete_event(&state, &event_id).await?;
    let _ = state.io().to(format!("server:{server_id}")).emit(
        "event:deleted",
        &json!({ "serverId": server_id, "eventId": event_id }),
    );
    Ok(StatusCode::NO_CONTENT)
}

async fn set_interest(
    state: &AppState,
    user_id: &str,
    event_id: &str,
    interested: bool,
) -> AppResult<Json<ScheduledEventDto>> {
    let server_id = event::event_server(state, event_id).await?;
    membership::require_permission(state, &server_id, user_id, 0).await?;
    let row = event::set_interest(state, event_id, user_id, interested).await?;
    let dto = to_scheduled_event(&row);
    let _ = state.io().to(format!("server:{server_id}")).emit(
        "event:interest",
        &json!({
            "serverId": server_id,
            "eventId": event_id,
            "interestedCount": dto.interested_count,
        }),
    );
    Ok(Json(dto))
}

async fn interest(
    user: AuthUser,
    Path(event_id): Path<String>,
    State(state): State<AppState>,
) -> AppResult<Json<ScheduledEventDto>> {
    set_interest(&state, &user.user_id, &event_id, true).await
}

async fn uninterest(
    user: AuthUser,
    Path(event_id): Path<String>,
    State(state): State<AppState>,
) -> AppResult<Json<ScheduledEventDto>> {
    set_interest(&state, &user.user_id, &event_id, false).await
}
