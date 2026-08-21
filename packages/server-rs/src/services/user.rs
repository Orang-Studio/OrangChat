
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
    pub profile_widgets: Option<serde_json::Value>,
    pub app_icon_url: Option<Option<String>>,
    pub dm_privacy: Option<String>,
    pub friend_request_privacy: Option<String>,
    pub typing_indicators: Option<bool>,
    pub notify_friend_requests: Option<bool>,
    pub notify_friend_accepted: Option<bool>,
    pub notify_friend_online: Option<bool>,
    pub e2ee_strict: Option<bool>,
    pub game_activity: Option<bool>,
}

fn is_wire_form(value: Option<&str>) -> bool {
    value.is_some_and(is_asset_url)
}

const MAX_DISPLAY_NAME: usize = 100;
const MAX_STATUS: usize = 200;
const MAX_BIO: usize = 4000;
const MAX_PRONOUNS: usize = 100;
const MAX_CSS: usize = 100_000;
const MAX_URL: usize = 2048;

fn check_len(value: Option<&str>, field: &str, max: usize) -> AppResult<()> {
    if value.is_some_and(|v| v.len() > max) {
        return Err(AppError::BadRequest(format!(
            "{field} is too long (limit {max} characters)"
        )));
    }
    Ok(())
}

pub(crate) fn check_image_url(value: Option<&str>, field: &str) -> AppResult<()> {
    let Some(url) = value else { return Ok(()) };
    if url.is_empty() {
        return Ok(());
    }
    check_len(Some(url), field, MAX_URL)?;

    let ok = if let Some(rest) = url.strip_prefix('/') {
        !rest.starts_with('/') && url.starts_with("/uploads/")
    } else {
        let lower = url.to_ascii_lowercase();
        lower.starts_with("http://") || lower.starts_with("https://")
    };

    if !ok {
        return Err(AppError::BadRequest(format!(
            "{field} must be an http(s) url"
        )));
    }
    Ok(())
}

fn validate_patch(patch: &UserPatch) -> AppResult<()> {
    check_len(patch.display_name.as_deref(), "displayName", MAX_DISPLAY_NAME)?;
    check_len(patch.status.as_deref(), "status", MAX_STATUS)?;
    check_len(flat(&patch.bio), "bio", MAX_BIO)?;
    check_len(flat(&patch.pronouns), "pronouns", MAX_PRONOUNS)?;
    check_len(flat(&patch.custom_css), "customCss", MAX_CSS)?;
    check_len(flat(&patch.profile_css), "profileCss", MAX_CSS)?;

    check_image_url(flat(&patch.avatar_url), "avatarUrl")?;
    check_image_url(flat(&patch.banner_url), "bannerUrl")?;
    check_image_url(flat(&patch.app_icon_url), "appIconUrl")?;
    Ok(())
}

fn flat(value: &Option<Option<String>>) -> Option<&str> {
    value.as_ref().and_then(|v| v.as_deref())
}

pub async fn update_profile(
    state: &AppState,
    user_id: &str,
    patch: UserPatch,
) -> AppResult<UserRow> {
    validate_patch(&patch)?;

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
    if let Some(widgets) = patch.profile_widgets {
        sep.push(r#""profileWidgets" = "#).push_bind_unseparated(widgets);
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
    if let Some(v) = patch.game_activity {
        sep.push(r#""gameActivity" = "#).push_bind_unseparated(v);
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

pub async fn get_profile_audience_rooms(state: &AppState, user_id: &str) -> AppResult<Vec<String>> {
    let server_ids = get_shared_server_ids(state, user_id).await?;

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

    if let Some(ref email) = profile.email.as_ref().filter(|_| profile.email_verified) {
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

#[cfg(test)]
mod image_url_tests {
    use super::check_image_url;

    fn accepts(url: &str) -> bool {
        check_image_url(Some(url), "avatarUrl").is_ok()
    }

    #[test]
    fn accepts_the_two_forms_this_server_stores() {
        assert!(accepts("https://cdn.discordapp.com/avatars/1/a.png"));
        assert!(accepts("http://example.test/a.png"));
        assert!(accepts("/uploads/abc.jpg"));
    }

    #[test]
    fn absent_and_cleared_values_are_not_edits() {
        assert!(check_image_url(None, "avatarUrl").is_ok());
        assert!(accepts(""));
    }

    #[test]
    fn rejects_schemes_a_client_should_never_render() {
        assert!(!accepts("javascript:alert(1)"));
        assert!(!accepts("JavaScript:alert(1)"));
        assert!(!accepts("data:image/svg+xml;base64,PHN2Zz48L3N2Zz4="));
        assert!(!accepts("vbscript:msgbox(1)"));
        assert!(!accepts("file:///etc/passwd"));
    }

    #[test]
    fn rejects_paths_that_leave_this_origin() {
        assert!(!accepts("//evil.tld/a.png"));
        assert!(!accepts("/api/admin"));
    }

    #[test]
    fn rejects_an_over_long_url() {
        let long = format!("https://example.test/{}", "a".repeat(4096));
        assert!(!accepts(&long));
    }
}
