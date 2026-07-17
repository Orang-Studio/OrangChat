//! Minimal OAuth 2.0 authorization-code clients for Google and Discord.
//! Mirrors src/auth/oauth.ts — no SDK, plain HTTP via reqwest.

use serde_json::Value;

use crate::config::Config;
use crate::error::{AppError, AppResult};

pub struct OAuthProfile {
    pub provider: String,
    pub provider_id: String,
    pub email: Option<String>,
    pub display_name: String,
    pub avatar_url: Option<String>,
}

pub fn is_oauth_provider(value: &str) -> bool {
    value == "google" || value == "discord"
}

fn creds<'a>(cfg: &'a Config, provider: &str) -> (Option<&'a String>, Option<&'a String>) {
    match provider {
        "google" => (
            cfg.google_client_id.as_ref(),
            cfg.google_client_secret.as_ref(),
        ),
        "discord" => (
            cfg.discord_client_id.as_ref(),
            cfg.discord_client_secret.as_ref(),
        ),
        _ => (None, None),
    }
}

pub fn is_provider_configured(cfg: &Config, provider: &str) -> bool {
    let (id, secret) = creds(cfg, provider);
    id.is_some() && secret.is_some()
}

fn redirect_uri(cfg: &Config, provider: &str) -> String {
    format!(
        "{}/api/auth/oauth/{}/callback",
        cfg.oauth_redirect_base, provider
    )
}

fn auth_url(provider: &str) -> &'static str {
    match provider {
        "google" => "https://accounts.google.com/o/oauth2/v2/auth",
        _ => "https://discord.com/oauth2/authorize",
    }
}
fn token_url(provider: &str) -> &'static str {
    match provider {
        "google" => "https://oauth2.googleapis.com/token",
        _ => "https://discord.com/api/oauth2/token",
    }
}
fn scopes(provider: &str) -> &'static str {
    match provider {
        "google" => "openid email profile",
        _ => "identify email",
    }
}

pub fn authorization_url(cfg: &Config, provider: &str, state: &str) -> String {
    let (id, _) = creds(cfg, provider);
    let client_id = id.cloned().unwrap_or_default();
    let mut params: Vec<(&str, String)> = vec![
        ("client_id", client_id),
        ("redirect_uri", redirect_uri(cfg, provider)),
        ("response_type", "code".into()),
        ("scope", scopes(provider).into()),
        ("state", state.to_string()),
    ];
    if provider == "google" {
        params.push(("access_type", "online".into()));
        params.push(("prompt", "select_account".into()));
    }
    let qs = serde_urlencoded_lite(&params);
    format!("{}?{}", auth_url(provider), qs)
}

pub async fn exchange_code_for_profile(
    cfg: &Config,
    provider: &str,
    code: &str,
) -> AppResult<OAuthProfile> {
    let (id, secret) = creds(cfg, provider);
    let client = reqwest::Client::new();

    let form = [
        ("client_id", id.cloned().unwrap_or_default()),
        ("client_secret", secret.cloned().unwrap_or_default()),
        ("grant_type", "authorization_code".to_string()),
        ("code", code.to_string()),
        ("redirect_uri", redirect_uri(cfg, provider)),
    ];

    let res = client
        .post(token_url(provider))
        .header("Accept", "application/json")
        .form(&form)
        .send()
        .await
        .map_err(|e| AppError::Internal(format!("{provider} token exchange failed: {e}")))?;
    if !res.status().is_success() {
        return Err(AppError::Internal(format!(
            "{provider} token exchange failed: {}",
            res.status()
        )));
    }
    let token: Value = res
        .json()
        .await
        .map_err(|e| AppError::Internal(format!("{provider} token parse: {e}")))?;
    let access_token = token
        .get("access_token")
        .and_then(|v| v.as_str())
        .ok_or_else(|| AppError::Internal("missing access_token".into()))?;

    match provider {
        "google" => fetch_google(&client, access_token).await,
        _ => fetch_discord(&client, access_token).await,
    }
}

async fn fetch_google(client: &reqwest::Client, access_token: &str) -> AppResult<OAuthProfile> {
    let p: Value = client
        .get("https://openidconnect.googleapis.com/v1/userinfo")
        .bearer_auth(access_token)
        .send()
        .await
        .and_then(|r| r.error_for_status())
        .map_err(|e| AppError::Internal(format!("Google userinfo failed: {e}")))?
        .json()
        .await
        .map_err(|e| AppError::Internal(format!("Google userinfo parse: {e}")))?;

    let email = p.get("email").and_then(|v| v.as_str()).map(String::from);
    let name = p.get("name").and_then(|v| v.as_str()).map(String::from);
    let display_name = name
        .or_else(|| {
            email
                .as_ref()
                .and_then(|e| e.split('@').next().map(String::from))
        })
        .unwrap_or_else(|| "user".into());
    Ok(OAuthProfile {
        provider: "google".into(),
        provider_id: p
            .get("sub")
            .and_then(|v| v.as_str())
            .unwrap_or_default()
            .into(),
        email,
        display_name,
        avatar_url: p.get("picture").and_then(|v| v.as_str()).map(String::from),
    })
}

async fn fetch_discord(client: &reqwest::Client, access_token: &str) -> AppResult<OAuthProfile> {
    let p: Value = client
        .get("https://discord.com/api/users/@me")
        .bearer_auth(access_token)
        .send()
        .await
        .and_then(|r| r.error_for_status())
        .map_err(|e| AppError::Internal(format!("Discord userinfo failed: {e}")))?
        .json()
        .await
        .map_err(|e| AppError::Internal(format!("Discord userinfo parse: {e}")))?;

    let id = p
        .get("id")
        .and_then(|v| v.as_str())
        .unwrap_or_default()
        .to_string();
    let username = p
        .get("username")
        .and_then(|v| v.as_str())
        .unwrap_or("user")
        .to_string();
    let global_name = p
        .get("global_name")
        .and_then(|v| v.as_str())
        .map(String::from);
    let avatar = p.get("avatar").and_then(|v| v.as_str());
    Ok(OAuthProfile {
        provider: "discord".into(),
        provider_id: id.clone(),
        email: p.get("email").and_then(|v| v.as_str()).map(String::from),
        display_name: global_name.unwrap_or(username),
        avatar_url: avatar.map(|a| format!("https://cdn.discordapp.com/avatars/{id}/{a}.png")),
    })
}

/// Tiny x-www-form-urlencoded encoder for the authorization query string.
fn serde_urlencoded_lite(params: &[(&str, String)]) -> String {
    params
        .iter()
        .map(|(k, v)| format!("{}={}", urlencode(k), urlencode(v)))
        .collect::<Vec<_>>()
        .join("&")
}

fn urlencode(s: &str) -> String {
    let mut out = String::with_capacity(s.len());
    for b in s.bytes() {
        match b {
            b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'-' | b'_' | b'.' | b'~' => {
                out.push(b as char)
            }
            b' ' => out.push_str("%20"),
            _ => out.push_str(&format!("%{b:02X}")),
        }
    }
    out
}
