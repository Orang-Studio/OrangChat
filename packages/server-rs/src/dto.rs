//! Wire DTOs (camelCase JSON) and mappers from DB rows. Mirrors mappers.ts.

use serde::{Deserialize, Serialize};
use serde_json::Value as Json;

use crate::http::media_proxy::same_origin_asset;
use crate::models::*;
use crate::permissions;
use crate::services::server;
use crate::timefmt::{iso, iso_opt};

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct ActivityDto {
    pub kind: String,
    pub name: String,
    pub details: Option<String>,
    pub url: Option<String>,
    pub image_url: Option<String>,
    pub started_at: Option<String>,
    pub ends_at: Option<String>,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct UserDto {
    pub id: String,
    pub username: String,
    pub display_name: String,
    pub avatar_url: Option<String>,
    pub status: String,
    pub devices: Vec<String>,
    pub activities: Vec<ActivityDto>,
    pub bio: Option<String>,
    pub banner_url: Option<String>,
    pub accent_color: Option<i32>,
    pub pronouns: Option<String>,
    pub profile_css: Option<String>,
    pub badges: Vec<String>,
    /// True for a bot account. Clients render this as a label beside the name -
    /// it is a property of the account, not text anyone can put in a nickname.
    pub bot: bool,
    pub created_at: String,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SelfUserDto {
    pub id: String,
    pub username: String,
    pub display_name: String,
    pub avatar_url: Option<String>,
    pub status: String,
    pub devices: Vec<String>,
    pub activities: Vec<ActivityDto>,
    pub bio: Option<String>,
    pub banner_url: Option<String>,
    pub accent_color: Option<i32>,
    pub pronouns: Option<String>,
    pub profile_css: Option<String>,
    pub badges: Vec<String>,
    pub created_at: String,
    pub email: String,
    pub custom_css: Option<String>,
    /// Replaces the OrangChat mark on this user's own clients. Self-only: it is
    /// deliberately absent from UserDto so it can never be fetched per-viewer.
    pub app_icon_url: Option<String>,
    pub dm_privacy: String,
    pub friend_request_privacy: String,
    pub typing_indicators: bool,
    pub notify_friend_requests: bool,
    pub notify_friend_accepted: bool,
    pub notify_friend_online: bool,
    /// "Require verification before messaging anyone new" (docs/E2EE.md §6.5).
    /// Enforced entirely on the owner's own clients; the server stores it so the
    /// choice follows the account rather than the browser.
    pub e2ee_strict: bool,
    /// "Display the game you're playing". Gates the desktop client's process
    /// scan, and is re-checked here on every `activity:game` so that turning it
    /// off drops presence an older shell is still reporting.
    pub game_activity: bool,
    /// Whether TOTP is active. The secret itself is never serialized.
    pub two_factor_enabled: bool,
    /// False for OAuth-only accounts, which have no password to re-confirm.
    pub has_password: bool,
    /// True while the account is frozen; see services::account.
    pub lockdown: bool,
}

/// A linked external account, as shown on a profile card.
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ConnectionDto {
    pub id: String,
    pub provider: String,
    pub name: String,
    pub profile_url: Option<String>,
    /// True only when an OAuth/OpenID round trip proved control of the account.
    pub verified: bool,
    pub visible: bool,
    pub created_at: String,
}

/// An accepted friend (the *other* user) plus the friendship id.
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct FriendDto {
    pub id: String,
    pub user: UserDto,
    pub created_at: String,
}

/// A pending friend request, tagged by direction relative to the viewer.
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct FriendRequestDto {
    pub id: String,
    pub user: UserDto,
    /// "incoming" (they asked you) | "outgoing" (you asked them).
    pub direction: String,
    pub created_at: String,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ServerDto {
    pub id: String,
    pub name: String,
    pub icon_url: Option<String>,
    pub description: Option<String>,
    pub banner_url: Option<String>,
    pub system_channel_id: Option<String>,
    pub afk_channel_id: Option<String>,
    pub afk_timeout: i32,
    pub default_message_notifications: String,
    pub owner_id: String,
    pub created_at: String,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ChannelDto {
    pub id: String,
    pub server_id: Option<String>,
    pub name: Option<String>,
    #[serde(rename = "type")]
    pub channel_type: String,
    pub topic: Option<String>,
    pub position: i32,
    pub parent_category_id: Option<String>,
    pub nsfw: bool,
    pub rate_limit_per_user: i32,
    pub user_limit: i32,
    pub bitrate: i32,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct RoleDto {
    pub id: String,
    pub server_id: String,
    pub name: String,
    pub color: i32,
    pub permissions: String,
    pub position: i32,
    pub hoist: bool,
    pub mentionable: bool,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct EmojiDto {
    pub id: String,
    pub server_id: String,
    pub name: String,
    pub url: String,
    pub animated: bool,
    pub creator_id: Option<String>,
    pub created_at: String,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ScheduledEventDto {
    pub id: String,
    pub server_id: String,
    pub channel_id: Option<String>,
    pub creator_id: Option<String>,
    pub name: String,
    pub description: Option<String>,
    pub location: Option<String>,
    pub starts_at: String,
    pub ends_at: Option<String>,
    pub created_at: String,
    pub interested_count: i64,
    pub interested: bool,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SoundDto {
    pub id: String,
    pub server_id: String,
    pub name: String,
    pub url: String,
    pub duration: f64,
    pub emoji: Option<String>,
    pub volume: f64,
    pub creator_id: Option<String>,
    pub created_at: String,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ChannelOverwriteDto {
    pub id: String,
    pub channel_id: String,
    #[serde(rename = "type")]
    pub ow_type: String,
    pub target_id: String,
    pub allow: String,
    pub deny: String,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ServerMemberDto {
    pub id: String,
    pub server_id: String,
    pub user_id: String,
    pub nickname: Option<String>,
    pub timed_out_until: Option<String>,
    pub joined_at: String,
    pub role_ids: Vec<String>,
    pub user: UserDto,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ReactionDto {
    pub emoji: String,
    pub count: i64,
    pub me: bool,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct MessageDto {
    pub id: String,
    pub channel_id: String,
    pub author: UserDto,
    pub content: String,
    /// Existing custom emoji referenced by `content`. This is message display
    /// data, not a list of emoji the viewer is allowed to pick.
    pub emojis: Vec<EmojiDto>,
    pub created_at: String,
    pub edited_at: Option<String>,
    pub reply_to_id: Option<String>,
    pub attachments: Json,
    pub reactions: Vec<ReactionDto>,
    pub pinned: bool,
    pub pinned_at: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub ciphertext: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub enc_epoch: Option<i32>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub enc_version: Option<i32>,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct DeviceDto {
    pub id: String,
    pub user_id: String,
    pub name: String,
    pub platform: String,
    pub ik_sig_pub: String,
    pub ik_dh_pub: String,
    pub bundle_sig: String,
    pub authorized_by: Option<String>,
    pub authorization_sig: Option<String>,
    pub created_at: String,
    pub last_seen_at: String,
    pub revoked_at: Option<String>,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct DeviceLogEntryDto {
    pub seq: i32,
    pub kind: String,
    pub payload: String,
    pub entry_hash: String,
    pub prev_hash: Option<String>,
    pub signature: String,
    pub created_at: String,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct EpochDto {
    pub id: String,
    pub channel_id: String,
    pub epoch: i32,
    pub created_by: String,
    pub created_at: String,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct EnvelopeDto {
    pub epoch_id: String,
    pub device_id: String,
    pub ephemeral_pub: String,
    pub wrap_nonce: String,
    pub wrapped: String,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct EpochKeyDto {
    pub epoch: EpochDto,
    pub envelope: EnvelopeDto,
}

fn b64(bytes: &[u8]) -> String {
    use base64::Engine;
    base64::engine::general_purpose::STANDARD.encode(bytes)
}

pub fn to_device(d: &DeviceRow) -> DeviceDto {
    DeviceDto {
        id: d.id.clone(),
        user_id: d.user_id.clone(),
        name: d.name.clone(),
        platform: d.platform.clone(),
        ik_sig_pub: b64(&d.ik_sig_pub),
        ik_dh_pub: b64(&d.ik_dh_pub),
        bundle_sig: b64(&d.bundle_sig),
        authorized_by: d.authorized_by.clone(),
        authorization_sig: d.authorization_sig.as_deref().map(b64),
        created_at: iso(d.created_at),
        last_seen_at: iso(d.last_seen_at),
        revoked_at: iso_opt(d.revoked_at),
    }
}

pub fn to_device_log_entry(e: &DeviceLogEntryRow) -> DeviceLogEntryDto {
    DeviceLogEntryDto {
        seq: e.seq,
        kind: e.kind.clone(),
        payload: b64(&e.payload),
        entry_hash: b64(&e.entry_hash),
        prev_hash: e.prev_hash.as_deref().map(b64),
        signature: b64(&e.signature),
        created_at: iso(e.created_at),
    }
}

pub fn to_epoch(e: &ChannelEpochRow) -> EpochDto {
    EpochDto {
        id: e.id.clone(),
        channel_id: e.channel_id.clone(),
        epoch: e.epoch,
        created_by: e.created_by.clone(),
        created_at: iso(e.created_at),
    }
}

pub fn to_envelope(e: &KeyEnvelopeRow) -> EnvelopeDto {
    EnvelopeDto {
        epoch_id: e.epoch_id.clone(),
        device_id: e.device_id.clone(),
        ephemeral_pub: b64(&e.ephemeral_pub),
        wrap_nonce: b64(&e.wrap_nonce),
        wrapped: b64(&e.wrapped),
    }
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ConversationDto {
    pub id: String,
    #[serde(rename = "type")]
    pub conv_type: String,
    pub name: Option<String>,
    pub participants: Vec<UserDto>,
    pub last_message_at: Option<String>,
    pub latest_message: Option<MessageDto>,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct InviteDto {
    pub code: String,
    pub server_id: String,
    pub inviter_id: String,
    pub expires_at: Option<String>,
    pub max_uses: Option<i32>,
    pub uses: i32,
}

/// What an invite link resolves to, for the embed card and the join page.
///
/// Deliberately thin: this is readable by anyone holding the code, so it says
/// what a joiner needs to decide and nothing about who else is inside.
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct InvitePreviewDto {
    pub code: String,
    pub server: ServerDto,
    pub member_count: i64,
    pub inviter_name: Option<String>,
    pub expires_at: Option<String>,
    /// "ok" | "expired" | "exhausted" | "banned" | "alreadyMember"
    pub status: String,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Page<T: Serialize> {
    pub items: Vec<T>,
    pub next_cursor: Option<String>,
}

// ── Mappers ─────────────────────────────────────────────

pub fn to_user(u: &UserRow) -> UserDto {
    UserDto {
        id: u.id.clone(),
        username: u.username.clone(),
        display_name: u.display_name.clone(),
        avatar_url: same_origin_asset(u.avatar_url.as_deref(), "avatar", &u.id),
        status: u.status.clone(),
        devices: Vec::new(),
        activities: Vec::new(),
        bio: u.bio.clone(),
        banner_url: same_origin_asset(u.banner_url.as_deref(), "banner", &u.id),
        accent_color: u.accent_color,
        pronouns: u.pronouns.clone(),
        profile_css: u.profile_css.clone(),
        badges: u.badges.clone(),
        bot: u.is_bot,
        created_at: iso(u.created_at),
    }
}

pub fn to_self_user(u: &UserRow) -> SelfUserDto {
    SelfUserDto {
        id: u.id.clone(),
        username: u.username.clone(),
        display_name: u.display_name.clone(),
        avatar_url: same_origin_asset(u.avatar_url.as_deref(), "avatar", &u.id),
        status: u.status.clone(),
        devices: Vec::new(),
        activities: Vec::new(),
        bio: u.bio.clone(),
        banner_url: same_origin_asset(u.banner_url.as_deref(), "banner", &u.id),
        accent_color: u.accent_color,
        pronouns: u.pronouns.clone(),
        profile_css: u.profile_css.clone(),
        badges: u.badges.clone(),
        created_at: iso(u.created_at),
        email: u.email.clone(),
        custom_css: u.custom_css.clone(),
        app_icon_url: same_origin_asset(u.app_icon_url.as_deref(), "app-icon", &u.id),
        dm_privacy: u.dm_privacy.clone(),
        friend_request_privacy: u.friend_request_privacy.clone(),
        typing_indicators: u.typing_indicators,
        notify_friend_requests: u.notify_friend_requests,
        notify_friend_accepted: u.notify_friend_accepted,
        notify_friend_online: u.notify_friend_online,
        e2ee_strict: u.e2ee_strict,
        game_activity: u.game_activity,
        two_factor_enabled: u.totp_enabled,
        has_password: u.password_hash.is_some(),
        lockdown: u.lockdown_at.is_some(),
    }
}

pub fn to_connection(c: &ConnectionRow) -> ConnectionDto {
    ConnectionDto {
        id: c.id.clone(),
        provider: c.provider.clone(),
        name: c.name.clone(),
        profile_url: c.profile_url.clone(),
        verified: c.verified,
        visible: c.visible,
        created_at: iso(c.created_at),
    }
}

pub fn to_friend(r: &FriendJoinRow) -> FriendDto {
    FriendDto {
        id: r.friendship_id.clone(),
        user: to_user(&r.user),
        created_at: iso(r.friendship_created_at),
    }
}

pub fn to_friend_request(r: &FriendJoinRow, direction: &str) -> FriendRequestDto {
    FriendRequestDto {
        id: r.friendship_id.clone(),
        user: to_user(&r.user),
        direction: direction.to_string(),
        created_at: iso(r.friendship_created_at),
    }
}

pub fn to_server(s: &ServerRow) -> ServerDto {
    ServerDto {
        id: s.id.clone(),
        name: s.name.clone(),
        icon_url: same_origin_asset(s.icon_url.as_deref(), "server-icon", &s.id),
        description: s.description.clone(),
        banner_url: same_origin_asset(s.banner_url.as_deref(), "server-banner", &s.id),
        system_channel_id: s.system_channel_id.clone(),
        afk_channel_id: s.afk_channel_id.clone(),
        afk_timeout: s.afk_timeout,
        default_message_notifications: s.default_message_notifications.clone(),
        owner_id: s.owner_id.clone(),
        created_at: iso(s.created_at),
    }
}

pub fn to_channel(c: &ChannelRow) -> ChannelDto {
    ChannelDto {
        id: c.id.clone(),
        server_id: c.server_id.clone(),
        name: c.name.clone(),
        channel_type: c.channel_type.clone(),
        topic: c.topic.clone(),
        position: c.position,
        parent_category_id: c.parent_category_id.clone(),
        nsfw: c.nsfw,
        rate_limit_per_user: c.rate_limit_per_user,
        user_limit: c.user_limit,
        bitrate: c.bitrate,
    }
}

pub fn to_channel_overwrite(o: &ChannelOverwriteRow) -> ChannelOverwriteDto {
    ChannelOverwriteDto {
        id: o.id.clone(),
        channel_id: o.channel_id.clone(),
        ow_type: o.ow_type.clone(),
        target_id: o.target_id.clone(),
        allow: o.allow.to_string(),
        deny: o.deny.to_string(),
    }
}

pub fn to_emoji(e: &EmojiRow) -> EmojiDto {
    EmojiDto {
        id: e.id.clone(),
        server_id: e.server_id.clone(),
        name: e.name.clone(),
        url: same_origin_asset(Some(&e.url), "emoji", &e.id).unwrap_or_default(),
        animated: e.animated,
        creator_id: e.creator_id.clone(),
        created_at: iso(e.created_at),
    }
}

pub fn to_scheduled_event(e: &ScheduledEventRow) -> ScheduledEventDto {
    ScheduledEventDto {
        id: e.id.clone(),
        server_id: e.server_id.clone(),
        channel_id: e.channel_id.clone(),
        creator_id: e.creator_id.clone(),
        name: e.name.clone(),
        description: e.description.clone(),
        location: e.location.clone(),
        starts_at: iso(e.starts_at),
        ends_at: e.ends_at.map(iso),
        created_at: iso(e.created_at),
        interested_count: e.interested_count,
        interested: e.interested,
    }
}

pub fn to_sound(s: &SoundRow) -> SoundDto {
    SoundDto {
        id: s.id.clone(),
        server_id: s.server_id.clone(),
        name: s.name.clone(),
        url: same_origin_asset(Some(&s.url), "sound", &s.id).unwrap_or_default(),
        duration: s.duration,
        emoji: s.emoji.clone(),
        volume: s.volume,
        creator_id: s.creator_id.clone(),
        created_at: iso(s.created_at),
    }
}

pub fn to_role(r: &RoleRow) -> RoleDto {
    RoleDto {
        id: r.id.clone(),
        server_id: r.server_id.clone(),
        name: r.name.clone(),
        color: r.color,
        permissions: permissions::serialize(r.permissions),
        position: r.position,
        hoist: r.hoist,
        mentionable: r.mentionable,
    }
}

pub fn to_server_member(
    m: &ServerMemberRow,
    user: &UserRow,
    role_ids: Vec<String>,
) -> ServerMemberDto {
    ServerMemberDto {
        id: m.id.clone(),
        server_id: m.server_id.clone(),
        user_id: m.user_id.clone(),
        nickname: m.nickname.clone(),
        timed_out_until: iso_opt(m.timed_out_until),
        joined_at: iso(m.joined_at),
        role_ids,
        user: to_user(user),
    }
}

pub fn to_conversation(
    c: &ChannelRow,
    participants: &[UserRow],
    latest_message: Option<MessageDto>,
) -> ConversationDto {
    ConversationDto {
        id: c.id.clone(),
        conv_type: if c.channel_type == "group_dm" {
            "group_dm".into()
        } else {
            "dm".into()
        },
        name: c.name.clone(),
        participants: participants.iter().map(to_user).collect(),
        last_message_at: Some(iso(c.updated_at)),
        latest_message,
    }
}

pub fn to_invite(i: &InviteRow) -> InviteDto {
    InviteDto {
        code: i.code.clone(),
        server_id: i.server_id.clone(),
        inviter_id: i.inviter_id.clone(),
        expires_at: iso_opt(i.expires_at),
        max_uses: i.max_uses,
        uses: i.uses,
    }
}

pub fn to_invite_preview(p: &server::InvitePreview) -> InvitePreviewDto {
    InvitePreviewDto {
        code: p.invite.code.clone(),
        server: to_server(&p.server),
        member_count: p.member_count,
        inviter_name: p.inviter.as_ref().map(|u| u.display_name.clone()),
        expires_at: iso_opt(p.invite.expires_at),
        status: p.status.as_str().to_string(),
    }
}

/// Aggregate raw reaction rows into per-emoji counts, flagged for the viewer.
/// Preserves first-seen emoji order, matching the JS Map iteration order.
pub fn to_reactions(reactions: &[ReactionRow], viewer_id: &str) -> Vec<ReactionDto> {
    let mut order: Vec<String> = Vec::new();
    let mut map: std::collections::HashMap<String, ReactionDto> = std::collections::HashMap::new();
    for r in reactions {
        let entry = map.entry(r.emoji.clone()).or_insert_with(|| {
            order.push(r.emoji.clone());
            ReactionDto {
                emoji: r.emoji.clone(),
                count: 0,
                me: false,
            }
        });
        entry.count += 1;
        if r.user_id == viewer_id {
            entry.me = true;
        }
    }
    order.into_iter().map(|e| map.remove(&e).unwrap()).collect()
}

pub fn to_message(
    m: &MessageRow,
    author: &UserRow,
    reactions: &[ReactionRow],
    emojis: &[EmojiRow],
    viewer_id: &str,
) -> MessageDto {
    let attachments = if m.attachments.is_array() {
        m.attachments.clone()
    } else {
        Json::Array(vec![])
    };
    MessageDto {
        id: m.id.clone(),
        channel_id: m.channel_id.clone(),
        author: to_user(author),
        content: m.content.clone(),
        emojis: emojis.iter().map(to_emoji).collect(),
        created_at: iso(m.created_at),
        edited_at: iso_opt(m.edited_at),
        reply_to_id: m.reply_to_id.clone(),
        attachments,
        reactions: to_reactions(reactions, viewer_id),
        pinned: m.pinned,
        pinned_at: iso_opt(m.pinned_at),
        ciphertext: m.ciphertext.as_deref().map(b64),
        enc_epoch: m.enc_epoch,
        enc_version: m.enc_version,
    }
}
