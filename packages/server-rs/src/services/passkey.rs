
use redis::AsyncCommands;
use serde::{Deserialize, Serialize};
use uuid::Uuid;
use webauthn_rs::prelude::{
    DiscoverableAuthentication, DiscoverableKey, Passkey, PasskeyAuthentication,
    PasskeyRegistration, PublicKeyCredential, RegisterPublicKeyCredential, Url, Webauthn,
    WebauthnBuilder,
};

use crate::error::{AppError, AppResult};
use crate::ids::cuid;
use crate::models::{PasskeyRow, UserRow};
use crate::state::AppState;

const CEREMONY_TTL_SECONDS: u64 = 300;

pub const MAX_PER_USER: i64 = 20;

fn key(token: &str) -> String {
    format!("passkey:ceremony:{token}")
}

#[derive(Serialize, Deserialize)]
enum Ceremony {
    Registration {
        user_id: String,
        state: Box<PasskeyRegistration>,
    },
    Discoverable { state: Box<DiscoverableAuthentication> },
    Known {
        user_id: String,
        state: Box<PasskeyAuthentication>,
    },
}

pub fn webauthn(state: &AppState) -> AppResult<Webauthn> {
    let origin = Url::parse(&state.config.client_origin)
        .map_err(|e| AppError::Internal(format!("passkey: CLIENT_ORIGIN is not a url: {e}")))?;
    if origin.scheme() != "https" {
        return Err(AppError::Internal(
            "passkey: CLIENT_ORIGIN must be https - passkeys require a secure origin".into(),
        ));
    }
    let rp_id = origin
        .host_str()
        .ok_or_else(|| AppError::Internal("passkey: CLIENT_ORIGIN has no host".into()))?
        .to_string();
    let mut builder = WebauthnBuilder::new(&rp_id, &origin)
        .map_err(|e| AppError::Internal(format!("passkey: {e}")))?
        .rp_name("OrangChat");
    for android in &state.config.android_origins {
        let parsed = Url::parse(android)
            .map_err(|e| AppError::Internal(format!("passkey: android origin: {e}")))?;
        builder = builder.append_allowed_origin(&parsed);
    }
    builder
        .build()
        .map_err(|e| AppError::Internal(format!("passkey: {e}")))
}

fn handle(user_id: &str) -> Uuid {
    Uuid::new_v5(&Uuid::NAMESPACE_URL, user_id.as_bytes())
}

async fn park(state: &AppState, ceremony: &Ceremony) -> AppResult<String> {
    let token = Uuid::new_v4().to_string();
    let encoded = serde_json::to_string(ceremony)
        .map_err(|e| AppError::Internal(format!("passkey: {e}")))?;
    let mut con = state.rd();
    let _: () = con.set_ex(key(&token), encoded, CEREMONY_TTL_SECONDS).await?;
    Ok(token)
}

async fn take(state: &AppState, token: &str) -> AppResult<Ceremony> {
    let mut con = state.rd();
    let raw: Option<String> = con.get(key(token)).await?;
    let _: () = con.del(key(token)).await?;
    raw.and_then(|r| serde_json::from_str(&r).ok())
        .ok_or_else(|| AppError::BadRequest("That took too long - start again".into()))
}


pub async fn list(state: &AppState, user_id: &str) -> AppResult<Vec<PasskeyRow>> {
    Ok(sqlx::query_as(
        r#"SELECT * FROM "Passkey" WHERE "userId" = $1 ORDER BY "createdAt" ASC"#,
    )
    .bind(user_id)
    .fetch_all(&state.pool)
    .await?)
}

fn credential_of(row: &PasskeyRow) -> Option<Passkey> {
    serde_json::from_value(row.credential.clone()).ok()
}

pub async fn start_registration(
    state: &AppState,
    user: &UserRow,
) -> AppResult<(serde_json::Value, String)> {
    let existing = list(state, &user.id).await?;
    if existing.len() as i64 >= MAX_PER_USER {
        return Err(AppError::BadRequest(format!(
            "You can have at most {MAX_PER_USER} passkeys. Remove one first."
        )));
    }
    let exclude = existing
        .iter()
        .filter_map(credential_of)
        .map(|p| p.cred_id().clone())
        .collect::<Vec<_>>();

    let (challenge, reg) = webauthn(state)?
        .start_passkey_registration(
            handle(&user.id),
            &user.username,
            &user.username,
            Some(exclude),
        )
        .map_err(|e| AppError::BadRequest(format!("passkey: {e}")))?;

    let token = park(
        state,
        &Ceremony::Registration {
            user_id: user.id.clone(),
            state: Box::new(reg),
        },
    )
    .await?;
    let challenge =
        serde_json::to_value(challenge).map_err(|e| AppError::Internal(format!("passkey: {e}")))?;
    Ok((challenge, token))
}

pub async fn finish_registration(
    state: &AppState,
    user_id: &str,
    token: &str,
    response: RegisterPublicKeyCredential,
    name: &str,
) -> AppResult<PasskeyRow> {
    let reg = match take(state, token).await? {
        Ceremony::Registration {
            user_id: owner,
            state,
        } if owner == user_id => state,
        _ => return Err(AppError::BadRequest("That took too long - start again".into())),
    };

    let credential = webauthn(state)?
        .finish_passkey_registration(&response, &reg)
        .map_err(|e| AppError::BadRequest(format!("That passkey could not be registered: {e}")))?;

    let credential_id = base64url(credential.cred_id().as_ref());
    let encoded = serde_json::to_value(&credential)
        .map_err(|e| AppError::Internal(format!("passkey: {e}")))?;
    let name = clean_name(name);
    let backed_up = backup_eligible(&encoded);

    let row: PasskeyRow = sqlx::query_as(
        r#"INSERT INTO "Passkey" (id, "userId", "credentialId", credential, name, "backedUp")
           VALUES ($1, $2, $3, $4, $5, $6) RETURNING *"#,
    )
    .bind(cuid())
    .bind(user_id)
    .bind(&credential_id)
    .bind(encoded)
    .bind(&name)
    .bind(backed_up)
    .fetch_one(&state.pool)
    .await
    .map_err(|e| match e {
        sqlx::Error::Database(ref db) if db.is_unique_violation() => {
            AppError::Conflict("That passkey is already registered".into())
        }
        other => other.into(),
    })?;
    Ok(row)
}

pub async fn rename(state: &AppState, user_id: &str, id: &str, name: &str) -> AppResult<PasskeyRow> {
    let row: Option<PasskeyRow> = sqlx::query_as(
        r#"UPDATE "Passkey" SET name = $3 WHERE id = $1 AND "userId" = $2 RETURNING *"#,
    )
    .bind(id)
    .bind(user_id)
    .bind(clean_name(name))
    .fetch_optional(&state.pool)
    .await?;
    row.ok_or_else(|| AppError::NotFound("No such passkey".into()))
}

pub async fn remove(state: &AppState, user_id: &str, id: &str) -> AppResult<()> {
    let done = sqlx::query(r#"DELETE FROM "Passkey" WHERE id = $1 AND "userId" = $2"#)
        .bind(id)
        .bind(user_id)
        .execute(&state.pool)
        .await?;
    if done.rows_affected() == 0 {
        return Err(AppError::NotFound("No such passkey".into()));
    }
    Ok(())
}


pub async fn start_discoverable(state: &AppState) -> AppResult<(serde_json::Value, String)> {
    let (challenge, auth) = webauthn(state)?
        .start_discoverable_authentication()
        .map_err(|e| AppError::Internal(format!("passkey: {e}")))?;
    let token = park(
        state,
        &Ceremony::Discoverable {
            state: Box::new(auth),
        },
    )
    .await?;
    let challenge =
        serde_json::to_value(challenge).map_err(|e| AppError::Internal(format!("passkey: {e}")))?;
    Ok((challenge, token))
}

pub async fn start_known(
    state: &AppState,
    user_id: &str,
) -> AppResult<(serde_json::Value, String)> {
    let credentials = list(state, user_id)
        .await?
        .iter()
        .filter_map(credential_of)
        .collect::<Vec<_>>();
    if credentials.is_empty() {
        return Err(AppError::BadRequest("No passkeys on this account".into()));
    }
    let (challenge, auth) = webauthn(state)?
        .start_passkey_authentication(&credentials)
        .map_err(|e| AppError::Internal(format!("passkey: {e}")))?;
    let token = park(
        state,
        &Ceremony::Known {
            user_id: user_id.to_string(),
            state: Box::new(auth),
        },
    )
    .await?;
    let challenge =
        serde_json::to_value(challenge).map_err(|e| AppError::Internal(format!("passkey: {e}")))?;
    Ok((challenge, token))
}

pub async fn finish_authentication(
    state: &AppState,
    token: &str,
    response: PublicKeyCredential,
) -> AppResult<UserRow> {
    let webauthn = webauthn(state)?;
    let failed = || AppError::Unauthorized("That passkey wasn't accepted".into());

    let (row, result) = match take(state, token).await? {
        Ceremony::Discoverable { state: auth } => {
            let (_handle, cred_id) = webauthn
                .identify_discoverable_authentication(&response)
                .map_err(|_| failed())?;
            let row = by_credential_id(state, &base64url(cred_id))
                .await?
                .ok_or_else(failed)?;
            let credential = credential_of(&row).ok_or_else(failed)?;
            let keys = [DiscoverableKey::from(&credential)];
            let result = webauthn
                .finish_discoverable_authentication(&response, *auth, &keys)
                .map_err(|_| failed())?;
            (row, result)
        }
        Ceremony::Known {
            user_id,
            state: auth,
        } => {
            let result = webauthn
                .finish_passkey_authentication(&response, &auth)
                .map_err(|_| failed())?;
            let row = by_credential_id(state, &base64url(result.cred_id().as_ref()))
                .await?
                .ok_or_else(failed)?;
            if row.user_id != user_id {
                return Err(failed());
            }
            (row, result)
        }
        Ceremony::Registration { .. } => return Err(failed()),
    };

    if result.needs_update() {
        if let Some(mut credential) = credential_of(&row) {
            credential.update_credential(&result);
            if let Ok(encoded) = serde_json::to_value(&credential) {
                let _ = sqlx::query(r#"UPDATE "Passkey" SET credential = $2 WHERE id = $1"#)
                    .bind(&row.id)
                    .bind(encoded)
                    .execute(&state.pool)
                    .await;
            }
        }
    }
    let _ = sqlx::query(r#"UPDATE "Passkey" SET "lastUsedAt" = now() WHERE id = $1"#)
        .bind(&row.id)
        .execute(&state.pool)
        .await;

    let user: Option<UserRow> = sqlx::query_as(r#"SELECT * FROM "User" WHERE id = $1"#)
        .bind(&row.user_id)
        .fetch_optional(&state.pool)
        .await?;
    let user = user.ok_or_else(failed)?;
    if user.deleted_at.is_some() {
        return Err(failed());
    }
    Ok(user)
}

async fn by_credential_id(state: &AppState, credential_id: &str) -> AppResult<Option<PasskeyRow>> {
    Ok(
        sqlx::query_as(r#"SELECT * FROM "Passkey" WHERE "credentialId" = $1"#)
            .bind(credential_id)
            .fetch_optional(&state.pool)
            .await?,
    )
}

pub async fn count(state: &AppState, user_id: &str) -> AppResult<i64> {
    let (n,): (i64,) = sqlx::query_as(r#"SELECT count(*) FROM "Passkey" WHERE "userId" = $1"#)
        .bind(user_id)
        .fetch_one(&state.pool)
        .await?;
    Ok(n)
}

fn backup_eligible(encoded: &serde_json::Value) -> bool {
    encoded
        .pointer("/cred/backup_eligible")
        .and_then(serde_json::Value::as_bool)
        .unwrap_or(false)
}

fn base64url(bytes: &[u8]) -> String {
    use base64::Engine;
    base64::engine::general_purpose::URL_SAFE_NO_PAD.encode(bytes)
}

fn clean_name(name: &str) -> String {
    let trimmed = name.trim();
    if trimmed.is_empty() {
        "Passkey".to_string()
    } else {
        trimmed.chars().take(60).collect()
    }
}
