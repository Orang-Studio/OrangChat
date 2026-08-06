//! Messages the server writes about a conversation, rather than lines a person
//! typed into it.
//!
//! These used to be ordinary messages whose exact text every client matched,
//! which meant anyone could forge one by typing the sentence, and the notice was
//! attributed to whoever sent it. Now the kind lives in `Message.systemNotice`,
//! a column only this module ever writes, and only from an action the server
//! carried out itself. `authorId` stays the person whose action it was, so the
//! notice can still say who did it - it is authored *about* them by the server,
//! not *by* them.
//!
//! `content` is a complete plaintext sentence naming the actor, so a client that
//! has never heard of a kind still renders something true; clients that know the
//! kind re-word it ("You turned off ...") and draw it as a notice. Notices stay
//! plaintext even in an E2EE conversation: nothing here is anything a person
//! wrote, and the server is the one that knows the event happened. Decrypt paths
//! already pass a message with no ciphertext straight through.
//!
//! Kinds are append-only. Adding one is a new string plus rendering on each
//! client; nothing existing has to move.

use serde_json::{json, Value as Json};

use crate::dto::MessageDto;
use crate::error::AppResult;
use crate::ids::cuid;
use crate::state::AppState;

/// A one-sentence notice. Anything that needs a payload (a call) is its own
/// constructor below instead.
#[derive(Debug, Clone, Copy)]
pub enum Notice {
    StrictEnabled,
    StrictDisabled,
    KeyReset,
    BackgroundChanged,
    BackgroundRemoved,
}

impl Notice {
    /// The wire value stored in `systemNotice`. Never change one of these: an
    /// edit orphans every notice already written.
    pub fn kind(self) -> &'static str {
        match self {
            Notice::StrictEnabled => "strictEnabled",
            Notice::StrictDisabled => "strictDisabled",
            Notice::KeyReset => "keyReset",
            Notice::BackgroundChanged => "backgroundChanged",
            Notice::BackgroundRemoved => "backgroundRemoved",
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
        }
    }
}

/// `systemNotice` of a call card. Unlike the notices above it is edited in place
/// as the call runs, and it carries `systemData`.
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

/// Write the row and hand back the wire message. `actor_id` is the person the
/// notice is about; it is never read off a request body.
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

    // A notice is part of the conversation's activity, so DM ordering should see
    // it - but it deliberately does not reopen a DM someone closed, and does not
    // touch unread counts or push. Nobody needs their phone to buzz about a
    // background.
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

/// Say on the conversation that something about it changed.
///
/// Best-effort by design: the notice is a courtesy to the other side, and a
/// failed write must not fail - or undo - the change it describes. Losing one
/// costs an unexplained background; refusing the action would cost the setting.
pub async fn announce(state: &AppState, channel_id: &str, actor_id: &str, notice: Notice) {
    let name = display_name(state, actor_id).await;
    let content = notice.sentence(&name);
    match insert(state, channel_id, actor_id, notice.kind(), &content, None).await {
        Ok(msg) => emit_new(state, channel_id, &msg),
        Err(error) => tracing::warn!(%error, kind = notice.kind(), "could not write system notice"),
    }
}

/// The card's payload. Mirrors `CallNotice` in packages/shared.
///
/// `joined` is everyone who was ever connected, not who is connected now: after
/// the call it is the answer to "did anyone pick up", and during it, it is the
/// row of faces. A call nobody but the caller ever joined is a missed call.
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
            // Answered by exactly nobody. Kept as its own flag rather than left
            // for each client to infer from an empty-ish `joined`.
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

/// Put a live call card on the conversation. Returns the message id, which the
/// call keeps so the same card can be finished off when the call is.
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

/// Rewrite a call card in place - someone answered, someone left, or the call is
/// over. The card is the same message throughout, so the conversation never
/// fills up with one line per state change.
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
