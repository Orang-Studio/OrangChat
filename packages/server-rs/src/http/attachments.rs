//! Message attachments. Two ways in, one way out:
//!
//! * **≤ 10 MB** - the client posts the bytes here (`/uploads/attachment`) and
//!   they go to Cloudinary, or to OrangChat's own disk when Cloudinary is
//!   unconfigured. Either way they live as long as the message.
//! * **> 10 MB** - the client uploads straight to OrangMove and posts only the
//!   resulting token here (`/uploads/attachment/external`). The bytes never
//!   transit this process; a 1 GB file would otherwise be paid for twice.
//!   Cloudinary does not participate: it is not a general file host and its
//!   raw-upload ceiling sits below what OrangMove is here to carry.
//!
//! Either way the response is a descriptor with an id. Nothing is attached to a
//! message yet: the id names a `PendingAttachment` row that `message:send`
//! claims later (see services::message). That indirection is what keeps a client
//! from inventing its own url/size or pointing an attachment at someone else's
//! upload - the server only ever serializes rows it wrote itself.
//!
//! Caveat worth knowing: OrangMove reaps files within an hour, so attachments
//! over 10 MB carry an `expiresAt` and will 404 afterwards. Clients surface it.
//!
//! Images are moderated on the way in (services::image_moderation) and carry the
//! verdict as `flagged`. It rides on the descriptor rather than blocking the
//! upload; clients withhold the pixels.

use std::io::Cursor;
use std::path::PathBuf;
use std::time::Duration;

use axum::extract::{DefaultBodyLimit, Multipart, Path, Query, State};
use axum::http::header::{
    CACHE_CONTROL, CONTENT_DISPOSITION, CONTENT_LENGTH, CONTENT_SECURITY_POLICY, CONTENT_TYPE,
    ETAG, IF_NONE_MATCH,
};
use axum::http::{HeaderMap, HeaderValue, StatusCode};
use axum::response::{IntoResponse, Response};
use axum::routing::{get, post};
use axum::{Json, Router};
use chrono::{DateTime, Duration as ChronoDuration, NaiveDateTime, Utc};
use serde::Deserialize;
use serde_json::{json, Value};

use crate::error::{AppError, AppResult};
use crate::http::AuthUser;
use crate::http::uploads::process_image;
use crate::ids::cuid;
use crate::services::attachment_crypto::ENVELOPE_OVERHEAD;
use crate::services::cloudinary::public_id_from_url;
use crate::services::image_moderation;
use crate::services::rate_limit;
use crate::state::AppState;
use crate::timefmt::iso_opt;

/// The local/OrangMove split. Clients apply the same threshold when choosing
/// which endpoint to call, so keep the three copies in sync (web:
/// features/chat/attachments.ts, Android: AttachmentUploader.kt).
///
/// 10 MB because that is Cloudinary's ceiling for `image` and `raw` on the Free
/// plan (queried from its /usage endpoint: image_max_size_bytes = raw_max_size_bytes
/// = 10485760). Anything larger would be accepted here and then rejected by the
/// upload, after the user had already waited for the transfer. Raise this the day
/// the plan changes, not before.
pub const MAX_LOCAL_ATTACHMENT: usize = 10 * 1024 * 1024 - ENVELOPE_OVERHEAD;

/// Mirrors `attachmentIds.max(10)` in shared/schemas.ts.
pub const MAX_PER_MESSAGE: usize = 10;

/// An OrangMove token is 32 random bytes as base64url - see its token.rs. Check
/// the shape before spending a request on it.
const ORANGMOVE_TOKEN_LEN: usize = 43;

/// Upper bound on a media duration, in seconds. Prevents a lying client from
/// freezing a number on every receiver that fits whatever it wants there.
const MAX_MEDIA_SECS: f64 = 24.0 * 60.0 * 60.0;

pub fn routes() -> Router<AppState> {
    Router::new()
        .route(
            "/uploads/attachment",
            post(upload_attachment)
                // Per-route: the sibling image route keeps its own smaller cap.
                .layer(DefaultBodyLimit::max(MAX_LOCAL_ATTACHMENT + 1024 * 1024)),
        )
        .route("/uploads/attachment/external", post(register_external))
        .route(
            "/attachments/encrypted/:asset",
            get(deliver_encrypted_attachment),
        )
        .route("/attachments/sealed/:asset", get(deliver_sealed_attachment))
}

/// Both delivery routes are keyed by a storage id that is minted once and never
/// names different bytes, so a copy a client already holds cannot go stale - it
/// can only be evicted. An hour was leaving every re-opened conversation
/// re-downloading (and, for encrypted blobs, re-decrypting) the same files.
/// `private` still keeps plaintext out of shared proxies and CDNs.
const IMMUTABLE_CACHE_CONTROL: &str = "private, max-age=31536000, immutable";

/// The storage id already identifies the bytes uniquely, so it is the entity
/// tag. Worth having even alongside `immutable`: a client that revalidates
/// anyway - after eviction, on a forced reload, or because a proxy asked - gets
/// a 304 from the check below instead of a Cloudinary round trip and a decrypt.
fn etag_for(storage_id: &str) -> String {
    format!("\"{storage_id}\"")
}

/// True when the client's `If-None-Match` already covers `etag`. Handles the
/// comma-separated list and the weak prefix; `*` matches anything we hold.
fn etag_matches(headers: &HeaderMap, etag: &str) -> bool {
    headers
        .get(IF_NONE_MATCH)
        .and_then(|value| value.to_str().ok())
        .is_some_and(|raw| {
            raw.split(',').any(|candidate| {
                let candidate = candidate.trim();
                candidate == "*" || candidate.trim_start_matches("W/") == etag
            })
        })
}

/// A 304 carries no body, but must repeat the caching headers so the client can
/// refresh what it knows about the copy it kept.
fn not_modified(etag: &str) -> AppResult<Response> {
    let mut headers = HeaderMap::new();
    headers.insert(
        ETAG,
        HeaderValue::from_str(etag).map_err(|_| AppError::Internal("Invalid etag".into()))?,
    );
    headers.insert(
        CACHE_CONTROL,
        HeaderValue::from_static(IMMUTABLE_CACHE_CONTROL),
    );
    Ok((StatusCode::NOT_MODIFIED, headers).into_response())
}

const SEALED_PUBLIC_ID_PREFIX: &str = "orangchat/sealed/";

fn sealed_public_id(storage_id: &str) -> String {
    format!("{SEALED_PUBLIC_ID_PREFIX}{storage_id}.{ENCRYPTED_BLOB_EXTENSION}")
}

fn sealed_delivery_url(storage_id: &str) -> String {
    format!("/api/attachments/sealed/{storage_id}.{ENCRYPTED_BLOB_EXTENSION}")
}

pub fn sealed_storage_id_from_delivery_url(url: &str) -> Option<&str> {
    let asset = url.strip_prefix("/api/attachments/sealed/")?;
    let (storage_id, _) = asset.split_once('.')?;
    valid_storage_id(storage_id).then_some(storage_id)
}

/// Hands back exactly the bytes the client uploaded. An attachment in an
/// end-to-end encrypted conversation is sealed under a key only the
/// conversation's devices hold (docs/E2EE.md §7), so there is nothing here to
/// decrypt and nothing worth guessing a content type for: the real filename and
/// type travel inside the message, not beside the blob.
async fn deliver_sealed_attachment(
    State(state): State<AppState>,
    Path(asset): Path<String>,
    request_headers: HeaderMap,
) -> AppResult<Response> {
    let storage_id = asset
        .split_once('.')
        .map(|(id, _)| id)
        .filter(|id| valid_storage_id(id))
        .ok_or_else(|| AppError::NotFound("Attachment not found".into()))?;

    // Before the fetch: a hit here costs nothing but the round trip.
    let etag = etag_for(storage_id);
    if etag_matches(&request_headers, &etag) {
        return not_modified(&etag);
    }

    let bytes = match state.cloudinary.as_ref() {
        Some(cloudinary) => {
            cloudinary
                .download_raw(&sealed_public_id(storage_id))
                .await?
        }
        None => tokio::fs::read(
            attachments_dir().join(format!("{storage_id}.{ENCRYPTED_BLOB_EXTENSION}")),
        )
        .await
        .map_err(|_| AppError::NotFound("Attachment not found".into()))?,
    };

    let mut headers = HeaderMap::new();
    headers.insert(
        CONTENT_TYPE,
        HeaderValue::from_static("application/octet-stream"),
    );
    headers.insert(
        CONTENT_LENGTH,
        HeaderValue::from_str(&bytes.len().to_string())
            .map_err(|_| AppError::Internal("Invalid attachment length".into()))?,
    );
    headers.insert(CONTENT_DISPOSITION, HeaderValue::from_static("attachment"));
    headers.insert(
        CACHE_CONTROL,
        HeaderValue::from_static(IMMUTABLE_CACHE_CONTROL),
    );
    headers.insert(
        ETAG,
        HeaderValue::from_str(&etag).map_err(|_| AppError::Internal("Invalid etag".into()))?,
    );
    headers.insert(
        CONTENT_SECURITY_POLICY,
        HeaderValue::from_static("default-src 'none'; sandbox"),
    );
    Ok((headers, bytes).into_response())
}

const ENCRYPTED_PUBLIC_ID_PREFIX: &str = "orangchat/attachments/";
const ENCRYPTED_BLOB_EXTENSION: &str = "ocf";

fn encrypted_public_id(storage_id: &str) -> String {
    format!("{ENCRYPTED_PUBLIC_ID_PREFIX}{storage_id}.{ENCRYPTED_BLOB_EXTENSION}")
}

fn encrypted_delivery_url(storage_id: &str, original_extension: Option<&str>) -> String {
    let extension = original_extension.unwrap_or("bin");
    format!("/api/attachments/encrypted/{storage_id}.{extension}")
}

fn encrypted_storage_id_from_delivery_url(url: &str) -> Option<&str> {
    let asset = url.strip_prefix("/api/attachments/encrypted/")?;
    let (storage_id, _) = asset.split_once('.')?;
    valid_storage_id(storage_id).then_some(storage_id)
}

fn valid_storage_id(storage_id: &str) -> bool {
    !storage_id.is_empty()
        && storage_id.len() <= 64
        && storage_id
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || matches!(b, b'-' | b'_'))
}

/// Decrypt a new Cloudinary attachment. This intentionally retains the old
/// public, unguessable-link access model: media elements and Android's system
/// downloader cannot attach OrangChat bearer tokens. Cloudinary itself only
/// ever receives the encrypted `.ocf` object.
async fn deliver_encrypted_attachment(
    State(state): State<AppState>,
    Path(asset): Path<String>,
    request_headers: HeaderMap,
) -> AppResult<Response> {
    let storage_id = asset
        .split_once('.')
        .map(|(id, _)| id)
        .filter(|id| valid_storage_id(id))
        .ok_or_else(|| AppError::NotFound("Attachment not found".into()))?;

    // Checked first so a revalidation skips both the download and the decrypt.
    let etag = etag_for(storage_id);
    if etag_matches(&request_headers, &etag) {
        return not_modified(&etag);
    }

    let cloudinary = state
        .cloudinary
        .as_ref()
        .ok_or_else(|| AppError::NotFound("Attachment not found".into()))?;
    let cipher = state
        .attachment_cipher
        .as_ref()
        .ok_or_else(|| AppError::Internal("Attachment encryption is not configured".into()))?;

    let envelope = cloudinary
        .download_raw(&encrypted_public_id(storage_id))
        .await?;
    let plaintext = cipher.decrypt(&envelope, storage_id)?;
    let content_type = content_type_for(&asset);
    let inline = content_type.starts_with("image/")
        || content_type.starts_with("video/")
        || content_type.starts_with("audio/");

    let mut headers = HeaderMap::new();
    headers.insert(CONTENT_TYPE, HeaderValue::from_static(content_type));
    headers.insert(
        CONTENT_LENGTH,
        HeaderValue::from_str(&plaintext.len().to_string())
            .map_err(|_| AppError::Internal("Invalid attachment length".into()))?,
    );
    headers.insert(
        CONTENT_DISPOSITION,
        HeaderValue::from_static(if inline { "inline" } else { "attachment" }),
    );
    // Browser-private caching avoids repeated decryptions without placing
    // plaintext in shared proxies or CDNs.
    headers.insert(
        CACHE_CONTROL,
        HeaderValue::from_static(IMMUTABLE_CACHE_CONTROL),
    );
    headers.insert(
        ETAG,
        HeaderValue::from_str(&etag).map_err(|_| AppError::Internal("Invalid etag".into()))?,
    );
    headers.insert(
        CONTENT_SECURITY_POLICY,
        HeaderValue::from_static("default-src 'none'; sandbox"),
    );
    Ok((headers, plaintext).into_response())
}

#[cfg(test)]
mod cache_tests {
    use super::*;

    #[test]
    fn conditional_requests_are_recognised() {
        let etag = etag_for("abc123");
        assert_eq!(etag, "\"abc123\"");

        let with = |value: &str| {
            let mut headers = HeaderMap::new();
            headers.insert(IF_NONE_MATCH, HeaderValue::from_str(value).unwrap());
            etag_matches(&headers, &etag)
        };

        assert!(with("\"abc123\""));
        assert!(with("*"));
        assert!(with("W/\"abc123\""));
        assert!(with("\"zzz\", \"abc123\""));
        // A different blob, and a bare id without the quotes, are both misses.
        assert!(!with("\"abc124\""));
        assert!(!with("abc123"));
        assert!(!etag_matches(&HeaderMap::new(), &etag));
    }
}

/// Attachments live apart from avatars/banners because they're served under
/// different rules: `/uploads/` hands back re-encoded images, while `/attachments/`
/// forces a download of bytes we never inspected (see deploy/nginx).
fn attachments_dir() -> PathBuf {
    std::env::var("ATTACHMENT_DIR")
        .unwrap_or_else(|_| "/var/lib/orangchat/attachments".into())
        .into()
}

/// Extension → MIME. Only needed for the handful of types worth previewing
/// inline; everything else downloads, and `application/octet-stream` is the
/// right answer for bytes we haven't validated. This is also the only source of
/// a content type for OrangMove files, which report every upload as
/// `application/octet-stream` regardless of what it is.
fn content_type_for(filename: &str) -> &'static str {
    let ext = filename
        .rsplit_once('.')
        .map(|(_, e)| e.to_ascii_lowercase())
        .unwrap_or_default();
    match ext.as_str() {
        "png" => "image/png",
        "jpg" | "jpeg" => "image/jpeg",
        "gif" => "image/gif",
        "webp" => "image/webp",
        "mp4" => "video/mp4",
        "m4v" => "video/x-m4v",
        "webm" => "video/webm",
        "mkv" => "video/x-matroska",
        "mov" => "video/quicktime",
        "3gp" => "video/3gpp",
        "mp3" => "audio/mpeg",
        "m4a" | "mp4a" => "audio/mp4",
        "aac" => "audio/aac",
        "ogg" | "oga" | "opus" => "audio/ogg",
        "weba" => "audio/webm",
        "wav" => "audio/wav",
        "flac" => "audio/flac",
        "pdf" => "application/pdf",
        "txt" | "log" | "md" => "text/plain",
        "json" => "application/json",
        "csv" => "text/csv",
        "zip" => "application/zip",
        _ => "application/octet-stream",
    }
}

/// Keep the real extension on disk so nginx serves images with a content type
/// the browser will render - `nosniff` means a wrong one shows a broken image.
/// The rest of the stored name is a cuid, so the user's filename never reaches
/// the filesystem and can't traverse out of the directory.
fn safe_extension(filename: &str) -> Option<String> {
    let ext = filename.rsplit_once('.')?.1;
    let ok = !ext.is_empty() && ext.len() <= 12 && ext.bytes().all(|b| b.is_ascii_alphanumeric());
    ok.then(|| ext.to_ascii_lowercase())
}

/// Read just the header for dimensions. Decoding a 10 MB image to measure it
/// would hand any user a cheap way to chew through memory.
fn image_dims(bytes: &[u8]) -> Option<(u32, u32)> {
    image::ImageReader::new(Cursor::new(bytes))
        .with_guessed_format()
        .ok()?
        .into_dimensions()
        .ok()
}

/// The wire shape from shared/src/types.ts (`Attachment`).
#[allow(clippy::too_many_arguments)]
fn descriptor(
    id: &str,
    url: &str,
    filename: &str,
    content_type: &str,
    size: i32,
    width: Option<i32>,
    height: Option<i32>,
    duration: Option<f64>,
    thumbnail_url: Option<&str>,
    storage: &str,
    flagged: bool,
    expires_at: Option<NaiveDateTime>,
) -> Value {
    json!({
        "id": id,
        "url": url,
        "filename": filename,
        "contentType": content_type,
        "size": size,
        "width": width,
        "height": height,
        "duration": duration,
        "thumbnailUrl": thumbnail_url,
        "storage": storage,
        "flagged": flagged,
        "expiresAt": iso_opt(expires_at),
    })
}

#[allow(clippy::too_many_arguments)]
async fn insert_pending(
    state: &AppState,
    uploader_id: &str,
    url: &str,
    filename: &str,
    content_type: &str,
    size: i32,
    dims: Option<(u32, u32)>,
    duration: Option<f64>,
    thumbnail_url: Option<&str>,
    storage: &str,
    flagged: bool,
    expires_at: Option<NaiveDateTime>,
) -> AppResult<Value> {
    let id = cuid();
    let (width, height) = match dims {
        Some((w, h)) => (Some(w as i32), Some(h as i32)),
        None => (None, None),
    };

    sqlx::query(
        r#"INSERT INTO "PendingAttachment"
             (id, "uploaderId", url, filename, "contentType", size, width, height, duration, "thumbnailUrl", storage, flagged, "expiresAt")
           VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13)"#,
    )
    .bind(&id)
    .bind(uploader_id)
    .bind(url)
    .bind(filename)
    .bind(content_type)
    .bind(size)
    .bind(width)
    .bind(height)
    .bind(duration)
    .bind(thumbnail_url)
    .bind(storage)
    .bind(flagged)
    .bind(expires_at)
    .execute(&state.pool)
    .await?;

    Ok(descriptor(
        &id,
        url,
        filename,
        content_type,
        size,
        width,
        height,
        duration,
        thumbnail_url,
        storage,
        flagged,
        expires_at,
    ))
}

/// Strip anything the user's filename could smuggle: it's echoed back into chat
/// and used as the browser's download name, never as a path.
fn clean_filename(raw: &str) -> String {
    let base = raw
        .rsplit(['/', '\\'])
        .next()
        .unwrap_or("file")
        .replace(['\r', '\n', '\0'], "");
    let trimmed = base.trim();
    if trimmed.is_empty() || trimmed == "." || trimmed == ".." {
        return "file".into();
    }
    trimmed.chars().take(200).collect()
}

#[derive(Deserialize, Default)]
struct UploadQuery {
    /// Set by clients uploading bytes they have already sealed themselves. The
    /// server then stores an opaque blob and learns only its length.
    #[serde(default)]
    sealed: Option<u8>,
}

/// A duration the client measured on its side (audio length, video length) as
/// a string number of seconds. Only persisted for audio/video; on anything
/// else it is ignored, and a value that fails to parse is the client's bug.
fn parse_duration(raw: Option<&str>, is_media: bool) -> AppResult<Option<f64>> {
    let Some(raw) = raw.filter(|r| !r.is_empty()) else {
        return Ok(None);
    };
    if !is_media {
        return Ok(None);
    }
    let seconds: f64 = raw
        .parse()
        .map_err(|_| AppError::BadRequest("Invalid media duration".into()))?;
    if !seconds.is_finite() || seconds <= 0.0 || seconds > MAX_MEDIA_SECS {
        return Err(AppError::BadRequest("Invalid media duration".into()));
    }
    Ok(Some(seconds))
}

/// Store a client-made video still the same way the main bytes are stored -
/// re-encoded to bound it, encrypted where the bytes are encrypted - and hand
/// back the url to keep next to the attachment.
async fn store_thumbnail(state: &AppState, bytes: Vec<u8>) -> AppResult<String> {
    let (out, ext) = tokio::task::spawn_blocking(move || process_image(&bytes, "avatar"))
        .await
        .map_err(|_| AppError::Internal("Thumbnail processing failed".into()))??;
    let id = cuid();

    if let Some(cloudinary) = &state.cloudinary {
        let cipher = state
            .attachment_cipher
            .as_ref()
            .ok_or_else(|| AppError::Internal("Attachment encryption is not configured".into()))?;
        let encrypted = cipher.encrypt(&out, &id)?;
        let public_id = encrypted_public_id(&id);
        if let Err(e) = cloudinary.upload(encrypted, &public_id, "raw").await {
            let _ = cloudinary.destroy(&public_id, "raw").await;
            return Err(e);
        }
        return Ok(encrypted_delivery_url(&id, Some(ext)));
    }

    let name = format!("{id}.{ext}");
    let dir = attachments_dir();
    tokio::fs::create_dir_all(&dir)
        .await
        .map_err(|_| AppError::Internal("Failed to store thumbnail".into()))?;
    tokio::fs::write(dir.join(&name), &out)
        .await
        .map_err(|_| AppError::Internal("Failed to store thumbnail".into()))?;
    Ok(format!("/attachments/{name}"))
}

async fn upload_attachment(
    user: AuthUser,
    State(state): State<AppState>,
    Query(q): Query<UploadQuery>,
    mut multipart: Multipart,
) -> AppResult<Json<Value>> {
    if q.sealed == Some(1) {
        return upload_sealed(user, state, multipart).await;
    }
    rate_limit::check(
        &state,
        "upload:attachment",
        &user.user_id,
        rate_limit::UPLOAD_ATTACHMENT_PER_USER,
    )
    .await?;

    let mut found: Option<(String, Vec<u8>)> = None;
    let mut duration_raw: Option<String> = None;
    let mut thumbnail: Option<(String, Vec<u8>)> = None;

    while let Some(field) = multipart
        .next_field()
        .await
        .map_err(|_| AppError::BadRequest("Invalid upload".into()))?
    {
        match field.name() {
            Some("file") if found.is_none() => {
                let filename = clean_filename(field.file_name().unwrap_or("file"));
                let data = field.bytes().await.map_err(|_| {
                    AppError::BadRequest("File is too large (max 10 MB for a direct upload)".into())
                })?;
                found = Some((filename, data.to_vec()));
            }
            Some("duration") => {
                duration_raw = Some(
                    field
                        .text()
                        .await
                        .map_err(|_| AppError::BadRequest("Invalid media duration".into()))?,
                );
            }
            Some("thumbnail") => {
                let filename = clean_filename(field.file_name().unwrap_or("thumb"));
                let data = field.bytes().await.map_err(|_| {
                    AppError::BadRequest("Thumbnail is too large".into())
                })?;
                thumbnail = Some((filename, data.to_vec()));
            }
            _ => {}
        }
    }

    let (filename, bytes) = found.ok_or_else(|| AppError::BadRequest("No file provided".into()))?;
    if bytes.is_empty() {
        return Err(AppError::BadRequest("File is empty".into()));
    }
    if bytes.len() > MAX_LOCAL_ATTACHMENT {
        // The body limit usually catches this first; this covers the exact-fit
        // case and keeps the rule true regardless of how the layer is tuned.
        return Err(AppError::BadRequest(
            "File is too large (max 10 MB for a direct upload)".into(),
        ));
    }

    let content_type = content_type_for(&filename);
    let is_image = content_type.starts_with("image/");
    let is_media = content_type.starts_with("audio/") || content_type.starts_with("video/");
    let dims = is_image.then(|| image_dims(&bytes)).flatten();
    let duration = parse_duration(duration_raw.as_deref(), is_media)?;
    let size = bytes.len() as i32;
    let id = cuid();
    let ext = safe_extension(&filename);

    // A video's first frame rides the same upload. It is re-encoded and
    // moderated like any other image; a frame moderation dislikes simply means
    // no preview, never a withheld attachment.
    let thumbnail_url = if is_media {
        match thumbnail.filter(|(_, bytes)| !bytes.is_empty()) {
            Some((thumb_name, thumb)) => {
                let thumb_type = content_type_for(&thumb_name);
                let flagged = image_moderation::flag_bytes(
                    state.image_moderation.as_deref(),
                    &thumb,
                    thumb_type,
                )
                .await;
                if flagged {
                    None
                } else {
                    Some(store_thumbnail(&state, thumb).await?)
                }
            }
            None => None,
        }
    } else {
        None
    };

    let flagged = if is_image {
        image_moderation::flag_bytes(state.image_moderation.as_deref(), &bytes, content_type).await
    } else {
        false
    };

    if let Some(cloudinary) = state.cloudinary.clone() {
        let cipher = state
            .attachment_cipher
            .as_ref()
            .ok_or_else(|| AppError::Internal("Attachment encryption is not configured".into()))?;
        let encrypted = cipher.encrypt(&bytes, &id)?;
        let public_id = encrypted_public_id(&id);
        cloudinary.upload(encrypted, &public_id, "raw").await?;
        let delivery_url = encrypted_delivery_url(&id, ext.as_deref());

        let result = insert_pending(
            &state,
            &user.user_id,
            &delivery_url,
            &filename,
            content_type,
            size,
            dims,
            duration,
            thumbnail_url.as_deref(),
            "cloudinary",
            flagged,
            None,
        )
        .await;

        if result.is_err() {
            let _ = cloudinary.destroy(&public_id, "raw").await;
        }
        return Ok(Json(result?));
    }

    let stored = match &ext {
        Some(ext) => format!("{id}.{ext}"),
        None => id,
    };
    let dir = attachments_dir();
    tokio::fs::create_dir_all(&dir)
        .await
        .map_err(|_| AppError::Internal("Failed to store attachment".into()))?;
    tokio::fs::write(dir.join(&stored), &bytes)
        .await
        .map_err(|_| AppError::Internal("Failed to store attachment".into()))?;

    let url = format!("/attachments/{stored}");
    let result = insert_pending(
        &state,
        &user.user_id,
        &url,
        &filename,
        content_type,
        size,
        dims,
        duration,
        thumbnail_url.as_deref(),
        "local",
        flagged,
        None,
    )
    .await;

    // Don't leave bytes on disk that no row points at.
    if result.is_err() {
        let _ = tokio::fs::remove_file(dir.join(&stored)).await;
    }
    Ok(Json(result?))
}

/// The E2EE path (docs/E2EE.md §7). The client has already encrypted the bytes,
/// the filename and the content type under a key we do not have, so there is
/// nothing here to moderate, no dimensions to read, and no reason to seal it a
/// second time under a key we *do* hold. What the row keeps is a storage id and
/// a byte length, which is all §7 allows it to know.
async fn upload_sealed(
    user: AuthUser,
    state: AppState,
    mut multipart: Multipart,
) -> AppResult<Json<Value>> {
    rate_limit::check(
        &state,
        "upload:attachment",
        &user.user_id,
        rate_limit::UPLOAD_ATTACHMENT_PER_USER,
    )
    .await?;

    let mut bytes: Option<Vec<u8>> = None;
    while let Some(field) = multipart
        .next_field()
        .await
        .map_err(|_| AppError::BadRequest("Invalid upload".into()))?
    {
        if field.name() != Some("file") {
            continue;
        }
        let data = field.bytes().await.map_err(|_| {
            AppError::BadRequest("File is too large (max 10 MB for a direct upload)".into())
        })?;
        bytes = Some(data.to_vec());
        break;
    }

    let bytes = bytes.ok_or_else(|| AppError::BadRequest("No file provided".into()))?;
    if bytes.is_empty() {
        return Err(AppError::BadRequest("File is empty".into()));
    }
    if bytes.len() > MAX_LOCAL_ATTACHMENT {
        return Err(AppError::BadRequest(
            "File is too large (max 10 MB for a direct upload)".into(),
        ));
    }

    let id = cuid();
    let size = bytes.len() as i32;

    if let Some(cloudinary) = state.cloudinary.clone() {
        let public_id = sealed_public_id(&id);
        cloudinary.upload(bytes, &public_id, "raw").await?;
        let result = insert_pending(
            &state,
            &user.user_id,
            &sealed_delivery_url(&id),
            "sealed",
            "application/octet-stream",
            size,
            None,
            None,
            None,
            "cloudinary",
            false,
            None,
        )
        .await;
        if result.is_err() {
            let _ = cloudinary.destroy(&public_id, "raw").await;
        }
        return Ok(Json(result?));
    }

    let stored = format!("{id}.{ENCRYPTED_BLOB_EXTENSION}");
    let dir = attachments_dir();
    tokio::fs::create_dir_all(&dir)
        .await
        .map_err(|_| AppError::Internal("Failed to store attachment".into()))?;
    tokio::fs::write(dir.join(&stored), &bytes)
        .await
        .map_err(|_| AppError::Internal("Failed to store attachment".into()))?;

    let result = insert_pending(
        &state,
        &user.user_id,
        &sealed_delivery_url(&id),
        "sealed",
        "application/octet-stream",
        size,
        None,
        None,
        None,
        "local",
        false,
        None,
    )
    .await;
    if result.is_err() {
        let _ = tokio::fs::remove_file(dir.join(&stored)).await;
    }
    Ok(Json(result?))
}

/// How long an unsent upload is kept before the sweeper takes it. Long enough to
/// cover someone picking a file and leaving the tab open over lunch.
const PENDING_TTL_HOURS: i64 = 24;

/// Delete staged uploads nobody ever sent, and the local bytes behind them.
///
/// `send_message` removes the row it claims, so anything still here is an upload
/// that was abandoned - a file picked and never sent, or a send that failed.
/// Without this they'd accumulate on disk forever, which is a slow leak any
/// logged-in user could drive.
///
/// Returns how many rows went. OrangMove-backed rows own no bytes of ours: that
/// file is already gone, reaped an hour after it was uploaded. Cloudinary-backed
/// rows do, and are destroyed over the API.
pub async fn sweep_pending(state: &AppState) -> AppResult<u64> {
    // Compute the cutoff here rather than as SQL interval arithmetic: it's one
    // bound timestamp instead of a cast Postgres has to infer a type for.
    let cutoff = Utc::now().naive_utc() - ChronoDuration::hours(PENDING_TTL_HOURS);
    let stale: Vec<(String, String)> = sqlx::query_as(
        r#"DELETE FROM "PendingAttachment"
            WHERE "createdAt" < $1
        RETURNING url, storage"#,
    )
    .bind(cutoff)
    .fetch_all(&state.pool)
    .await?;

    let dir = attachments_dir();
    for (url, storage) in &stale {
        match storage.as_str() {
            "local" => {
                // The row's url is one we built, but read the basename back out
                // rather than trusting it to be one - a join with a "../" would
                // escape the dir.
                if let Some(name) = url
                    .rsplit('/')
                    .next()
                    .filter(|n| !n.is_empty() && !n.contains(".."))
                {
                    let _ = tokio::fs::remove_file(dir.join(name)).await;
                }
            }
            "cloudinary" => {
                // Unconfigured-but-rows-exist happens when credentials are pulled
                // after the fact; the row still goes, the asset is left behind.
                if let Some(cloudinary) = &state.cloudinary {
                    if let Some(storage_id) = encrypted_storage_id_from_delivery_url(url) {
                        let _ = cloudinary
                            .destroy(&encrypted_public_id(storage_id), "raw")
                            .await;
                    } else if let Some(storage_id) = sealed_storage_id_from_delivery_url(url) {
                        let _ = cloudinary
                            .destroy(&sealed_public_id(storage_id), "raw")
                            .await;
                    } else if let Some((public_id, resource_type)) = public_id_from_url(url) {
                        // Compatibility for pending uploads created before
                        // server-side encryption was enabled.
                        let _ = cloudinary.destroy(&public_id, &resource_type).await;
                    }
                }
            }
            _ => {}
        }
    }
    Ok(stale.len() as u64)
}

#[derive(Deserialize)]
struct ExternalBody {
    token: String,
    /// Optional media length in seconds, measured by the sender's client.
    /// There is no way to derive it server-side: the bytes never come through
    /// this process, and OrangMove reports every file as a generic blob.
    #[serde(default)]
    duration: Option<f64>,
    /// Id of a separately uploaded still image (the video's first frame) to
    /// claim as this attachment's thumbnail. Uploaded through the normal
    /// endpoint first, then consumed here so it can never be attached to a
    /// message as a file of its own.
    #[serde(rename = "thumbnailId", default)]
    thumbnail_id: Option<String>,
}

/// What OrangMove's `/api/meta/:token` returns (store::Meta).
#[derive(Deserialize)]
struct OrangMoveMeta {
    name: String,
    size: i64,
    /// Unix seconds. OrangMove's reaper deletes the file at this point.
    expires_at: i64,
}

/// Turn an OrangMove token into an attachment. The token alone is the client's
/// claim; everything recorded about the file comes from OrangMove's own metadata,
/// so a client can't overstate a size or rename someone else's upload. A token
/// that has already expired (or never existed) is rejected rather than stored as
/// a link that's dead on arrival.
async fn register_external(
    user: AuthUser,
    State(state): State<AppState>,
    Json(body): Json<ExternalBody>,
) -> AppResult<Json<Value>> {
    rate_limit::check(
        &state,
        "upload:attachment",
        &user.user_id,
        rate_limit::UPLOAD_ATTACHMENT_PER_USER,
    )
    .await?;

    let token = body.token.trim();
    let valid_shape = token.len() == ORANGMOVE_TOKEN_LEN
        && token
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || b == b'-' || b == b'_');
    if !valid_shape {
        return Err(AppError::BadRequest("Invalid upload token".into()));
    }

    let client = reqwest::Client::builder()
        .connect_timeout(Duration::from_secs(4))
        .timeout(Duration::from_secs(8))
        .user_agent("OrangChat/1.0")
        .build()
        .map_err(|e| AppError::Internal(format!("attachment client error: {e}")))?;

    let response = client
        .get(format!("{}/meta/{}", state.config.orangmove_api_url, token))
        .send()
        .await
        .map_err(|_| AppError::Internal("Could not reach the file service".into()))?;

    if !response.status().is_success() {
        return Err(AppError::BadRequest(
            "That upload has expired or does not exist".into(),
        ));
    }
    let meta: OrangMoveMeta = response
        .json()
        .await
        .map_err(|_| AppError::Internal("Unexpected response from the file service".into()))?;

    let expires_at = DateTime::from_timestamp(meta.expires_at, 0)
        .ok_or_else(|| AppError::Internal("Invalid expiry from the file service".into()))?
        .naive_utc();

    let filename = clean_filename(&meta.name);
    // OrangMove records every upload as application/octet-stream, so the name is
    // the only thing left to go on.
    let content_type = content_type_for(&filename);
    let is_media = content_type.starts_with("audio/") || content_type.starts_with("video/");
    let size = i32::try_from(meta.size)
        .map_err(|_| AppError::BadRequest("That file is too large".into()))?;

    let duration = match body.duration {
        Some(d) if is_media => {
            if !d.is_finite() || d <= 0.0 || d > MAX_MEDIA_SECS {
                return Err(AppError::BadRequest("Invalid media duration".into()));
            }
            Some(d)
        }
        _ => None,
    };

    // The thumbnail was uploaded through the normal endpoint as its own
    // pending row; take it now so it can't be attached anywhere else. A
    // moderation flag on the frame is treated as "no preview".
    let thumbnail_url = match &body.thumbnail_id {
        Some(thumb_id) if is_media => {
            let claimed: Option<(String, bool)> = sqlx::query_as(
                r#"DELETE FROM "PendingAttachment"
                    WHERE id = $1 AND "uploaderId" = $2
                RETURNING url, flagged"#,
            )
            .bind(thumb_id)
            .bind(&user.user_id)
            .fetch_optional(&state.pool)
            .await?;
            match claimed {
                Some((url, flagged)) if !flagged => Some(url),
                Some(_) => None,
                None => {
                    return Err(AppError::BadRequest(
                        "That preview does not exist or was already used".into(),
                    ))
                }
            }
        }
        _ => None,
    };

    // Served through the same-origin proxy rather than the OrangMove host: the
    // browser CSP would otherwise have to allow it, and the token stays off
    // cross-origin referrers. See deploy/nginx/chat.oranges.lt.conf.
    let url = format!("/orangmove/file/{token}");

    // The bytes never come through this process, so moderation names the file by
    // its public url and lets OpenAI fetch it. Absolute, since "public" is the
    // whole requirement; on a dev box CLIENT_ORIGIN is localhost, the fetch fails,
    // and it fails open.
    let flagged = if content_type.starts_with("image/") {
        let public_url = format!(
            "{}{}",
            state.config.client_origin.trim_end_matches('/'),
            url
        );
        image_moderation::flag_url(state.image_moderation.as_deref(), &public_url).await
    } else {
        false
    };

    // Dimensions would mean downloading the file back; not worth it for
    // something this size, so large images lay out from their intrinsic size.
    Ok(Json(
        insert_pending(
            &state,
            &user.user_id,
            &url,
            &filename,
            content_type,
            size,
            None,
            duration,
            thumbnail_url.as_deref(),
            "orangmove",
            flagged,
            Some(expires_at),
        )
        .await?,
    ))
}

#[cfg(test)]
mod encrypted_url_tests {
    use super::{
        encrypted_delivery_url, encrypted_public_id, encrypted_storage_id_from_delivery_url,
    };

    #[test]
    fn encrypted_urls_keep_only_the_safe_extension() {
        let url = encrypted_delivery_url("cm123", Some("png"));
        assert_eq!(url, "/api/attachments/encrypted/cm123.png");
        assert_eq!(encrypted_storage_id_from_delivery_url(&url), Some("cm123"));
        assert_eq!(
            encrypted_public_id("cm123"),
            "orangchat/attachments/cm123.ocf"
        );
    }

    #[test]
    fn rejects_invalid_encrypted_delivery_urls() {
        assert_eq!(
            encrypted_storage_id_from_delivery_url("/api/attachments/encrypted/../secret.png"),
            None
        );
        assert_eq!(
            encrypted_storage_id_from_delivery_url("https://example.test/cm123.png"),
            None
        );
    }
}

#[cfg(test)]
mod media_preview_tests {
    use super::{content_type_for, parse_duration, AppError, MAX_MEDIA_SECS};

    #[test]
    fn recognizes_previewable_media_extensions() {
        assert_eq!(content_type_for("clip.m4v"), "video/x-m4v");
        assert_eq!(content_type_for("clip.mkv"), "video/x-matroska");
        assert_eq!(content_type_for("clip.3gp"), "video/3gpp");
        assert_eq!(content_type_for("voice.oga"), "audio/ogg");
    }

    #[test]
    fn duration_is_optional_and_only_for_media() {
        assert_eq!(parse_duration(None, true).unwrap(), None);
        assert_eq!(parse_duration(Some(""), true).unwrap(), None);
        assert_eq!(parse_duration(Some("12.5"), false).unwrap(), None);
        assert_eq!(parse_duration(Some("12.5"), true).unwrap(), Some(12.5));
    }

    #[test]
    fn rejects_unparsable_or_out_of_range_durations() {
        assert!(matches!(
            parse_duration(Some("abc"), true),
            Err(AppError::BadRequest(_))
        ));
        assert!(matches!(
            parse_duration(Some("0"), true),
            Err(AppError::BadRequest(_))
        ));
        assert!(matches!(
            parse_duration(Some("-1"), true),
            Err(AppError::BadRequest(_))
        ));
        assert!(matches!(
            parse_duration(Some("NaN"), true),
            Err(AppError::BadRequest(_))
        ));
        assert!(matches!(
            parse_duration(Some("inf"), true),
            Err(AppError::BadRequest(_))
        ));
        let over = (MAX_MEDIA_SECS + 1.0).to_string();
        assert!(matches!(
            parse_duration(Some(&over), true),
            Err(AppError::BadRequest(_))
        ));
    }
}
