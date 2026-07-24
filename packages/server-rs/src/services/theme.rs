//! Community theme marketplace.
//!
//! A theme is a map of allow-listed `--oc-*` CSS variables to colour values.
//! The security model lives entirely in [`validate_vars`]: keys must be on the
//! fixed allow-list, and values must match a strict colour grammar. Nothing else
//! is storable - no freeform CSS, no `url()`, no code - so installing a
//! stranger's theme can only ever recolour the app. There is no way to inject
//! script or an overlay that could fake a prompt.

use serde_json::{Map, Value};

use crate::dto::ThemeDto;
use crate::error::{AppError, AppResult};
use crate::models::ThemeRow;
use crate::state::AppState;
use crate::timefmt::iso;

/// The variables a theme may set. Matches the `--oc-*` custom properties the
/// stylesheet defines; anything outside this set is dropped rather than stored.
const ALLOWED_VARS: &[&str] = &[
    "--oc-surface-0",
    "--oc-surface-1",
    "--oc-surface-2",
    "--oc-surface-3",
    "--oc-surface-4",
    "--oc-border",
    "--oc-border-strong",
    "--oc-ink",
    "--oc-ink-secondary",
    "--oc-ink-muted",
    "--oc-ink-on-primary",
    "--oc-primary",
    "--oc-primary-hover",
    "--oc-primary-active",
    "--oc-primary-soft",
    "--oc-success",
    "--oc-warning",
    "--oc-danger",
    "--oc-info",
];

const MAX_NAME: usize = 60;

/// Whether a string is a colour we're willing to store. Deliberately strict:
/// hex, or an rgb()/rgba()/hsl()/hsla() whose interior is only digits,
/// separators and units. That interior grammar is what makes a breakout like
/// `red; position: fixed` impossible - none of `;{}:` can appear.
fn is_color(value: &str) -> bool {
    let v = value.trim();
    if v.is_empty() || v.len() > 64 {
        return false;
    }

    // #rgb, #rgba, #rrggbb, #rrggbbaa
    if let Some(hex) = v.strip_prefix('#') {
        return matches!(hex.len(), 3 | 4 | 6 | 8) && hex.bytes().all(|b| b.is_ascii_hexdigit());
    }

    // functional colour: name '(' ...safe... ')'. Longer names first, so "rgb"
    // doesn't shadow "rgba" and leave a stray "a(" that then fails to parse.
    let functional = ["rgba", "hsla", "rgb", "hsl"]
        .iter()
        .find_map(|f| v.strip_prefix(*f).map(|rest| rest.trim_start()));
    if let Some(rest) = functional {
        if let Some(inner) = rest.strip_prefix('(').and_then(|r| r.strip_suffix(')')) {
            return !inner.is_empty()
                && inner.bytes().all(|b| {
                    b.is_ascii_digit()
                        || matches!(b, b'.' | b',' | b' ' | b'%' | b'/' | b'-')
                });
        }
    }

    false
}

/// Keep only allow-listed keys with valid colour values. Returns the cleaned
/// map, or an error if nothing survived - an empty theme is a mistake worth
/// reporting rather than silently storing.
pub fn validate_vars(input: &Value) -> AppResult<Value> {
    let obj = input
        .as_object()
        .ok_or_else(|| AppError::BadRequest("vars must be an object".into()))?;

    let mut clean = Map::new();
    for (key, value) in obj {
        if !ALLOWED_VARS.contains(&key.as_str()) {
            continue;
        }
        let Some(color) = value.as_str() else { continue };
        if is_color(color) {
            clean.insert(key.clone(), Value::String(color.trim().to_string()));
        }
    }

    if clean.is_empty() {
        return Err(AppError::BadRequest(
            "A theme needs at least one valid colour".into(),
        ));
    }
    Ok(Value::Object(clean))
}

fn validate_name(name: &str) -> AppResult<String> {
    let name = name.trim();
    if name.is_empty() || name.len() > MAX_NAME {
        return Err(AppError::BadRequest(
            "Theme name must be 1-60 characters".into(),
        ));
    }
    Ok(name.to_string())
}

fn to_dto(row: ThemeRow, author_name: Option<String>) -> ThemeDto {
    ThemeDto {
        id: row.id,
        author_id: row.author_id,
        author_name,
        name: row.name,
        vars: row.vars,
        submitted: row.submitted,
        published: row.published,
        installs: row.installs,
        created_at: iso(row.created_at),
    }
}

/// The published-theme row plus its author's display name, for the marketplace.
#[derive(sqlx::FromRow)]
struct ThemeWithAuthor {
    #[sqlx(flatten)]
    theme: ThemeRow,
    author_name: Option<String>,
}

/// Published themes for the marketplace, most-installed first.
pub async fn list_published(state: &AppState) -> AppResult<Vec<ThemeDto>> {
    let rows: Vec<ThemeWithAuthor> = sqlx::query_as(
        r#"SELECT t.*, u."displayName" AS author_name
           FROM "Theme" t JOIN "User" u ON u.id = t."authorId"
           WHERE t.published = true
           ORDER BY t.installs DESC, t."createdAt" DESC
           LIMIT 200"#,
    )
    .fetch_all(&state.pool)
    .await?;
    Ok(rows
        .into_iter()
        .map(|r| to_dto(r.theme, r.author_name))
        .collect())
}

/// The caller's own themes, published or not.
pub async fn list_mine(state: &AppState, user_id: &str) -> AppResult<Vec<ThemeDto>> {
    let rows: Vec<ThemeRow> = sqlx::query_as(
        r#"SELECT * FROM "Theme" WHERE "authorId" = $1 ORDER BY "createdAt" DESC"#,
    )
    .bind(user_id)
    .fetch_all(&state.pool)
    .await?;
    Ok(rows.into_iter().map(|r| to_dto(r, None)).collect())
}

/// Themes awaiting review: submitted by their author, not yet published. The
/// host-only admin panel is the only caller. Oldest first, so the queue is FIFO.
pub async fn list_pending(state: &AppState) -> AppResult<Vec<ThemeDto>> {
    let rows: Vec<ThemeWithAuthor> = sqlx::query_as(
        r#"SELECT t.*, u."displayName" AS author_name
           FROM "Theme" t JOIN "User" u ON u.id = t."authorId"
           WHERE t.submitted = true AND t.published = false
           ORDER BY t."createdAt" ASC"#,
    )
    .fetch_all(&state.pool)
    .await?;
    Ok(rows
        .into_iter()
        .map(|r| to_dto(r.theme, r.author_name))
        .collect())
}

pub async fn create(
    state: &AppState,
    user_id: &str,
    name: &str,
    vars: &Value,
    submitted: bool,
) -> AppResult<ThemeDto> {
    let name = validate_name(name)?;
    let vars = validate_vars(vars)?;

    // published is never set here - only the host-only approve path grants it.
    let row: ThemeRow = sqlx::query_as(
        r#"INSERT INTO "Theme" (id, "authorId", name, vars, submitted, "updatedAt")
           VALUES ($1, $2, $3, $4, $5, now()) RETURNING *"#,
    )
    .bind(crate::ids::cuid())
    .bind(user_id)
    .bind(&name)
    .bind(&vars)
    .bind(submitted)
    .fetch_one(&state.pool)
    .await?;
    Ok(to_dto(row, None))
}

/// Author edits: rename, and submit or withdraw. Crucially cannot touch
/// `published` - that is the admin panel's alone, so a user can't self-publish.
pub async fn update_own(
    state: &AppState,
    user_id: &str,
    theme_id: &str,
    name: Option<&str>,
    submitted: Option<bool>,
) -> AppResult<ThemeDto> {
    let row: Option<ThemeRow> = sqlx::query_as(r#"SELECT * FROM "Theme" WHERE id = $1"#)
        .bind(theme_id)
        .fetch_optional(&state.pool)
        .await?;
    let row = row.ok_or_else(|| AppError::NotFound("Theme not found".into()))?;
    if row.author_id != user_id {
        return Err(AppError::Permission("Not your theme".into()));
    }

    let name = match name {
        Some(n) => validate_name(n)?,
        None => row.name.clone(),
    };
    let submitted = submitted.unwrap_or(row.submitted);

    let updated: ThemeRow = sqlx::query_as(
        r#"UPDATE "Theme" SET name = $2, submitted = $3, "updatedAt" = now()
           WHERE id = $1 RETURNING *"#,
    )
    .bind(theme_id)
    .bind(&name)
    .bind(submitted)
    .fetch_one(&state.pool)
    .await?;
    Ok(to_dto(updated, None))
}

/// Admin decision, host-only. Approve lists it; reject clears the submission so
/// it drops out of the queue and back to private.
pub async fn review(state: &AppState, theme_id: &str, approve: bool) -> AppResult<ThemeDto> {
    let updated: Option<ThemeRow> = sqlx::query_as(
        r#"UPDATE "Theme"
           SET published = $2,
               submitted = CASE WHEN $2 THEN submitted ELSE false END,
               "updatedAt" = now()
           WHERE id = $1 RETURNING *"#,
    )
    .bind(theme_id)
    .bind(approve)
    .fetch_optional(&state.pool)
    .await?;
    updated
        .map(|r| to_dto(r, None))
        .ok_or_else(|| AppError::NotFound("Theme not found".into()))
}

pub async fn delete(state: &AppState, user_id: &str, theme_id: &str) -> AppResult<()> {
    let deleted = sqlx::query(r#"DELETE FROM "Theme" WHERE id = $1 AND "authorId" = $2"#)
        .bind(theme_id)
        .bind(user_id)
        .execute(&state.pool)
        .await?;
    if deleted.rows_affected() == 0 {
        return Err(AppError::NotFound("Theme not found".into()));
    }
    Ok(())
}

/// Records an install and returns the theme. A published theme is installable by
/// anyone; a private one only by its author (so previewing your own draft works).
pub async fn install(state: &AppState, user_id: &str, theme_id: &str) -> AppResult<ThemeDto> {
    let row: Option<ThemeRow> = sqlx::query_as(r#"SELECT * FROM "Theme" WHERE id = $1"#)
        .bind(theme_id)
        .fetch_optional(&state.pool)
        .await?;
    let row = row.ok_or_else(|| AppError::NotFound("Theme not found".into()))?;
    if !row.published && row.author_id != user_id {
        return Err(AppError::NotFound("Theme not found".into()));
    }

    // Count installs of other people's themes, not previews of your own.
    if row.author_id != user_id {
        sqlx::query(r#"UPDATE "Theme" SET installs = installs + 1 WHERE id = $1"#)
            .bind(theme_id)
            .execute(&state.pool)
            .await?;
    }
    Ok(to_dto(row, None))
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn accepts_valid_colours() {
        for c in ["#fff", "#ffffff", "#ffffffff", "rgb(1,2,3)", "rgba(1,2,3,0.5)", "hsl(1, 2%, 3%)"] {
            assert!(is_color(c), "should accept {c}");
        }
    }

    #[test]
    fn rejects_breakouts_and_non_colours() {
        for c in [
            "red; position: fixed",
            "javascript:alert(1)",
            "expression(x)",
            "url(https://evil)",
            "#12g",
            "rgb(1,2,3); }",
            "blue}",
            "",
        ] {
            assert!(!is_color(c), "should reject {c}");
        }
    }

    #[test]
    fn validate_keeps_only_allowlisted_valid_pairs() {
        let input = json!({
            "--oc-primary": "red; position:fixed", // bad value -> dropped
            "--oc-primary-hover": "#00ff00",         // kept
            "--evil": "#000",                        // bad key -> dropped
            "background": "url(x)",                   // bad key -> dropped
            "--oc-surface-0": "rgb(10,10,10)",       // kept
        });
        let out = validate_vars(&input).expect("has valid entries");
        let obj = out.as_object().unwrap();
        assert_eq!(obj.len(), 2);
        assert_eq!(obj["--oc-primary-hover"], "#00ff00");
        assert_eq!(obj["--oc-surface-0"], "rgb(10,10,10)");
        assert!(!obj.contains_key("--oc-primary"));
        assert!(!obj.contains_key("--evil"));
    }

    #[test]
    fn validate_rejects_all_invalid() {
        let input = json!({ "--oc-primary": "javascript:alert(1)", "--oc-ink": "expression(x)" });
        assert!(validate_vars(&input).is_err());
    }
}
