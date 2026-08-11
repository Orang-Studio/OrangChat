use std::env;

use base64::engine::general_purpose::{STANDARD, URL_SAFE_NO_PAD};
use base64::Engine as _;

use crate::services::update_policy::{PlatformPolicy, UpdatePolicy};

#[derive(Clone)]
pub struct Config {
    pub node_env: String,
    pub port: u16,
    pub client_origin: String,
    pub android_origins: Vec<String>,
    pub database_url: String,
    pub redis_url: String,
    pub jwt_access_secret: String,
    pub jwt_refresh_secret: String,
    pub access_ttl_seconds: i64,
    pub refresh_ttl_seconds: i64,
    pub recaptcha_site_key: Option<String>,
    pub recaptcha_secret_key: Option<String>,
    pub resend_api_key: Option<String>,
    pub email_from: String,
    pub google_client_id: Option<String>,
    pub google_client_secret: Option<String>,
    pub discord_client_id: Option<String>,
    pub discord_client_secret: Option<String>,
    pub oauth_redirect_base: String,
    pub github_client_id: Option<String>,
    pub github_client_secret: Option<String>,
    pub gitlab_client_id: Option<String>,
    pub gitlab_client_secret: Option<String>,
    pub spotify_client_id: Option<String>,
    pub spotify_client_secret: Option<String>,
    pub twitch_client_id: Option<String>,
    pub twitch_client_secret: Option<String>,
    pub youtube_client_id: Option<String>,
    pub youtube_client_secret: Option<String>,
    pub reddit_client_id: Option<String>,
    pub reddit_client_secret: Option<String>,
    pub x_client_id: Option<String>,
    pub x_client_secret: Option<String>,
    pub steam_api_key: Option<String>,
    pub livekit_url: Option<String>,
    pub livekit_api_key: Option<String>,
    pub livekit_api_secret: Option<String>,
    pub cloudinary_cloud_name: Option<String>,
    pub cloudinary_api_key: Option<String>,
    pub cloudinary_api_secret: Option<String>,
    pub attachment_encryption_key: Option<[u8; 32]>,
    pub openai_api_key: Option<String>,
    pub vapid_public_key: Option<String>,
    pub vapid_private_key: Option<String>,
    pub vapid_subject: Option<String>,
    pub fcm_service_account: Option<String>,
    pub orangmove_api_url: String,
    pub badges_file: String,
    pub i18n_dir: String,
    pub update_policy: UpdatePolicy,
}

impl Config {
    pub fn is_prod(&self) -> bool {
        self.node_env == "production"
    }

    pub fn from_env() -> Result<Config, String> {
        let database_url = req("DATABASE_URL")?;
        let access_ttl = env::var("JWT_ACCESS_TTL").unwrap_or_else(|_| "15m".into());
        let refresh_ttl = env::var("JWT_REFRESH_TTL").unwrap_or_else(|_| "30d".into());
        let cloudinary = cloudinary_credentials()?;
        let attachment_encryption_key = attachment_encryption_key()?;
        if cloudinary.is_some() && attachment_encryption_key.is_none() {
            return Err(
                "ATTACHMENT_ENCRYPTION_KEY is required when Cloudinary is configured; \
                 generate one with `openssl rand -base64 32`"
                    .into(),
            );
        }
        let vapid = vapid_credentials()?;
        let android_origins = android_origins()?;

        Ok(Config {
            node_env: env::var("NODE_ENV").unwrap_or_else(|_| "development".into()),
            port: env::var("PORT")
                .ok()
                .and_then(|p| p.parse().ok())
                .unwrap_or(3001),
            client_origin: env::var("CLIENT_ORIGIN")
                .unwrap_or_else(|_| "http://localhost:5173".into()),
            android_origins,
            database_url,
            redis_url: env::var("REDIS_URL").unwrap_or_else(|_| "redis://localhost:6380".into()),
            jwt_access_secret: env::var("JWT_ACCESS_SECRET")
                .unwrap_or_else(|_| "dev-access-secret-change-me".into()),
            jwt_refresh_secret: env::var("JWT_REFRESH_SECRET")
                .unwrap_or_else(|_| "dev-refresh-secret-change-me".into()),
            access_ttl_seconds: duration_to_seconds(&access_ttl)?,
            refresh_ttl_seconds: duration_to_seconds(&refresh_ttl)?,
            recaptcha_site_key: opt("RECAPTCHA_SITE_KEY"),
            recaptcha_secret_key: opt("RECAPTCHA_SECRET_KEY"),
            resend_api_key: opt("RESEND_API_KEY"),
            email_from: env::var("EMAIL_FROM")
                .unwrap_or_else(|_| "OrangChat <noreply@oranges.lt>".into()),
            google_client_id: opt("GOOGLE_CLIENT_ID"),
            google_client_secret: opt("GOOGLE_CLIENT_SECRET"),
            discord_client_id: opt("DISCORD_CLIENT_ID"),
            discord_client_secret: opt("DISCORD_CLIENT_SECRET"),
            oauth_redirect_base: env::var("OAUTH_REDIRECT_BASE")
                .unwrap_or_else(|_| "http://localhost:3001".into()),
            github_client_id: opt("GITHUB_CLIENT_ID"),
            github_client_secret: opt("GITHUB_CLIENT_SECRET"),
            gitlab_client_id: opt("GITLAB_CLIENT_ID"),
            gitlab_client_secret: opt("GITLAB_CLIENT_SECRET"),
            spotify_client_id: opt("SPOTIFY_CLIENT_ID"),
            spotify_client_secret: opt("SPOTIFY_CLIENT_SECRET"),
            twitch_client_id: opt("TWITCH_CLIENT_ID"),
            twitch_client_secret: opt("TWITCH_CLIENT_SECRET"),
            youtube_client_id: opt("YOUTUBE_CLIENT_ID"),
            youtube_client_secret: opt("YOUTUBE_CLIENT_SECRET"),
            reddit_client_id: opt("REDDIT_CLIENT_ID"),
            reddit_client_secret: opt("REDDIT_CLIENT_SECRET"),
            x_client_id: opt("X_CLIENT_ID"),
            x_client_secret: opt("X_CLIENT_SECRET"),
            steam_api_key: opt("STEAM_API_KEY"),
            livekit_url: opt("LIVEKIT_URL"),
            livekit_api_key: opt("LIVEKIT_API_KEY"),
            livekit_api_secret: opt("LIVEKIT_API_SECRET"),
            cloudinary_cloud_name: cloudinary.clone().map(|c| c.0),
            cloudinary_api_key: cloudinary.clone().map(|c| c.1),
            cloudinary_api_secret: cloudinary.map(|c| c.2),
            attachment_encryption_key,
            openai_api_key: opt("OPENAI_API_KEY"),
            vapid_public_key: vapid.clone().map(|v| v.0),
            vapid_private_key: vapid.clone().map(|v| v.1),
            vapid_subject: vapid.map(|v| v.2),
            fcm_service_account: opt("FCM_SERVICE_ACCOUNT"),
            orangmove_api_url: env::var("ORANGMOVE_API_URL")
                .unwrap_or_else(|_| "http://127.0.0.1:8080/api".into())
                .trim_end_matches('/')
                .to_string(),
            badges_file: env::var("BADGES_FILE")
                .ok()
                .filter(|v| !v.is_empty())
                .unwrap_or_else(|| "badges.json".into()),
            i18n_dir: env::var("I18N_DIR")
                .ok()
                .filter(|v| !v.is_empty())
                .unwrap_or_else(|| "i18n".into()),
            update_policy: UpdatePolicy {
                android: platform_policy("ANDROID"),
                desktop: platform_policy("DESKTOP"),
            },
        })
    }
}

fn platform_policy(prefix: &str) -> PlatformPolicy {
    PlatformPolicy {
        latest: opt(&format!("{prefix}_LATEST_VERSION")),
        min_recommended: opt(&format!("{prefix}_MIN_RECOMMENDED")),
        min_supported: opt(&format!("{prefix}_MIN_SUPPORTED")),
    }
}

fn attachment_encryption_key() -> Result<Option<[u8; 32]>, String> {
    let Some(encoded) = opt("ATTACHMENT_ENCRYPTION_KEY") else {
        return Ok(None);
    };
    parse_attachment_encryption_key(&encoded).map(Some)
}

fn parse_attachment_encryption_key(encoded: &str) -> Result<[u8; 32], String> {
    let decoded = STANDARD.decode(encoded.trim()).map_err(|_| {
        "ATTACHMENT_ENCRYPTION_KEY must be a base64-encoded 32-byte key; \
         generate one with `openssl rand -base64 32`"
            .to_string()
    })?;
    decoded.try_into().map_err(|_| {
        "ATTACHMENT_ENCRYPTION_KEY must decode to exactly 32 bytes; \
         generate one with `openssl rand -base64 32`"
            .to_string()
    })
}

const DEFAULT_ANDROID_FINGERPRINT: &str =
    "D7:25:35:7E:07:C4:E5:7F:E5:40:38:1E:5E:DC:AA:A3:E1:B1:C7:30:C5:5D:18:C2:35:15:05:CA:D6:F9:41:45";

fn android_origins() -> Result<Vec<String>, String> {
    opt("ANDROID_CERT_FINGERPRINTS")
        .unwrap_or_else(|| DEFAULT_ANDROID_FINGERPRINT.to_string())
        .split(',')
        .map(str::trim)
        .filter(|f| !f.is_empty())
        .map(android_origin)
        .collect()
}

fn android_origin(fingerprint: &str) -> Result<String, String> {
    let hex = fingerprint.replace([':', ' '], "");
    if hex.len() != 64 {
        return Err(format!(
            "ANDROID_CERT_FINGERPRINTS: `{fingerprint}` is not a SHA-256 fingerprint \
             (expected 32 hex bytes, optionally colon-separated)"
        ));
    }
    let bytes = (0..32)
        .map(|i| u8::from_str_radix(&hex[i * 2..i * 2 + 2], 16))
        .collect::<Result<Vec<u8>, _>>()
        .map_err(|_| format!("ANDROID_CERT_FINGERPRINTS: `{fingerprint}` is not hex"))?;
    Ok(format!(
        "android:apk-key-hash:{}",
        URL_SAFE_NO_PAD.encode(bytes)
    ))
}

fn req(key: &str) -> Result<String, String> {
    env::var(key).map_err(|_| format!("Missing required env var: {key}"))
}

fn opt(key: &str) -> Option<String> {
    env::var(key).ok().filter(|v| !v.is_empty())
}

fn vapid_credentials() -> Result<Option<(String, String, String)>, String> {
    match (opt("VAPID_PUBLIC_KEY"), opt("VAPID_PRIVATE_KEY")) {
        (Some(public), Some(private)) => {
            let subject = opt("VAPID_SUBJECT").ok_or(
                "VAPID_SUBJECT is required alongside the VAPID keys: push \
                        services reject a signature whose `sub` claim is absent. \
                        Use a mailto: or https: URL they can reach you at",
            )?;
            if !(subject.starts_with("mailto:") || subject.starts_with("https://")) {
                return Err("VAPID_SUBJECT must be a mailto: or https: URL".into());
            }
            Ok(Some((public, private, subject)))
        }
        (None, None) => Ok(None),
        _ => Err(
            "Web Push is half-configured: set both VAPID_PUBLIC_KEY and \
                  VAPID_PRIVATE_KEY, or neither"
                .into(),
        ),
    }
}

fn cloudinary_credentials() -> Result<Option<(String, String, String)>, String> {
    let (mut cloud_name, mut api_key, mut api_secret) = (
        opt("CLOUDINARY_CLOUD_NAME"),
        opt("CLOUDINARY_API_KEY"),
        opt("CLOUDINARY_API_SECRET"),
    );

    if let Some(raw) = opt("CLOUDINARY_URL") {
        let (url_cloud, url_key, url_secret) = parse_cloudinary_url(&raw)?;
        cloud_name = cloud_name.or(Some(url_cloud));
        api_key = api_key.or(Some(url_key));
        api_secret = api_secret.or(Some(url_secret));
    }

    match (cloud_name, api_key, api_secret) {
        (Some(cloud_name), Some(api_key), Some(api_secret)) => {
            Ok(Some((cloud_name, api_key, api_secret)))
        }
        (None, None, None) => Ok(None),
        _ => Err(
            "Cloudinary is half-configured: set CLOUDINARY_URL, or all three \
                  of CLOUDINARY_CLOUD_NAME / CLOUDINARY_API_KEY / CLOUDINARY_API_SECRET, \
                  or none of them"
                .into(),
        ),
    }
}

fn parse_cloudinary_url(raw: &str) -> Result<(String, String, String), String> {
    let malformed = || {
        "CLOUDINARY_URL must look like cloudinary://<api_key>:<api_secret>@<cloud_name>".to_string()
    };
    let rest = raw
        .trim()
        .strip_prefix("cloudinary://")
        .ok_or_else(malformed)?;
    let (creds, cloud_name) = rest.rsplit_once('@').ok_or_else(malformed)?;
    let (api_key, api_secret) = creds.split_once(':').ok_or_else(malformed)?;

    for part in [cloud_name, api_key, api_secret] {
        if part.is_empty() {
            return Err(malformed());
        }
        if part.starts_with('<') || part.ends_with('>') {
            return Err(format!(
                "CLOUDINARY_URL still contains the placeholder {part:?} - copy the real \
                 value from the Cloudinary dashboard's API Keys panel"
            ));
        }
    }
    Ok((
        cloud_name.to_string(),
        api_key.to_string(),
        api_secret.to_string(),
    ))
}

pub fn duration_to_seconds(input: &str) -> Result<i64, String> {
    let s = input.trim();
    let (num, unit) = s.split_at(s.len().saturating_sub(1));
    let value: i64 = num
        .parse()
        .map_err(|_| format!("Invalid duration: {input}"))?;
    let mult = match unit {
        "s" => 1,
        "m" => 60,
        "h" => 3600,
        "d" => 86_400,
        _ => return Err(format!("Invalid duration: {input}")),
    };
    Ok(value * mult)
}

#[cfg(test)]
mod cloudinary_url_tests {
    use super::{parse_attachment_encryption_key, parse_cloudinary_url, STANDARD};
    use base64::Engine as _;

    #[test]
    fn parses_dashboard_format() {
        let (cloud, key, secret) =
            parse_cloudinary_url("cloudinary://123456789:aBcD-eFgH_iJkL@mycloud").unwrap();
        assert_eq!(cloud, "mycloud");
        assert_eq!(key, "123456789");
        assert_eq!(secret, "aBcD-eFgH_iJkL");
    }

    #[test]
    fn splits_on_the_last_at_sign() {
        let (cloud, _, secret) = parse_cloudinary_url("cloudinary://123:se@cret@mycloud").unwrap();
        assert_eq!(cloud, "mycloud");
        assert_eq!(secret, "se@cret");
    }

    #[test]
    fn rejects_unsubstituted_placeholders() {
        let err = parse_cloudinary_url("cloudinary://<your_api_key>:<your_api_secret>@mdonoj6d")
            .unwrap_err();
        assert!(err.contains("placeholder"), "{err}");
    }

    #[test]
    fn rejects_malformed() {
        assert!(parse_cloudinary_url("https://example.com").is_err());
        assert!(parse_cloudinary_url("cloudinary://nocolon@cloud").is_err());
        assert!(parse_cloudinary_url("cloudinary://key:secret").is_err());
        assert!(parse_cloudinary_url("cloudinary://key:@cloud").is_err());
    }

    #[test]
    fn parses_attachment_encryption_key() {
        let encoded = STANDARD.encode([42_u8; 32]);
        assert_eq!(
            parse_attachment_encryption_key(&encoded).unwrap(),
            [42_u8; 32]
        );
    }

    #[test]
    fn rejects_bad_attachment_encryption_keys() {
        assert!(parse_attachment_encryption_key("not base64!").is_err());
        assert!(parse_attachment_encryption_key(&STANDARD.encode([1_u8; 31])).is_err());
    }
}

#[cfg(test)]
mod android_origin_tests {
    use super::{android_origin, DEFAULT_ANDROID_FINGERPRINT};

    #[test]
    fn encodes_the_release_fingerprint() {
        assert_eq!(
            android_origin(DEFAULT_ANDROID_FINGERPRINT).unwrap(),
            "android:apk-key-hash:1yU1fgfE5X_lQDgeXtyqo-GxxzDFXRjCNRUFytb5QUU"
        );
    }

    #[test]
    fn accepts_fingerprints_without_colons() {
        let plain = DEFAULT_ANDROID_FINGERPRINT.replace(':', "");
        assert_eq!(
            android_origin(&plain).unwrap(),
            android_origin(DEFAULT_ANDROID_FINGERPRINT).unwrap()
        );
    }

    #[test]
    fn rejects_anything_that_is_not_a_sha256() {
        assert!(android_origin("AB:CD").is_err());
        assert!(android_origin(&"Z".repeat(64)).is_err());
    }
}
