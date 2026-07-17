//! Image moderation via OpenAI's omni-moderation model. Optional: with
//! `OPENAI_API_KEY` unset nothing is checked and everything comes back unflagged.
//!
//! A flagged image is still stored and still attached to its message — only the
//! pixels are withheld, and clients render a placeholder. Rejecting the upload
//! would hand the uploader a free oracle for probing the classifier.
//!
//! Fails open: a moderation outage must not take the upload path down with it.

use std::time::Duration;

use base64::engine::general_purpose::STANDARD;
use base64::Engine;
use serde::Deserialize;
use serde_json::json;

use crate::config::Config;

const ENDPOINT: &str = "https://api.openai.com/v1/moderations";

/// The only model that accepts images. Unpinned on purpose: for a safety
/// classifier, drifting to the newer model is the point.
const MODEL: &str = "omni-moderation-latest";

#[derive(Clone)]
pub struct ImageModeration {
    api_key: String,
    client: reqwest::Client,
}

/// Uploads that pass through this process hand over their `Bytes`; OrangMove
/// files never do, so those are named by `Url` for OpenAI to fetch itself.
pub enum ImageSource<'a> {
    Bytes {
        bytes: &'a [u8],
        content_type: &'a str,
    },
    Url(&'a str),
}

#[derive(Deserialize)]
struct ModerationResponse {
    results: Vec<ModerationResult>,
}

#[derive(Deserialize)]
struct ModerationResult {
    flagged: bool,
}

impl ImageModeration {
    pub fn from_config(config: &Config) -> Option<ImageModeration> {
        let api_key = config.openai_api_key.clone()?;
        let client = reqwest::Client::builder()
            .connect_timeout(Duration::from_secs(5))
            // Bounded because this sits in front of the user's upload response.
            .timeout(Duration::from_secs(20))
            .user_agent("OrangChat/1.0")
            .build()
            .ok()?;
        Some(ImageModeration { api_key, client })
    }

    pub async fn is_flagged(&self, source: ImageSource<'_>) -> bool {
        let url = match source {
            // A data url keeps the bytes off any public url we'd otherwise have
            // to expose; the endpoint takes JSON only, so there's no upload form.
            ImageSource::Bytes {
                bytes,
                content_type,
            } => format!("data:{content_type};base64,{}", STANDARD.encode(bytes)),
            ImageSource::Url(url) => url.to_string(),
        };

        match self.request(&url).await {
            Ok(flagged) => flagged,
            Err(e) => {
                tracing::warn!("image moderation failed, allowing image: {e}");
                false
            }
        }
    }

    async fn request(&self, image_url: &str) -> Result<bool, String> {
        let response = self
            .client
            .post(ENDPOINT)
            .bearer_auth(&self.api_key)
            .json(&json!({
                "model": MODEL,
                "input": [{ "type": "image_url", "image_url": { "url": image_url } }],
            }))
            .send()
            .await
            .map_err(|e| format!("request error: {e}"))?;

        let status = response.status();
        if !status.is_success() {
            let body = response.text().await.unwrap_or_default();
            return Err(format!("openai returned {status}: {}", body.trim()));
        }

        let parsed: ModerationResponse = response
            .json()
            .await
            .map_err(|e| format!("unexpected response: {e}"))?;
        parsed
            .results
            .first()
            .map(|r| r.flagged)
            .ok_or_else(|| "openai returned no moderation results".to_string())
    }
}

pub async fn flag_bytes(
    moderation: Option<&ImageModeration>,
    bytes: &[u8],
    content_type: &str,
) -> bool {
    let Some(moderation) = moderation else {
        return false;
    };
    moderation
        .is_flagged(ImageSource::Bytes {
            bytes,
            content_type,
        })
        .await
}

/// Only usable when `url` is reachable from the public internet, so callers build
/// it from `CLIENT_ORIGIN`. On a dev box that's localhost, OpenAI's fetch fails,
/// and this fails open — the same answer as having no key, which is correct there.
pub async fn flag_url(moderation: Option<&ImageModeration>, url: &str) -> bool {
    let Some(moderation) = moderation else {
        return false;
    };
    moderation.is_flagged(ImageSource::Url(url)).await
}
