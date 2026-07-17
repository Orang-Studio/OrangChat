//! Per-user, per-channel read state: what the user has seen and how many times
//! they've been @mentioned in a channel they haven't opened. Drives unread dots
//! and mention badges. No Prisma equivalent — new for the unread feature.

use sqlx::FromRow;

use crate::error::AppResult;
use crate::models::ChannelRow;
use crate::state::AppState;

/// Explicit user ids mentioned via `<@id>`, plus whether `@everyone`/`@here`
/// was used.
pub struct ParsedMentions {
    pub user_ids: Vec<String>,
    pub everyone: bool,
}

/// Extract `<@userId>` targets (cuid-shaped, `[a-z0-9]`) and detect
/// `@everyone`/`@here`. Hand-rolled to avoid a regex dependency.
pub fn parse_mentions(content: &str) -> ParsedMentions {
    let bytes = content.as_bytes();
    let mut user_ids: Vec<String> = Vec::new();
    let mut i = 0;
    while i + 1 < bytes.len() {
        if bytes[i] == b'<' && bytes[i + 1] == b'@' {
            let start = i + 2;
            let mut j = start;
            while j < bytes.len() && bytes[j].is_ascii_alphanumeric() {
                j += 1;
            }
            if j < bytes.len() && bytes[j] == b'>' && j > start {
                user_ids.push(content[start..j].to_string());
                i = j + 1;
                continue;
            }
        }
        i += 1;
    }
    user_ids.sort();
    user_ids.dedup();

    let everyone = content.contains("@everyone") || content.contains("@here");
    ParsedMentions { user_ids, everyone }
}

/// The user ids that belong to a channel (server members, or DM participants).
pub async fn channel_member_ids(state: &AppState, channel: &ChannelRow) -> AppResult<Vec<String>> {
    let rows: Vec<(String,)> = if let Some(server_id) = &channel.server_id {
        sqlx::query_as(r#"SELECT "userId" FROM "ServerMember" WHERE "serverId" = $1"#)
            .bind(server_id)
            .fetch_all(&state.pool)
            .await?
    } else {
        sqlx::query_as(r#"SELECT "userId" FROM "ChannelParticipant" WHERE "channelId" = $1"#)
            .bind(&channel.id)
            .fetch_all(&state.pool)
            .await?
    };
    Ok(rows.into_iter().map(|(id,)| id).collect())
}

/// Resolve who should get a mention badge for a message: explicit `<@id>`
/// targets that are actually in the channel, or everyone (minus the author) for
/// `@everyone`/`@here`. Returns deduped recipient ids, never including `author_id`.
pub async fn resolve_mention_recipients(
    state: &AppState,
    channel: &ChannelRow,
    author_id: &str,
    parsed: &ParsedMentions,
) -> AppResult<Vec<String>> {
    let members = channel_member_ids(state, channel).await?;
    let recipients: Vec<String> = if parsed.everyone {
        members.into_iter().filter(|id| id != author_id).collect()
    } else {
        members
            .into_iter()
            .filter(|id| id != author_id && parsed.user_ids.contains(id))
            .collect()
    };
    Ok(recipients)
}

/// Increment the mention counter for each recipient in a channel, creating a
/// read-state row if none exists yet.
pub async fn add_mentions(
    state: &AppState,
    channel_id: &str,
    recipient_ids: &[String],
) -> AppResult<()> {
    for uid in recipient_ids {
        sqlx::query(
            r#"INSERT INTO "ReadState" ("userId", "channelId", "mentionCount", "updatedAt")
               VALUES ($1, $2, 1, now())
               ON CONFLICT ("userId", "channelId")
               DO UPDATE SET "mentionCount" = "ReadState"."mentionCount" + 1,
                             "updatedAt" = now()"#,
        )
        .bind(uid)
        .bind(channel_id)
        .execute(&state.pool)
        .await?;
    }
    Ok(())
}

/// Mark a channel fully read for a user: point at the latest message and clear
/// mentions.
pub async fn mark_read(state: &AppState, user_id: &str, channel_id: &str) -> AppResult<()> {
    let latest: Option<String> = sqlx::query_scalar(
        r#"SELECT id FROM "Message" WHERE "channelId" = $1
           ORDER BY "createdAt" DESC, id DESC LIMIT 1"#,
    )
    .bind(channel_id)
    .fetch_optional(&state.pool)
    .await?;

    sqlx::query(
        r#"INSERT INTO "ReadState" ("userId", "channelId", "lastReadMessageId", "mentionCount", "updatedAt")
           VALUES ($1, $2, $3, 0, now())
           ON CONFLICT ("userId", "channelId")
           DO UPDATE SET "lastReadMessageId" = EXCLUDED."lastReadMessageId",
                         "mentionCount" = 0,
                         "updatedAt" = now()"#,
    )
    .bind(user_id)
    .bind(channel_id)
    .bind(latest)
    .execute(&state.pool)
    .await?;
    Ok(())
}

/// Unread counting stops here. Bounds the per-channel COUNT for users who have
/// never opened a busy channel; clients render the cap as "99+".
pub const UNREAD_COUNT_CAP: i64 = 100;

#[derive(FromRow)]
struct UnreadRow {
    channel_id: String,
    server_id: Option<String>,
    mention_count: i32,
    unread_count: i64,
}

/// A channel with unread activity for the user. Serializes to the wire shape
/// `{ channelId, serverId, unread, unreadCount, mentionCount }`.
pub struct Unread {
    pub channel_id: String,
    pub server_id: Option<String>,
    pub unread: bool,
    /// Unread messages from other people, saturating at [`UNREAD_COUNT_CAP`].
    pub unread_count: i64,
    pub mention_count: i32,
}

/// Every channel (server text channels the user belongs to + their DMs) that has
/// unread messages or pending mentions. Channels that are fully caught up are
/// omitted to keep the payload small.
pub async fn get_unreads(state: &AppState, user_id: &str) -> AppResult<Vec<Unread>> {
    // The count is taken over a LIMIT'd subquery so a never-opened channel with
    // years of history costs at most UNREAD_COUNT_CAP index rows, not a full scan.
    let rows: Vec<UnreadRow> = sqlx::query_as(
        r#"
        WITH accessible AS (
            SELECT c.id
            FROM "Channel" c
            WHERE c.type = 'text'
              AND c."serverId" IN (SELECT "serverId" FROM "ServerMember" WHERE "userId" = $1)
            UNION
            SELECT "channelId" FROM "ChannelParticipant" WHERE "userId" = $1
        )
        SELECT a.id AS channel_id,
               c."serverId" AS server_id,
               COALESCE(rs."mentionCount", 0) AS mention_count,
               (
                   SELECT COUNT(*) FROM (
                       SELECT 1 FROM "Message" m
                       WHERE m."channelId" = a.id
                         AND m."authorId" <> $1
                         AND (
                           rs."lastReadMessageId" IS NULL
                           OR m."createdAt" > COALESCE(
                                (SELECT "createdAt" FROM "Message" WHERE id = rs."lastReadMessageId"),
                                to_timestamp(0)
                              )
                         )
                       LIMIT $2
                   ) capped
               ) AS unread_count
        FROM accessible a
        JOIN "Channel" c ON c.id = a.id
        LEFT JOIN "ReadState" rs ON rs."channelId" = a.id AND rs."userId" = $1
        "#,
    )
    .bind(user_id)
    .bind(UNREAD_COUNT_CAP)
    .fetch_all(&state.pool)
    .await?;

    Ok(rows
        .into_iter()
        .filter(|r| r.unread_count > 0 || r.mention_count > 0)
        .map(|r| Unread {
            channel_id: r.channel_id,
            server_id: r.server_id,
            unread: r.unread_count > 0,
            unread_count: r.unread_count,
            mention_count: r.mention_count,
        })
        .collect())
}
