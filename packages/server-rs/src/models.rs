//! Database row structs (sqlx FromRow) mirroring the Prisma models. Column names
//! are camelCase in Postgres, so fields are renamed accordingly.

use chrono::NaiveDateTime;
use serde_json::Value as Json;
use sqlx::FromRow;

#[derive(Debug, Clone, FromRow)]
pub struct UserRow {
    pub id: String,
    pub email: String,
    #[sqlx(rename = "passwordHash")]
    pub password_hash: Option<String>,
    pub username: String,
    #[sqlx(rename = "displayName")]
    pub display_name: String,
    #[sqlx(rename = "avatarUrl")]
    pub avatar_url: Option<String>,
    pub status: String,
    pub bio: Option<String>,
    #[sqlx(rename = "bannerUrl")]
    pub banner_url: Option<String>,
    #[sqlx(rename = "accentColor")]
    pub accent_color: Option<i32>,
    pub pronouns: Option<String>,
    #[sqlx(rename = "customCss")]
    pub custom_css: Option<String>,
    #[sqlx(rename = "profileCss")]
    pub profile_css: Option<String>,
    #[sqlx(rename = "appIconUrl")]
    pub app_icon_url: Option<String>,
    #[sqlx(rename = "dmPrivacy")]
    pub dm_privacy: String,
    #[sqlx(rename = "friendRequestPrivacy")]
    pub friend_request_privacy: String,
    #[sqlx(rename = "typingIndicators")]
    pub typing_indicators: bool,
    #[sqlx(rename = "notifyFriendRequests")]
    pub notify_friend_requests: bool,
    #[sqlx(rename = "notifyFriendAccepted")]
    pub notify_friend_accepted: bool,
    #[sqlx(rename = "notifyFriendOnline")]
    pub notify_friend_online: bool,
    #[sqlx(rename = "e2eeStrict")]
    pub e2ee_strict: bool,
    #[sqlx(rename = "gameActivity")]
    pub game_activity: bool,
    #[sqlx(rename = "totpSecret")]
    pub totp_secret: Option<String>,
    #[sqlx(rename = "totpEnabled")]
    pub totp_enabled: bool,
    #[sqlx(rename = "emailVerifiedAt")]
    pub email_verified_at: Option<NaiveDateTime>,
    /// Awarded badge slugs; see services::badge for the catalog.
    pub badges: Vec<String>,
    /// A bot account rather than a person; see services::bot.
    #[sqlx(rename = "isBot")]
    pub is_bot: bool,
    /// Set while the account is locked down; see services::account.
    #[sqlx(rename = "lockdownAt")]
    pub lockdown_at: Option<NaiveDateTime>,
    /// Set on a tombstoned account; see services::account.
    #[sqlx(rename = "deletedAt")]
    pub deleted_at: Option<NaiveDateTime>,
    #[sqlx(rename = "createdAt")]
    pub created_at: NaiveDateTime,
}

/// A friendship/friend-request row joined with the *other* party's user record.
/// `friendship_id`/`friendship_created_at` are explicit aliases so they don't
/// collide with the flattened user's own `id`/`createdAt`.
#[derive(Debug, Clone, FromRow)]
pub struct FriendJoinRow {
    pub friendship_id: String,
    pub friendship_created_at: NaiveDateTime,
    #[allow(dead_code)]
    pub status: String,
    #[sqlx(flatten)]
    pub user: UserRow,
}

#[derive(Debug, Clone, FromRow)]
pub struct ServerRow {
    pub id: String,
    pub name: String,
    #[sqlx(rename = "iconUrl")]
    pub icon_url: Option<String>,
    pub description: Option<String>,
    #[sqlx(rename = "bannerUrl")]
    pub banner_url: Option<String>,
    #[sqlx(rename = "systemChannelId")]
    pub system_channel_id: Option<String>,
    #[sqlx(rename = "afkChannelId")]
    pub afk_channel_id: Option<String>,
    #[sqlx(rename = "afkTimeout")]
    pub afk_timeout: i32,
    #[sqlx(rename = "defaultMessageNotifications")]
    pub default_message_notifications: String,
    #[sqlx(rename = "ownerId")]
    pub owner_id: String,
    #[sqlx(rename = "createdAt")]
    pub created_at: NaiveDateTime,
}

#[derive(Debug, Clone, FromRow)]
pub struct ChannelRow {
    pub id: String,
    #[sqlx(rename = "serverId")]
    pub server_id: Option<String>,
    pub name: Option<String>,
    #[sqlx(rename = "type")]
    pub channel_type: String,
    pub topic: Option<String>,
    #[sqlx(rename = "backgroundUrl")]
    pub background_url: Option<String>,
    pub position: i32,
    #[sqlx(rename = "parentCategoryId")]
    pub parent_category_id: Option<String>,
    pub nsfw: bool,
    #[sqlx(rename = "rateLimitPerUser")]
    pub rate_limit_per_user: i32,
    #[sqlx(rename = "userLimit")]
    pub user_limit: i32,
    pub bitrate: i32,
    pub e2ee: bool,
    #[sqlx(rename = "epochNumber")]
    pub epoch_number: i32,
    #[sqlx(rename = "updatedAt")]
    pub updated_at: NaiveDateTime,
}

#[derive(Debug, Clone, FromRow)]
pub struct RoleRow {
    pub id: String,
    #[sqlx(rename = "serverId")]
    pub server_id: String,
    pub name: String,
    pub color: i32,
    pub permissions: i64,
    pub position: i32,
    pub hoist: bool,
    pub mentionable: bool,
}

#[derive(Debug, Clone, FromRow)]
pub struct EmojiRow {
    pub id: String,
    #[sqlx(rename = "serverId")]
    pub server_id: String,
    pub name: String,
    pub url: String,
    pub animated: bool,
    #[sqlx(rename = "creatorId")]
    pub creator_id: Option<String>,
    #[sqlx(rename = "createdAt")]
    pub created_at: NaiveDateTime,
}

#[derive(Debug, Clone, FromRow)]
pub struct ScheduledEventRow {
    pub id: String,
    #[sqlx(rename = "serverId")]
    pub server_id: String,
    #[sqlx(rename = "channelId")]
    pub channel_id: Option<String>,
    #[sqlx(rename = "creatorId")]
    pub creator_id: Option<String>,
    pub name: String,
    pub description: Option<String>,
    pub location: Option<String>,
    #[sqlx(rename = "startsAt")]
    pub starts_at: NaiveDateTime,
    #[sqlx(rename = "endsAt")]
    pub ends_at: Option<NaiveDateTime>,
    #[sqlx(rename = "createdAt")]
    pub created_at: NaiveDateTime,
    #[sqlx(rename = "interestedCount")]
    pub interested_count: i64,
    pub interested: bool,
}

#[derive(Debug, Clone, FromRow)]
pub struct SoundRow {
    pub id: String,
    #[sqlx(rename = "serverId")]
    pub server_id: String,
    pub name: String,
    pub url: String,
    pub duration: f64,
    pub emoji: Option<String>,
    pub volume: f64,
    #[sqlx(rename = "creatorId")]
    pub creator_id: Option<String>,
    #[sqlx(rename = "createdAt")]
    pub created_at: NaiveDateTime,
}

#[derive(Debug, Clone, FromRow)]
pub struct ServerMemberRow {
    pub id: String,
    #[sqlx(rename = "serverId")]
    pub server_id: String,
    #[sqlx(rename = "userId")]
    pub user_id: String,
    pub nickname: Option<String>,
    #[sqlx(rename = "timedOutUntil")]
    pub timed_out_until: Option<NaiveDateTime>,
    #[sqlx(rename = "joinedAt")]
    pub joined_at: NaiveDateTime,
}

#[derive(Debug, Clone, FromRow)]
pub struct ChannelOverwriteRow {
    pub id: String,
    #[sqlx(rename = "channelId")]
    pub channel_id: String,
    #[sqlx(rename = "type")]
    pub ow_type: String,
    #[sqlx(rename = "targetId")]
    pub target_id: String,
    pub allow: i64,
    pub deny: i64,
}

#[derive(Debug, Clone, FromRow)]
pub struct MessageRow {
    pub id: String,
    #[sqlx(rename = "channelId")]
    pub channel_id: String,
    #[sqlx(rename = "authorId")]
    pub author_id: String,
    pub content: String,
    #[sqlx(rename = "createdAt")]
    pub created_at: NaiveDateTime,
    #[sqlx(rename = "editedAt")]
    pub edited_at: Option<NaiveDateTime>,
    #[sqlx(rename = "replyToId")]
    pub reply_to_id: Option<String>,
    pub attachments: Json,
    pub pinned: bool,
    #[sqlx(rename = "pinnedAt")]
    pub pinned_at: Option<NaiveDateTime>,
    pub ciphertext: Option<Vec<u8>>,
    #[sqlx(rename = "encEpoch")]
    pub enc_epoch: Option<i32>,
    #[sqlx(rename = "encVersion")]
    pub enc_version: Option<i32>,
}

#[derive(Debug, Clone, FromRow)]
pub struct DeviceRow {
    pub id: String,
    #[sqlx(rename = "userId")]
    pub user_id: String,
    pub name: String,
    pub platform: String,
    #[sqlx(rename = "ikSigPub")]
    pub ik_sig_pub: Vec<u8>,
    #[sqlx(rename = "ikDhPub")]
    pub ik_dh_pub: Vec<u8>,
    #[sqlx(rename = "bundleSig")]
    pub bundle_sig: Vec<u8>,
    #[sqlx(rename = "authorizedBy")]
    pub authorized_by: Option<String>,
    #[sqlx(rename = "authorizationSig")]
    pub authorization_sig: Option<Vec<u8>>,
    #[sqlx(rename = "createdAt")]
    pub created_at: NaiveDateTime,
    #[sqlx(rename = "lastSeenAt")]
    pub last_seen_at: NaiveDateTime,
    #[sqlx(rename = "revokedAt")]
    pub revoked_at: Option<NaiveDateTime>,
}

#[derive(Debug, Clone, FromRow)]
pub struct DeviceLogEntryRow {
    #[allow(dead_code)]
    pub id: String,
    #[allow(dead_code)]
    #[sqlx(rename = "userId")]
    pub user_id: String,
    pub seq: i32,
    pub kind: String,
    pub payload: Vec<u8>,
    #[sqlx(rename = "entryHash")]
    pub entry_hash: Vec<u8>,
    #[sqlx(rename = "prevHash")]
    pub prev_hash: Option<Vec<u8>>,
    pub signature: Vec<u8>,
    #[sqlx(rename = "createdAt")]
    pub created_at: NaiveDateTime,
}

#[derive(Debug, Clone, FromRow)]
pub struct ChannelEpochRow {
    pub id: String,
    #[sqlx(rename = "channelId")]
    pub channel_id: String,
    pub epoch: i32,
    #[sqlx(rename = "createdAt")]
    pub created_at: NaiveDateTime,
    #[sqlx(rename = "createdBy")]
    pub created_by: String,
}

#[derive(Debug, Clone, FromRow)]
pub struct KeyEnvelopeRow {
    #[allow(dead_code)]
    pub id: String,
    #[sqlx(rename = "epochId")]
    pub epoch_id: String,
    #[sqlx(rename = "deviceId")]
    pub device_id: String,
    #[sqlx(rename = "ephemeralPub")]
    pub ephemeral_pub: Vec<u8>,
    #[sqlx(rename = "wrapNonce")]
    pub wrap_nonce: Vec<u8>,
    pub wrapped: Vec<u8>,
}

#[derive(Debug, Clone, FromRow)]
pub struct ReactionRow {
    #[sqlx(rename = "userId")]
    pub user_id: String,
    pub emoji: String,
}

#[derive(Debug, Clone, FromRow)]
pub struct InviteRow {
    pub code: String,
    #[sqlx(rename = "serverId")]
    pub server_id: String,
    #[sqlx(rename = "inviterId")]
    pub inviter_id: String,
    #[sqlx(rename = "expiresAt")]
    pub expires_at: Option<NaiveDateTime>,
    #[sqlx(rename = "maxUses")]
    pub max_uses: Option<i32>,
    pub uses: i32,
}

#[derive(Debug, Clone, FromRow)]
pub struct BanRow {
    #[sqlx(rename = "userId")]
    pub user_id: String,
    #[sqlx(rename = "bannedById")]
    pub banned_by_id: String,
    pub reason: Option<String>,
    #[sqlx(rename = "createdAt")]
    pub created_at: NaiveDateTime,
}

#[derive(Debug, Clone, FromRow)]
pub struct AuditLogRow {
    pub id: String,
    #[sqlx(rename = "actorId")]
    pub actor_id: Option<String>,
    pub action: String,
    #[sqlx(rename = "targetId")]
    pub target_id: Option<String>,
    #[sqlx(rename = "targetType")]
    pub target_type: Option<String>,
    pub changes: Json,
    pub reason: Option<String>,
    #[sqlx(rename = "createdAt")]
    pub created_at: NaiveDateTime,
}

#[derive(Debug, Clone, FromRow)]
pub struct ConnectionRow {
    pub id: String,
    pub provider: String,
    pub name: String,
    #[sqlx(rename = "profileUrl")]
    pub profile_url: Option<String>,
    pub verified: bool,
    pub visible: bool,
    #[sqlx(rename = "createdAt")]
    pub created_at: NaiveDateTime,
}

#[derive(Debug, Clone, FromRow)]
pub struct PasskeyRow {
    pub id: String,
    #[sqlx(rename = "userId")]
    pub user_id: String,
    /// Looked up by SQL rather than read in Rust - it is how a discoverable
    /// sign-in finds the account - but the column is part of the row.
    #[allow(dead_code)]
    #[sqlx(rename = "credentialId")]
    pub credential_id: String,
    /// webauthn-rs's own serialised credential; see prisma/schema.prisma.
    pub credential: Json,
    pub name: String,
    #[sqlx(rename = "backedUp")]
    pub backed_up: bool,
    #[sqlx(rename = "createdAt")]
    pub created_at: NaiveDateTime,
    #[sqlx(rename = "lastUsedAt")]
    pub last_used_at: Option<NaiveDateTime>,
}
