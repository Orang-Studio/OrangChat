//! Erasing an account's encryption identity when nobody can sign for it.
//!
//! Every other way out of a lost key is cryptographic: a device that still holds
//! the key signs a revocation, or signs a new device in. This is the case where
//! that is impossible - the last keyed device is gone, so no such signature will
//! ever exist again. Without an escape hatch the account is stuck forever
//! (`enroll_genesis` refuses while devices are listed, `revoke_device` refuses
//! without a signer), which is exactly the state that had operators reaching for
//! `DELETE FROM "Device"` by hand.
//!
//! The whole design is therefore about the *other* person holding the account:
//! whoever stole the password can file this request too. Three things stand in
//! their way, and none of them is a secret they can steal:
//!
//! 1. A delay, so the request is never a single irreversible click.
//! 2. Notification at request time with a cancel link, so the real owner hears
//!    about it while stopping it still means something.
//! 3. An automatic abort if any device still holding the key checks in during
//!    the wait - the account answering the question for itself.
//!
//! Only (3) was in the original sketch. It is the weakest of the three on its
//! own: presence is trivially waited out by filing while somebody sleeps.

use chrono::{Duration, NaiveDateTime, Utc};
use sqlx::Row;

use crate::auth_security::{random_token, token_hash};
use crate::error::{AppError, AppResult};
use crate::ids::cuid;
use crate::services::{email, push};
use crate::state::AppState;

/// With a TOTP code behind it the request is already something an attacker had
/// to defeat 2FA to file, so it does not need to survive a night's sleep as
/// well. Without one, a stolen password is the *only* thing between them and the
/// keys, and the wait has to be long enough that the owner is awake for part of
/// it.
pub const DELAY_WITH_2FA: i64 = 3;
pub const DELAY_WITHOUT_2FA: i64 = 24;

pub struct PendingRequest {
    pub requested_at: NaiveDateTime,
    pub execute_after: NaiveDateTime,
}

pub async fn pending(state: &AppState, user_id: &str) -> AppResult<Option<PendingRequest>> {
    let row = sqlx::query(
        r#"SELECT "requestedAt", "executeAfter" FROM "KeyDeletionRequest"
           WHERE "userId" = $1 AND "cancelledAt" IS NULL AND "abortedAt" IS NULL
             AND "executedAt" IS NULL"#,
    )
    .bind(user_id)
    .fetch_optional(&state.pool)
    .await?;

    Ok(row.map(|r| PendingRequest {
        requested_at: r.get("requestedAt"),
        execute_after: r.get("executeAfter"),
    }))
}

/// Files the request and tells every channel the account has. The warning email
/// is not a courtesy - it is the control that makes the waiting period mean
/// something - so a request that could not be announced is withdrawn rather than
/// left standing. Scheduling a silent destruction because the mail server was
/// down is the one outcome this whole module exists to prevent.
pub async fn request(
    state: &AppState,
    user_id: &str,
    email_address: &str,
    totp_enabled: bool,
    ip: Option<&str>,
) -> AppResult<PendingRequest> {
    if pending(state, user_id).await?.is_some() {
        return Err(AppError::Conflict(
            "There is already a key erasure waiting on this account".into(),
        ));
    }

    let hours = if totp_enabled {
        DELAY_WITH_2FA
    } else {
        DELAY_WITHOUT_2FA
    };
    // Naive UTC throughout: Prisma emits `timestamp without time zone`, so a
    // `DateTime<Utc>` would not decode back out of these columns at all. The
    // database runs in UTC, which is what makes `now()` comparable to this.
    let execute_after = Utc::now().naive_utc() + Duration::hours(hours);
    let token = random_token();
    let id = cuid();

    sqlx::query(
        r#"INSERT INTO "KeyDeletionRequest"
           (id, "userId", "executeAfter", "cancelTokenHash", "requestedIp")
           VALUES ($1, $2, $3, $4, $5)"#,
    )
    .bind(&id)
    .bind(user_id)
    .bind(execute_after)
    .bind(token_hash(&token))
    .bind(ip)
    .execute(&state.pool)
    .await?;

    if let Err(e) =
        email::send_key_deletion_requested(&state.config, email_address, &token, hours).await
    {
        tracing::error!(user = %user_id, error = %e, "could not warn user of key erasure request");
        sqlx::query(r#"DELETE FROM "KeyDeletionRequest" WHERE id = $1"#)
            .bind(&id)
            .execute(&state.pool)
            .await?;
        return Err(AppError::Internal(
            "We could not email you about this, so nothing was scheduled. Try again shortly."
                .into(),
        ));
    }

    push::notify_security(
        state,
        user_id,
        &format!("key-deletion:{id}"),
        "Your encryption keys are scheduled to be erased",
        &format!(
            "In {hours} hours, unless a device holding your keys opens OrangChat first. If this was not you, open OrangChat now."
        ),
        "/?keyErasure=1",
    )
    .await;

    Ok(PendingRequest {
        requested_at: Utc::now().naive_utc(),
        execute_after,
    })
}

/// Called from the emailed link and from the settings screen alike. Returns the
/// owning user so the caller can confirm what was stopped.
pub async fn cancel_by_token(state: &AppState, token: &str) -> AppResult<String> {
    let row = sqlx::query(
        r#"UPDATE "KeyDeletionRequest" SET "cancelledAt" = now()
           WHERE "cancelTokenHash" = $1 AND "cancelledAt" IS NULL
             AND "abortedAt" IS NULL AND "executedAt" IS NULL
           RETURNING "userId""#,
    )
    .bind(token_hash(token))
    .fetch_optional(&state.pool)
    .await?;

    let user_id: String = row
        .ok_or_else(|| AppError::BadRequest("This link is no longer valid".into()))?
        .get("userId");

    push::notify_security(
        state,
        &user_id,
        "key-deletion-cancelled",
        "Key erasure cancelled",
        "Your encryption keys will not be erased.",
        "/?keyErasure=1",
    )
    .await;

    Ok(user_id)
}

pub async fn cancel_for_user(state: &AppState, user_id: &str) -> AppResult<()> {
    let done = sqlx::query(
        r#"UPDATE "KeyDeletionRequest" SET "cancelledAt" = now()
           WHERE "userId" = $1 AND "cancelledAt" IS NULL
             AND "abortedAt" IS NULL AND "executedAt" IS NULL"#,
    )
    .bind(user_id)
    .execute(&state.pool)
    .await?;

    if done.rows_affected() == 0 {
        return Err(AppError::BadRequest(
            "There is no key erasure waiting on this account".into(),
        ));
    }
    Ok(())
}

/// Called the moment a device holding the key checks in, rather than leaving the
/// question to the sweep at the end of the wait.
///
/// Both orderings reach the same outcome, but only this one is honest to the
/// user: the request says any surviving device cancels it, so a device that
/// checks in and changes nothing on screen for another three hours reads as the
/// promise not being kept. It also means the "nothing was erased" mail lands
/// while the person is still holding the phone that sent it.
///
/// A no-op when nothing is pending, which is the overwhelmingly common case -
/// `/e2ee/seen` is on the startup path of every client.
pub async fn abort_on_device_seen(state: &AppState, user_id: &str) -> AppResult<()> {
    let done = sqlx::query(
        r#"UPDATE "KeyDeletionRequest" SET "abortedAt" = now()
           WHERE "userId" = $1 AND "cancelledAt" IS NULL
             AND "abortedAt" IS NULL AND "executedAt" IS NULL
           RETURNING id"#,
    )
    .bind(user_id)
    .fetch_optional(&state.pool)
    .await
    .map(|row| row.is_some());

    let aborted = match done {
        Ok(aborted) => aborted,
        Err(e) => {
            tracing::warn!(user = %user_id, error = %e, "could not abort key erasure on check-in");
            return Ok(());
        }
    };
    if !aborted {
        return Ok(());
    }

    let address: Option<String> = sqlx::query_scalar(r#"SELECT email FROM "User" WHERE id = $1"#)
        .bind(user_id)
        .fetch_optional(&state.pool)
        .await?;
    if let Some(address) = address {
        if let Err(e) = email::send_key_deletion_aborted(&state.config, &address).await {
            tracing::warn!(user = %user_id, error = %e, "could not send key erasure abort notice");
        }
    }
    push::notify_security(
        state,
        user_id,
        "key-deletion-aborted",
        "Your encryption keys were not erased",
        "A device holding your keys checked in, so the erasure was cancelled.",
        "/?keyErasure=1",
    )
    .await;

    Ok(())
}

/// The wipe itself. Both tables have to go: revoking the devices alone leaves
/// the log head in place, and `enroll_genesis` only accepts an entry at seq 0
/// with no previous hash, so the account would still be unable to start over.
/// `KeyEnvelope` follows the devices by cascade.
async fn erase(state: &AppState, user_id: &str) -> AppResult<()> {
    let mut tx = state.pool.begin().await?;
    sqlx::query(r#"DELETE FROM "DeviceLogEntry" WHERE "userId" = $1"#)
        .bind(user_id)
        .execute(&mut *tx)
        .await?;
    sqlx::query(r#"DELETE FROM "Device" WHERE "userId" = $1"#)
        .bind(user_id)
        .execute(&mut *tx)
        .await?;
    tx.commit().await?;
    Ok(())
}

/// Walks the requests whose wait is up. Runs on a timer, so it is written to
/// survive being late: a request that came due an hour ago is still handled, and
/// each one is independent of the others' failures.
pub async fn sweep(state: &AppState) -> AppResult<u64> {
    let due = sqlx::query(
        r#"SELECT k.id, k."userId", k."requestedAt", u.email
           FROM "KeyDeletionRequest" k JOIN "User" u ON u.id = k."userId"
           WHERE k."executeAfter" <= now() AND k."cancelledAt" IS NULL
             AND k."abortedAt" IS NULL AND k."executedAt" IS NULL"#,
    )
    .fetch_all(&state.pool)
    .await?;

    let mut erased = 0_u64;
    for row in due {
        let id: String = row.get("id");
        let user_id: String = row.get("userId");
        let requested_at: NaiveDateTime = row.get("requestedAt");
        let address: String = row.get("email");

        // The condition the whole feature turns on. `lastSeenAt` is only moved
        // by `/e2ee/seen`, which a device can only call once it has loaded a
        // local identity - so this is not "did somebody log in", it is "does a
        // working key still exist". Anything else here would let the attacker's
        // own session cancel the request they filed.
        let alive: i64 = sqlx::query_scalar(
            r#"SELECT count(*) FROM "Device"
               WHERE "userId" = $1 AND "revokedAt" IS NULL AND "lastSeenAt" > $2"#,
        )
        .bind(&user_id)
        .bind(requested_at)
        .fetch_one(&state.pool)
        .await?;

        if alive > 0 {
            sqlx::query(r#"UPDATE "KeyDeletionRequest" SET "abortedAt" = now() WHERE id = $1"#)
                .bind(&id)
                .execute(&state.pool)
                .await?;
            if let Err(e) = email::send_key_deletion_aborted(&state.config, &address).await {
                tracing::warn!(user = %user_id, error = %e, "could not send key erasure abort notice");
            }
            push::notify_security(
                state,
                &user_id,
                "key-deletion-aborted",
                "Your encryption keys were not erased",
                "A device holding your keys checked in, so the erasure was cancelled.",
                "/?keyErasure=1",
            )
            .await;
            continue;
        }

        erase(state, &user_id).await?;
        sqlx::query(r#"UPDATE "KeyDeletionRequest" SET "executedAt" = now() WHERE id = $1"#)
            .bind(&id)
            .execute(&state.pool)
            .await?;
        erased += 1;

        if let Err(e) = email::send_key_deletion_done(&state.config, &address).await {
            tracing::warn!(user = %user_id, error = %e, "could not send key erasure notice");
        }
        push::notify_security(
            state,
            &user_id,
            "key-deletion-done",
            "Your encryption keys were erased",
            "Sign in again to set up a new encryption identity on this device.",
            "/?keyErasure=1",
        )
        .await;
    }

    Ok(erased)
}
