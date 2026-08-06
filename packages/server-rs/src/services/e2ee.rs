use base64::Engine;
use p256::ecdsa::signature::Verifier;
use p256::ecdsa::{Signature, VerifyingKey};
use p256::pkcs8::DecodePublicKey;
use rand::RngCore;
use redis::AsyncCommands;
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};

use crate::dto::{
    to_device, to_device_log_entry, to_envelope, to_epoch, DeviceDto, DeviceLogEntryDto, EpochDto,
    EpochKeyDto,
};
use crate::error::{AppError, AppResult};
use crate::ids::cuid;
use crate::models::{ChannelEpochRow, DeviceLogEntryRow, DeviceRow, KeyEnvelopeRow};
use crate::services::key_deletion;
use crate::state::AppState;

pub const DOMAIN_DEVICE_BUNDLE: &str = "orangchat/device-bundle/v1";
pub const DOMAIN_GENESIS: &str = "orangchat/genesis/v1";
pub const DOMAIN_ADD_DEVICE: &str = "orangchat/add-device/v1";
pub const DOMAIN_REVOKE: &str = "orangchat/revoke/v1";
pub const DOMAIN_LOG_ENTRY: &str = "orangchat/device-log/v1";

const GRANT_TTL_SECONDS: u64 = 60;
const BLOB_TTL_SECONDS: u64 = 90;
const BLOB_MAX_BYTES: usize = 1024 * 1024;
const BLOB_MAX_FETCH_ATTEMPTS: i64 = 60;
const TRANSFER_ID_HEX_LEN: usize = 32;
const MAX_DEVICES_PER_USER: i64 = 16;
const MAX_PAYLOAD_BYTES: usize = 8 * 1024;
const MAX_ENVELOPES_PER_EPOCH: usize = 256;
const MAX_WRAPPED_BYTES: usize = 512;

pub fn encode_fields(fields: &[&[u8]]) -> Vec<u8> {
    let mut out = Vec::new();
    for field in fields {
        out.extend_from_slice(&(field.len() as u32).to_be_bytes());
        out.extend_from_slice(field);
    }
    out
}

pub fn log_entry_hash(prev_hash: Option<&[u8]>, payload: &[u8]) -> Vec<u8> {
    let encoded = encode_fields(&[
        DOMAIN_LOG_ENTRY.as_bytes(),
        prev_hash.unwrap_or(&[]),
        payload,
    ]);
    Sha256::digest(&encoded).to_vec()
}

pub fn device_bundle_bytes(user_id: &str, ik_sig_pub: &[u8], ik_dh_pub: &[u8]) -> Vec<u8> {
    encode_fields(&[
        DOMAIN_DEVICE_BUNDLE.as_bytes(),
        user_id.as_bytes(),
        ik_sig_pub,
        ik_dh_pub,
    ])
}

pub fn genesis_statement_bytes(
    user_id: &str,
    ik_sig_pub: &[u8],
    ik_dh_pub: &[u8],
    identity_generation: &str,
) -> Vec<u8> {
    encode_fields(&[
        DOMAIN_GENESIS.as_bytes(),
        user_id.as_bytes(),
        ik_sig_pub,
        ik_dh_pub,
        identity_generation.as_bytes(),
    ])
}

pub fn add_device_statement_bytes(
    user_id: &str,
    ik_sig_pub: &[u8],
    ik_dh_pub: &[u8],
    transfer_id: &str,
) -> Vec<u8> {
    encode_fields(&[
        DOMAIN_ADD_DEVICE.as_bytes(),
        user_id.as_bytes(),
        ik_sig_pub,
        ik_dh_pub,
        transfer_id.as_bytes(),
    ])
}

pub fn revoke_statement_bytes(user_id: &str, device_id: &str, revoked_at: &str) -> Vec<u8> {
    encode_fields(&[
        DOMAIN_REVOKE.as_bytes(),
        user_id.as_bytes(),
        device_id.as_bytes(),
        revoked_at.as_bytes(),
    ])
}

#[cfg_attr(not(test), allow(dead_code))]
pub fn genesis_commitment(
    user_id: &str,
    ik_sig_pub: &[u8],
    ik_dh_pub: &[u8],
    identity_generation: &str,
) -> Vec<u8> {
    Sha256::digest(genesis_statement_bytes(
        user_id,
        ik_sig_pub,
        ik_dh_pub,
        identity_generation,
    ))
    .to_vec()
}

pub fn verify_p256(spki_pub: &[u8], message: &[u8], signature: &[u8]) -> bool {
    let Ok(key) = VerifyingKey::from_public_key_der(spki_pub) else {
        return false;
    };
    let Ok(sig) = Signature::from_slice(signature) else {
        return false;
    };
    key.verify(message, &sig).is_ok()
}

fn decode_b64(label: &str, value: &str) -> AppResult<Vec<u8>> {
    base64::engine::general_purpose::STANDARD
        .decode(value)
        .map_err(|_| AppError::BadRequest(format!("{label} is not valid base64")))
}

fn encode_b64(bytes: &[u8]) -> String {
    base64::engine::general_purpose::STANDARD.encode(bytes)
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct BundleInput {
    pub name: String,
    pub platform: String,
    pub ik_sig_pub: String,
    pub ik_dh_pub: String,
    pub bundle_sig: String,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct LogEntryInput {
    pub payload: String,
    pub prev_hash: Option<String>,
    pub entry_hash: String,
    pub signature: String,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct EnrollGenesisInput {
    #[serde(flatten)]
    pub bundle: BundleInput,
    pub identity_generation: String,
    pub log: LogEntryInput,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct AddDeviceInput {
    #[serde(flatten)]
    pub bundle: BundleInput,
    pub transfer_id: String,
    pub grant: String,
    pub authorized_by: String,
    pub authorization_sig: String,
    pub log: LogEntryInput,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct RevokeDeviceInput {
    pub device_id: String,
    pub signer_device_id: String,
    pub revoked_at: String,
    pub log: LogEntryInput,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct LogHeadDto {
    pub seq: i32,
    pub entry_hash: String,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct DeviceListDto {
    pub user_id: String,
    pub devices: Vec<DeviceDto>,
    pub log: Vec<DeviceLogEntryDto>,
    pub head: Option<LogHeadDto>,
}

struct ParsedBundle {
    name: String,
    platform: String,
    ik_sig_pub: Vec<u8>,
    ik_dh_pub: Vec<u8>,
    bundle_sig: Vec<u8>,
}

fn parse_bundle(user_id: &str, input: &BundleInput) -> AppResult<ParsedBundle> {
    let name = input.name.trim();
    if name.is_empty() || name.chars().count() > 64 {
        return Err(AppError::BadRequest(
            "Device name must be 1-64 characters".into(),
        ));
    }
    if !matches!(input.platform.as_str(), "web" | "android" | "desktop") {
        return Err(AppError::BadRequest("Unknown device platform".into()));
    }

    let ik_sig_pub = decode_b64("ikSigPub", &input.ik_sig_pub)?;
    let ik_dh_pub = decode_b64("ikDhPub", &input.ik_dh_pub)?;
    let bundle_sig = decode_b64("bundleSig", &input.bundle_sig)?;

    if VerifyingKey::from_public_key_der(&ik_sig_pub).is_err() {
        return Err(AppError::BadRequest(
            "ikSigPub is not a P-256 SPKI key".into(),
        ));
    }
    if p256::PublicKey::from_public_key_der(&ik_dh_pub).is_err() {
        return Err(AppError::BadRequest(
            "ikDhPub is not a P-256 SPKI key".into(),
        ));
    }

    let bundle_bytes = device_bundle_bytes(user_id, &ik_sig_pub, &ik_dh_pub);
    if !verify_p256(&ik_sig_pub, &bundle_bytes, &bundle_sig) {
        return Err(AppError::BadRequest(
            "Device bundle self-signature does not verify".into(),
        ));
    }

    Ok(ParsedBundle {
        name: name.to_string(),
        platform: input.platform.clone(),
        ik_sig_pub,
        ik_dh_pub,
        bundle_sig,
    })
}

struct ParsedLogEntry {
    payload: Vec<u8>,
    prev_hash: Option<Vec<u8>>,
    entry_hash: Vec<u8>,
    signature: Vec<u8>,
}

fn parse_log_entry(input: &LogEntryInput) -> AppResult<ParsedLogEntry> {
    let payload = decode_b64("log.payload", &input.payload)?;
    if payload.is_empty() || payload.len() > MAX_PAYLOAD_BYTES {
        return Err(AppError::BadRequest("Log payload is out of range".into()));
    }
    let prev_hash = match &input.prev_hash {
        Some(value) => Some(decode_b64("log.prevHash", value)?),
        None => None,
    };
    let entry_hash = decode_b64("log.entryHash", &input.entry_hash)?;
    let signature = decode_b64("log.signature", &input.signature)?;
    Ok(ParsedLogEntry {
        payload,
        prev_hash,
        entry_hash,
        signature,
    })
}

async fn head(state: &AppState, user_id: &str) -> AppResult<Option<(i32, Vec<u8>)>> {
    let row: Option<(i32, Vec<u8>)> = sqlx::query_as(
        r#"SELECT seq, "entryHash" FROM "DeviceLogEntry"
           WHERE "userId" = $1 ORDER BY seq DESC LIMIT 1"#,
    )
    .bind(user_id)
    .fetch_optional(&state.pool)
    .await?;
    Ok(row)
}

fn check_chain(
    entry: &ParsedLogEntry,
    expected_seq: i32,
    current_head: Option<&(i32, Vec<u8>)>,
) -> AppResult<()> {
    match (expected_seq, current_head) {
        (0, None) => {
            if entry.prev_hash.is_some() {
                return Err(AppError::BadRequest(
                    "The first log entry cannot have a previous hash".into(),
                ));
            }
        }
        (_, Some((_, head_hash))) => {
            let prev = entry.prev_hash.as_deref().unwrap_or_default();
            if prev != head_hash.as_slice() {
                return Err(AppError::Conflict(
                    "Device log has moved on; refetch and retry".into(),
                ));
            }
        }
        _ => {
            return Err(AppError::Conflict(
                "Device log has moved on; refetch and retry".into(),
            ))
        }
    }

    let expected = log_entry_hash(entry.prev_hash.as_deref(), &entry.payload);
    if expected != entry.entry_hash {
        return Err(AppError::BadRequest(
            "Log entry hash does not match its payload".into(),
        ));
    }
    Ok(())
}

fn log_signature_bytes(entry_hash: &[u8]) -> Vec<u8> {
    encode_fields(&[DOMAIN_LOG_ENTRY.as_bytes(), entry_hash])
}

async fn insert_log_entry(
    tx: &mut sqlx::Transaction<'_, sqlx::Postgres>,
    user_id: &str,
    seq: i32,
    kind: &str,
    entry: &ParsedLogEntry,
) -> AppResult<()> {
    sqlx::query(
        r#"INSERT INTO "DeviceLogEntry"
           (id, "userId", seq, kind, payload, "entryHash", "prevHash", signature)
           VALUES ($1, $2, $3, $4, $5, $6, $7, $8)"#,
    )
    .bind(cuid())
    .bind(user_id)
    .bind(seq)
    .bind(kind)
    .bind(&entry.payload)
    .bind(&entry.entry_hash)
    .bind(&entry.prev_hash)
    .bind(&entry.signature)
    .execute(&mut **tx)
    .await
    .map_err(|e| match &e {
        sqlx::Error::Database(db) if db.is_unique_violation() => {
            AppError::Conflict("Device log has moved on; refetch and retry".into())
        }
        _ => AppError::from(e),
    })?;
    Ok(())
}

pub async fn active_devices(state: &AppState, user_id: &str) -> AppResult<Vec<DeviceRow>> {
    let rows: Vec<DeviceRow> = sqlx::query_as(
        r#"SELECT * FROM "Device" WHERE "userId" = $1 AND "revokedAt" IS NULL
           ORDER BY "createdAt" ASC"#,
    )
    .bind(user_id)
    .fetch_all(&state.pool)
    .await?;
    Ok(rows)
}

pub async fn device(state: &AppState, device_id: &str) -> AppResult<Option<DeviceRow>> {
    let row: Option<DeviceRow> = sqlx::query_as(r#"SELECT * FROM "Device" WHERE id = $1"#)
        .bind(device_id)
        .fetch_optional(&state.pool)
        .await?;
    Ok(row)
}

pub async fn owned_active_device(
    state: &AppState,
    user_id: &str,
    device_id: &str,
) -> AppResult<DeviceRow> {
    let row = device(state, device_id)
        .await?
        .filter(|d| d.user_id == user_id && d.revoked_at.is_none())
        .ok_or_else(|| AppError::NotFound("Unknown device".into()))?;
    Ok(row)
}

pub async fn list(state: &AppState, user_id: &str, since: Option<i32>) -> AppResult<DeviceListDto> {
    let devices: Vec<DeviceRow> =
        sqlx::query_as(r#"SELECT * FROM "Device" WHERE "userId" = $1 ORDER BY "createdAt" ASC"#)
            .bind(user_id)
            .fetch_all(&state.pool)
            .await?;

    let entries: Vec<DeviceLogEntryRow> = sqlx::query_as(
        r#"SELECT * FROM "DeviceLogEntry" WHERE "userId" = $1 AND seq >= $2 ORDER BY seq ASC"#,
    )
    .bind(user_id)
    .bind(since.unwrap_or(0))
    .fetch_all(&state.pool)
    .await?;

    let head = head(state, user_id).await?.map(|(seq, hash)| LogHeadDto {
        seq,
        entry_hash: encode_b64(&hash),
    });

    Ok(DeviceListDto {
        user_id: user_id.to_string(),
        devices: devices.iter().map(to_device).collect(),
        log: entries.iter().map(to_device_log_entry).collect(),
        head,
    })
}

pub async fn enroll_genesis(
    state: &AppState,
    user_id: &str,
    input: &EnrollGenesisInput,
) -> AppResult<DeviceDto> {
    let bundle = parse_bundle(user_id, &input.bundle)?;
    let entry = parse_log_entry(&input.log)?;

    if !active_devices(state, user_id).await?.is_empty() {
        return Err(AppError::Conflict(
            "This account already has an encryption identity; enrol from an existing device".into(),
        ));
    }

    let expected_payload = genesis_statement_bytes(
        user_id,
        &bundle.ik_sig_pub,
        &bundle.ik_dh_pub,
        &input.identity_generation,
    );
    if expected_payload != entry.payload {
        return Err(AppError::BadRequest(
            "Genesis statement does not match the submitted bundle".into(),
        ));
    }

    let current = head(state, user_id).await?;
    let seq = current.as_ref().map(|(s, _)| s + 1).unwrap_or(0);
    check_chain(&entry, seq, current.as_ref())?;

    if !verify_p256(
        &bundle.ik_sig_pub,
        &log_signature_bytes(&entry.entry_hash),
        &entry.signature,
    ) {
        return Err(AppError::BadRequest(
            "Genesis log entry is not signed by the device it enrols".into(),
        ));
    }

    let id = cuid();
    let mut tx = state.pool.begin().await?;
    sqlx::query(
        r#"INSERT INTO "Device"
           (id, "userId", name, platform, "ikSigPub", "ikDhPub", "bundleSig")
           VALUES ($1, $2, $3, $4, $5, $6, $7)"#,
    )
    .bind(&id)
    .bind(user_id)
    .bind(&bundle.name)
    .bind(&bundle.platform)
    .bind(&bundle.ik_sig_pub)
    .bind(&bundle.ik_dh_pub)
    .bind(&bundle.bundle_sig)
    .execute(&mut *tx)
    .await?;
    insert_log_entry(&mut tx, user_id, seq, "genesis", &entry).await?;
    tx.commit().await?;

    let row = device(state, &id)
        .await?
        .ok_or_else(|| AppError::Internal("device vanished after insert".into()))?;
    Ok(to_device(&row))
}

pub async fn add_device(
    state: &AppState,
    user_id: &str,
    input: &AddDeviceInput,
) -> AppResult<DeviceDto> {
    let bundle = parse_bundle(user_id, &input.bundle)?;
    let entry = parse_log_entry(&input.log)?;

    let existing = active_devices(state, user_id).await?;
    if existing.len() as i64 >= MAX_DEVICES_PER_USER {
        return Err(AppError::BadRequest(
            "This account has too many devices; revoke one first".into(),
        ));
    }

    let authorizer = existing
        .iter()
        .find(|d| d.id == input.authorized_by)
        .ok_or_else(|| {
            AppError::BadRequest("The authorizing device is not active on this account".into())
        })?;

    consume_transfer_grant(
        state,
        user_id,
        &input.transfer_id,
        &input.grant,
        &bundle.ik_sig_pub,
        &bundle.ik_dh_pub,
    )
    .await?;

    let statement = add_device_statement_bytes(
        user_id,
        &bundle.ik_sig_pub,
        &bundle.ik_dh_pub,
        &input.transfer_id,
    );
    let authorization_sig = decode_b64("authorizationSig", &input.authorization_sig)?;
    if !verify_p256(&authorizer.ik_sig_pub, &statement, &authorization_sig) {
        return Err(AppError::BadRequest(
            "Authorization signature does not verify against the authorizing device".into(),
        ));
    }
    if statement != entry.payload {
        return Err(AppError::BadRequest(
            "Add-device statement does not match the submitted bundle".into(),
        ));
    }

    let current = head(state, user_id).await?;
    let seq = current.as_ref().map(|(s, _)| s + 1).unwrap_or(0);
    check_chain(&entry, seq, current.as_ref())?;

    if !verify_p256(
        &authorizer.ik_sig_pub,
        &log_signature_bytes(&entry.entry_hash),
        &entry.signature,
    ) {
        return Err(AppError::BadRequest(
            "Log entry is not signed by the authorizing device".into(),
        ));
    }

    let id = cuid();
    let mut tx = state.pool.begin().await?;
    sqlx::query(
        r#"INSERT INTO "Device"
           (id, "userId", name, platform, "ikSigPub", "ikDhPub", "bundleSig",
            "authorizedBy", "authorizationSig")
           VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9)"#,
    )
    .bind(&id)
    .bind(user_id)
    .bind(&bundle.name)
    .bind(&bundle.platform)
    .bind(&bundle.ik_sig_pub)
    .bind(&bundle.ik_dh_pub)
    .bind(&bundle.bundle_sig)
    .bind(&authorizer.id)
    .bind(&authorization_sig)
    .execute(&mut *tx)
    .await?;
    insert_log_entry(&mut tx, user_id, seq, "add-device", &entry).await?;
    tx.commit().await?;

    let row = device(state, &id)
        .await?
        .ok_or_else(|| AppError::Internal("device vanished after insert".into()))?;
    Ok(to_device(&row))
}

pub async fn revoke_device(
    state: &AppState,
    user_id: &str,
    input: &RevokeDeviceInput,
) -> AppResult<DeviceDto> {
    let entry = parse_log_entry(&input.log)?;
    let target = owned_active_device(state, user_id, &input.device_id).await?;
    let signer = owned_active_device(state, user_id, &input.signer_device_id).await?;

    let statement = revoke_statement_bytes(user_id, &target.id, &input.revoked_at);
    if statement != entry.payload {
        return Err(AppError::BadRequest(
            "Revocation statement does not match the device being revoked".into(),
        ));
    }

    let current = head(state, user_id).await?;
    let seq = current.as_ref().map(|(s, _)| s + 1).unwrap_or(0);
    check_chain(&entry, seq, current.as_ref())?;

    if !verify_p256(
        &signer.ik_sig_pub,
        &log_signature_bytes(&entry.entry_hash),
        &entry.signature,
    ) {
        return Err(AppError::BadRequest(
            "Revocation is not signed by an authorized device".into(),
        ));
    }

    let mut tx = state.pool.begin().await?;
    sqlx::query(r#"UPDATE "Device" SET "revokedAt" = now() WHERE id = $1"#)
        .bind(&target.id)
        .execute(&mut *tx)
        .await?;
    insert_log_entry(&mut tx, user_id, seq, "revoke", &entry).await?;
    tx.commit().await?;

    let row = device(state, &target.id)
        .await?
        .ok_or_else(|| AppError::Internal("device vanished after revoke".into()))?;
    Ok(to_device(&row))
}

pub async fn touch(state: &AppState, user_id: &str, device_id: &str) -> AppResult<()> {
    let updated = sqlx::query(
        r#"UPDATE "Device" SET "lastSeenAt" = now()
           WHERE id = $1 AND "userId" = $2 AND "revokedAt" IS NULL"#,
    )
    .bind(device_id)
    .bind(user_id)
    .execute(&state.pool)
    .await?;

    // Only a device that has loaded a local identity ever gets here, so this is
    // the account demonstrating that a working key still exists - which is
    // exactly the question a pending key erasure is waiting on. Answer it now
    // rather than at the end of the wait.
    if updated.rows_affected() > 0 {
        key_deletion::abort_on_device_seen(state, user_id).await?;
    }
    Ok(())
}

fn grant_key(user_id: &str, transfer_id: &str) -> String {
    format!("e2ee:grant:{user_id}:{transfer_id}")
}

fn blob_key(transfer_id: &str, slot: &str) -> String {
    format!("e2ee:blob:{transfer_id}:{slot}")
}

fn blob_attempt_key(transfer_id: &str, slot: &str, source: &str) -> String {
    format!("e2ee:blob-attempt:{transfer_id}:{slot}:{source}")
}

fn burned_transfer_key(transfer_id: &str) -> String {
    format!("e2ee:blob-burned:{transfer_id}")
}

/// The relay fallback carries three things, in order: the new device's name for
/// the device list ("hello"), the old device's ephemeral public key so both ends
/// derive the same six digits ("handshake"), and the history bundle itself.
/// Naming the slots keeps them from overwriting each other on a hostile network
/// where all three have to go this way. All are opaque to the server; the
/// pairing secret only ever existed on the QR a camera read.
pub fn valid_blob_slot(slot: &str) -> bool {
    matches!(slot, "hello" | "handshake" | "bundle")
}

pub fn valid_transfer_id(value: &str) -> bool {
    value.len() == TRANSFER_ID_HEX_LEN && value.chars().all(|c| c.is_ascii_hexdigit())
}

pub fn new_transfer_id() -> String {
    let mut bytes = [0u8; 16];
    rand::thread_rng().fill_bytes(&mut bytes);
    hex::encode(bytes)
}

fn device_fingerprint(ik_sig_pub: &[u8], ik_dh_pub: &[u8]) -> String {
    hex::encode(Sha256::digest(encode_fields(&[ik_sig_pub, ik_dh_pub])))
}

#[derive(Debug, Serialize, Deserialize)]
struct GrantRecord {
    grant: String,
    fingerprint: String,
}

pub async fn issue_transfer_grant(
    state: &AppState,
    user_id: &str,
    transfer_id: &str,
    ik_sig_pub: &[u8],
    ik_dh_pub: &[u8],
) -> AppResult<(String, u64)> {
    if !valid_transfer_id(transfer_id) {
        return Err(AppError::BadRequest("Malformed transfer id".into()));
    }
    let mut bytes = [0u8; 32];
    rand::thread_rng().fill_bytes(&mut bytes);
    let grant = hex::encode(bytes);

    let record = GrantRecord {
        grant: grant.clone(),
        fingerprint: device_fingerprint(ik_sig_pub, ik_dh_pub),
    };
    let encoded = serde_json::to_string(&record)
        .map_err(|e| AppError::Internal(format!("transfer grant: {e}")))?;

    let mut con = state.rd();
    let burned: bool = con.exists(burned_transfer_key(transfer_id)).await?;
    if burned {
        return Err(AppError::NotFound(
            "This transfer was burned; start it again on both devices".into(),
        ));
    }
    let _: () = con
        .set_ex(grant_key(user_id, transfer_id), encoded, GRANT_TTL_SECONDS)
        .await?;
    Ok((grant, GRANT_TTL_SECONDS))
}

async fn consume_transfer_grant(
    state: &AppState,
    user_id: &str,
    transfer_id: &str,
    grant: &str,
    ik_sig_pub: &[u8],
    ik_dh_pub: &[u8],
) -> AppResult<()> {
    if !valid_transfer_id(transfer_id) {
        return Err(AppError::BadRequest("Malformed transfer id".into()));
    }
    let key = grant_key(user_id, transfer_id);
    let mut con = state.rd();
    let raw: Option<String> = con.get(&key).await?;
    let raw = raw.ok_or_else(|| {
        AppError::Unauthorized("This transfer approval expired; start the transfer again".into())
    })?;
    let record: GrantRecord = serde_json::from_str(&raw)
        .map_err(|e| AppError::Internal(format!("transfer grant: {e}")))?;

    let ok = record.grant.len() == grant.len()
        && record
            .grant
            .bytes()
            .zip(grant.bytes())
            .fold(0u8, |acc, (a, b)| acc | (a ^ b))
            == 0;
    if !ok || record.fingerprint != device_fingerprint(ik_sig_pub, ik_dh_pub) {
        return Err(AppError::Unauthorized(
            "This transfer approval does not match this device".into(),
        ));
    }

    let consumed: Option<String> = con.get_del(&key).await?;
    if consumed.as_deref() != Some(raw.as_str()) {
        return Err(AppError::Unauthorized(
            "This transfer approval was already used; start the transfer again".into(),
        ));
    }
    Ok(())
}

pub async fn put_transfer_blob(
    state: &AppState,
    transfer_id: &str,
    slot: &str,
    blob: &str,
) -> AppResult<u64> {
    if !valid_transfer_id(transfer_id) || !valid_blob_slot(slot) {
        return Err(AppError::BadRequest("Malformed transfer id".into()));
    }
    let bytes = decode_b64("blob", blob)?;
    if bytes.is_empty() || bytes.len() > BLOB_MAX_BYTES {
        return Err(AppError::BadRequest("Transfer blob is out of range".into()));
    }
    let mut con = state.rd();
    let _: () = con
        .set_ex(blob_key(transfer_id, slot), blob, BLOB_TTL_SECONDS)
        .await?;
    Ok(BLOB_TTL_SECONDS)
}

/// Single-use by construction: the read deletes. A blob fetched by anyone but
/// the waiting device is a burned transfer, which is loud on purpose.
pub async fn take_transfer_blob(
    state: &AppState,
    transfer_id: &str,
    slot: &str,
    source: &str,
) -> AppResult<String> {
    if !valid_transfer_id(transfer_id) || !valid_blob_slot(slot) {
        return Err(AppError::BadRequest("Malformed transfer id".into()));
    }
    let key = blob_key(transfer_id, slot);
    let mut con = state.rd();
    if con.exists(burned_transfer_key(transfer_id)).await? {
        return Err(AppError::NotFound(
            "This transfer was burned; start it again on both devices".into(),
        ));
    }

    let attempts_key = blob_attempt_key(transfer_id, slot, source);
    let attempts: i64 = con.incr(&attempts_key, 1).await?;
    let _: bool = con.expire(&attempts_key, BLOB_TTL_SECONDS as i64).await?;
    if attempts > BLOB_MAX_FETCH_ATTEMPTS {
        let burned_key = burned_transfer_key(transfer_id);
        let _: () = con.set_ex(&burned_key, "1", BLOB_TTL_SECONDS).await?;
        for transfer_slot in ["hello", "handshake", "bundle"] {
            let _: () = con.del(blob_key(transfer_id, transfer_slot)).await?;
        }
        return Err(AppError::NotFound(
            "Too many transfer fetch attempts; start it again on both devices".into(),
        ));
    }

    // GETDEL is one Redis operation. A GET followed by DEL has a race where two
    // callers can both receive a blob that is meant to be single-use.
    let blob: Option<String> = con.get_del(&key).await?;
    let blob = blob.ok_or_else(|| {
        AppError::NotFound("This transfer is no longer available; start it again".into())
    })?;
    Ok(blob)
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct EnvelopeInput {
    pub device_id: String,
    pub ephemeral_pub: String,
    pub wrap_nonce: String,
    pub wrapped: String,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct MintEpochInput {
    pub id: String,
    pub created_by: String,
    pub envelopes: Vec<EnvelopeInput>,
    /// Whether the conversation should be told a key was started. Only a person
    /// choosing "reset the key" sets this: epochs are also minted whenever the
    /// device set changes, and announcing those would bury the conversation in
    /// notices about routine rekeying. It says nothing the server has to trust -
    /// at worst a client announces its own rekey.
    #[serde(default)]
    pub announce: bool,
}

/// The epoch id is minted by the client, not here, because it is the HKDF salt
/// every wrapping is already bound to by the time we see them. A server-assigned
/// id would have to be predicted before it existed.
pub fn valid_epoch_id(value: &str) -> bool {
    value.len() == TRANSFER_ID_HEX_LEN && value.chars().all(|c| c.is_ascii_hexdigit())
}

pub async fn mint_epoch(
    state: &AppState,
    channel_id: &str,
    user_id: &str,
    input: &MintEpochInput,
) -> AppResult<EpochDto> {
    if input.envelopes.is_empty() || input.envelopes.len() > MAX_ENVELOPES_PER_EPOCH {
        return Err(AppError::BadRequest(
            "An epoch must be wrapped to at least one and at most 256 devices".into(),
        ));
    }
    if !valid_epoch_id(&input.id) {
        return Err(AppError::BadRequest("Malformed epoch id".into()));
    }
    owned_active_device(state, user_id, &input.created_by).await?;

    let recipients = member_devices(state, channel_id).await?;
    let mut decoded = Vec::with_capacity(input.envelopes.len());
    for envelope in &input.envelopes {
        if !recipients.iter().any(|d| d.id == envelope.device_id) {
            return Err(AppError::BadRequest(
                "An envelope names a device that is not in this conversation".into(),
            ));
        }
        let ephemeral_pub = decode_b64("ephemeralPub", &envelope.ephemeral_pub)?;
        let wrap_nonce = decode_b64("wrapNonce", &envelope.wrap_nonce)?;
        let wrapped = decode_b64("wrapped", &envelope.wrapped)?;
        if wrap_nonce.len() != 12 {
            return Err(AppError::BadRequest("wrapNonce must be 12 bytes".into()));
        }
        if wrapped.is_empty() || wrapped.len() > MAX_WRAPPED_BYTES {
            return Err(AppError::BadRequest("wrapped key is out of range".into()));
        }
        if p256::PublicKey::from_public_key_der(&ephemeral_pub).is_err() {
            return Err(AppError::BadRequest(
                "ephemeralPub is not a P-256 SPKI key".into(),
            ));
        }
        decoded.push((
            envelope.device_id.clone(),
            ephemeral_pub,
            wrap_nonce,
            wrapped,
        ));
    }

    let mut tx = state.pool.begin().await?;
    let epoch: i32 = sqlx::query_scalar(
        r#"UPDATE "Channel" SET "epochNumber" = "epochNumber" + 1, e2ee = true
           WHERE id = $1 RETURNING "epochNumber""#,
    )
    .bind(channel_id)
    .fetch_optional(&mut *tx)
    .await?
    .ok_or_else(|| AppError::NotFound("Channel not found".into()))?;

    let epoch_id = input.id.clone();
    sqlx::query(
        r#"INSERT INTO "ChannelEpoch" (id, "channelId", epoch, "createdBy")
           VALUES ($1, $2, $3, $4)"#,
    )
    .bind(&epoch_id)
    .bind(channel_id)
    .bind(epoch)
    .bind(&input.created_by)
    .execute(&mut *tx)
    .await
    .map_err(|e| match &e {
        sqlx::Error::Database(db) if db.is_unique_violation() => {
            AppError::Conflict("That epoch id is already in use".into())
        }
        _ => AppError::from(e),
    })?;

    for (device_id, ephemeral_pub, wrap_nonce, wrapped) in &decoded {
        sqlx::query(
            r#"INSERT INTO "KeyEnvelope"
               (id, "epochId", "deviceId", "ephemeralPub", "wrapNonce", wrapped)
               VALUES ($1, $2, $3, $4, $5, $6)
               ON CONFLICT ("epochId", "deviceId") DO NOTHING"#,
        )
        .bind(cuid())
        .bind(&epoch_id)
        .bind(device_id)
        .bind(ephemeral_pub)
        .bind(wrap_nonce)
        .bind(wrapped)
        .execute(&mut *tx)
        .await?;
    }
    tx.commit().await?;

    let row: ChannelEpochRow = sqlx::query_as(r#"SELECT * FROM "ChannelEpoch" WHERE id = $1"#)
        .bind(&epoch_id)
        .fetch_one(&state.pool)
        .await?;
    Ok(to_epoch(&row))
}

pub async fn member_devices(state: &AppState, channel_id: &str) -> AppResult<Vec<DeviceRow>> {
    let rows: Vec<DeviceRow> = sqlx::query_as(
        r#"SELECT d.* FROM "Device" d
           JOIN "ChannelParticipant" p ON p."userId" = d."userId"
           WHERE p."channelId" = $1 AND d."revokedAt" IS NULL
           ORDER BY d."userId" ASC, d."createdAt" ASC"#,
    )
    .bind(channel_id)
    .fetch_all(&state.pool)
    .await?;
    Ok(rows)
}

pub async fn epoch_keys(
    state: &AppState,
    channel_id: &str,
    user_id: &str,
    device_id: &str,
    since: Option<i32>,
) -> AppResult<Vec<EpochKeyDto>> {
    owned_active_device(state, user_id, device_id).await?;

    let rows: Vec<(ChannelEpochRow, KeyEnvelopeRow)> = sqlx::query_as::<_, EpochKeyRow>(
        r#"SELECT e.id AS "epochRowId", e."channelId", e.epoch, e."createdAt", e."createdBy",
                  k.id AS "envelopeId", k."epochId", k."deviceId", k."ephemeralPub",
                  k."wrapNonce", k.wrapped
           FROM "ChannelEpoch" e
           JOIN "KeyEnvelope" k ON k."epochId" = e.id
           WHERE e."channelId" = $1 AND k."deviceId" = $2 AND e.epoch >= $3
           ORDER BY e.epoch ASC"#,
    )
    .bind(channel_id)
    .bind(device_id)
    .bind(since.unwrap_or(0))
    .fetch_all(&state.pool)
    .await?
    .into_iter()
    .map(EpochKeyRow::split)
    .collect();

    Ok(rows
        .iter()
        .map(|(epoch, envelope)| EpochKeyDto {
            epoch: to_epoch(epoch),
            envelope: to_envelope(envelope),
        })
        .collect())
}

#[derive(sqlx::FromRow)]
struct EpochKeyRow {
    #[sqlx(rename = "epochRowId")]
    epoch_row_id: String,
    #[sqlx(rename = "channelId")]
    channel_id: String,
    epoch: i32,
    #[sqlx(rename = "createdAt")]
    created_at: chrono::NaiveDateTime,
    #[sqlx(rename = "createdBy")]
    created_by: String,
    #[sqlx(rename = "envelopeId")]
    envelope_id: String,
    #[sqlx(rename = "epochId")]
    epoch_id: String,
    #[sqlx(rename = "deviceId")]
    device_id: String,
    #[sqlx(rename = "ephemeralPub")]
    ephemeral_pub: Vec<u8>,
    #[sqlx(rename = "wrapNonce")]
    wrap_nonce: Vec<u8>,
    wrapped: Vec<u8>,
}

impl EpochKeyRow {
    fn split(self) -> (ChannelEpochRow, KeyEnvelopeRow) {
        (
            ChannelEpochRow {
                id: self.epoch_row_id,
                channel_id: self.channel_id,
                epoch: self.epoch,
                created_at: self.created_at,
                created_by: self.created_by,
            },
            KeyEnvelopeRow {
                id: self.envelope_id,
                epoch_id: self.epoch_id,
                device_id: self.device_id,
                ephemeral_pub: self.ephemeral_pub,
                wrap_nonce: self.wrap_nonce,
                wrapped: self.wrapped,
            },
        )
    }
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ChannelStateDto {
    pub channel_id: String,
    /// "dm" | "group_dm" | …; strict mode is DM-only in v1 (docs/E2EE.md §6.3),
    /// so the client has to know which one it is before it wraps anything.
    pub channel_type: String,
    pub e2ee: bool,
    pub epoch_number: i32,
    pub capable: bool,
    /// True when the current epoch's envelopes no longer exactly match the
    /// active devices of the current members (device or membership change).
    pub rotation_required: bool,
    pub current_epoch_created_at: Option<String>,
    pub current_epoch_message_count: i64,
    pub member_devices: Vec<DeviceDto>,
}

pub async fn channel_state(state: &AppState, channel_id: &str) -> AppResult<ChannelStateDto> {
    let row: Option<(bool, i32, String, Option<chrono::NaiveDateTime>, i64, bool)> =
        sqlx::query_as(
            r#"SELECT c.e2ee, c."epochNumber", c.type, e."createdAt",
                      (SELECT COUNT(*) FROM "Message" m
                       WHERE m."channelId" = c.id AND m."encEpoch" = c."epochNumber"),
                      CASE WHEN c."epochNumber" = 0 THEN false ELSE (
                        EXISTS (
                          SELECT 1
                          FROM "Device" d
                          JOIN "ChannelParticipant" p ON p."userId" = d."userId"
                          WHERE p."channelId" = c.id AND d."revokedAt" IS NULL
                            AND NOT EXISTS (
                              SELECT 1 FROM "KeyEnvelope" k
                              WHERE k."epochId" = e.id AND k."deviceId" = d.id
                            )
                        )
                        OR EXISTS (
                          SELECT 1 FROM "KeyEnvelope" k
                          JOIN "Device" d ON d.id = k."deviceId"
                          WHERE k."epochId" = e.id
                            AND (
                              d."revokedAt" IS NOT NULL OR NOT EXISTS (
                                SELECT 1 FROM "ChannelParticipant" p
                                WHERE p."channelId" = c.id AND p."userId" = d."userId"
                              )
                            )
                        )
                      ) END
               FROM "Channel" c
               LEFT JOIN "ChannelEpoch" e
                 ON e."channelId" = c.id AND e.epoch = c."epochNumber"
               WHERE c.id = $1"#,
        )
        .bind(channel_id)
        .fetch_optional(&state.pool)
        .await?;
    let (
        e2ee,
        epoch_number,
        channel_type,
        current_epoch_created_at,
        current_epoch_message_count,
        rotation_required,
    ) = row.ok_or_else(|| AppError::NotFound("Channel not found".into()))?;
    let devices = member_devices(state, channel_id).await?;
    Ok(ChannelStateDto {
        channel_id: channel_id.to_string(),
        channel_type,
        e2ee,
        epoch_number,
        capable: everyone_is_capable(state, channel_id).await?,
        rotation_required,
        current_epoch_created_at: current_epoch_created_at
            .map(|value| value.and_utc().to_rfc3339()),
        current_epoch_message_count,
        member_devices: devices.iter().map(to_device).collect(),
    })
}

pub async fn everyone_is_capable(state: &AppState, channel_id: &str) -> AppResult<bool> {
    let missing: i64 = sqlx::query_scalar(
        r#"SELECT COUNT(*) FROM "ChannelParticipant" p
           WHERE p."channelId" = $1
             AND NOT EXISTS (
               SELECT 1 FROM "Device" d
               WHERE d."userId" = p."userId" AND d."revokedAt" IS NULL
             )"#,
    )
    .bind(channel_id)
    .fetch_one(&state.pool)
    .await?;
    Ok(missing == 0)
}

pub async fn peer_rooms(state: &AppState, user_id: &str) -> AppResult<Vec<String>> {
    let peers: Vec<String> = sqlx::query_scalar(
        r#"SELECT DISTINCT p."userId" FROM "ChannelParticipant" p
           JOIN "Channel" c ON c.id = p."channelId"
           WHERE c.type IN ('dm', 'group_dm')
             AND p."channelId" IN (
               SELECT "channelId" FROM "ChannelParticipant" WHERE "userId" = $1
             )"#,
    )
    .bind(user_id)
    .fetch_all(&state.pool)
    .await?;

    let mut rooms: Vec<String> = peers.into_iter().map(|id| format!("user:{id}")).collect();
    rooms.push(format!("user:{user_id}"));
    rooms.sort();
    rooms.dedup();
    Ok(rooms)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn encode_fields_is_unambiguous_across_a_boundary_shift() {
        let a = encode_fields(&[b"ab", b"c"]);
        let b = encode_fields(&[b"a", b"bc"]);
        assert_ne!(a, b);
    }

    #[test]
    fn encode_fields_length_prefixes_every_field() {
        assert_eq!(
            encode_fields(&[b"hi", b""]),
            vec![0, 0, 0, 2, b'h', b'i', 0, 0, 0, 0]
        );
    }

    #[test]
    fn log_entry_hash_chains_on_the_previous_hash() {
        let first = log_entry_hash(None, b"payload");
        let second = log_entry_hash(Some(&first), b"payload");
        assert_ne!(first, second);
        assert_eq!(first.len(), 32);
    }

    #[test]
    fn genesis_commitment_changes_with_the_identity_generation() {
        let a = genesis_commitment("u1", b"sig", b"dh", "gen-a");
        let b = genesis_commitment("u1", b"sig", b"dh", "gen-b");
        assert_ne!(a, b);
    }

    #[test]
    fn transfer_ids_are_128_bits_of_hex() {
        let id = new_transfer_id();
        assert!(valid_transfer_id(&id));
        assert_eq!(id.len(), 32);
        assert!(!valid_transfer_id("short"));
        assert!(!valid_transfer_id(&"z".repeat(32)));
    }

    #[test]
    fn a_garbage_signature_never_verifies() {
        assert!(!verify_p256(b"not a key", b"message", b"signature"));
    }
}
