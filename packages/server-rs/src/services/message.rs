//! Message + reaction persistence and history. Mirrors message-service.ts.

use std::collections::{HashMap, HashSet};

use chrono::{NaiveDateTime, Utc};
use serde_json::Value as Json;

use crate::dto::{to_message, MessageDto, Page};
use crate::error::{AppError, AppResult};
use crate::http::attachments::MAX_PER_MESSAGE;
use crate::ids::cuid;
use crate::models::{MessageRow, ReactionRow, UserRow};
use crate::state::AppState;
use crate::timefmt::iso_opt;

/// Load authors + reactions for a set of message rows and build wire DTOs.
async fn build_dtos(
    state: &AppState,
    rows: &[MessageRow],
    viewer_id: &str,
) -> AppResult<Vec<MessageDto>> {
    if rows.is_empty() {
        return Ok(vec![]);
    }
    let author_ids: Vec<String> = rows.iter().map(|m| m.author_id.clone()).collect();
    let message_ids: Vec<String> = rows.iter().map(|m| m.id.clone()).collect();

    let authors: Vec<UserRow> = sqlx::query_as(r#"SELECT * FROM "User" WHERE id = ANY($1)"#)
        .bind(&author_ids)
        .fetch_all(&state.pool)
        .await?;
    let author_map: HashMap<&str, &UserRow> = authors.iter().map(|u| (u.id.as_str(), u)).collect();

    let reactions: Vec<(String, String, String)> = sqlx::query_as(
        r#"SELECT "messageId", "userId", emoji FROM "Reaction" WHERE "messageId" = ANY($1)"#,
    )
    .bind(&message_ids)
    .fetch_all(&state.pool)
    .await?;
    let mut reaction_map: HashMap<String, Vec<ReactionRow>> = HashMap::new();
    for (mid, uid, emoji) in reactions {
        reaction_map.entry(mid).or_default().push(ReactionRow {
            user_id: uid,
            emoji,
        });
    }

    let mut out = Vec::with_capacity(rows.len());
    for m in rows {
        let author = author_map
            .get(m.author_id.as_str())
            .ok_or_else(|| AppError::Internal("message author missing".into()))?;
        let empty = Vec::new();
        let rs = reaction_map.get(&m.id).unwrap_or(&empty);
        out.push(to_message(m, author, rs, viewer_id));
    }
    Ok(out)
}

async fn load_one(state: &AppState, message_id: &str, viewer_id: &str) -> AppResult<MessageDto> {
    let row: MessageRow = sqlx::query_as(r#"SELECT * FROM "Message" WHERE id = $1"#)
        .bind(message_id)
        .fetch_one(&state.pool)
        .await?;
    let mut dtos = build_dtos(state, std::slice::from_ref(&row), viewer_id).await?;
    Ok(dtos.remove(0))
}

/// A staged upload, on its way from `PendingAttachment` into a message.
#[derive(sqlx::FromRow)]
struct PendingAttachmentRow {
    id: String,
    url: String,
    filename: String,
    #[sqlx(rename = "contentType")]
    content_type: String,
    size: i32,
    width: Option<i32>,
    height: Option<i32>,
    storage: String,
    flagged: bool,
    #[sqlx(rename = "expiresAt")]
    expires_at: Option<NaiveDateTime>,
}

/// Take ownership of staged uploads and turn them into the JSON that gets frozen
/// onto the message.
///
/// The delete *is* the claim: a row can only be taken once, so the same upload
/// can't be stapled to two messages, and `uploaderId` in the predicate means ids
/// belonging to someone else simply don't come back. Anything the caller asked
/// for that didn't come back is an error rather than a silent omission —
/// quietly dropping an attachment would send a message the author didn't write.
/// `spoiler_ids` is the author's own presentation choice, so it's taken at face
/// value; ids in it that aren't being attached are simply ignored.
async fn claim_attachments(
    state: &AppState,
    author_id: &str,
    attachment_ids: &[String],
    spoiler_ids: &[String],
) -> AppResult<Json> {
    if attachment_ids.is_empty() {
        return Ok(Json::Array(vec![]));
    }
    if attachment_ids.len() > MAX_PER_MESSAGE {
        return Err(AppError::BadRequest(format!(
            "A message can carry at most {MAX_PER_MESSAGE} attachments"
        )));
    }

    let rows: Vec<PendingAttachmentRow> = sqlx::query_as(
        r#"DELETE FROM "PendingAttachment"
            WHERE id = ANY($1) AND "uploaderId" = $2
        RETURNING id, url, filename, "contentType", size, width, height, storage, flagged, "expiresAt""#,
    )
    .bind(attachment_ids)
    .bind(author_id)
    .fetch_all(&state.pool)
    .await?;

    if rows.len() != attachment_ids.len() {
        return Err(AppError::BadRequest(
            "An attachment was already sent or is no longer available".into(),
        ));
    }

    // An OrangMove file that expired between upload and send is already gone, so
    // attaching it would post a link that 404s on arrival.
    let now = Utc::now().naive_utc();
    if let Some(stale) = rows.iter().find(|r| r.expires_at.is_some_and(|e| e <= now)) {
        return Err(AppError::BadRequest(format!(
            "\"{}\" expired before it was sent",
            stale.filename
        )));
    }

    // RETURNING doesn't preserve the order asked for, and attachment order is
    // visible in the message, so put them back the way the client sent them.
    let by_id: HashMap<&str, &PendingAttachmentRow> =
        rows.iter().map(|r| (r.id.as_str(), r)).collect();
    let spoilers: HashSet<&str> = spoiler_ids.iter().map(String::as_str).collect();

    let out: Vec<Json> = attachment_ids
        .iter()
        .filter_map(|id| by_id.get(id.as_str()))
        .map(|r| {
            serde_json::json!({
                "id": r.id,
                "url": r.url,
                "filename": r.filename,
                "contentType": r.content_type,
                "size": r.size,
                "width": r.width,
                "height": r.height,
                "storage": r.storage,
                "flagged": r.flagged,
                "spoiler": spoilers.contains(r.id.as_str()),
                "expiresAt": iso_opt(r.expires_at),
            })
        })
        .collect();

    Ok(Json::Array(out))
}

pub async fn send_message(
    state: &AppState,
    channel_id: &str,
    author_id: &str,
    content: &str,
    reply_to_id: Option<&str>,
    attachment_ids: &[String],
    spoiler_ids: &[String],
) -> AppResult<MessageDto> {
    if let Some(rid) = reply_to_id {
        let parent: Option<String> =
            sqlx::query_scalar(r#"SELECT "channelId" FROM "Message" WHERE id = $1"#)
                .bind(rid)
                .fetch_optional(&state.pool)
                .await?;
        if parent.as_deref() != Some(channel_id) {
            return Err(AppError::Permission(
                "Reply target is not in this channel".into(),
            ));
        }
    }

    // Attachments are a message's whole content when there's no text, but a
    // message with neither is nothing at all.
    if content.trim().is_empty() && attachment_ids.is_empty() {
        return Err(AppError::BadRequest("Message is empty".into()));
    }

    let attachments = claim_attachments(state, author_id, attachment_ids, spoiler_ids).await?;

    let id = cuid();
    sqlx::query(
        r#"INSERT INTO "Message" (id, "channelId", "authorId", content, "replyToId", attachments)
           VALUES ($1, $2, $3, $4, $5, $6)"#,
    )
    .bind(&id)
    .bind(channel_id)
    .bind(author_id)
    .bind(content)
    .bind(reply_to_id)
    .bind(&attachments)
    .execute(&state.pool)
    .await?;

    // Bump the channel so DM/recent-activity ordering can use updatedAt.
    sqlx::query(r#"UPDATE "Channel" SET "updatedAt" = now() WHERE id = $1"#)
        .bind(channel_id)
        .execute(&state.pool)
        .await?;

    load_one(state, &id, author_id).await
}

pub async fn edit_message(
    state: &AppState,
    message_id: &str,
    user_id: &str,
    content: &str,
) -> AppResult<MessageDto> {
    let existing: Option<(String, String)> =
        sqlx::query_as(r#"SELECT "authorId", "channelId" FROM "Message" WHERE id = $1"#)
            .bind(message_id)
            .fetch_optional(&state.pool)
            .await?;
    let (author_id, _) =
        existing.ok_or_else(|| AppError::Permission("Message not found".into()))?;
    if author_id != user_id {
        return Err(AppError::Permission(
            "You can only edit your own messages".into(),
        ));
    }

    sqlx::query(r#"UPDATE "Message" SET content = $1, "editedAt" = now() WHERE id = $2"#)
        .bind(content)
        .bind(message_id)
        .execute(&state.pool)
        .await?;

    load_one(state, message_id, user_id).await
}

pub async fn delete_message(
    state: &AppState,
    message_id: &str,
    user_id: &str,
    can_manage: bool,
) -> AppResult<String> {
    let existing: Option<(String, String)> =
        sqlx::query_as(r#"SELECT "authorId", "channelId" FROM "Message" WHERE id = $1"#)
            .bind(message_id)
            .fetch_optional(&state.pool)
            .await?;
    let (author_id, channel_id) =
        existing.ok_or_else(|| AppError::Permission("Message not found".into()))?;
    if author_id != user_id && !can_manage {
        return Err(AppError::Permission(
            "Not allowed to delete this message".into(),
        ));
    }
    sqlx::query(r#"DELETE FROM "Message" WHERE id = $1"#)
        .bind(message_id)
        .execute(&state.pool)
        .await?;
    Ok(channel_id)
}

pub async fn get_history(
    state: &AppState,
    channel_id: &str,
    viewer_id: &str,
    before: Option<&str>,
    limit: i64,
) -> AppResult<Page<MessageDto>> {
    let take = limit + 1;
    let rows: Vec<MessageRow> = if let Some(before_id) = before {
        // Keyset: rows strictly older than the cursor, newest first.
        let cursor: Option<(chrono::NaiveDateTime, String)> =
            sqlx::query_as(r#"SELECT "createdAt", id FROM "Message" WHERE id = $1"#)
                .bind(before_id)
                .fetch_optional(&state.pool)
                .await?;
        match cursor {
            None => vec![],
            Some((created_at, id)) => {
                sqlx::query_as(
                    r#"SELECT * FROM "Message"
                       WHERE "channelId" = $1
                         AND ("createdAt" < $2 OR ("createdAt" = $2 AND id < $3))
                       ORDER BY "createdAt" DESC, id DESC
                       LIMIT $4"#,
                )
                .bind(channel_id)
                .bind(created_at)
                .bind(id)
                .bind(take)
                .fetch_all(&state.pool)
                .await?
            }
        }
    } else {
        sqlx::query_as(
            r#"SELECT * FROM "Message" WHERE "channelId" = $1
               ORDER BY "createdAt" DESC, id DESC LIMIT $2"#,
        )
        .bind(channel_id)
        .bind(take)
        .fetch_all(&state.pool)
        .await?
    };

    let has_more = rows.len() as i64 > limit;
    let page: Vec<MessageRow> = if has_more {
        rows.into_iter().take(limit as usize).collect()
    } else {
        rows
    };
    let next_cursor = if has_more {
        page.last().map(|m| m.id.clone())
    } else {
        None
    };
    let items = build_dtos(state, &page, viewer_id).await?;
    Ok(Page { items, next_cursor })
}

/// Discord's cap. Pins are a curated shortlist, not an archive, and an unbounded
/// list would make the pins panel a way to fetch a whole channel in one request.
pub const MAX_PINS_PER_CHANNEL: i64 = 50;

/// Pin or unpin. Returns the updated message.
///
/// The message must live in `channel_id`: without that check a pin request could
/// name any message id in the database and pin it into a channel the caller
/// happens to moderate.
pub async fn set_pinned(
    state: &AppState,
    channel_id: &str,
    message_id: &str,
    pinned: bool,
) -> AppResult<MessageRow> {
    let existing: Option<MessageRow> = sqlx::query_as(r#"SELECT * FROM "Message" WHERE id = $1"#)
        .bind(message_id)
        .fetch_optional(&state.pool)
        .await?;
    let existing = existing
        .filter(|m| m.channel_id == channel_id)
        .ok_or_else(|| AppError::NotFound("Message not found in this channel".into()))?;

    if existing.pinned == pinned {
        return Ok(existing);
    }
    if pinned {
        let count: i64 = sqlx::query_scalar(
            r#"SELECT COUNT(*) FROM "Message" WHERE "channelId" = $1 AND pinned"#,
        )
        .bind(channel_id)
        .fetch_one(&state.pool)
        .await?;
        if count >= MAX_PINS_PER_CHANNEL {
            return Err(AppError::BadRequest(format!(
                "This channel already has the maximum of {MAX_PINS_PER_CHANNEL} pinned messages"
            )));
        }
    }

    Ok(sqlx::query_as::<_, MessageRow>(
        r#"UPDATE "Message" SET pinned = $1, "pinnedAt" = $2 WHERE id = $3 RETURNING *"#,
    )
    .bind(pinned)
    .bind(if pinned {
        Some(chrono::Utc::now().naive_utc())
    } else {
        None
    })
    .bind(message_id)
    .fetch_one(&state.pool)
    .await?)
}

/// Pinned messages, most recently pinned first. Unpaginated by design: the list
/// is capped at MAX_PINS_PER_CHANNEL.
pub async fn list_pins(
    state: &AppState,
    channel_id: &str,
    viewer_id: &str,
) -> AppResult<Vec<MessageDto>> {
    let rows: Vec<MessageRow> = sqlx::query_as(
        r#"SELECT * FROM "Message" WHERE "channelId" = $1 AND pinned
           ORDER BY "pinnedAt" DESC NULLS LAST, id DESC"#,
    )
    .bind(channel_id)
    .fetch_all(&state.pool)
    .await?;
    build_dtos(state, &rows, viewer_id).await
}

/// Full-text-ish search across the text channels of a server the viewer can
/// see. Offset-paginated; `next_cursor` carries the next offset when more rows
/// remain. `channel_id`/`author_id` narrow the scope when provided.
pub async fn search_messages(
    state: &AppState,
    server_id: &str,
    viewer_id: &str,
    query: &str,
    channel_id: Option<&str>,
    author_id: Option<&str>,
    limit: i64,
    offset: i64,
) -> AppResult<Page<MessageDto>> {
    use crate::permissions::{has_permission, VIEW_CHANNEL};
    use crate::services::membership;

    // Channels in this server the viewer is allowed to read.
    let text_channels: Vec<(String,)> =
        sqlx::query_as(r#"SELECT id FROM "Channel" WHERE "serverId" = $1 AND type = 'text'"#)
            .bind(server_id)
            .fetch_all(&state.pool)
            .await?;

    let mut accessible: Vec<String> = Vec::new();
    for (cid,) in text_channels {
        if let Some(perms) = membership::channel_permissions(state, &cid, viewer_id).await? {
            if has_permission(perms, VIEW_CHANNEL) {
                accessible.push(cid);
            }
        }
    }
    // Narrow to a single channel when requested (only if the viewer can see it).
    if let Some(only) = channel_id {
        accessible.retain(|c| c == only);
    }
    if accessible.is_empty() {
        return Ok(Page {
            items: vec![],
            next_cursor: None,
        });
    }

    // Escape LIKE wildcards so user input matches literally.
    let escaped = query
        .replace('\\', "\\\\")
        .replace('%', "\\%")
        .replace('_', "\\_");
    let pattern = format!("%{escaped}%");
    let take = limit + 1;

    let rows: Vec<MessageRow> = sqlx::query_as(
        r#"SELECT * FROM "Message"
           WHERE "channelId" = ANY($1)
             AND content ILIKE $2 ESCAPE '\'
             AND ($3::text IS NULL OR "authorId" = $3)
           ORDER BY "createdAt" DESC, id DESC
           LIMIT $4 OFFSET $5"#,
    )
    .bind(&accessible)
    .bind(&pattern)
    .bind(author_id)
    .bind(take)
    .bind(offset)
    .fetch_all(&state.pool)
    .await?;

    let has_more = rows.len() as i64 > limit;
    let page: Vec<MessageRow> = if has_more {
        rows.into_iter().take(limit as usize).collect()
    } else {
        rows
    };
    let next_cursor = if has_more {
        Some((offset + limit).to_string())
    } else {
        None
    };
    let items = build_dtos(state, &page, viewer_id).await?;
    Ok(Page { items, next_cursor })
}

pub async fn add_reaction(
    state: &AppState,
    message_id: &str,
    user_id: &str,
    emoji: &str,
) -> AppResult<(String, bool)> {
    let channel_id: Option<String> =
        sqlx::query_scalar(r#"SELECT "channelId" FROM "Message" WHERE id = $1"#)
            .bind(message_id)
            .fetch_optional(&state.pool)
            .await?;
    let channel_id = channel_id.ok_or_else(|| AppError::Permission("Message not found".into()))?;

    let result = sqlx::query(
        r#"INSERT INTO "Reaction" (id, "messageId", "userId", emoji)
           VALUES ($1, $2, $3, $4)
           ON CONFLICT ("messageId", "userId", emoji) DO NOTHING"#,
    )
    .bind(cuid())
    .bind(message_id)
    .bind(user_id)
    .bind(emoji)
    .execute(&state.pool)
    .await?;
    Ok((channel_id, result.rows_affected() > 0))
}

pub async fn remove_reaction(
    state: &AppState,
    message_id: &str,
    user_id: &str,
    emoji: &str,
) -> AppResult<(String, bool)> {
    let channel_id: Option<String> =
        sqlx::query_scalar(r#"SELECT "channelId" FROM "Message" WHERE id = $1"#)
            .bind(message_id)
            .fetch_optional(&state.pool)
            .await?;
    let channel_id = channel_id.ok_or_else(|| AppError::Permission("Message not found".into()))?;

    let result = sqlx::query(
        r#"DELETE FROM "Reaction" WHERE "messageId" = $1 AND "userId" = $2 AND emoji = $3"#,
    )
    .bind(message_id)
    .bind(user_id)
    .bind(emoji)
    .execute(&state.pool)
    .await?;
    Ok((channel_id, result.rows_affected() > 0))
}
