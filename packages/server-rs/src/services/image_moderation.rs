
use std::time::Duration;

use base64::engine::general_purpose::STANDARD;
use base64::Engine;
use serde::Deserialize;
use serde_json::json;

use crate::config::Config;

const ENDPOINT: &str = "https://api.openai.com/v1/moderations";

const MODEL: &str = "omni-moderation-latest";

#[derive(Clone)]
pub struct ImageModeration {
    api_key: String,
    client: reqwest::Client,
}

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
            .timeout(Duration::from_secs(20))
            .user_agent("OrangChat/1.0")
            .build()
            .ok()?;
        Some(ImageModeration { api_key, client })
    }

    pub async fn is_flagged(&self, source: ImageSource<'_>) -> bool {
        let url = match source {
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

pub async fn flag_url(moderation: Option<&ImageModeration>, url: &str) -> bool {
    let Some(moderation) = moderation else {
        return false;
    };
    moderation.is_flagged(ImageSource::Url(url)).await
}
