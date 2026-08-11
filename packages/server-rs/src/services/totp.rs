
use totp_rs::{Algorithm, Secret, TOTP};

use crate::auth::{hash_password, verify_password};
use crate::error::{AppError, AppResult};
use crate::ids::cuid;
use crate::state::AppState;

const ISSUER: &str = "OrangChat";
const BACKUP_CODE_COUNT: usize = 10;
const BACKUP_ALPHABET: &[u8] = b"ABCDEFGHJKMNPQRSTUVWXYZ23456789";

pub fn generate_secret() -> String {
    Secret::generate_secret().to_encoded().to_string()
}

fn totp_for(secret: &str, account: &str) -> AppResult<TOTP> {
    let bytes = Secret::Encoded(secret.to_string())
        .to_bytes()
        .map_err(|_| AppError::Internal("invalid TOTP secret".into()))?;
    TOTP::new(
        Algorithm::SHA1,
        6,
        1,
        30,
        bytes,
        Some(ISSUER.to_string()),
        account.to_string(),
    )
    .map_err(|e| AppError::Internal(format!("TOTP init failed: {e}")))
}

pub fn provisioning_uri(secret: &str, account: &str) -> AppResult<String> {
    Ok(totp_for(secret, account)?.get_url())
}

pub fn verify_code(secret: &str, account: &str, code: &str) -> AppResult<bool> {
    let code = code.trim().replace(' ', "");
    if code.len() != 6 || !code.chars().all(|c| c.is_ascii_digit()) {
        return Ok(false);
    }
    totp_for(secret, account)?
        .check_current(&code)
        .map_err(|e| AppError::Internal(format!("TOTP check failed: {e}")))
}

fn random_backup_code() -> String {
    use rand::Rng;
    let mut rng = rand::thread_rng();
    let block = |rng: &mut rand::rngs::ThreadRng| -> String {
        (0..4)
            .map(|_| BACKUP_ALPHABET[rng.gen_range(0..BACKUP_ALPHABET.len())] as char)
            .collect()
    };
    format!("{}-{}", block(&mut rng), block(&mut rng))
}

pub async fn regenerate_backup_codes(state: &AppState, user_id: &str) -> AppResult<Vec<String>> {
    let codes: Vec<String> = (0..BACKUP_CODE_COUNT)
        .map(|_| random_backup_code())
        .collect();

    let mut tx = state.pool.begin().await?;
    sqlx::query(r#"DELETE FROM "TotpBackupCode" WHERE "userId" = $1"#)
        .bind(user_id)
        .execute(&mut *tx)
        .await?;
    for code in &codes {
        sqlx::query(
            r#"INSERT INTO "TotpBackupCode" (id, "userId", "codeHash") VALUES ($1, $2, $3)"#,
        )
        .bind(cuid())
        .bind(user_id)
        .bind(hash_password(code)?)
        .execute(&mut *tx)
        .await?;
    }
    tx.commit().await?;
    Ok(codes)
}

pub async fn count_unused_backup_codes(state: &AppState, user_id: &str) -> AppResult<i64> {
    Ok(sqlx::query_scalar(
        r#"SELECT count(*) FROM "TotpBackupCode" WHERE "userId" = $1 AND "usedAt" IS NULL"#,
    )
    .bind(user_id)
    .fetch_one(&state.pool)
    .await?)
}

pub async fn consume_backup_code(state: &AppState, user_id: &str, code: &str) -> AppResult<bool> {
    let normalized = code.trim().to_uppercase();
    let rows: Vec<(String, String)> = sqlx::query_as(
        r#"SELECT id, "codeHash" FROM "TotpBackupCode" WHERE "userId" = $1 AND "usedAt" IS NULL"#,
    )
    .bind(user_id)
    .fetch_all(&state.pool)
    .await?;

    for (id, hash) in rows {
        if verify_password(&hash, &normalized) {
            sqlx::query(r#"UPDATE "TotpBackupCode" SET "usedAt" = now() WHERE id = $1"#)
                .bind(&id)
                .execute(&state.pool)
                .await?;
            return Ok(true);
        }
    }
    Ok(false)
}

pub async fn clear_backup_codes(state: &AppState, user_id: &str) -> AppResult<()> {
    sqlx::query(r#"DELETE FROM "TotpBackupCode" WHERE "userId" = $1"#)
        .bind(user_id)
        .execute(&state.pool)
        .await?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    const ACCOUNT: &str = "user@example.com";

    #[test]
    fn accepts_the_code_an_authenticator_would_show() {
        let secret = generate_secret();
        let current = totp_for(&secret, ACCOUNT)
            .unwrap()
            .generate_current()
            .unwrap();
        assert!(verify_code(&secret, ACCOUNT, &current).unwrap());
    }

    #[test]
    fn rejects_wrong_and_malformed_codes() {
        let secret = generate_secret();
        let current = totp_for(&secret, ACCOUNT)
            .unwrap()
            .generate_current()
            .unwrap();
        let wrong = if current == "000000" {
            "111111"
        } else {
            "000000"
        };
        assert!(!verify_code(&secret, ACCOUNT, wrong).unwrap());
        for bad in ["", "12345", "1234567", "abcdef", "12 34 56"] {
            assert!(
                !verify_code(&secret, ACCOUNT, bad).unwrap(),
                "accepted {bad:?}"
            );
        }
    }

    #[test]
    fn a_code_from_another_secret_does_not_work() {
        let a = generate_secret();
        let b = generate_secret();
        let code_b = totp_for(&b, ACCOUNT).unwrap().generate_current().unwrap();
        assert!(!verify_code(&a, ACCOUNT, &code_b).unwrap());
    }

    #[test]
    fn provisioning_uri_carries_issuer_and_account() {
        let secret = generate_secret();
        let uri = provisioning_uri(&secret, ACCOUNT).unwrap();
        assert!(uri.starts_with("otpauth://totp/"), "{uri}");
        assert!(uri.contains("issuer=OrangChat"), "{uri}");
    }

    #[test]
    fn backup_codes_are_unambiguous_and_shaped() {
        for _ in 0..50 {
            let code = random_backup_code();
            assert_eq!(code.len(), 9, "{code}");
            assert_eq!(code.chars().nth(4), Some('-'), "{code}");
            assert!(
                code.chars()
                    .filter(|c| *c != '-')
                    .all(|c| BACKUP_ALPHABET.contains(&(c as u8))),
                "{code}",
            );
        }
    }
}
