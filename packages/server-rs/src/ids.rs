use base64::Engine;
use rand::RngCore;

/// Collision-resistant id, matching Prisma's `@default(cuid())` (cuid v1 format).
pub fn cuid() -> String {
    cuid::cuid1().expect("cuid generation failed")
}

/// 8 url-safe chars, matching server-service.ts generateInviteCode:
/// `randomBytes(6).toString('base64url').slice(0, 8)`.
pub fn invite_code() -> String {
    let mut bytes = [0u8; 6];
    rand::thread_rng().fill_bytes(&mut bytes);
    base64::engine::general_purpose::URL_SAFE_NO_PAD.encode(bytes)
}
