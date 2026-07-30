//! User profile + OAuth account resolution. Mirrors user-service.ts.

use sqlx::QueryBuilder;

use crate::error::{AppError, AppResult};
use crate::http::media_proxy::is_asset_url;
use crate::ids::cuid;
use crate::models::UserRow;
use crate::oauth::OAuthProfile;
use crate::services::badge;
use crate::state::AppState;

#[derive(Default)]
pub struct UserPatch {
    pub username: Option<String>,
    pub display_name: Option<String>,
    pub avatar_url: Option<Option<String>>,
    pub status: Option<String>,
    pub bio: Option<Option<String>>,
    pub banner_url: Option<Option<String>>,
    pub accent_color: Option<Option<i32>>,
    pub pronouns: Option<Option<String>>,
    pub custom_css: Option<Option<String>>,
    pub profile_css: Option<Option<String>>,
    pub app_icon_url: Option<Option<String>>,
    pub dm_privacy: Option<String>,
    pub friend_request_privacy: Option<String>,
    pub typing_indicators: Option<bool>,
    pub notify_friend_requests: Option<bool>,
    pub notify_friend_accepted: Option<bool>,
    pub notify_friend_online: Option<bool>,
    pub e2ee_strict: Option<bool>,
}

/// A patch value that is really this api's own output handed back to it. `None`
/// is the client clearing the field, which is a genuine edit.
fn is_wire_form(value: Option<&str>) -> bool {
    value.is_some_and(is_asset_url)
}

pub async fn update_profile(
    state: &AppState,
    user_id: &str,
    patch: UserPatch,
) -> AppResult<UserRow> {
    if let Some(ref username) = patch.username {
        let existing: Option<String> =
            sqlx::query_scalar(r#"SELECT id FROM "User" WHERE lower(username) = lower($1)"#)
                .bind(username)
                .fetch_optional(&state.pool)
                .await?;
        if let Some(other_id) = existing {
            if other_id != user_id {
                return Err(AppError::UsernameTaken("Username already taken".into()));
            }
        }
    }

    let mut qb: QueryBuilder<sqlx::Postgres> = QueryBuilder::new(r#"UPDATE "User" SET "#);
    let mut sep = qb.separated(", ");
    if let Some(username) = patch.username {
        sep.push(r#"username = "#).push_bind_unseparated(username);
    }
    if let Some(dn) = patch.display_name {
        sep.push(r#""displayName" = "#).push_bind_unseparated(dn);
    }
    // A client that round-trips the user object sends back the wire form the api
    // gave it, which is this row's own asset route; storing it would erase the
    // real url. Nothing else is a legitimate reason to send one, so drop the
    // field rather than fail the whole save.
    if let Some(av) = patch.avatar_url.filter(|v| !is_wire_form(v.as_deref())) {
        sep.push(r#""avatarUrl" = "#).push_bind_unseparated(av);
    }
    if let Some(status) = patch.status {
        sep.push(r#"status = "#).push_bind_unseparated(status);
    }
    if let Some(bio) = patch.bio {
        sep.push(r#"bio = "#).push_bind_unseparated(bio);
    }
    if let Some(banner) = patch.banner_url.filter(|v| !is_wire_form(v.as_deref())) {
        sep.push(r#""bannerUrl" = "#).push_bind_unseparated(banner);
    }
    if let Some(accent) = patch.accent_color {
        sep.push(r#""accentColor" = "#)
            .push_bind_unseparated(accent);
    }
    if let Some(pronouns) = patch.pronouns {
        sep.push(r#"pronouns = "#).push_bind_unseparated(pronouns);
    }
    if let Some(css) = patch.custom_css {
        sep.push(r#""customCss" = "#).push_bind_unseparated(css);
    }
    if let Some(css) = patch.profile_css {
        sep.push(r#""profileCss" = "#).push_bind_unseparated(css);
    }
    if let Some(icon) = patch.app_icon_url.filter(|v| !is_wire_form(v.as_deref())) {
        sep.push(r#""appIconUrl" = "#).push_bind_unseparated(icon);
    }
    if let Some(v) = patch.dm_privacy {
        sep.push(r#""dmPrivacy" = "#).push_bind_unseparated(v);
    }
    if let Some(v) = patch.friend_request_privacy {
        sep.push(r#""friendRequestPrivacy" = "#)
            .push_bind_unseparated(v);
    }
    if let Some(v) = patch.typing_indicators {
        sep.push(r#""typingIndicators" = "#)
            .push_bind_unseparated(v);
    }
    if let Some(v) = patch.notify_friend_requests {
        sep.push(r#""notifyFriendRequests" = "#)
            .push_bind_unseparated(v);
    }
    if let Some(v) = patch.notify_friend_accepted {
        sep.push(r#""notifyFriendAccepted" = "#)
            .push_bind_unseparated(v);
    }
    if let Some(v) = patch.notify_friend_online {
        sep.push(r#""notifyFriendOnline" = "#)
            .push_bind_unseparated(v);
    }
    if let Some(v) = patch.e2ee_strict {
        sep.push(r#""e2eeStrict" = "#).push_bind_unseparated(v);
    }
    sep.push(r#""updatedAt" = now()"#);
    qb.push(r#" WHERE id = "#)
        .push_bind(user_id)
        .push(r#" RETURNING *"#);
    Ok(qb
        .build_query_as::<UserRow>()
        .fetch_one(&state.pool)
        .await?)
}

pub async fn get_shared_server_ids(state: &AppState, user_id: &str) -> AppResult<Vec<String>> {
    Ok(
        sqlx::query_scalar(r#"SELECT "serverId" FROM "ServerMember" WHERE "userId" = $1"#)
            .bind(user_id)
            .fetch_all(&state.pool)
            .await?,
    )
}

/// Every socket room that should hear about `user_id`'s profile changing.
///
/// Sharing a server is only one of the ways to be looking at someone's name and
/// avatar: friends and DM partners render them too, and often share no server at
/// all - so fanning out to `server:*` alone leaves them on a stale profile until
/// they reload. The user's own `user:<id>` room is included so their other tabs
/// and their phone follow an edit made anywhere.
pub async fn get_profile_audience_rooms(state: &AppState, user_id: &str) -> AppResult<Vec<String>> {
    let server_ids = get_shared_server_ids(state, user_id).await?;

    // Friends (accepted, either direction) and everyone sharing a DM / group DM.
    let peer_ids: Vec<String> = sqlx::query_scalar(
        r#"SELECT DISTINCT "addresseeId" AS id FROM "Friendship"
             WHERE "requesterId" = $1 AND status = 'accepted'
           UNION
           SELECT DISTINCT "requesterId" AS id FROM "Friendship"
             WHERE "addresseeId" = $1 AND status = 'accepted'
           UNION
           SELECT DISTINCT p."userId" AS id FROM "ChannelParticipant" p
             WHERE p."channelId" IN (
               SELECT "channelId" FROM "ChannelParticipant" WHERE "userId" = $1
             )"#,
    )
    .bind(user_id)
    .fetch_all(&state.pool)
    .await?;

    let mut rooms: Vec<String> = server_ids
        .into_iter()
        .map(|id| format!("server:{id}"))
        .collect();
    // The DM query returns the user themselves; that is wanted (own tabs), and
    // dedupe keeps it to one room either way.
    rooms.extend(peer_ids.into_iter().map(|id| format!("user:{id}")));
    rooms.push(format!("user:{user_id}"));
    rooms.sort();
    rooms.dedup();
    Ok(rooms)
}

fn to_username_base(input: &str) -> String {
    let base: String = input
        .to_lowercase()
        .chars()
        .filter(|c| c.is_ascii_lowercase() || c.is_ascii_digit() || *c == '_' || *c == '.')
        .take(24)
        .collect();
    if base.len() >= 2 {
        base
    } else {
        "user".into()
    }
}

async fn username_taken(state: &AppState, username: &str) -> AppResult<bool> {
    let existing: Option<String> =
        sqlx::query_scalar(r#"SELECT id FROM "User" WHERE lower(username) = lower($1)"#)
            .bind(username)
            .fetch_optional(&state.pool)
            .await?;
    Ok(existing.is_some())
}

pub async fn generate_unique_username(state: &AppState, seed: &str) -> AppResult<String> {
    let base = to_username_base(seed);
    if !username_taken(state, &base).await? {
        return Ok(base);
    }
    for _ in 0..10_000 {
        let suffix = 1000 + (rand::random::<u32>() % 9000);
        let candidate = format!("{base}{suffix}");
        if !username_taken(state, &candidate).await? {
            return Ok(candidate);
        }
    }
    Err(AppError::Internal(
        "Could not allocate a unique username".into(),
    ))
}

pub async fn find_or_create_oauth_user(
    state: &AppState,
    profile: &OAuthProfile,
) -> AppResult<UserRow> {
    let linked_user_id: Option<String> = sqlx::query_scalar(
        r#"SELECT "userId" FROM "OAuthAccount" WHERE provider = $1 AND "providerId" = $2"#,
    )
    .bind(&profile.provider)
    .bind(&profile.provider_id)
    .fetch_optional(&state.pool)
    .await?;
    if let Some(uid) = linked_user_id {
        return Ok(sqlx::query_as(r#"SELECT * FROM "User" WHERE id = $1"#)
            .bind(uid)
            .fetch_one(&state.pool)
            .await?);
    }

    // Matching on an address the provider has *not* verified would hand the
    // account to whoever typed it in: sign up at the provider with the victim's
    // email, leave it unconfirmed, and this lookup adopts their account -
    // password and TOTP never consulted. Unverified means "no address": fall
    // through and create a separate account instead.
    if let Some(ref email) = profile.email.as_ref().filter(|_| profile.email_verified) {
        // Case-insensitively, or a provider that hands back a differently-cased
        // address silently forks a second account instead of linking to the one
        // the person already has.
        let by_email: Option<UserRow> =
            sqlx::query_as(r#"SELECT * FROM "User" WHERE lower(email) = lower($1)"#)
                .bind(email)
                .fetch_optional(&state.pool)
                .await?;
        if let Some(user) = by_email {
            sqlx::query(
                r#"INSERT INTO "OAuthAccount" (id, provider, "providerId", "userId")
                   VALUES ($1, $2, $3, $4)"#,
            )
            .bind(cuid())
            .bind(&profile.provider)
            .bind(&profile.provider_id)
            .bind(&user.id)
            .execute(&state.pool)
            .await?;
            // The provider just proved this address belongs to them, which is
            // exactly what our own verification mail asks for. Sending one
            // anyway would strand an account that has no password to log in
            // with and re-request it.
            let user: UserRow = sqlx::query_as(
                r#"UPDATE "User" SET "emailVerifiedAt" = COALESCE("emailVerifiedAt", now()) WHERE id = $1 RETURNING *"#,
            )
            .bind(&user.id)
            .fetch_one(&state.pool)
            .await?;
            return Ok(user);
        }
    }

    let seed = if !profile.display_name.is_empty() {
        profile.display_name.clone()
    } else {
        profile.email.clone().unwrap_or_else(|| "user".into())
    };
    let username = generate_unique_username(state, &seed).await?;
    // Stored canonically, the same as a password signup: the placeholder for a
    // provider that gives us no address has to be lowercase too, or it collides
    // with the lower(email) index on a re-link it should have matched.
    let email = profile
        .email
        .clone()
        .unwrap_or_else(|| {
            format!(
                "{}_{}@oauth.orangchat.local",
                profile.provider, profile.provider_id
            )
        })
        .to_lowercase();

    // Signing in through the provider already proved the address (or there is
    // no real address to prove, for a provider that hands back none), so these
    // accounts skip our verification mail. Without this every OAuth signup is
    // locked out by the email-verified check on the way back in, and having no
    // password there is no other route in.
    let email_verified = profile.email_verified || profile.email.is_none();

    let user_id = cuid();
    let badges = badge::initial_badges();
    let mut tx = state.pool.begin().await?;
    let user: UserRow = sqlx::query_as(
        r#"INSERT INTO "User" (id, email, username, "displayName", "avatarUrl", "passwordHash", badges, "emailVerifiedAt", "updatedAt")
           VALUES ($1, $2, $3, $4, $5, NULL, $6, CASE WHEN $7 THEN now() ELSE NULL END, now()) RETURNING *"#,
    )
    .bind(&user_id)
    .bind(&email)
    .bind(&username)
    .bind(&profile.display_name)
    .bind(&profile.avatar_url)
    .bind(&badges)
    .bind(email_verified)
    .fetch_one(&mut *tx)
    .await?;
    sqlx::query(
        r#"INSERT INTO "OAuthAccount" (id, provider, "providerId", "userId") VALUES ($1, $2, $3, $4)"#,
    )
    .bind(cuid())
    .bind(&profile.provider)
    .bind(&profile.provider_id)
    .bind(&user_id)
    .execute(&mut *tx)
    .await?;
    tx.commit().await?;
    Ok(user)
}
