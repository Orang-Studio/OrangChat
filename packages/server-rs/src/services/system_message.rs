
use serde_json::{json, Value as Json};

use crate::dto::MessageDto;
use crate::error::AppResult;
use crate::ids::cuid;
use crate::state::AppState;

#[derive(Debug, Clone, Copy)]
pub enum Notice {
    StrictEnabled,
    StrictDisabled,
    KeyReset,
    BackgroundChanged,
    BackgroundRemoved,
    IconChanged,
    IconRemoved,
}

impl Notice {
    pub fn kind(self) -> &'static str {
        match self {
            Notice::StrictEnabled => "strictEnabled",
            Notice::StrictDisabled => "strictDisabled",
            Notice::KeyReset => "keyReset",
            Notice::BackgroundChanged => "backgroundChanged",
            Notice::BackgroundRemoved => "backgroundRemoved",
            Notice::IconChanged => "iconChanged",
            Notice::IconRemoved => "iconRemoved",
        }
    }

    fn sentence(self, name: &str) -> String {
        match self {
            Notice::StrictEnabled => format!(
                "{name} turned on the requirement to verify before messaging in this conversation."
            ),
            Notice::StrictDisabled => format!(
                "{name} turned off the requirement to verify before messaging in this conversation."
            ),
            Notice::KeyReset => format!("{name} started a new encryption key for this conversation."),
            Notice::BackgroundChanged => format!("{name} changed the chat background."),
            Notice::BackgroundRemoved => format!("{name} removed the chat background."),
            Notice::IconChanged => format!("{name} changed the group icon."),
            Notice::IconRemoved => format!("{name} removed the group icon."),
        }
    }
}

pub const CALL: &str = "call";

async fn display_name(state: &AppState, user_id: &str) -> String {
    sqlx::query_scalar::<_, String>(r#"SELECT "displayName" FROM "User" WHERE id = $1"#)
        .bind(user_id)
        .fetch_optional(&state.pool)
        .await
        .ok()
        .flatten()
        .unwrap_or_else(|| "Someone".into())
}

async fn insert(
    state: &AppState,
    channel_id: &str,
    actor_id: &str,
    kind: &str,
    content: &str,
    data: Option<Json>,
) -> AppResult<MessageDto> {
    let id = cuid();
    sqlx::query(
        r#"INSERT INTO "Message"
           (id, "channelId", "authorId", content, attachments, "systemNotice", "systemData")
           VALUES ($1, $2, $3, $4, '[]'::jsonb, $5, $6)"#,
    )
    .bind(&id)
    .bind(channel_id)
    .bind(actor_id)
    .bind(content)
    .bind(kind)
    .bind(&data)
    .execute(&state.pool)
    .await?;

    sqlx::query(r#"UPDATE "Channel" SET "updatedAt" = now() WHERE id = $1"#)
        .bind(channel_id)
        .execute(&state.pool)
        .await?;

    super::message::load_one(state, &id, actor_id).await
}

fn emit_new(state: &AppState, channel_id: &str, msg: &MessageDto) {
    let _ = state
        .io()
        .to(format!("channel:{channel_id}"))
        .emit("message:new", msg);
}

fn emit_updated(state: &AppState, channel_id: &str, msg: &MessageDto) {
    let _ = state
        .io()
        .to(format!("channel:{channel_id}"))
        .emit("message:updated", msg);
}

pub async fn announce(state: &AppState, channel_id: &str, actor_id: &str, notice: Notice) {
    let name = display_name(state, actor_id).await;
    let content = notice.sentence(&name);
    match insert(state, channel_id, actor_id, notice.kind(), &content, None).await {
        Ok(msg) => emit_new(state, channel_id, &msg),
        Err(error) => tracing::warn!(%error, kind = notice.kind(), "could not write system notice"),
    }
}

#[derive(Debug, Clone)]
pub struct CallCard<'a> {
    pub caller_id: &'a str,
    pub video: bool,
    pub started_at: &'a str,
    pub ended_at: Option<&'a str>,
    pub joined: &'a [String],
    pub ringing: &'a [String],
    pub duration_sec: Option<i64>,
}

impl CallCard<'_> {
    fn data(&self) -> Json {
        json!({
            "callerId": self.caller_id,
            "video": self.video,
            "startedAt": self.started_at,
            "endedAt": self.ended_at,
            "joined": self.joined,
            "ringing": self.ringing,
            "durationSec": self.duration_sec,
            "missed": self.ended_at.is_some() && self.joined.len() <= 1,
        })
    }

    fn sentence(&self, name: &str) -> String {
        let kind = if self.video { "video call" } else { "call" };
        match self.ended_at {
            None => format!("{name} started a {kind}."),
            Some(_) if self.joined.len() <= 1 => format!("{name} called. Nobody answered."),
            Some(_) => format!("{name} started a {kind}."),
        }
    }
}

pub async fn open_call(state: &AppState, channel_id: &str, card: &CallCard<'_>) -> Option<String> {
    let name = display_name(state, card.caller_id).await;
    let content = card.sentence(&name);
    match insert(
        state,
        channel_id,
        card.caller_id,
        CALL,
        &content,
        Some(card.data()),
    )
    .await
    {
        Ok(msg) => {
            emit_new(state, channel_id, &msg);
            Some(msg.id)
        }
        Err(error) => {
            tracing::warn!(%error, "could not open call card");
            None
        }
    }
}

pub async fn update_call(
    state: &AppState,
    channel_id: &str,
    message_id: &str,
    card: &CallCard<'_>,
) {
    let name = display_name(state, card.caller_id).await;
    let content = card.sentence(&name);
    let result = sqlx::query(
        r#"UPDATE "Message" SET content = $2, "systemData" = $3
           WHERE id = $1 AND "systemNotice" = 'call'"#,
    )
    .bind(message_id)
    .bind(&content)
    .bind(card.data())
    .execute(&state.pool)
    .await;
    if let Err(error) = result {
        tracing::warn!(%error, "could not update call card");
        return;
    }
    match super::message::load_one(state, message_id, card.caller_id).await {
        Ok(msg) => emit_updated(state, channel_id, &msg),
        Err(error) => tracing::warn!(%error, "could not reload call card"),
    }
}
