
use serde_json::Value;

use crate::config::Config;
use crate::error::{AppError, AppResult};

pub struct ConnectionProfile {
    pub provider: String,
    pub provider_id: String,
    pub name: String,
    pub profile_url: Option<String>,
}

pub struct OAuthGrant {
    pub access_token: String,
    pub refresh_token: Option<String>,
    pub expires_in: i64,
    pub scope: Option<String>,
}

pub struct AuthorizedConnection {
    pub profile: ConnectionProfile,
    pub grant: OAuthGrant,
}

pub struct Provider {
    pub key: &'static str,
    pub label: &'static str,
    pub auth_url: &'static str,
    pub token_url: &'static str,
    pub scopes: &'static str,
    pub basic_auth: bool,
    pub pkce: bool,
}

pub const PROVIDERS: &[Provider] = &[
    Provider {
        key: "github",
        label: "GitHub",
        auth_url: "https://github.com/login/oauth/authorize",
        token_url: "https://github.com/login/oauth/access_token",
        scopes: "read:user",
        basic_auth: false,
        pkce: false,
    },
    Provider {
        key: "gitlab",
        label: "GitLab",
        auth_url: "https://gitlab.com/oauth/authorize",
        token_url: "https://gitlab.com/oauth/token",
        scopes: "read_user",
        basic_auth: false,
        pkce: false,
    },
    Provider {
        key: "twitch",
        label: "Twitch",
        auth_url: "https://id.twitch.tv/oauth2/authorize",
        token_url: "https://id.twitch.tv/oauth2/token",
        scopes: "user:read:email",
        basic_auth: false,
        pkce: false,
    },
    Provider {
        key: "youtube",
        label: "YouTube",
        auth_url: "https://accounts.google.com/o/oauth2/v2/auth",
        token_url: "https://oauth2.googleapis.com/token",
        scopes: "https://www.googleapis.com/auth/youtube.readonly",
        basic_auth: false,
        pkce: false,
    },
    Provider {
        key: "reddit",
        label: "Reddit",
        auth_url: "https://www.reddit.com/api/v1/authorize",
        token_url: "https://www.reddit.com/api/v1/access_token",
        scopes: "identity",
        basic_auth: true,
        pkce: false,
    },
    Provider {
        key: "x",
        label: "X",
        auth_url: "https://twitter.com/i/oauth2/authorize",
        token_url: "https://api.twitter.com/2/oauth2/token",
        scopes: "users.read tweet.read",
        basic_auth: true,
        pkce: true,
    },
    Provider {
        key: "steam",
        label: "Steam",
        auth_url: "https://steamcommunity.com/openid/login",
        token_url: "",
        scopes: "",
        basic_auth: false,
        pkce: false,
    },
];

pub const CUSTOM: &str = "custom";

pub fn find(key: &str) -> Option<&'static Provider> {
    PROVIDERS.iter().find(|p| p.key == key)
}

pub fn creds<'a>(cfg: &'a Config, provider: &str) -> (Option<&'a String>, Option<&'a String>) {
    match provider {
        "github" => (
            cfg.github_client_id.as_ref(),
            cfg.github_client_secret.as_ref(),
        ),
        "gitlab" => (
            cfg.gitlab_client_id.as_ref(),
            cfg.gitlab_client_secret.as_ref(),
        ),
        "twitch" => (
            cfg.twitch_client_id.as_ref(),
            cfg.twitch_client_secret.as_ref(),
        ),
        "youtube" => (
            cfg.youtube_client_id.as_ref(),
            cfg.youtube_client_secret.as_ref(),
        ),
        "reddit" => (
            cfg.reddit_client_id.as_ref(),
            cfg.reddit_client_secret.as_ref(),
        ),
        "x" => (cfg.x_client_id.as_ref(), cfg.x_client_secret.as_ref()),
        _ => (None, None),
    }
}

pub fn is_configured(cfg: &Config, provider: &str) -> bool {
    if provider == "steam" {
        return true;
    }
    let (id, secret) = creds(cfg, provider);
    id.is_some() && secret.is_some()
}

pub fn redirect_uri(cfg: &Config, provider: &str) -> String {
    format!(
        "{}/api/connections/{}/callback",
        cfg.oauth_redirect_base, provider
    )
}

pub fn authorization_url(
    cfg: &Config,
    provider: &Provider,
    state: &str,
    challenge: &str,
) -> String {
    let (id, _) = creds(cfg, provider.key);
    let mut params: Vec<(&str, String)> = vec![
        ("client_id", id.cloned().unwrap_or_default()),
        ("redirect_uri", redirect_uri(cfg, provider.key)),
        ("response_type", "code".into()),
        ("scope", provider.scopes.into()),
        ("state", state.to_string()),
    ];
    match provider.key {
        "youtube" => params.push(("prompt", "select_account".into())),
        "reddit" => params.push(("duration", "temporary".into())),
        _ => {}
    }
    if provider.pkce {
        params.push(("code_challenge", challenge.to_string()));
        params.push(("code_challenge_method", "plain".into()));
    }
    format!("{}?{}", provider.auth_url, encode_params(&params))
}

pub async fn exchange_code_for_profile(
    cfg: &Config,
    provider: &Provider,
    code: &str,
    verifier: &str,
) -> AppResult<AuthorizedConnection> {
    let (id, secret) = creds(cfg, provider.key);
    let client_id = id.cloned().unwrap_or_default();
    let client_secret = secret.cloned().unwrap_or_default();
    let client = reqwest::Client::new();

    let mut form: Vec<(&str, String)> = vec![
        ("grant_type", "authorization_code".into()),
        ("code", code.to_string()),
        ("redirect_uri", redirect_uri(cfg, provider.key)),
    ];
    if provider.pkce {
        form.push(("code_verifier", verifier.to_string()));
    }
    if !provider.basic_auth {
        form.push(("client_id", client_id.clone()));
        form.push(("client_secret", client_secret.clone()));
    } else {
        form.push(("client_id", client_id.clone()));
    }

    let mut req = client
        .post(provider.token_url)
        .header("Accept", "application/json")
        .header("User-Agent", "orangchat/1.0 (+https://chat.oranges.lt)")
        .form(&form);
    if provider.basic_auth {
        req = req.basic_auth(&client_id, Some(&client_secret));
    }

    let res = req
        .send()
        .await
        .map_err(|e| AppError::Internal(format!("{} token exchange failed: {e}", provider.key)))?;
    if !res.status().is_success() {
        return Err(AppError::Internal(format!(
            "{} token exchange failed: {}",
            provider.key,
            res.status()
        )));
    }
    let token: Value = res
        .json()
        .await
        .map_err(|e| AppError::Internal(format!("{} token parse: {e}", provider.key)))?;
    let access_token = token
        .get("access_token")
        .and_then(Value::as_str)
        .ok_or_else(|| AppError::Internal(format!("{}: missing access_token", provider.key)))?
        .to_string();

    let profile = fetch_profile(cfg, provider.key, &access_token).await?;
    Ok(AuthorizedConnection {
        profile,
        grant: OAuthGrant {
            access_token,
            refresh_token: token
                .get("refresh_token")
                .and_then(Value::as_str)
                .map(str::to_string),
            expires_in: token
                .get("expires_in")
                .and_then(Value::as_i64)
                .unwrap_or(3600),
            scope: token
                .get("scope")
                .and_then(Value::as_str)
                .map(str::to_string),
        },
    })
}

async fn fetch_profile(cfg: &Config, provider: &str, token: &str) -> AppResult<ConnectionProfile> {
    let client = reqwest::Client::new();
    let get = |url: &str| {
        client
            .get(url)
            .bearer_auth(token)
            .header("User-Agent", "orangchat/1.0 (+https://chat.oranges.lt)")
    };
    let fail = |e: String| AppError::Internal(format!("{provider} profile fetch failed: {e}"));

    match provider {
        "github" => {
            let p: Value = json(get("https://api.github.com/user"))
                .await
                .map_err(fail)?;
            let login = str_at(&p, "login").unwrap_or_else(|| "user".into());
            Ok(ConnectionProfile {
                provider: provider.into(),
                provider_id: num_or_str(&p, "id"),
                profile_url: Some(
                    str_at(&p, "html_url").unwrap_or(format!("https://github.com/{login}")),
                ),
                name: login,
            })
        }
        "gitlab" => {
            let p: Value = json(get("https://gitlab.com/api/v4/user"))
                .await
                .map_err(fail)?;
            let username = str_at(&p, "username").unwrap_or_else(|| "user".into());
            Ok(ConnectionProfile {
                provider: provider.into(),
                provider_id: num_or_str(&p, "id"),
                profile_url: Some(
                    str_at(&p, "web_url").unwrap_or(format!("https://gitlab.com/{username}")),
                ),
                name: username,
            })
        }
        "spotify" => {
            let p: Value = json(get("https://api.spotify.com/v1/me"))
                .await
                .map_err(fail)?;
            let id = str_at(&p, "account_id")
                .or_else(|| str_at(&p, "id"))
                .unwrap_or_default();
            Ok(ConnectionProfile {
                provider: provider.into(),
                name: str_at(&p, "display_name").unwrap_or_else(|| id.clone()),
                profile_url: p
                    .pointer("/external_urls/spotify")
                    .and_then(Value::as_str)
                    .map(String::from)
                    .or(Some(format!("https://open.spotify.com/user/{id}"))),
                provider_id: id,
            })
        }
        "twitch" => {
            let (id, _) = creds(cfg, provider);
            let p: Value = json(
                get("https://api.twitch.tv/helix/users")
                    .header("Client-Id", id.cloned().unwrap_or_default()),
            )
            .await
            .map_err(fail)?;
            let u = p
                .pointer("/data/0")
                .ok_or_else(|| AppError::Internal("twitch: empty user list".into()))?;
            let login = str_at(u, "login").unwrap_or_else(|| "user".into());
            Ok(ConnectionProfile {
                provider: provider.into(),
                provider_id: str_at(u, "id").unwrap_or_default(),
                name: str_at(u, "display_name").unwrap_or_else(|| login.clone()),
                profile_url: Some(format!("https://twitch.tv/{login}")),
            })
        }
        "youtube" => {
            let p: Value = json(get(
                "https://www.googleapis.com/youtube/v3/channels?part=snippet&mine=true",
            ))
            .await
            .map_err(fail)?;
            let c = p.pointer("/items/0").ok_or_else(|| {
                AppError::BadRequest("That Google account has no YouTube channel".into())
            })?;
            let id = str_at(c, "id").unwrap_or_default();
            let handle = c.pointer("/snippet/customUrl").and_then(Value::as_str);
            Ok(ConnectionProfile {
                provider: provider.into(),
                name: c
                    .pointer("/snippet/title")
                    .and_then(Value::as_str)
                    .unwrap_or("channel")
                    .to_string(),
                profile_url: Some(match handle {
                    Some(h) => format!("https://youtube.com/{h}"),
                    None => format!("https://youtube.com/channel/{id}"),
                }),
                provider_id: id,
            })
        }
        "reddit" => {
            let p: Value = json(get("https://oauth.reddit.com/api/v1/me"))
                .await
                .map_err(fail)?;
            let name = str_at(&p, "name").unwrap_or_else(|| "user".into());
            Ok(ConnectionProfile {
                provider: provider.into(),
                provider_id: str_at(&p, "id").unwrap_or_else(|| name.clone()),
                profile_url: Some(format!("https://reddit.com/user/{name}")),
                name,
            })
        }
        "x" => {
            let p: Value = json(get("https://api.twitter.com/2/users/me"))
                .await
                .map_err(fail)?;
            let u = p
                .pointer("/data")
                .ok_or_else(|| AppError::Internal("x: missing user data".into()))?;
            let username = str_at(u, "username").unwrap_or_else(|| "user".into());
            Ok(ConnectionProfile {
                provider: provider.into(),
                provider_id: str_at(u, "id").unwrap_or_default(),
                profile_url: Some(format!("https://x.com/{username}")),
                name: str_at(u, "name").unwrap_or_else(|| username.clone()),
            })
        }
        _ => Err(AppError::NotFound("Unknown provider".into())),
    }
}


const STEAM_LOGIN: &str = "https://steamcommunity.com/openid/login";
const STEAM_NS: &str = "http://specs.openid.net/auth/2.0";

pub fn steam_authorization_url(cfg: &Config, state: &str) -> String {
    let return_to = format!("{}?state={}", redirect_uri(cfg, "steam"), urlencode(state));
    let realm = cfg.oauth_redirect_base.clone();
    let params = [
        ("openid.ns", STEAM_NS.to_string()),
        ("openid.mode", "checkid_setup".into()),
        ("openid.return_to", return_to),
        ("openid.realm", realm),
        ("openid.identity", format!("{STEAM_NS}/identifier_select")),
        ("openid.claimed_id", format!("{STEAM_NS}/identifier_select")),
    ];
    format!("{}?{}", STEAM_LOGIN, encode_params(&params))
}

pub async fn verify_steam_callback(
    cfg: &Config,
    params: &std::collections::HashMap<String, String>,
) -> AppResult<ConnectionProfile> {
    let mut form: Vec<(String, String)> = params
        .iter()
        .filter(|(k, _)| k.starts_with("openid."))
        .map(|(k, v)| (k.clone(), v.clone()))
        .collect();
    form.retain(|(k, _)| k != "openid.mode");
    form.push(("openid.mode".into(), "check_authentication".into()));

    let res = reqwest::Client::new()
        .post(STEAM_LOGIN)
        .form(&form)
        .send()
        .await
        .map_err(|e| AppError::Internal(format!("steam verify failed: {e}")))?
        .text()
        .await
        .map_err(|e| AppError::Internal(format!("steam verify parse: {e}")))?;

    if !res.lines().any(|l| l.trim() == "is_valid:true") {
        return Err(AppError::BadRequest(
            "Steam sign-in could not be verified".into(),
        ));
    }

    let claimed = params
        .get("openid.claimed_id")
        .ok_or_else(|| AppError::BadRequest("Steam returned no identity".into()))?;
    let steam_id = claimed
        .strip_prefix("https://steamcommunity.com/openid/id/")
        .filter(|id| !id.is_empty() && id.chars().all(|c| c.is_ascii_digit()))
        .ok_or_else(|| AppError::BadRequest("Unexpected Steam identity".into()))?
        .to_string();

    let fallback = ConnectionProfile {
        provider: "steam".into(),
        name: steam_id.clone(),
        profile_url: Some(format!("https://steamcommunity.com/profiles/{steam_id}")),
        provider_id: steam_id.clone(),
    };

    let Some(key) = cfg.steam_api_key.as_ref() else {
        return Ok(fallback);
    };
    let url = format!(
        "https://api.steampowered.com/ISteamUser/GetPlayerSummaries/v2/?key={}&steamids={}",
        urlencode(key),
        steam_id
    );
    let Ok(p) = json(reqwest::Client::new().get(&url)).await else {
        return Ok(fallback);
    };
    let Some(player) = p.pointer("/response/players/0") else {
        return Ok(fallback);
    };
    Ok(ConnectionProfile {
        provider: "steam".into(),
        provider_id: steam_id.clone(),
        name: str_at(player, "personaname").unwrap_or(steam_id),
        profile_url: str_at(player, "profileurl").or(fallback.profile_url),
    })
}


async fn json(req: reqwest::RequestBuilder) -> Result<Value, String> {
    req.send()
        .await
        .and_then(|r| r.error_for_status())
        .map_err(|e| e.to_string())?
        .json()
        .await
        .map_err(|e| e.to_string())
}

fn str_at(v: &Value, key: &str) -> Option<String> {
    v.get(key).and_then(Value::as_str).map(String::from)
}

fn num_or_str(v: &Value, key: &str) -> String {
    match v.get(key) {
        Some(Value::Number(n)) => n.to_string(),
        Some(Value::String(s)) => s.clone(),
        _ => String::new(),
    }
}

fn encode_params<K: AsRef<str>>(params: &[(K, String)]) -> String {
    params
        .iter()
        .map(|(k, v)| format!("{}={}", urlencode(k.as_ref()), urlencode(v)))
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
            _ => out.push_str(&format!("%{b:02X}")),
        }
    }
    out
}
