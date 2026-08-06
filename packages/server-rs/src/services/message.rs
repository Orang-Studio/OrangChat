//! Message + reaction persistence and history. Mirrors message-service.ts.

use std::collections::{HashMap, HashSet};

use base64::Engine;
use chrono::{NaiveDateTime, Utc};
use serde_json::Value as Json;

use crate::dto::{to_message, MessageDto, Page};
use crate::error::{AppError, AppResult};
use crate::http::attachments::MAX_PER_MESSAGE;
use crate::ids::cuid;
use crate::models::{ChannelRow, EmojiRow, MessageRow, ReactionRow, UserRow};
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
    let emoji_ids: Vec<String> = rows
        .iter()
        .flat_map(|m| custom_emoji_ids(&m.content))
        .collect::<HashSet<_>>()
        .into_iter()
        .collect();

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

    let emojis: Vec<EmojiRow> = if emoji_ids.is_empty() {
        vec![]
    } else {
        sqlx::query_as(
            r#"SELECT id, "serverId", name, url, animated, "creatorId", "createdAt"
               FROM "Emoji" WHERE id = ANY($1)"#,
        )
        .bind(&emoji_ids)
        .fetch_all(&state.pool)
        .await?
    };
    let emoji_map: HashMap<&str, &EmojiRow> = emojis
        .iter()
        .map(|emoji| (emoji.id.as_str(), emoji))
        .collect();

    let mut out = Vec::with_capacity(rows.len());
    for m in rows {
        let author = author_map
            .get(m.author_id.as_str())
            .ok_or_else(|| AppError::Internal("message author missing".into()))?;
        let empty = Vec::new();
        let rs = reaction_map.get(&m.id).unwrap_or(&empty);
        let message_emojis: Vec<EmojiRow> = custom_emoji_ids(&m.content)
            .into_iter()
            .filter_map(|id| emoji_map.get(id.as_str()).map(|emoji| (*emoji).clone()))
            .collect();
        out.push(to_message(m, author, rs, &message_emojis, viewer_id));
    }
    Ok(out)
}

/// Pull durable ids out of well-formed `<:name:id>` / `<a:name:id>` tokens.
/// Parsing here mirrors the clients closely enough to avoid exposing unrelated
/// emoji while still making every message independently renderable.
fn custom_emoji_ids(content: &str) -> Vec<String> {
    let bytes = content.as_bytes();
    let mut ids = Vec::new();
    let mut cursor = 0;

    while cursor < bytes.len() {
        let Some(relative_start) = content[cursor..].find('<') else {
            break;
        };
        let start = cursor + relative_start;
        let Some(relative_end) = content[start..].find('>') else {
            break;
        };
        let end = start + relative_end;
        let token = &content[start + 1..end];
        let token = token
            .strip_prefix("a:")
            .or_else(|| token.strip_prefix("A:"))
            .or_else(|| token.strip_prefix(':'));

        if let Some(token) = token {
            if let Some((name, id)) = token.rsplit_once(':') {
                let valid_name = (2..=32).contains(&name.len())
                    && name
                        .bytes()
                        .all(|c| c.is_ascii_alphanumeric() || c == b'_' || c == b'-');
                let valid_id = !id.is_empty() && id.bytes().all(|c| c.is_ascii_alphanumeric());
                if valid_name && valid_id && !ids.iter().any(|existing| existing == id) {
                    ids.push(id.to_string());
                }
            }
        }
        cursor = end + 1;
    }

    ids
}

#[cfg(test)]
mod custom_emoji_tests {
    use super::custom_emoji_ids;

    #[test]
    fn extracts_static_and_animated_custom_emoji() {
        assert_eq!(
            custom_emoji_ids("hi <:orange:abc123> <a:dance:def456> <A:LOUD:GHI789>"),
            vec!["abc123", "def456", "GHI789"]
        );
    }

    #[test]
    fn ignores_malformed_tokens_and_deduplicates_ids() {
        assert_eq!(
            custom_emoji_ids("<:x:nope> <:ok:abc123> <:old_name:abc123> <@user>"),
            vec!["abc123"]
        );
    }
}

async fn load_one(state: &AppState, message_id: &str, viewer_id: &str) -> AppResult<MessageDto> {
    let row: MessageRow = sqlx::query_as(r#"SELECT * FROM "Message" WHERE id = $1"#)
        .bind(message_id)
        .fetch_one(&state.pool)
        .await?;
    let mut dtos = build_dtos(state, std::slice::from_ref(&row), viewer_id).await?;
    Ok(dtos.remove(0))
}

/// The newest message is included with the DM list so navigation can show a
/// preview without opening every conversation. Encrypted rows are returned as
/// envelopes and opened by the client that owns the conversation key.
pub async fn latest_for_channel(
    state: &AppState,
    channel_id: &str,
    viewer_id: &str,
) -> AppResult<Option<MessageDto>> {
    let row: Option<MessageRow> = sqlx::query_as(
        r#"SELECT * FROM "Message"
           WHERE "channelId" = $1
           ORDER BY "createdAt" DESC, id DESC
           LIMIT 1"#,
    )
    .bind(channel_id)
    .fetch_optional(&state.pool)
    .await?;

    match row {
        Some(row) => Ok(Some(build_dtos(state, &[row], viewer_id).await?.remove(0))),
        None => Ok(None),
    }
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
    duration: Option<f64>,
    #[sqlx(rename = "thumbnailUrl")]
    thumbnail_url: Option<String>,
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
/// for that didn't come back is an error rather than a silent omission -
/// quietly dropping an attachment would send a message the author didn't write.
/// `spoiler_ids` is the author's own presentation choice, so it's taken at face
/// value; ids in it that aren't being attached are simply ignored.
async fn claim_attachments(
    state: &AppState,
    author_id: &str,
    attachment_ids: &[String],
    spoiler_ids: &[String],
    sealed: bool,
) -> AppResult<Json> {
    if attachment_ids.is_empty() {
        return Ok(Json::Array(vec![]));
    }
    // Each logical encrypted image may carry one separately sealed thumbnail.
    // The user-facing limit remains ten files; supporting blobs are not files.
    let limit = if sealed {
        MAX_PER_MESSAGE * 2
    } else {
        MAX_PER_MESSAGE
    };
    if attachment_ids.len() > limit {
        return Err(AppError::BadRequest(format!(
            "A message can carry at most {MAX_PER_MESSAGE} attachments"
        )));
    }

    let rows: Vec<PendingAttachmentRow> = sqlx::query_as(
        r#"DELETE FROM "PendingAttachment"
            WHERE id = ANY($1) AND "uploaderId" = $2
        RETURNING id, url, filename, "contentType", size, width, height, duration, "thumbnailUrl", storage, flagged, "expiresAt""#,
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
                "duration": r.duration,
                "thumbnailUrl": r.thumbnail_url,
                "storage": r.storage,
                "flagged": r.flagged,
                "spoiler": spoilers.contains(r.id.as_str()),
                "expiresAt": iso_opt(r.expires_at),
            })
        })
        .collect();

    Ok(Json::Array(out))
}

pub struct Sealed {
    pub ciphertext: Vec<u8>,
    pub epoch: i32,
    pub version: i32,
}

const MAX_CIPHERTEXT_BYTES: usize = 256 * 1024;
const ENVELOPE_VERSION: i32 = 1;

/// Longest plaintext message body accepted, in bytes.
///
/// `Message.content` is a Postgres `text`, so nothing below this layer bounds
/// it. The ceiling is generous next to the ~4k a client will let you type, but
/// it stops a scripted client from storing a row that every member of the
/// channel then has to download on every history fetch. Bytes rather than chars
/// because it is the transfer size that matters here.
const MAX_CONTENT_BYTES: usize = 64 * 1024;

/// Rejects an over-long plaintext body. Encrypted messages are bounded by
/// [`MAX_CIPHERTEXT_BYTES`] instead, and carry an empty `content`.
fn check_content_len(content: &str) -> AppResult<()> {
    if content.len() > MAX_CONTENT_BYTES {
        return Err(AppError::BadRequest(format!(
            "Message is too long (limit {} bytes)",
            MAX_CONTENT_BYTES
        )));
    }
    Ok(())
}

/// Decides whether this send is encrypted, and refuses the two shapes that
/// would quietly break the promise: plaintext into a channel that has latched
/// on, and ciphertext into a channel where it means nothing.
pub fn parse_sealed(
    channel: &ChannelRow,
    ciphertext: Option<&str>,
    enc_epoch: Option<i32>,
    enc_version: Option<i32>,
) -> AppResult<Option<Sealed>> {
    let Some(raw) = ciphertext.filter(|c| !c.is_empty()) else {
        if channel.e2ee {
            return Err(AppError::Permission(
                "This conversation is end-to-end encrypted; plaintext cannot be sent to it".into(),
            ));
        }
        return Ok(None);
    };

    if !matches!(channel.channel_type.as_str(), "dm" | "group_dm") {
        return Err(AppError::BadRequest(
            "Only direct conversations can be end-to-end encrypted".into(),
        ));
    }

    let bytes = base64::engine::general_purpose::STANDARD
        .decode(raw)
        .map_err(|_| AppError::BadRequest("ciphertext is not valid base64".into()))?;
    if bytes.is_empty() || bytes.len() > MAX_CIPHERTEXT_BYTES {
        return Err(AppError::BadRequest("ciphertext is out of range".into()));
    }

    let version = enc_version.unwrap_or(ENVELOPE_VERSION);
    if version != ENVELOPE_VERSION {
        return Err(AppError::BadRequest(
            "Unsupported message envelope version".into(),
        ));
    }

    let epoch = enc_epoch.ok_or_else(|| AppError::BadRequest("encEpoch is required".into()))?;
    if epoch < 1 || epoch > channel.epoch_number {
        return Err(AppError::BadRequest(
            "encEpoch does not name a minted epoch of this conversation".into(),
        ));
    }

    Ok(Some(Sealed {
        ciphertext: bytes,
        epoch,
        version,
    }))
}

#[allow(clippy::too_many_arguments)]
pub async fn send_message(
    state: &AppState,
    channel_id: &str,
    author_id: &str,
    content: &str,
    reply_to_id: Option<&str>,
    attachment_ids: &[String],
    spoiler_ids: &[String],
    sealed: Option<&Sealed>,
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
    // message with neither is nothing at all. An encrypted message carries its
    // body in the envelope, so `content` being empty there says nothing.
    if sealed.is_none() && content.trim().is_empty() && attachment_ids.is_empty() {
        return Err(AppError::BadRequest("Message is empty".into()));
    }
    check_content_len(content)?;

    let attachments = claim_attachments(
        state,
        author_id,
        attachment_ids,
        spoiler_ids,
        sealed.is_some(),
    )
    .await?;

    // Encrypted rows keep `content` as "" so every existing plaintext query and
    // DTO path stays valid and simply finds nothing.
    let stored_content = if sealed.is_some() { "" } else { content };

    let id = cuid();
    sqlx::query(
        r#"INSERT INTO "Message"
           (id, "channelId", "authorId", content, "replyToId", attachments,
            ciphertext, "encEpoch", "encVersion")
           VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9)"#,
    )
    .bind(&id)
    .bind(channel_id)
    .bind(author_id)
    .bind(stored_content)
    .bind(reply_to_id)
    .bind(&attachments)
    .bind(sealed.map(|s| s.ciphertext.as_slice()))
    .bind(sealed.map(|s| s.epoch))
    .bind(sealed.map(|s| s.version))
    .execute(&state.pool)
    .await?;

    // Bump the channel so DM/recent-activity ordering can use updatedAt.
    sqlx::query(r#"UPDATE "Channel" SET "updatedAt" = now() WHERE id = $1"#)
        .bind(channel_id)
        .execute(&state.pool)
        .await?;

    // A DM someone closed comes back the moment it has something new in it -
    // otherwise the message is delivered to a conversation they cannot see.
    let _ = super::dm::reopen_for_new_message(state, channel_id).await;

    // Sending is what a draft was building toward, so drop it. best-effort.
    let _ = super::draft::clear(state, author_id, channel_id).await;

    load_one(state, &id, author_id).await
}

pub async fn edit_message(
    state: &AppState,
    message_id: &str,
    user_id: &str,
    content: &str,
    sealed: Option<&Sealed>,
) -> AppResult<MessageDto> {
    check_content_len(content)?;

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

    let stored_content = if sealed.is_some() { "" } else { content };
    sqlx::query(
        r#"UPDATE "Message"
           SET content = $1, ciphertext = $2, "encEpoch" = $3, "encVersion" = $4,
               "editedAt" = now()
           WHERE id = $5"#,
    )
    .bind(stored_content)
    .bind(sealed.map(|s| s.ciphertext.as_slice()))
    .bind(sealed.map(|s| s.epoch))
    .bind(sealed.map(|s| s.version))
    .bind(message_id)
    .execute(&state.pool)
    .await?;

    load_one(state, message_id, user_id).await
}

/// Delete a message. Returns the channel it lived in.
///
/// The message must live in `channel_id`: `can_manage` is decided against that
/// channel's server, so without the check a moderator of any one server could
/// name any message id in the database and delete it.
pub async fn delete_message(
    state: &AppState,
    channel_id: &str,
    message_id: &str,
    user_id: &str,
    can_manage: bool,
) -> AppResult<String> {
    let existing: Option<(String, String)> =
        sqlx::query_as(r#"SELECT "authorId", "channelId" FROM "Message" WHERE id = $1"#)
            .bind(message_id)
            .fetch_optional(&state.pool)
            .await?;
    let (author_id, channel_id) = existing
        .filter(|(_, ch)| ch == channel_id)
        .ok_or_else(|| AppError::NotFound("Message not found in this channel".into()))?;
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
#[allow(clippy::too_many_arguments)]
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
    use crate::services::membership;

    // Channels in this server the viewer is allowed to read.
    let mut accessible = membership::viewable_text_channels(state, server_id, viewer_id).await?;
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

    let take = limit + 1;

    // `websearch_to_tsquery` accepts anything a user can type - it never raises
    // on stray operators the way `to_tsquery` does - but it yields an empty
    // query for input that is all stopwords or punctuation ("the", "?!"). That
    // would match nothing at all, so those fall back to the old substring scan
    // rather than silently returning no results.
    let tsquery_is_empty: bool =
        sqlx::query_scalar(r#"SELECT websearch_to_tsquery('english', $1)::text = ''"#)
            .bind(query)
            .fetch_one(&state.pool)
            .await?;

    // The two branches are kept apart rather than OR'd into one statement: an
    // OR across the two predicates makes the planner drop the GIN index and go
    // back to scanning every message in the server.
    let rows: Vec<MessageRow> = if tsquery_is_empty {
        // Escape LIKE wildcards so user input matches literally.
        let escaped = query
            .replace('\\', "\\\\")
            .replace('%', "\\%")
            .replace('_', "\\_");
        let pattern = format!("%{escaped}%");
        sqlx::query_as(
            r#"SELECT * FROM "Message"
               WHERE "channelId" = ANY($1)
                 AND ciphertext IS NULL
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
        .await?
    } else {
        // "searchVector" is a stored generated column (see the
        // 20260730120000_message_search_fts migration), so this reads a
        // materialised tsvector whether the planner uses Message_searchVector_idx
        // or reaches the rows by channel and filters.
        sqlx::query_as(
            r#"SELECT * FROM "Message"
               WHERE "channelId" = ANY($1)
                 AND ciphertext IS NULL
                 AND "searchVector" @@ websearch_to_tsquery('english', $2)
                 AND ($3::text IS NULL OR "authorId" = $3)
               ORDER BY "createdAt" DESC, id DESC
               LIMIT $4 OFFSET $5"#,
        )
        .bind(&accessible)
        .bind(query)
        .bind(author_id)
        .bind(take)
        .bind(offset)
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
        Some((offset + limit).to_string())
    } else {
        None
    };
    let items = build_dtos(state, &page, viewer_id).await?;
    Ok(Page { items, next_cursor })
}

/// React to a message. The message must live in `channel_id` - the caller's
/// access is checked against that channel, so an unbound message id would let a
/// reaction land on a message in any channel, including DMs they aren't in.
pub async fn add_reaction(
    state: &AppState,
    channel_id: &str,
    message_id: &str,
    user_id: &str,
    emoji: &str,
) -> AppResult<(String, bool)> {
    let found: Option<String> =
        sqlx::query_scalar(r#"SELECT "channelId" FROM "Message" WHERE id = $1"#)
            .bind(message_id)
            .fetch_optional(&state.pool)
            .await?;
    let channel_id = found
        .filter(|ch| ch == channel_id)
        .ok_or_else(|| AppError::NotFound("Message not found in this channel".into()))?;

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
    channel_id: &str,
    message_id: &str,
    user_id: &str,
    emoji: &str,
) -> AppResult<(String, bool)> {
    let found: Option<String> =
        sqlx::query_scalar(r#"SELECT "channelId" FROM "Message" WHERE id = $1"#)
            .bind(message_id)
            .fetch_optional(&state.pool)
            .await?;
    let channel_id = found
        .filter(|ch| ch == channel_id)
        .ok_or_else(|| AppError::NotFound("Message not found in this channel".into()))?;

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
