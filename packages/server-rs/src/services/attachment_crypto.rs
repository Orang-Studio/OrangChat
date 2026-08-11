
use aes_gcm::aead::{Aead, AeadCore, KeyInit, OsRng, Payload};
use aes_gcm::{Aes256Gcm, Nonce};

use crate::config::Config;
use crate::error::{AppError, AppResult};

const MAGIC: &[u8; 8] = b"ORANGAE1";
const NONCE_LEN: usize = 12;
const TAG_LEN: usize = 16;
const HEADER_LEN: usize = MAGIC.len() + NONCE_LEN;
pub const ENVELOPE_OVERHEAD: usize = HEADER_LEN + TAG_LEN;

#[derive(Clone)]
pub struct AttachmentCipher {
    cipher: Aes256Gcm,
}

impl AttachmentCipher {
    pub fn from_config(config: &Config) -> Option<Self> {
        config
            .attachment_encryption_key
            .map(|key| AttachmentCipher {
                cipher: Aes256Gcm::new_from_slice(&key).expect("AES-256 key has validated length"),
            })
    }

    pub fn encrypt(&self, plaintext: &[u8], storage_id: &str) -> AppResult<Vec<u8>> {
        let nonce = Aes256Gcm::generate_nonce(&mut OsRng);
        let ciphertext = self
            .cipher
            .encrypt(
                &nonce,
                Payload {
                    msg: plaintext,
                    aad: storage_id.as_bytes(),
                },
            )
            .map_err(|_| AppError::Internal("Failed to encrypt attachment".into()))?;

        let mut envelope = Vec::with_capacity(HEADER_LEN + ciphertext.len());
        envelope.extend_from_slice(MAGIC);
        envelope.extend_from_slice(&nonce);
        envelope.extend_from_slice(&ciphertext);
        Ok(envelope)
    }

    pub fn decrypt(&self, envelope: &[u8], storage_id: &str) -> AppResult<Vec<u8>> {
        if envelope.len() < HEADER_LEN + TAG_LEN || &envelope[..MAGIC.len()] != MAGIC {
            return Err(AppError::Internal("Invalid encrypted attachment".into()));
        }

        let nonce = Nonce::from_slice(&envelope[MAGIC.len()..HEADER_LEN]);
        self.cipher
            .decrypt(
                nonce,
                Payload {
                    msg: &envelope[HEADER_LEN..],
                    aad: storage_id.as_bytes(),
                },
            )
            .map_err(|_| AppError::Internal("Could not decrypt attachment".into()))
    }
}

#[cfg(test)]
mod tests {
    use super::AttachmentCipher;
    use aes_gcm::{Aes256Gcm, KeyInit};

    fn cipher() -> AttachmentCipher {
        AttachmentCipher {
            cipher: Aes256Gcm::new_from_slice(&[7_u8; 32]).unwrap(),
        }
    }

    #[test]
    fn round_trip_preserves_arbitrary_bytes() {
        let plaintext = b"\0not really an image\xff\x80";
        let encrypted = cipher().encrypt(plaintext, "asset-1").unwrap();
        assert_ne!(encrypted.as_slice(), plaintext);
        assert_eq!(cipher().decrypt(&encrypted, "asset-1").unwrap(), plaintext);
    }

    #[test]
    fn every_encryption_uses_a_fresh_nonce() {
        let first = cipher().encrypt(b"same", "asset-1").unwrap();
        let second = cipher().encrypt(b"same", "asset-1").unwrap();
        assert_ne!(first, second);
    }

    #[test]
    fn rejects_tampering_and_wrong_storage_id() {
        let encrypted = cipher().encrypt(b"secret", "asset-1").unwrap();
        assert!(cipher().decrypt(&encrypted, "asset-2").is_err());

        let mut tampered = encrypted;
        *tampered.last_mut().unwrap() ^= 1;
        assert!(cipher().decrypt(&tampered, "asset-1").is_err());
    }
}
