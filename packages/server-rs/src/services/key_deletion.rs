
use chrono::{Duration, NaiveDateTime, Utc};
use sqlx::Row;

use crate::auth_security::{random_token, token_hash};
use crate::error::{AppError, AppResult};
use crate::ids::cuid;
use crate::services::{email, push};
use crate::state::AppState;

pub const DELAY_WITH_2FA: i64 = 3;
pub const DELAY_WITHOUT_2FA: i64 = 24;

pub const PROOF_MAX_AGE_SECONDS: i64 = 300;

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

async fn erase(state: &AppState, user_id: &str) -> AppResult<()> {
    let rooms = crate::services::e2ee::peer_rooms(state, user_id)
        .await
        .unwrap_or_default();

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

    let payload = serde_json::json!({ "userId": user_id, "deviceId": null });
    for room in rooms {
        let _ = state.io().to(room).emit("e2ee:device:revoked", &payload);
    }
    Ok(())
}

pub async fn erase_now(state: &AppState, user_id: &str, email_address: &str) -> AppResult<()> {
    erase(state, user_id).await?;

    sqlx::query(
        r#"UPDATE "KeyDeletionRequest" SET "executedAt" = now()
           WHERE "userId" = $1 AND "cancelledAt" IS NULL
             AND "abortedAt" IS NULL AND "executedAt" IS NULL"#,
    )
    .bind(user_id)
    .execute(&state.pool)
    .await?;

    if let Err(e) = email::send_key_deletion_done(&state.config, email_address).await {
        tracing::warn!(user = %user_id, error = %e, "could not send key erasure notice");
    }
    push::notify_security(
        state,
        user_id,
        "key-deletion-done",
        "Your encryption keys were erased",
        "A device holding your keys asked for this. Sign in again to set up a new encryption identity.",
        "/?keyErasure=1",
    )
    .await;

    Ok(())
}

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
