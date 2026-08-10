//! Per-user, per-channel read state: what the user has seen and how many times
//! they've been @mentioned in a channel they haven't opened. Drives unread dots
//! and mention badges. No Prisma equivalent - new for the unread feature.

use sqlx::FromRow;

use crate::error::AppResult;
use crate::models::ChannelRow;
use crate::state::AppState;

/// Explicit users mentioned by id (`<@id>`) or handle (`@username`), plus
/// whether `@everyone`/`@here` was used.
pub struct ParsedMentions {
    pub user_ids: Vec<String>,
    /// Lowercased handles; usernames are stored lowercase-insensitively.
    pub usernames: Vec<String>,
    pub everyone: bool,
}

/// Extract mention targets and detect `@everyone`/`@here`. Hand-rolled to avoid
/// a regex dependency.
///
/// Both encodings are accepted. Clients now write plain `@username` so the raw
/// message text stays readable, but `<@id>` predates that and still sits in
/// every older message, so it keeps resolving.
pub fn parse_mentions(content: &str) -> ParsedMentions {
    let bytes = content.as_bytes();
    let mut user_ids: Vec<String> = Vec::new();
    let mut usernames: Vec<String> = Vec::new();
    let mut i = 0;
    while i < bytes.len() {
        if bytes[i] == b'<' && i + 1 < bytes.len() && bytes[i + 1] == b'@' {
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
        // An `@` glued to the end of a word is an email host, not a mention.
        if bytes[i] == b'@' && !(i > 0 && bytes[i - 1].is_ascii_alphanumeric()) {
            let start = i + 1;
            let mut j = start;
            while j < bytes.len()
                && (bytes[j].is_ascii_alphanumeric() || bytes[j] == b'_' || bytes[j] == b'.')
            {
                j += 1;
            }
            // A trailing dot is the full stop of a sentence, not part of the
            // handle. Only ASCII is consumed above, so these stay char bounds.
            while j > start && bytes[j - 1] == b'.' {
                j -= 1;
            }
            if j > start {
                usernames.push(content[start..j].to_lowercase());
                i = j;
                continue;
            }
        }
        i += 1;
    }
    user_ids.sort();
    user_ids.dedup();
    usernames.sort();
    usernames.dedup();

    let everyone = content.contains("@everyone") || content.contains("@here");
    ParsedMentions {
        user_ids,
        usernames,
        everyone,
    }
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

/// The (id, lowercased username) of everyone in a channel. Resolving handles
/// needs the username alongside the id, which `channel_member_ids` does not
/// carry.
async fn channel_member_handles(
    state: &AppState,
    channel: &ChannelRow,
) -> AppResult<Vec<(String, String)>> {
    let rows: Vec<(String, String)> = if let Some(server_id) = &channel.server_id {
        sqlx::query_as(
            r#"SELECT u.id, lower(u.username) FROM "ServerMember" m
               JOIN "User" u ON u.id = m."userId"
               WHERE m."serverId" = $1"#,
        )
        .bind(server_id)
        .fetch_all(&state.pool)
        .await?
    } else {
        sqlx::query_as(
            r#"SELECT u.id, lower(u.username) FROM "ChannelParticipant" p
               JOIN "User" u ON u.id = p."userId"
               WHERE p."channelId" = $1"#,
        )
        .bind(&channel.id)
        .fetch_all(&state.pool)
        .await?
    };
    Ok(rows)
}

/// Resolve who should get a mention badge for a message: `<@id>` or `@username`
/// targets that are actually in the channel, or everyone (minus the author) for
/// `@everyone`/`@here`. Returns deduped recipient ids, never including `author_id`.
pub async fn resolve_mention_recipients(
    state: &AppState,
    channel: &ChannelRow,
    author_id: &str,
    parsed: &ParsedMentions,
) -> AppResult<Vec<String>> {
    let members = channel_member_handles(state, channel).await?;
    let recipients: Vec<String> = if parsed.everyone {
        members
            .into_iter()
            .map(|(id, _)| id)
            .filter(|id| id != author_id)
            .collect()
    } else {
        members
            .into_iter()
            .filter(|(id, username)| {
                id != author_id
                    && (parsed.user_ids.contains(id) || parsed.usernames.contains(username))
            })
            .map(|(id, _)| id)
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

/// Whether one particular message is still sitting unread for a user.
///
/// The unread queries next to this one count a channel; a held-back notification
/// needs to know about the message it was raised for, because by the time it
/// comes due the channel may well have moved on. A message that no longer exists
/// counts as read: there is nothing left to notify anybody about.
pub async fn is_message_unread(
    state: &AppState,
    user_id: &str,
    message_id: &str,
) -> AppResult<bool> {
    let unread: Option<bool> = sqlx::query_scalar(
        r#"
        SELECT (
            rs."lastReadMessageId" IS NULL
            OR m."createdAt" > COALESCE(
                 (SELECT "createdAt" FROM "Message" WHERE id = rs."lastReadMessageId"),
                 to_timestamp(0)
               )
        )
        FROM "Message" m
        LEFT JOIN "ReadState" rs ON rs."channelId" = m."channelId" AND rs."userId" = $1
        WHERE m.id = $2
        "#,
    )
    .bind(user_id)
    .bind(message_id)
    .fetch_optional(&state.pool)
    .await?
    .flatten();
    Ok(unread.unwrap_or(false))
}

/// Rewind a user's read cursor so `message_id` and everything after it count as
/// unread again. The cursor lands on the message immediately before it, or is
/// cleared when there is nothing before it (the whole channel goes unread).
///
/// Mentions are left alone: the badge is a running count the client owns, and
/// re-deriving it here would double-count the ones already acknowledged.
pub async fn mark_unread(
    state: &AppState,
    user_id: &str,
    channel_id: &str,
    message_id: &str,
) -> AppResult<()> {
    // (createdAt, id) is the same ordering the history pages use, so the cursor
    // can't land on the wrong side of two messages sharing a timestamp.
    let previous: Option<String> = sqlx::query_scalar(
        r#"SELECT m.id FROM "Message" m
           WHERE m."channelId" = $1
             AND (m."createdAt", m.id) <
                 (SELECT t."createdAt", t.id FROM "Message" t WHERE t.id = $2)
           ORDER BY m."createdAt" DESC, m.id DESC LIMIT 1"#,
    )
    .bind(channel_id)
    .bind(message_id)
    .fetch_optional(&state.pool)
    .await?;

    sqlx::query(
        r#"INSERT INTO "ReadState" ("userId", "channelId", "lastReadMessageId", "updatedAt")
           VALUES ($1, $2, $3, now())
           ON CONFLICT ("userId", "channelId")
           DO UPDATE SET "lastReadMessageId" = EXCLUDED."lastReadMessageId",
                         "updatedAt" = now()"#,
    )
    .bind(user_id)
    .bind(channel_id)
    .bind(previous)
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

/// Unread state for a single channel, recomputed from the cursor. Used after a
/// mark-unread so the client (and the user's other devices) can update a badge
/// without refetching the whole unread list.
pub async fn get_channel_unread(
    state: &AppState,
    user_id: &str,
    channel_id: &str,
) -> AppResult<Unread> {
    let row: UnreadRow = sqlx::query_as(
        r#"
        SELECT c.id AS channel_id,
               c."serverId" AS server_id,
               COALESCE(rs."mentionCount", 0) AS mention_count,
               (
                   SELECT COUNT(*) FROM (
                       SELECT 1 FROM "Message" m
                       WHERE m."channelId" = c.id
                         AND m."authorId" <> $1
                         AND (
                           rs."lastReadMessageId" IS NULL
                           OR m."createdAt" > COALESCE(
                                (SELECT "createdAt" FROM "Message" WHERE id = rs."lastReadMessageId"),
                                to_timestamp(0)
                              )
                         )
                       LIMIT $3
                   ) capped
               ) AS unread_count
        FROM "Channel" c
        LEFT JOIN "ReadState" rs ON rs."channelId" = c.id AND rs."userId" = $1
        WHERE c.id = $2
        "#,
    )
    .bind(user_id)
    .bind(channel_id)
    .bind(UNREAD_COUNT_CAP)
    .fetch_one(&state.pool)
    .await?;

    Ok(Unread {
        channel_id: row.channel_id,
        server_id: row.server_id,
        unread: row.unread_count > 0,
        unread_count: row.unread_count,
        mention_count: row.mention_count,
    })
}

#[cfg(test)]
mod parse_mention_tests {
    use super::parse_mentions;

    fn names(content: &str) -> Vec<String> {
        parse_mentions(content).usernames
    }

    #[test]
    fn reads_plain_handles() {
        assert_eq!(names("hey @alice and @bob_2"), vec!["alice", "bob_2"]);
    }

    #[test]
    fn handles_are_case_insensitive() {
        assert_eq!(names("@Alice @ALICE"), vec!["alice"]);
    }

    #[test]
    fn a_sentence_final_dot_is_punctuation_not_part_of_the_handle() {
        assert_eq!(names("ask @alice."), vec!["alice"]);
        assert_eq!(names("ask @first.last."), vec!["first.last"]);
    }

    #[test]
    fn punctuation_around_a_handle_does_not_break_it() {
        assert_eq!(names("(@alice) @bob!"), vec!["alice", "bob"]);
    }

    #[test]
    fn an_email_host_is_not_a_mention() {
        assert!(names("mail me at bob@example.com").is_empty());
    }

    #[test]
    fn legacy_id_tokens_still_resolve() {
        let p = parse_mentions("hey <@ckx9f2abc> and @alice");
        assert_eq!(p.user_ids, vec!["ckx9f2abc"]);
        assert_eq!(p.usernames, vec!["alice"]);
    }

    #[test]
    fn an_id_token_is_not_also_read_as_a_handle() {
        assert!(parse_mentions("<@ckx9f2abc>").usernames.is_empty());
    }

    #[test]
    fn broadcasts_are_flagged() {
        assert!(parse_mentions("@everyone ship it").everyone);
        assert!(parse_mentions("@here ship it").everyone);
        assert!(!parse_mentions("@alice ship it").everyone);
    }

    #[test]
    fn non_ascii_text_does_not_panic_or_match() {
        assert_eq!(names("labas @alice, kaip sekasi? ąčęėįšųūž"), vec!["alice"]);
        assert!(names("ačiū").is_empty());
    }
}
