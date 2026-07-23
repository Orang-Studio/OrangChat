//! Friendships & friend requests. A single `Friendship` row models both a
//! pending request (status="pending", requester → addressee) and an accepted
//! friendship (status="accepted"). No TS equivalent - new for the Rust backend.

use crate::error::{AppError, AppResult};
use crate::ids::cuid;
use crate::models::{FriendJoinRow, UserRow};
use crate::state::AppState;

/// The other user in a friendship, joined against the viewer.
const OTHER_USER_JOIN: &str =
    r#"u.id = CASE WHEN f."requesterId" = $1 THEN f."addresseeId" ELSE f."requesterId" END"#;

pub async fn list_friends(state: &AppState, user_id: &str) -> AppResult<Vec<FriendJoinRow>> {
    let rows = sqlx::query_as::<_, FriendJoinRow>(&format!(
        r#"SELECT f.id AS friendship_id, f."createdAt" AS friendship_created_at, f.status, u.*
           FROM "Friendship" f
           JOIN "User" u ON {OTHER_USER_JOIN}
           WHERE f.status = 'accepted' AND $1 IN (f."requesterId", f."addresseeId")
           ORDER BY u."displayName" ASC"#
    ))
    .bind(user_id)
    .fetch_all(&state.pool)
    .await?;
    Ok(rows)
}

pub async fn list_incoming(state: &AppState, user_id: &str) -> AppResult<Vec<FriendJoinRow>> {
    let rows = sqlx::query_as::<_, FriendJoinRow>(
        r#"SELECT f.id AS friendship_id, f."createdAt" AS friendship_created_at, f.status, u.*
           FROM "Friendship" f
           JOIN "User" u ON u.id = f."requesterId"
           WHERE f.status = 'pending' AND f."addresseeId" = $1
           ORDER BY f."createdAt" DESC"#,
    )
    .bind(user_id)
    .fetch_all(&state.pool)
    .await?;
    Ok(rows)
}

pub async fn list_outgoing(state: &AppState, user_id: &str) -> AppResult<Vec<FriendJoinRow>> {
    let rows = sqlx::query_as::<_, FriendJoinRow>(
        r#"SELECT f.id AS friendship_id, f."createdAt" AS friendship_created_at, f.status, u.*
           FROM "Friendship" f
           JOIN "User" u ON u.id = f."addresseeId"
           WHERE f.status = 'pending' AND f."requesterId" = $1
           ORDER BY f."createdAt" DESC"#,
    )
    .bind(user_id)
    .fetch_all(&state.pool)
    .await?;
    Ok(rows)
}

/// Result of sending a request: the resulting row (as seen by the sender) and
/// whether it was auto-accepted (i.e. it matched an existing inbound request).
pub struct SendOutcome {
    pub row: FriendJoinRow,
    pub target: UserRow,
    pub accepted: bool,
}

/// Fetch a single friendship joined with the other party, from `user_id`'s view.
async fn fetch_joined(
    state: &AppState,
    user_id: &str,
    friendship_id: &str,
) -> AppResult<Option<FriendJoinRow>> {
    let row = sqlx::query_as::<_, FriendJoinRow>(&format!(
        r#"SELECT f.id AS friendship_id, f."createdAt" AS friendship_created_at, f.status, u.*
           FROM "Friendship" f
           JOIN "User" u ON {OTHER_USER_JOIN}
           WHERE f.id = $2"#
    ))
    .bind(user_id)
    .bind(friendship_id)
    .fetch_optional(&state.pool)
    .await?;
    Ok(row)
}

pub async fn are_friends(state: &AppState, a: &str, b: &str) -> AppResult<bool> {
    let found: Option<String> = sqlx::query_scalar(
        r#"SELECT id FROM "Friendship"
           WHERE status = 'accepted'
             AND (("requesterId" = $1 AND "addresseeId" = $2)
               OR ("requesterId" = $2 AND "addresseeId" = $1))
           LIMIT 1"#,
    )
    .bind(a)
    .bind(b)
    .fetch_optional(&state.pool)
    .await?;
    Ok(found.is_some())
}

pub async fn have_mutual_friend(state: &AppState, a: &str, b: &str) -> AppResult<bool> {
    let found: Option<String> = sqlx::query_scalar(
        r#"SELECT fa.id FROM "Friendship" fa
           JOIN "Friendship" fb ON
             CASE WHEN fb."requesterId" = $2 THEN fb."addresseeId" ELSE fb."requesterId" END =
             CASE WHEN fa."requesterId" = $1 THEN fa."addresseeId" ELSE fa."requesterId" END
           WHERE fa.status = 'accepted' AND fb.status = 'accepted'
             AND $1 IN (fa."requesterId", fa."addresseeId")
             AND $2 IN (fb."requesterId", fb."addresseeId")
           LIMIT 1"#,
    )
    .bind(a)
    .bind(b)
    .fetch_optional(&state.pool)
    .await?;
    Ok(found.is_some())
}

async fn can_request(state: &AppState, requester_id: &str, target: &UserRow) -> AppResult<bool> {
    // Same rule as can_dm: a locked-down account accepts nothing new, whatever
    // its own privacy setting says.
    if target.lockdown_at.is_some() {
        return Ok(false);
    }
    match target.friend_request_privacy.as_str() {
        "none" => Ok(false),
        "mutual" => have_mutual_friend(state, requester_id, &target.id).await,
        _ => Ok(true),
    }
}

pub async fn send_request(
    state: &AppState,
    requester_id: &str,
    target_username: &str,
) -> AppResult<SendOutcome> {
    // Usernames are unique case-insensitively (see the lower(username) index), so
    // adding a friend must not care how they typed it.
    let target: Option<UserRow> =
        sqlx::query_as(r#"SELECT * FROM "User" WHERE lower(username) = lower($1)"#)
            .bind(target_username)
            .fetch_optional(&state.pool)
            .await?;
    let target = target.ok_or_else(|| AppError::NotFound("User not found".into()))?;
    if target.id == requester_id {
        return Err(AppError::BadRequest("You cannot add yourself".into()));
    }

    // Any existing edge between the two, in either direction.
    let existing: Option<(String, String, String)> = sqlx::query_as(
        r#"SELECT id, status, "requesterId" FROM "Friendship"
           WHERE ("requesterId" = $1 AND "addresseeId" = $2)
              OR ("requesterId" = $2 AND "addresseeId" = $1)
           LIMIT 1"#,
    )
    .bind(requester_id)
    .bind(&target.id)
    .fetch_optional(&state.pool)
    .await?;

    let friendship_id = match existing {
        Some((id, status, req_id)) => {
            if status == "accepted" {
                return Err(AppError::Conflict("Already friends".into()));
            }
            // status == pending
            if req_id == requester_id {
                return Err(AppError::Conflict("Friend request already sent".into()));
            }
            // Inbound pending request from the target → accept it.
            sqlx::query(
                r#"UPDATE "Friendship" SET status = 'accepted', "updatedAt" = now() WHERE id = $1"#,
            )
            .bind(&id)
            .execute(&state.pool)
            .await?;
            let row = fetch_joined(state, requester_id, &id)
                .await?
                .ok_or_else(|| AppError::Internal("Friendship vanished".into()))?;
            return Ok(SendOutcome {
                row,
                target,
                accepted: true,
            });
        }
        None => {
            // Only a brand-new request is gated: accepting an inbound one above
            // is the target's own choice, whatever their setting now says.
            if !can_request(state, requester_id, &target).await? {
                return Err(AppError::Permission(
                    "This person isn't accepting friend requests".into(),
                ));
            }
            let id = cuid();
            sqlx::query(
                r#"INSERT INTO "Friendship" (id, "requesterId", "addresseeId", status, "updatedAt")
                   VALUES ($1, $2, $3, 'pending', now())"#,
            )
            .bind(&id)
            .bind(requester_id)
            .bind(&target.id)
            .execute(&state.pool)
            .await?;
            id
        }
    };

    let row = fetch_joined(state, requester_id, &friendship_id)
        .await?
        .ok_or_else(|| AppError::Internal("Friendship vanished".into()))?;
    Ok(SendOutcome {
        row,
        target,
        accepted: false,
    })
}

/// Accept a pending request addressed to `user_id`. Returns the row from the
/// accepter's view plus the requester's id (to notify them).
pub async fn accept_request(
    state: &AppState,
    user_id: &str,
    friendship_id: &str,
) -> AppResult<(FriendJoinRow, String)> {
    let requester_id: Option<String> = sqlx::query_scalar(
        r#"UPDATE "Friendship" SET status = 'accepted', "updatedAt" = now()
           WHERE id = $1 AND "addresseeId" = $2 AND status = 'pending'
           RETURNING "requesterId""#,
    )
    .bind(friendship_id)
    .bind(user_id)
    .fetch_optional(&state.pool)
    .await?;
    let requester_id =
        requester_id.ok_or_else(|| AppError::NotFound("Request not found".into()))?;
    let row = fetch_joined(state, user_id, friendship_id)
        .await?
        .ok_or_else(|| AppError::Internal("Friendship vanished".into()))?;
    Ok((row, requester_id))
}

/// Decline (addressee) or cancel (requester) a pending request. Returns the id
/// of the *other* party so they can be notified the request went away.
pub async fn delete_request(
    state: &AppState,
    user_id: &str,
    friendship_id: &str,
) -> AppResult<String> {
    let other: Option<(String, String)> = sqlx::query_as(
        r#"DELETE FROM "Friendship"
           WHERE id = $1 AND status = 'pending' AND $2 IN ("requesterId", "addresseeId")
           RETURNING "requesterId", "addresseeId""#,
    )
    .bind(friendship_id)
    .bind(user_id)
    .fetch_optional(&state.pool)
    .await?;
    let (req, addr) = other.ok_or_else(|| AppError::NotFound("Request not found".into()))?;
    Ok(if req == user_id { addr } else { req })
}

/// Remove an accepted friend by their user id. Returns the removed friend's id.
pub async fn remove_friend(state: &AppState, user_id: &str, other_id: &str) -> AppResult<String> {
    let deleted: Option<String> = sqlx::query_scalar(
        r#"DELETE FROM "Friendship"
           WHERE status = 'accepted'
             AND (("requesterId" = $1 AND "addresseeId" = $2)
               OR ("requesterId" = $2 AND "addresseeId" = $1))
           RETURNING id"#,
    )
    .bind(user_id)
    .bind(other_id)
    .fetch_optional(&state.pool)
    .await?;
    deleted.ok_or_else(|| AppError::NotFound("Not friends".into()))?;
    Ok(other_id.to_string())
}
