//! Transactional-email delivery through Resend's HTTPS API.

use serde_json::json;

use crate::config::Config;
use crate::error::{AppError, AppResult};

async fn send(config: &Config, to: &str, subject: &str, html: String) -> AppResult<()> {
    let key = config
        .resend_api_key
        .as_deref()
        .ok_or_else(|| AppError::Internal("Email delivery is not configured".into()))?;
    let response = reqwest::Client::new()
        .post("https://api.resend.com/emails")
        .bearer_auth(key)
        .json(&json!({ "from": config.email_from, "to": [to], "subject": subject, "html": html }))
        .send()
        .await
        .map_err(|_| AppError::Internal("Could not send email".into()))?;
    if response.status().is_success() {
        Ok(())
    } else {
        tracing::error!(status = %response.status(), "Resend rejected account email");
        Err(AppError::Internal("Could not send email".into()))
    }
}

pub async fn send_verification(config: &Config, to: &str, token: &str) -> AppResult<()> {
    let url = format!(
        "{}/api/auth/verify-email?token={token}",
        config.client_origin.trim_end_matches('/')
    );
    send(config, to, "Verify your OrangChat email", format!(
        "<h1>Verify your email</h1><p>Verify your email address before accessing OrangChat.</p><p><a href=\"{url}\">Verify email address</a></p><p>This link expires in 24 hours. If you did not create this account, you can ignore this email.</p>"
    )).await
}

pub async fn send_login_code(config: &Config, to: &str, code: &str) -> AppResult<()> {
    send(config, to, "Your OrangChat sign-in code", format!(
        "<h1>Confirm your sign-in</h1><p>Enter this code to finish signing in to OrangChat. It expires in 10 minutes.</p><p style=\"font-size:32px;font-weight:bold;letter-spacing:6px\">{code}</p><p>If you did not try to sign in, change your password.</p>"
    )).await
}

/// Sent the moment a wipe is requested, not when it runs. The cancel link is the
/// whole point of the wait: somebody who lost their key ignores this mail, and
/// somebody who did not lose it stops the wipe before anything is destroyed.
pub async fn send_key_deletion_requested(
    config: &Config,
    to: &str,
    token: &str,
    hours: i64,
) -> AppResult<()> {
    let url = format!(
        "{}/api/e2ee/keys/deletion/cancel?token={token}",
        config.client_origin.trim_end_matches('/')
    );
    send(config, to, "Someone asked to erase your OrangChat encryption keys", format!(
        "<h1>Your encryption keys are scheduled to be erased</h1><p>Somebody asked OrangChat to erase the encryption identity on your account. This is meant for people who have lost every device they were signed in on, and it cannot be undone: every message already in your encrypted conversations becomes permanently unreadable.</p><p>It will not happen for another {hours} hours. If any device that still holds your keys opens OrangChat before then, the request is cancelled automatically.</p><p><strong>If this was not you, stop it now:</strong></p><p><a href=\"{url}\">Cancel this and keep my keys</a></p><p>Then change your password and check Settings → Security for sessions you do not recognise.</p>"
    )).await
}

pub async fn send_key_deletion_done(config: &Config, to: &str) -> AppResult<()> {
    send(
        config,
        to,
        "Your OrangChat encryption keys were erased",
        "<h1>Encryption keys erased</h1><p>The encryption identity on your OrangChat account has been erased as requested. The next device you sign in on will set up a fresh one.</p><p>Messages sent to you before now cannot be decrypted by anyone, including us. Your contacts will be warned that your identity changed and will need to confirm it is really you.</p><p>If you did not ask for this, change your password immediately and turn on account lockdown in Settings → Security.</p>".into(),
    )
    .await
}

/// The happy accident: a device nobody thought was still alive checked in, so
/// the account answered for itself and the wipe was called off.
pub async fn send_key_deletion_aborted(config: &Config, to: &str) -> AppResult<()> {
    send(
        config,
        to,
        "Your OrangChat encryption keys were NOT erased",
        "<h1>Key erasure cancelled</h1><p>A device that still holds your encryption keys opened OrangChat while the erasure was waiting, so it was cancelled and nothing was deleted.</p><p>If you meant to erase them, sign out of that device first, then ask again from Settings → Encryption.</p>".into(),
    )
    .await
}

pub async fn send_device_transfer_notice(config: &Config, to: &str) -> AppResult<()> {
    send(
        config,
        to,
        "A new OrangChat encryption device was approved",
        "<h1>Encryption device approved</h1><p>A security code was used to approve a new end-to-end encryption device on your OrangChat account.</p><p>If this was not you, enable account lockdown and revoke the device from Settings → Encryption immediately.</p>".into(),
    )
    .await
}

/// The one-time code that stands in for an authenticator app when the account
/// has no TOTP enrolled. Same 10-minute lifetime and one-code-per-account rule
/// as the sign-in code.
pub async fn send_device_transfer_code(config: &Config, to: &str, code: &str) -> AppResult<()> {
    send(
        config,
        to,
        "Your OrangChat device-transfer code",
        format!(
            "<h1>Add a new device</h1><p>Someone is adding a new device to your OrangChat account. Enter this code on the device that already has your keys to approve it. It expires in 10 minutes.</p><p style=\"font-size:32px;font-weight:bold;letter-spacing:6px\">{code}</p><p>If this was not you, ignore this email and consider enabling account lockdown.</p>"
        ),
    )
    .await
}
