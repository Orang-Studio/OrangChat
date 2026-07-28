//! Authentication-only security helpers: CAPTCHA policy, opaque verification
//! tokens, one-time email codes, and Google reCAPTCHA verification.

use rand::Rng;
use sha2::{Digest, Sha256};

use crate::config::Config;
use crate::error::{AppError, AppResult};

pub const CAPTCHA_AFTER_FAILED_LOGINS: u64 = 2;

pub fn captcha_required(failed_attempts: u64) -> bool {
    failed_attempts >= CAPTCHA_AFTER_FAILED_LOGINS
}

/// Token values are emailed to a user but never persisted in plaintext.
pub fn random_token() -> String {
    let mut bytes = [0_u8; 32];
    rand::thread_rng().fill(&mut bytes);
    hex::encode(bytes)
}

pub fn token_hash(token: &str) -> String {
    hex::encode(Sha256::digest(token.as_bytes()))
}

pub fn random_email_code() -> String {
    format!("{:06}", rand::thread_rng().gen_range(0..1_000_000))
}

pub fn valid_email_code(code: &str) -> bool {
    code.len() == 6 && code.bytes().all(|b| b.is_ascii_digit())
}

/// Validate a v2 checkbox token server-side when this deployment has enabled
/// reCAPTCHA. Rate limits remain the fallback for self-hosted deployments that
/// have not provisioned Google keys.
pub async fn verify_recaptcha(
    config: &Config,
    token: Option<&str>,
    remote_ip: &str,
) -> AppResult<()> {
    let Some(secret) = config.recaptcha_secret_key.as_deref() else {
        return Ok(());
    };
    let token = token
        .filter(|value| !value.is_empty())
        .ok_or_else(|| AppError::BadRequest("Complete the reCAPTCHA challenge".into()))?;

    let mut form = vec![("secret", secret), ("response", token)];
    if remote_ip != "unknown" {
        form.push(("remoteip", remote_ip));
    }
    let result = reqwest::Client::new()
        .post("https://www.google.com/recaptcha/api/siteverify")
        .form(&form)
        .send()
        .await
        .map_err(|_| AppError::BadRequest("Could not verify the reCAPTCHA challenge".into()))?;
    let status = result.status();
    let body: serde_json::Value = result
        .json()
        .await
        .map_err(|_| AppError::BadRequest("Could not verify the reCAPTCHA challenge".into()))?;
    if status.is_success() && body.get("success").and_then(serde_json::Value::as_bool) == Some(true)
    {
        Ok(())
    } else {
        Err(AppError::BadRequest(
            "Complete the reCAPTCHA challenge".into(),
        ))
    }
}

#[cfg(test)]
mod tests {
    use super::{captcha_required, token_hash, valid_email_code};

    #[test]
    fn requires_captcha_after_two_failed_logins() {
        assert!(!captcha_required(1));
        assert!(captcha_required(2));
    }

    #[test]
    fn token_hash_is_deterministic_without_retaining_the_token() {
        assert_eq!(token_hash("secret"), token_hash("secret"));
        assert_ne!(token_hash("secret"), "secret");
    }

    #[test]
    fn email_codes_must_be_exactly_six_digits() {
        assert!(valid_email_code("000123"));
        assert!(!valid_email_code("12345"));
        assert!(!valid_email_code("1234567"));
        assert!(!valid_email_code("12a456"));
    }
}
