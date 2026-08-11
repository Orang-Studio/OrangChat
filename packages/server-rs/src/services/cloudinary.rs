
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

    pub async fn upload(
        &self,
        bytes: Vec<u8>,
        public_id: &str,
        resource_type: &str,
    ) -> AppResult<Uploaded> {
        let timestamp = Self::timestamp();
        let signature = self.sign(&[("public_id", public_id), ("timestamp", &timestamp)]);

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

const ID_PREFIX: &str = "orangchat/";

fn force_download_if_raw(secure_url: &str, resource_type: &str) -> String {
    match secure_url.split_once("/raw/upload/") {
        Some((head, tail)) if resource_type == "raw" => {
            format!("{head}/raw/upload/fl_attachment/{tail}")
        }
        _ => secure_url.to_string(),
    }
}

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

    #[test]
    fn rejects_urls_outside_our_prefix() {
        assert!(public_id_from_url(
            "https://res.cloudinary.com/demo/image/upload/v1/someone-else.png"
        )
        .is_none());
    }

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
        let sig = c.sign(&[("timestamp", "1315060510"), ("public_id", "sample")]);
        let mut expected = Sha1::new();
        expected.update(b"public_id=sample&timestamp=1315060510abcd");
        assert_eq!(sig, hex::encode(expected.finalize()));
    }
}
