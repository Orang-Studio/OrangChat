//! Cloudinary-backed blob storage for avatars, banners, and local attachments.
//!
//! Optional: with no credentials configured the callers fall back to writing
//! under UPLOAD_DIR / ATTACHMENT_DIR exactly as before, so dev and any
//! deployment that never sets the vars keep working untouched.
//!
//! Legacy rows do not store a `public_id` alongside the URL, so cleanup recovers
//! it with `public_id_from_url`. Encrypted attachment URLs are handled separately
//! by the attachment service; every upload still uses an explicit public id.

use std::time::{Duration, SystemTime, UNIX_EPOCH};

use serde::Deserialize;
use sha1::{Digest, Sha1};

use crate::config::Config;
use crate::error::{AppError, AppResult};

#[derive(Clone)]
pub struct Cloudinary {
    cloud_name: String,
    api_key: String,
    api_secret: String,
    client: reqwest::Client,
}

pub struct Uploaded {
    pub url: String,
}

#[derive(Deserialize)]
struct UploadResponse {
    secure_url: String,
}

#[derive(Deserialize)]
struct ApiError {
    error: ApiErrorBody,
}

#[derive(Deserialize)]
struct ApiErrorBody {
    message: String,
}

impl Cloudinary {
    pub fn from_config(config: &Config) -> Option<Cloudinary> {
        let cloud_name = config.cloudinary_cloud_name.clone()?;
        let api_key = config.cloudinary_api_key.clone()?;
        let api_secret = config.cloudinary_api_secret.clone()?;
        let client = reqwest::Client::builder()
            .connect_timeout(Duration::from_secs(5))
            .timeout(Duration::from_secs(60))
            .user_agent("OrangChat/1.0")
            .build()
            .ok()?;
        Some(Cloudinary {
            cloud_name,
            api_key,
            api_secret,
            client,
        })
    }

    /// Cloudinary signs the alphabetically sorted `k=v&k=v` of every parameter
    /// except file/api_key/resource_type, with the secret appended (not a keyed
    /// HMAC - the secret is plain suffix input to SHA-1).
    fn sign(&self, params: &[(&str, &str)]) -> String {
        let mut sorted: Vec<&(&str, &str)> = params.iter().collect();
        sorted.sort_by_key(|(k, _)| *k);
        let joined = sorted
            .iter()
            .map(|(k, v)| format!("{k}={v}"))
            .collect::<Vec<_>>()
            .join("&");
        let mut hasher = Sha1::new();
        hasher.update(joined.as_bytes());
        hasher.update(self.api_secret.as_bytes());
        hex::encode(hasher.finalize())
    }

    fn timestamp() -> String {
        SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap_or_default()
            .as_secs()
            .to_string()
    }

    /// `public_id` must already carry its extension for `raw`, and must not for
    /// `image`/`video` - Cloudinary appends the format itself for the latter two.
    pub async fn upload(
        &self,
        bytes: Vec<u8>,
        public_id: &str,
        resource_type: &str,
    ) -> AppResult<Uploaded> {
        let timestamp = Self::timestamp();
        let signature = self.sign(&[("public_id", public_id), ("timestamp", &timestamp)]);

        // The filename is never stored (public_id names the asset) but must be
        // present: a `file` part without one is read as a string, and Cloudinary
        // then tries to fetch the bytes as a remote URL - "Invalid URL for upload".
        let form = reqwest::multipart::Form::new()
            .part(
                "file",
                reqwest::multipart::Part::bytes(bytes).file_name("upload"),
            )
            .text("public_id", public_id.to_string())
            .text("timestamp", timestamp)
            .text("signature", signature)
            .text("api_key", self.api_key.clone());

        let response = self
            .client
            .post(format!(
                "https://api.cloudinary.com/v1_1/{}/{}/upload",
                self.cloud_name, resource_type
            ))
            .multipart(form)
            .send()
            .await
            .map_err(|_| AppError::Internal("Could not reach the image service".into()))?;

        if !response.status().is_success() {
            let detail = response
                .json::<ApiError>()
                .await
                .map(|e| e.error.message)
                .unwrap_or_else(|_| "upload rejected".into());
            tracing::error!("cloudinary upload failed: {detail}");
            return Err(AppError::Internal("Failed to store the file".into()));
        }

        let body: UploadResponse = response
            .json()
            .await
            .map_err(|_| AppError::Internal("Unexpected response from the image service".into()))?;

        Ok(Uploaded {
            url: force_download_if_raw(&body.secure_url, resource_type),
        })
    }

    /// Fetch an opaque raw asset back for server-side decryption. The public id
    /// is minted by us, not accepted from a URL, so it is safe to interpolate.
    pub async fn download_raw(&self, public_id: &str) -> AppResult<Vec<u8>> {
        let response = self
            .client
            .get(format!(
                "https://res.cloudinary.com/{}/raw/upload/{public_id}",
                self.cloud_name
            ))
            .send()
            .await
            .map_err(|_| AppError::Internal("Could not reach the file service".into()))?;

        if response.status() == reqwest::StatusCode::NOT_FOUND {
            return Err(AppError::NotFound("Attachment not found".into()));
        }
        if !response.status().is_success() {
            tracing::warn!(status = %response.status(), "cloudinary attachment download failed");
            return Err(AppError::Internal("Failed to retrieve attachment".into()));
        }

        response
            .bytes()
            .await
            .map(|bytes| bytes.to_vec())
            .map_err(|_| AppError::Internal("Failed to read attachment".into()))
    }

    pub async fn destroy(&self, public_id: &str, resource_type: &str) -> AppResult<()> {
        let timestamp = Self::timestamp();
        let signature = self.sign(&[("public_id", public_id), ("timestamp", &timestamp)]);

        let form = reqwest::multipart::Form::new()
            .text("public_id", public_id.to_string())
            .text("timestamp", timestamp)
            .text("signature", signature)
            .text("api_key", self.api_key.clone());

        self.client
            .post(format!(
                "https://api.cloudinary.com/v1_1/{}/{}/destroy",
                self.cloud_name, resource_type
            ))
            .multipart(form)
            .send()
            .await
            .map_err(|_| AppError::Internal("Could not reach the image service".into()))?;
        Ok(())
    }
}

/// Every id we mint lives under this prefix, which is what lets the parser below
/// skip Cloudinary's version and transformation segments without having to model
/// their grammar.
const ID_PREFIX: &str = "orangchat/";

/// `raw` is whatever bytes the user picked - never decoded, never re-encoded, so
/// it must never render. nginx forces the local copies down with
/// `Content-Disposition: attachment` and a sandbox CSP.
///
/// Cloudinary already defaults raw delivery to `attachment` (verified against the
/// API: an uploaded .html comes back `content-type: text/html` but disposition
/// `attachment`). This states it explicitly rather than resting a security
/// property on someone else's default. Images and video are left inline on
/// purpose: chat previews them.
fn force_download_if_raw(secure_url: &str, resource_type: &str) -> String {
    match secure_url.split_once("/raw/upload/") {
        Some((head, tail)) if resource_type == "raw" => {
            format!("{head}/raw/upload/fl_attachment/{tail}")
        }
        _ => secure_url.to_string(),
    }
}

/// Recover the `public_id` and resource type from a delivery url.
///
/// Sound only because we always upload with an explicit `public_id` under
/// `ID_PREFIX`: everything from that marker to the extension is exactly the id,
/// whatever version or transformation segments Cloudinary put in front of it.
/// Cloudinary's own auto-generated ids would break this, so never store a url
/// this module didn't build.
pub fn public_id_from_url(url: &str) -> Option<(String, String)> {
    let rest = url.strip_prefix("https://res.cloudinary.com/")?;
    let (_cloud, rest) = rest.split_once('/')?;
    let (resource_type, rest) = rest.split_once('/')?;
    if !matches!(resource_type, "image" | "video" | "raw") {
        return None;
    }
    let rest = rest.strip_prefix("upload/")?;

    let marker = rest.find(ID_PREFIX)?;
    let rest = &rest[marker..];
    // Only image/video carry a Cloudinary-appended format; a raw id keeps the
    // extension it was uploaded with.
    let public_id = if resource_type == "raw" {
        rest.to_string()
    } else {
        match rest.rsplit_once('.') {
            Some((stem, _ext)) if !stem.is_empty() => stem.to_string(),
            _ => rest.to_string(),
        }
    };
    Some((public_id, resource_type.to_string()))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn recovers_image_id_with_version() {
        let (id, kind) = public_id_from_url(
            "https://res.cloudinary.com/demo/image/upload/v1699999999/orangchat/avatars/abc123.jpg",
        )
        .unwrap();
        assert_eq!(id, "orangchat/avatars/abc123");
        assert_eq!(kind, "image");
    }

    #[test]
    fn recovers_raw_id_keeping_extension() {
        let (id, kind) = public_id_from_url(
            "https://res.cloudinary.com/demo/raw/upload/v1/orangchat/attachments/abc123.zip",
        )
        .unwrap();
        assert_eq!(id, "orangchat/attachments/abc123.zip");
        assert_eq!(kind, "raw");
    }

    #[test]
    fn recovers_id_without_version_segment() {
        let (id, _) =
            public_id_from_url("https://res.cloudinary.com/demo/image/upload/orangchat/a/b.png")
                .unwrap();
        assert_eq!(id, "orangchat/a/b");
    }

    /// The url the sweeper reads back is the one `upload` rewrote, so the
    /// fl_attachment segment has to survive the round trip.
    #[test]
    fn recovers_id_past_a_transformation_segment() {
        let url = force_download_if_raw(
            "https://res.cloudinary.com/demo/raw/upload/v1/orangchat/attachments/abc.zip",
            "raw",
        );
        assert_eq!(
            url,
            "https://res.cloudinary.com/demo/raw/upload/fl_attachment/v1/orangchat/attachments/abc.zip"
        );
        let (id, kind) = public_id_from_url(&url).unwrap();
        assert_eq!(id, "orangchat/attachments/abc.zip");
        assert_eq!(kind, "raw");
    }

    #[test]
    fn leaves_previewable_types_inline() {
        let url = "https://res.cloudinary.com/demo/image/upload/v1/orangchat/a.png";
        assert_eq!(force_download_if_raw(url, "image"), url);
    }

    #[test]
    fn rejects_foreign_urls() {
        assert!(public_id_from_url("/uploads/abc.png").is_none());
        assert!(public_id_from_url("https://example.com/image/upload/v1/a.png").is_none());
    }

    /// A cloudinary-hosted url that isn't ours has no id to destroy; the sweeper
    /// must not guess one.
    #[test]
    fn rejects_urls_outside_our_prefix() {
        assert!(public_id_from_url(
            "https://res.cloudinary.com/demo/image/upload/v1/someone-else.png"
        )
        .is_none());
    }

    /// Round-trips a real asset. Ignored by default - needs live credentials and
    /// the network. Run with the same env the server uses:
    ///   cargo test --bin orangchat-server -- --ignored round_trips_against_live_api
    #[tokio::test]
    #[ignore]
    async fn round_trips_against_live_api() {
        let _ = tracing_subscriber::fmt().with_test_writer().try_init();
        dotenvy::dotenv().ok();
        let config = crate::config::Config::from_env().expect("config");
        let cloudinary = Cloudinary::from_config(&config).expect("cloudinary credentials");

        let png: &[u8] = &[
            0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0x00, 0x00, 0x00, 0x0d, 0x49, 0x48,
            0x44, 0x52, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01, 0x08, 0x06, 0x00, 0x00,
            0x00, 0x1f, 0x15, 0xc4, 0x89, 0x00, 0x00, 0x00, 0x0d, 0x49, 0x44, 0x41, 0x54, 0x78,
            0xda, 0x63, 0xfc, 0xcf, 0xc0, 0x50, 0x0f, 0x00, 0x04, 0x85, 0x01, 0x80, 0x84, 0xa9,
            0x8c, 0x21, 0x00, 0x00, 0x00, 0x00, 0x49, 0x45, 0x4e, 0x44, 0xae, 0x42, 0x60, 0x82,
        ];
        let public_id = format!("orangchat/images/selftest-{}", crate::ids::cuid());
        let uploaded = cloudinary
            .upload(png.to_vec(), &public_id, "image")
            .await
            .expect("upload");

        let (recovered, resource_type) =
            public_id_from_url(&uploaded.url).expect("url should parse back");
        assert_eq!(recovered, public_id);
        assert_eq!(resource_type, "image");
        cloudinary
            .destroy(&public_id, "image")
            .await
            .expect("destroy");

        // The attachment path uploads only a non-image AES-GCM envelope, then
        // can recover the original bytes after fetching it through raw delivery.
        let storage_id = crate::ids::cuid();
        let encrypted_id = format!("orangchat/attachments/{storage_id}.ocf");
        let cipher = crate::services::attachment_crypto::AttachmentCipher::from_config(&config)
            .expect("attachment encryption key");
        let envelope = cipher.encrypt(png, &storage_id).expect("encrypt");
        assert!(!envelope.starts_with(b"\x89PNG"));
        cloudinary
            .upload(envelope, &encrypted_id, "raw")
            .await
            .expect("encrypted raw upload");
        let downloaded = cloudinary
            .download_raw(&encrypted_id)
            .await
            .expect("encrypted raw download");
        assert_eq!(
            cipher.decrypt(&downloaded, &storage_id).expect("decrypt"),
            png
        );
        // Optional deployed-server check: the running backend must use the same
        // production key and return the original media through its proxy route.
        if let Ok(base_url) = std::env::var("LIVE_ATTACHMENT_BASE_URL") {
            let delivered = reqwest::get(format!(
                "{}/api/attachments/encrypted/{storage_id}.png",
                base_url.trim_end_matches('/')
            ))
            .await
            .expect("deployed attachment request");
            assert!(
                delivered.status().is_success(),
                "deployed attachment route returned {}",
                delivered.status()
            );
            assert_eq!(delivered.bytes().await.expect("deployed bytes"), png);
        }
        cloudinary
            .destroy(&encrypted_id, "raw")
            .await
            .expect("encrypted raw destroy");

        // The raw path carries its extension in the id and must come back marked
        // for download - that's the whole reason un-decoded bytes are safe to host.
        let raw_id = format!("orangchat/attachments/selftest-{}.html", crate::ids::cuid());
        let raw = cloudinary
            .upload(b"<script>alert(1)</script>".to_vec(), &raw_id, "raw")
            .await
            .expect("raw upload");
        assert!(
            raw.url.contains("/raw/upload/fl_attachment/"),
            "{}",
            raw.url
        );

        let (recovered_raw, kind) =
            public_id_from_url(&raw.url).expect("raw url should parse back");
        assert_eq!(recovered_raw, raw_id);
        assert_eq!(kind, "raw");

        let disposition = reqwest::get(&raw.url)
            .await
            .expect("fetch raw")
            .headers()
            .get("content-disposition")
            .and_then(|v| v.to_str().ok())
            .unwrap_or_default()
            .to_string();
        assert!(
            disposition.starts_with("attachment"),
            "raw html must download, not render: {disposition:?}"
        );

        cloudinary
            .destroy(&raw_id, "raw")
            .await
            .expect("raw destroy");
    }

    #[test]
    fn signature_matches_cloudinary_reference() {
        let c = Cloudinary {
            cloud_name: "demo".into(),
            api_key: "key".into(),
            api_secret: "abcd".into(),
            client: reqwest::Client::new(),
        };
        // sha1("public_id=sample&timestamp=1315060510" + "abcd")
        let sig = c.sign(&[("timestamp", "1315060510"), ("public_id", "sample")]);
        let mut expected = Sha1::new();
        expected.update(b"public_id=sample&timestamp=1315060510abcd");
        assert_eq!(sig, hex::encode(expected.finalize()));
    }
}
