use base64::Engine;
use rand::RngCore;

pub fn cuid() -> String {
    cuid::cuid1().expect("cuid generation failed")
}

pub fn invite_code() -> String {
    let mut bytes = [0u8; 6];
    rand::thread_rng().fill_bytes(&mut bytes);
    base64::engine::general_purpose::URL_SAFE_NO_PAD.encode(bytes)
}
