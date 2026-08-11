
use aes_gcm::aead::{Aead, Payload};
use aes_gcm::{Aes256Gcm, KeyInit, Nonce};
use base64::Engine;
use sha2::{Digest, Sha256};

use crate::error::{AppError, AppResult};
use crate::ids::cuid;
use crate::services::{channel, e2ee};
use crate::state::AppState;

const ENVELOPE_MAGIC: &[u8] = b"OCE1";
const ENVELOPE_VERSION: u8 = 1;
const MESSAGE_NONCE: [u8; 12] = [0; 12];
const DOMAIN_MESSAGE_KEY: &[u8] = b"orangchat/msg/v1";
const DOMAIN_MESSAGE_SIG: &[u8] = b"orangchat/msg-sig/v1";
const MAX_REASON_CHARS: usize = 1_000;
const MAX_PLAINTEXT_BYTES: usize = 256 * 1024;

#[derive(Debug)]
struct Envelope<'a> {
    epoch: u64,
    seq: u64,
    sender_device_id: &'a [u8],
    sender_user_id: &'a [u8],
    signature: &'a [u8],
    ciphertext: &'a [u8],
}

#[derive(Debug)]
pub struct ReportResult {
    pub id: String,
    pub encrypted: bool,
    pub already_existed: bool,
}

fn malformed() -> AppError {
    AppError::BadRequest("The stored encrypted message is malformed".into())
}

fn next_field<'a>(bytes: &'a [u8], at: &mut usize) -> AppResult<&'a [u8]> {
    let end = at.checked_add(4).ok_or_else(malformed)?;
    let header: [u8; 4] = bytes
        .get(*at..end)
        .ok_or_else(malformed)?
        .try_into()
        .map_err(|_| malformed())?;
    *at = end;
    let len = u32::from_be_bytes(header) as usize;
    let end = at.checked_add(len).ok_or_else(malformed)?;
    let field = bytes.get(*at..end).ok_or_else(malformed)?;
    *at = end;
    Ok(field)
}

fn next_u64(bytes: &[u8], at: &mut usize) -> AppResult<u64> {
    let field = next_field(bytes, at)?;
    let raw: [u8; 8] = field.try_into().map_err(|_| malformed())?;
    Ok(u64::from_be_bytes(raw))
}

fn decode_envelope(bytes: &[u8]) -> AppResult<Envelope<'_>> {
    if bytes.get(..4) != Some(ENVELOPE_MAGIC) || bytes.get(4) != Some(&ENVELOPE_VERSION) {
        return Err(malformed());
    }
    let mut at = 5;
    let envelope = Envelope {
        epoch: next_u64(bytes, &mut at)?,
        seq: next_u64(bytes, &mut at)?,
        sender_device_id: next_field(bytes, &mut at)?,
        sender_user_id: next_field(bytes, &mut at)?,
        signature: next_field(bytes, &mut at)?,
        ciphertext: next_field(bytes, &mut at)?,
    };
    if at != bytes.len() {
        return Err(malformed());
    }
    Ok(envelope)
}

fn message_aad(channel_id: &str, envelope: &Envelope<'_>) -> Vec<u8> {
    let epoch = envelope.epoch.to_be_bytes();
    let seq = envelope.seq.to_be_bytes();
    e2ee::encode_fields(&[
        DOMAIN_MESSAGE_KEY,
        channel_id.as_bytes(),
        &epoch,
        envelope.sender_device_id,
        envelope.sender_user_id,
        &seq,
    ])
}

fn signature_payload(channel_id: &str, envelope: &Envelope<'_>, aad: &[u8]) -> Vec<u8> {
    let mut signed_bytes =
        Vec::with_capacity(envelope.ciphertext.len() + MESSAGE_NONCE.len() + aad.len());
    signed_bytes.extend_from_slice(envelope.ciphertext);
    signed_bytes.extend_from_slice(&MESSAGE_NONCE);
    signed_bytes.extend_from_slice(aad);
    let digest = Sha256::digest(signed_bytes);
    let epoch = envelope.epoch.to_be_bytes();
    let seq = envelope.seq.to_be_bytes();
    e2ee::encode_fields(&[
        DOMAIN_MESSAGE_SIG,
        channel_id.as_bytes(),
        &epoch,
        envelope.sender_device_id,
        envelope.sender_user_id,
        &seq,
        &digest,
    ])
}

async fn open_reported_message(
    state: &AppState,
    channel_id: &str,
    author_id: &str,
    stored_epoch: i32,
    stored_version: i32,
    bytes: &[u8],
    key_b64: Option<&str>,
) -> AppResult<(String, String, Vec<u8>)> {
    if stored_version != ENVELOPE_VERSION as i32 {
        return Err(AppError::BadRequest(
            "This encrypted message version cannot be reported by this server".into(),
        ));
    }
    let envelope = decode_envelope(bytes)?;
    if envelope.epoch != stored_epoch as u64 {
        return Err(AppError::BadRequest(
            "The message epoch does not match its stored envelope".into(),
        ));
    }
    let sender_user_id = std::str::from_utf8(envelope.sender_user_id).map_err(|_| malformed())?;
    let sender_device_id =
        std::str::from_utf8(envelope.sender_device_id).map_err(|_| malformed())?;
    if sender_user_id != author_id {
        return Err(AppError::BadRequest(
            "The encrypted message was not signed by its displayed author".into(),
        ));
    }

    let signing_key: Option<Vec<u8>> =
        sqlx::query_scalar(r#"SELECT "ikSigPub" FROM "Device" WHERE id = $1 AND "userId" = $2"#)
            .bind(sender_device_id)
            .bind(author_id)
            .fetch_optional(&state.pool)
            .await?;
    let signing_key = signing_key.ok_or_else(|| {
        AppError::BadRequest("The sender device is not in the author's device log".into())
    })?;

    let aad = message_aad(channel_id, &envelope);
    let signed = signature_payload(channel_id, &envelope, &aad);
    if !e2ee::verify_p256(&signing_key, &signed, envelope.signature) {
        return Err(AppError::BadRequest(
            "The sender-device signature does not verify".into(),
        ));
    }

    let key = base64::engine::general_purpose::STANDARD
        .decode(
            key_b64
                .filter(|value| !value.is_empty())
                .ok_or_else(|| AppError::BadRequest("messageKey is required".into()))?,
        )
        .map_err(|_| AppError::BadRequest("messageKey is not valid base64".into()))?;
    if key.len() != 32 {
        return Err(AppError::BadRequest(
            "messageKey must be exactly 32 bytes".into(),
        ));
    }
    let cipher = Aes256Gcm::new_from_slice(&key)
        .map_err(|_| AppError::BadRequest("messageKey is invalid".into()))?;
    let plaintext = cipher
        .decrypt(
            Nonce::from_slice(&MESSAGE_NONCE),
            Payload {
                msg: envelope.ciphertext,
                aad: &aad,
            },
        )
        .map_err(|_| {
            AppError::BadRequest(
                "The disclosed key does not authenticate this encrypted message".into(),
            )
        })?;
    if plaintext.len() > MAX_PLAINTEXT_BYTES {
        return Err(AppError::BadRequest(
            "The reported plaintext is too large".into(),
        ));
    }
    let plaintext = String::from_utf8(plaintext)
        .map_err(|_| AppError::BadRequest("The reported plaintext is not UTF-8".into()))?;
    Ok((
        plaintext,
        sender_device_id.to_string(),
        envelope.signature.to_vec(),
    ))
}

type MessageRow = (
    String,
    String,
    String,
    Option<Vec<u8>>,
    Option<i32>,
    Option<i32>,
);

pub async fn message(
    state: &AppState,
    reporter_id: &str,
    message_id: &str,
    reason: Option<&str>,
    message_key: Option<&str>,
) -> AppResult<ReportResult> {
    let row: Option<MessageRow> = sqlx::query_as(
        r#"SELECT "channelId", "authorId", content, ciphertext, "encEpoch", "encVersion"
           FROM "Message" WHERE id = $1"#,
    )
    .bind(message_id)
    .fetch_optional(&state.pool)
    .await?;
    let (channel_id, author_id, stored_content, ciphertext, enc_epoch, enc_version) =
        row.ok_or_else(|| AppError::NotFound("Message not found".into()))?;
    channel::require_channel_access(state, &channel_id, reporter_id).await?;
    if author_id == reporter_id {
        return Err(AppError::BadRequest(
            "You cannot report your own message".into(),
        ));
    }

    let reason = reason
        .map(str::trim)
        .filter(|value| !value.is_empty())
        .map(str::to_string);
    if reason
        .as_ref()
        .is_some_and(|value| value.chars().count() > MAX_REASON_CHARS)
    {
        return Err(AppError::BadRequest("The report reason is too long".into()));
    }

    let encrypted = ciphertext.is_some();
    let (plaintext, sender_device_id, signature) = if let Some(bytes) = ciphertext.as_deref() {
        let (plaintext, device, signature) = open_reported_message(
            state,
            &channel_id,
            &author_id,
            enc_epoch.ok_or_else(malformed)?,
            enc_version.ok_or_else(malformed)?,
            bytes,
            message_key,
        )
        .await?;
        (plaintext, Some(device), Some(signature))
    } else {
        if message_key.is_some() {
            return Err(AppError::BadRequest(
                "A plaintext message does not accept messageKey".into(),
            ));
        }
        (stored_content, None, None)
    };

    let id = cuid();
    let inserted: Option<String> = sqlx::query_scalar(
        r#"INSERT INTO "MessageReport"
           (id, "messageId", "channelId", "reporterId", "authorId", reason,
            plaintext, encrypted, ciphertext, "encEpoch", "senderDeviceId", signature)
           VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12)
           ON CONFLICT ("reporterId", "messageId") DO NOTHING
           RETURNING id"#,
    )
    .bind(&id)
    .bind(message_id)
    .bind(&channel_id)
    .bind(reporter_id)
    .bind(&author_id)
    .bind(reason)
    .bind(plaintext)
    .bind(encrypted)
    .bind(ciphertext)
    .bind(enc_epoch)
    .bind(sender_device_id)
    .bind(signature)
    .fetch_optional(&state.pool)
    .await?;

    let already_existed = inserted.is_none();
    let id = if let Some(id) = inserted {
        id
    } else {
        sqlx::query_scalar(
            r#"SELECT id FROM "MessageReport" WHERE "reporterId" = $1 AND "messageId" = $2"#,
        )
        .bind(reporter_id)
        .bind(message_id)
        .fetch_one(&state.pool)
        .await?
    };

    Ok(ReportResult {
        id,
        encrypted,
        already_existed,
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use p256::ecdsa::signature::Signer;
    use p256::ecdsa::{Signature, SigningKey};
    use p256::pkcs8::EncodePublicKey;
    use rand::rngs::OsRng;

    #[test]
    fn malformed_envelopes_are_rejected_without_panicking() {
        for raw in [
            &b""[..],
            &b"OCE1"[..],
            &b"OCE1\x01\0\0\0\x08short"[..],
            &b"NOPE\x01"[..],
        ] {
            assert!(decode_envelope(raw).is_err());
        }
    }

    #[test]
    fn reported_ciphertext_opens_and_its_sender_signature_verifies() {
        let signing = SigningKey::random(&mut OsRng);
        let sender_device = b"device-1";
        let sender_user = b"user-1";
        let empty = [];
        let metadata = Envelope {
            epoch: 3,
            seq: 7,
            sender_device_id: sender_device,
            sender_user_id: sender_user,
            signature: &empty,
            ciphertext: &empty,
        };
        let aad = message_aad("channel-1", &metadata);
        let message_key = [9_u8; 32];
        let plaintext = br#"{"v":1,"text":"reported message"}"#;
        let cipher = Aes256Gcm::new_from_slice(&message_key).unwrap();
        let ciphertext = cipher
            .encrypt(
                Nonce::from_slice(&MESSAGE_NONCE),
                Payload {
                    msg: plaintext,
                    aad: &aad,
                },
            )
            .unwrap();
        let unsigned = Envelope {
            ciphertext: &ciphertext,
            ..metadata
        };
        let payload = signature_payload("channel-1", &unsigned, &aad);
        let signature: Signature = signing.sign(&payload);
        let signature = signature.to_bytes();

        let epoch = 3_u64.to_be_bytes();
        let seq = 7_u64.to_be_bytes();
        let fields = e2ee::encode_fields(&[
            &epoch,
            &seq,
            sender_device,
            sender_user,
            &signature,
            &ciphertext,
        ]);
        let mut encoded = Vec::from(ENVELOPE_MAGIC);
        encoded.push(ENVELOPE_VERSION);
        encoded.extend_from_slice(&fields);

        let decoded = decode_envelope(&encoded).unwrap();
        let decoded_aad = message_aad("channel-1", &decoded);
        assert_eq!(decoded_aad, aad);
        let verifying_der = signing.verifying_key().to_public_key_der().unwrap();
        assert!(e2ee::verify_p256(
            verifying_der.as_bytes(),
            &signature_payload("channel-1", &decoded, &decoded_aad),
            decoded.signature,
        ));
        let opened = cipher
            .decrypt(
                Nonce::from_slice(&MESSAGE_NONCE),
                Payload {
                    msg: decoded.ciphertext,
                    aad: &decoded_aad,
                },
            )
            .unwrap();
        assert_eq!(opened, plaintext);
    }
}
