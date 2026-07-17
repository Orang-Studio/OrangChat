//! Permission bitfield — Discord-style, mirrors shared/permissions.ts.
//! Bitfields fit comfortably in i64 (highest bit is 1<<24).

pub const ADMINISTRATOR: i64 = 1 << 0;
pub const MANAGE_SERVER: i64 = 1 << 1;
pub const MANAGE_ROLES: i64 = 1 << 2;
pub const MANAGE_CHANNELS: i64 = 1 << 3;
pub const MANAGE_INVITES: i64 = 1 << 4;
pub const KICK_MEMBERS: i64 = 1 << 5;
pub const BAN_MEMBERS: i64 = 1 << 6;
pub const MANAGE_NICKNAMES: i64 = 1 << 7;
pub const MANAGE_MESSAGES: i64 = 1 << 8;
pub const VIEW_CHANNEL: i64 = 1 << 9;
pub const SEND_MESSAGES: i64 = 1 << 10;
pub const EMBED_LINKS: i64 = 1 << 11;
pub const ATTACH_FILES: i64 = 1 << 12;
pub const ADD_REACTIONS: i64 = 1 << 13;
#[allow(dead_code)]
pub const MENTION_EVERYONE: i64 = 1 << 14;
pub const READ_MESSAGE_HISTORY: i64 = 1 << 15;
pub const CONNECT: i64 = 1 << 16;
pub const SPEAK: i64 = 1 << 17;
pub const VIDEO: i64 = 1 << 18;
pub const SCREEN_SHARE: i64 = 1 << 19;
#[allow(dead_code)]
pub const MUTE_MEMBERS: i64 = 1 << 20;
#[allow(dead_code)]
pub const DEAFEN_MEMBERS: i64 = 1 << 21;
#[allow(dead_code)]
pub const MOVE_MEMBERS: i64 = 1 << 22;
/// Time a member out: no sending, reacting, or speaking until it expires.
pub const MODERATE_MEMBERS: i64 = 1 << 23;
pub const VIEW_AUDIT_LOG: i64 = 1 << 24;
/// Upload, rename and delete the server's custom emoji and soundboard sounds.
pub const MANAGE_EXPRESSIONS: i64 = 1 << 25;

/// Every permission bit set — the owner/admin superset (bits 0..=25).
pub const ALL_PERMISSIONS: i64 = (1 << 26) - 1;

/// Default permissions granted to @everyone on a new server.
pub const DEFAULT_EVERYONE_PERMISSIONS: i64 = VIEW_CHANNEL
    | SEND_MESSAGES
    | EMBED_LINKS
    | ATTACH_FILES
    | ADD_REACTIONS
    | READ_MESSAGE_HISTORY
    | CONNECT
    | SPEAK
    | VIDEO
    | SCREEN_SHARE;

/// True if `permissions` grants `required`. ADMINISTRATOR short-circuits.
pub fn has_permission(permissions: i64, required: i64) -> bool {
    if permissions & ADMINISTRATOR == ADMINISTRATOR {
        return true;
    }
    permissions & required == required
}

pub fn combine(bitfields: &[i64]) -> i64 {
    bitfields.iter().fold(0i64, |acc, b| acc | b)
}

/// Wire encoding: decimal string (JSON has no BigInt).
pub fn serialize(permissions: i64) -> String {
    permissions.to_string()
}

pub fn parse(value: &str) -> i64 {
    value.parse().unwrap_or(0)
}
