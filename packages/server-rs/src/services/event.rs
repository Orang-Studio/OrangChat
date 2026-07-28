//! Scheduled server events.

use chrono::NaiveDateTime;

use crate::error::{AppError, AppResult};
use crate::ids::cuid;
use crate::models::ScheduledEventRow;
use crate::state::AppState;

const NAME_MAX: usize = 100;
const DESCRIPTION_MAX: usize = 1000;
const LOCATION_MAX: usize = 100;
pub const PER_SERVER_LIMIT: i64 = 100;

pub struct EventInput {
    pub name: String,
    pub description: Option<String>,
    pub location: Option<String>,
    pub channel_id: Option<String>,
    pub starts_at: NaiveDateTime,
    pub ends_at: Option<NaiveDateTime>,
}

fn validate(input: &EventInput) -> AppResult<String> {
    let name = input.name.trim().to_string();
    if name.is_empty() || name.chars().count() > NAME_MAX {
        return Err(AppError::BadRequest(format!(
            "Event names must be 1-{NAME_MAX} characters"
        )));
    }
    if input
        .description
        .as_ref()
        .is_some_and(|d| d.chars().count() > DESCRIPTION_MAX)
    {
        return Err(AppError::BadRequest("Description is too long".into()));
    }
    if input
        .location
        .as_ref()
        .is_some_and(|l| l.chars().count() > LOCATION_MAX)
    {
        return Err(AppError::BadRequest("Location is too long".into()));
    }
    if input.ends_at.is_some_and(|end| end <= input.starts_at) {
        return Err(AppError::BadRequest("Event ends before it starts".into()));
    }
    Ok(name)
}

const SELECT: &str = r#"SELECT e.id, e."serverId", e."channelId", e."creatorId", e.name,
        e.description, e.location, e."startsAt", e."endsAt", e."createdAt",
        (SELECT COUNT(*) FROM "EventInterest" i WHERE i."eventId" = e.id) AS "interestedCount",
        EXISTS(SELECT 1 FROM "EventInterest" i WHERE i."eventId" = e.id AND i."userId" = $1)
            AS "interested"
   FROM "ScheduledEvent" e"#;

pub async fn list_events(
    state: &AppState,
    server_id: &str,
    user_id: &str,
) -> AppResult<Vec<ScheduledEventRow>> {
    let rows = sqlx::query_as::<_, ScheduledEventRow>(&format!(
        r#"{SELECT} WHERE e."serverId" = $2 ORDER BY e."startsAt" ASC"#
    ))
    .bind(user_id)
    .bind(server_id)
    .fetch_all(&state.pool)
    .await?;
    Ok(rows)
}

pub async fn get_event(
    state: &AppState,
    event_id: &str,
    user_id: &str,
) -> AppResult<ScheduledEventRow> {
    sqlx::query_as::<_, ScheduledEventRow>(&format!(r#"{SELECT} WHERE e.id = $2"#))
        .bind(user_id)
        .bind(event_id)
        .fetch_optional(&state.pool)
        .await?
        .ok_or_else(|| AppError::NotFound("Event not found".into()))
}

pub async fn create_event(
    state: &AppState,
    server_id: &str,
    creator_id: &str,
    input: EventInput,
) -> AppResult<ScheduledEventRow> {
    let name = validate(&input)?;

    let count: i64 =
        sqlx::query_scalar(r#"SELECT COUNT(*) FROM "ScheduledEvent" WHERE "serverId" = $1"#)
            .bind(server_id)
            .fetch_one(&state.pool)
            .await?;
    if count >= PER_SERVER_LIMIT {
        return Err(AppError::BadRequest(format!(
            "A server can hold at most {PER_SERVER_LIMIT} events"
        )));
    }

    if let Some(channel_id) = &input.channel_id {
        let owned: Option<String> = sqlx::query_scalar(
            r#"SELECT id FROM "Channel" WHERE id = $1 AND "serverId" = $2 AND type <> 'category'"#,
        )
        .bind(channel_id)
        .bind(server_id)
        .fetch_optional(&state.pool)
        .await?;
        if owned.is_none() {
            return Err(AppError::BadRequest("Unknown channel".into()));
        }
    }

    let id = cuid();
    sqlx::query(
        r#"INSERT INTO "ScheduledEvent"
           (id, "serverId", "channelId", "creatorId", name, description, location,
            "startsAt", "endsAt", "createdAt", "updatedAt")
           VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, now(), now())"#,
    )
    .bind(&id)
    .bind(server_id)
    .bind(&input.channel_id)
    .bind(creator_id)
    .bind(&name)
    .bind(&input.description)
    .bind(&input.location)
    .bind(input.starts_at)
    .bind(input.ends_at)
    .execute(&state.pool)
    .await?;

    get_event(state, &id, creator_id).await
}

pub async fn update_event(
    state: &AppState,
    event_id: &str,
    user_id: &str,
    input: EventInput,
) -> AppResult<ScheduledEventRow> {
    let name = validate(&input)?;
    sqlx::query(
        r#"UPDATE "ScheduledEvent"
           SET name = $2, description = $3, location = $4, "channelId" = $5,
               "startsAt" = $6, "endsAt" = $7, "updatedAt" = now()
           WHERE id = $1"#,
    )
    .bind(event_id)
    .bind(&name)
    .bind(&input.description)
    .bind(&input.location)
    .bind(&input.channel_id)
    .bind(input.starts_at)
    .bind(input.ends_at)
    .execute(&state.pool)
    .await?;
    get_event(state, event_id, user_id).await
}

pub async fn delete_event(state: &AppState, event_id: &str) -> AppResult<()> {
    sqlx::query(r#"DELETE FROM "ScheduledEvent" WHERE id = $1"#)
        .bind(event_id)
        .execute(&state.pool)
        .await?;
    Ok(())
}

pub async fn set_interest(
    state: &AppState,
    event_id: &str,
    user_id: &str,
    interested: bool,
) -> AppResult<ScheduledEventRow> {
    if interested {
        sqlx::query(
            r#"INSERT INTO "EventInterest" ("eventId", "userId", "createdAt")
               VALUES ($1, $2, now()) ON CONFLICT DO NOTHING"#,
        )
        .bind(event_id)
        .bind(user_id)
        .execute(&state.pool)
        .await?;
    } else {
        sqlx::query(r#"DELETE FROM "EventInterest" WHERE "eventId" = $1 AND "userId" = $2"#)
            .bind(event_id)
            .bind(user_id)
            .execute(&state.pool)
            .await?;
    }
    get_event(state, event_id, user_id).await
}

/// The server an event belongs to, for permission checks before a write.
pub async fn event_server(state: &AppState, event_id: &str) -> AppResult<String> {
    sqlx::query_scalar(r#"SELECT "serverId" FROM "ScheduledEvent" WHERE id = $1"#)
        .bind(event_id)
        .fetch_optional(&state.pool)
        .await?
        .ok_or_else(|| AppError::NotFound("Event not found".into()))
}
