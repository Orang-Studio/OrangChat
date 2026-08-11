
use std::collections::HashMap;
use std::sync::OnceLock;

use chrono::Utc;
use serde::Deserialize;

use crate::dto::ActivityDto;
use crate::error::AppResult;
use crate::services::presence;
use crate::state::AppState;
use crate::timefmt::iso;

#[derive(Debug, Deserialize)]
struct RegistryEntry {
    id: String,
    name: String,
}

#[derive(Debug, Deserialize)]
struct Registry {
    games: Vec<RegistryEntry>,
}

const REGISTRY_JSON: &str = include_str!("../../../shared/games.json");

const MAX_CUSTOM_NAME: usize = 64;

fn registry() -> &'static HashMap<String, String> {
    static REGISTRY: OnceLock<HashMap<String, String>> = OnceLock::new();
    REGISTRY.get_or_init(|| {
        let parsed: Registry = serde_json::from_str(REGISTRY_JSON)
            .expect("games.json is generated and compiled in; a parse failure is a build error");
        parsed
            .games
            .into_iter()
            .map(|entry| (entry.id, entry.name))
            .collect()
    })
}

fn sanitize_custom(name: &str) -> Option<String> {
    let cleaned: String = name
        .chars()
        .filter(|c| !c.is_control() && !('\u{202a}'..='\u{202e}').contains(c) && *c != '\u{2066}')
        .collect();
    let trimmed = cleaned.trim();
    if trimmed.is_empty() {
        return None;
    }
    Some(trimmed.chars().take(MAX_CUSTOM_NAME).collect())
}

fn image_url(id: &str) -> String {
    format!("/games/{id}.webp")
}

pub fn resolve(
    game_id: Option<&str>,
    custom_name: Option<&str>,
    previous: Option<&ActivityDto>,
    now: String,
) -> Option<ActivityDto> {
    let (name, image_url) = match (game_id, custom_name) {
        (Some(id), _) => {
            let name = registry().get(id)?.clone();
            (name, Some(image_url(id)))
        }
        (None, Some(custom)) => (sanitize_custom(custom)?, None),
        (None, None) => return None,
    };

    let started_at = previous
        .filter(|activity| activity.name == name)
        .and_then(|activity| activity.started_at.clone())
        .or(Some(now));

    Some(ActivityDto {
        kind: "game".into(),
        name,
        details: None,
        url: None,
        image_url,
        started_at,
        ends_at: None,
    })
}

async fn is_enabled(state: &AppState, user_id: &str) -> bool {
    sqlx::query_scalar::<_, bool>(r#"SELECT "gameActivity" FROM "User" WHERE id = $1"#)
        .bind(user_id)
        .fetch_optional(&state.pool)
        .await
        .ok()
        .flatten()
        .unwrap_or(false)
}

pub async fn report(
    state: &AppState,
    user_id: &str,
    game_id: Option<&str>,
    custom_name: Option<&str>,
) -> AppResult<bool> {
    let activity = if is_enabled(state, user_id).await {
        let activities = presence::get_activities(state, user_id).await?;
        let previous = activities.iter().find(|item| item.kind == "game");
        resolve(game_id, custom_name, previous, iso(Utc::now().naive_utc()))
    } else {
        None
    };
    presence::set_activity(state, user_id, "game", activity).await
}

pub async fn clear(state: &AppState, user_id: &str) -> AppResult<bool> {
    presence::set_activity(state, user_id, "game", None).await
}

#[cfg(test)]
mod tests {
    use super::*;

    fn dto(name: &str, started_at: &str) -> ActivityDto {
        ActivityDto {
            kind: "game".into(),
            name: name.into(),
            details: None,
            url: None,
            image_url: None,
            started_at: Some(started_at.into()),
            ends_at: None,
        }
    }

    #[test]
    fn registry_parses_and_is_populated() {
        assert!(registry().len() > 1000);
    }

    #[test]
    fn unknown_id_resolves_to_nothing() {
        assert!(resolve(Some("not-a-real-game"), None, None, "t0".into()).is_none());
    }

    #[test]
    fn known_id_takes_its_name_and_art_from_the_registry() {
        let id = registry().keys().next().unwrap().clone();
        let resolved = resolve(Some(&id), None, None, "t0".into()).unwrap();
        assert_eq!(resolved.name, registry()[&id]);
        assert_eq!(resolved.image_url, Some(format!("/games/{id}.webp")));
    }

    #[test]
    fn custom_name_never_gets_artwork() {
        let resolved = resolve(None, Some("Some Indie Game"), None, "t0".into()).unwrap();
        assert_eq!(resolved.name, "Some Indie Game");
        assert_eq!(resolved.image_url, None);
    }

    #[test]
    fn custom_name_is_stripped_and_capped() {
        assert!(resolve(None, Some("  \u{202e}\n "), None, "t0".into()).is_none());
        let long = "x".repeat(200);
        let resolved = resolve(None, Some(&long), None, "t0".into()).unwrap();
        assert_eq!(resolved.name.chars().count(), MAX_CUSTOM_NAME);
    }

    #[test]
    fn elapsed_clock_survives_a_repeat_report() {
        let previous = dto("Held Over", "t0");
        let resolved = resolve(None, Some("Held Over"), Some(&previous), "t9".into()).unwrap();
        assert_eq!(resolved.started_at, Some("t0".into()));
    }

    #[test]
    fn a_different_game_restarts_the_clock() {
        let previous = dto("Something Else", "t0");
        let resolved = resolve(None, Some("Held Over"), Some(&previous), "t9".into()).unwrap();
        assert_eq!(resolved.started_at, Some("t9".into()));
    }
}
