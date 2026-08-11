
use std::collections::HashMap;
use std::sync::Arc;

use axum::extract::{Query, State};
use axum::http::StatusCode;
use axum::response::IntoResponse;
use axum::routing::get;
use axum::{Json, Router};
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};

use crate::error::{AppError, AppResult};
use crate::state::AppState;

pub const DEFAULT_PLATFORM: &str = "android";

#[derive(Clone, Serialize, Deserialize)]
pub struct Catalog {
    pub code: String,
    pub endonym: String,
    pub rev: String,
    pub strings: HashMap<String, String>,
}

pub struct I18nStore {
    platforms: HashMap<String, HashMap<String, Arc<Catalog>>>,
}

impl I18nStore {
    pub fn load(dir: &str) -> Self {
        let mut platforms: HashMap<String, HashMap<String, Arc<Catalog>>> = HashMap::new();
        let Ok(root) = std::fs::read_dir(dir) else {
            tracing::warn!(%dir, "i18n directory missing; serving no remote catalogs");
            return I18nStore { platforms };
        };
        for entry in root.flatten() {
            let platform_dir = entry.path();
            if !platform_dir.is_dir() {
                continue;
            }
            let platform = entry.file_name().to_string_lossy().to_string();
            let mut catalogs: HashMap<String, Arc<Catalog>> = HashMap::new();
            for file in std::fs::read_dir(&platform_dir)
                .into_iter()
                .flatten()
                .flatten()
            {
                if file.path().extension().and_then(|e| e.to_str()) != Some("json") {
                    continue;
                }
                match std::fs::read_to_string(file.path()) {
                    Ok(raw) => match serde_json::from_str::<Catalog>(&raw) {
                        Ok(catalog) => {
                            tracing::info!(
                                platform,
                                code = %catalog.code,
                                rev = %catalog.rev,
                                "loaded i18n catalog"
                            );
                            catalogs.insert(catalog.code.clone(), Arc::new(catalog));
                        }
                        Err(err) => tracing::warn!(
                            path = %file.path().display(),
                            %err,
                            "skipping invalid i18n catalog"
                        ),
                    },
                    Err(err) => tracing::warn!(
                        path = %file.path().display(),
                        %err,
                        "could not read i18n catalog"
                    ),
                }
            }
            platforms.insert(platform, catalogs);
        }
        I18nStore { platforms }
    }

    pub fn languages(&self, platform: &str) -> Vec<Arc<Catalog>> {
        let mut list: Vec<Arc<Catalog>> = self
            .platforms
            .get(platform)
            .map(|catalogs| catalogs.values().cloned().collect())
            .unwrap_or_default();
        list.sort_by(|a, b| a.code.cmp(&b.code));
        list
    }

    pub fn catalog(&self, platform: &str, code: &str) -> Option<Arc<Catalog>> {
        self.platforms
            .get(platform)
            .and_then(|catalogs| catalogs.get(code))
            .cloned()
    }
}

pub fn routes() -> Router<AppState> {
    Router::new()
        .route("/i18n/languages", get(languages))
        .route("/i18n/catalog", get(catalog))
}

async fn languages(
    State(state): State<AppState>,
    Query(q): Query<HashMap<String, String>>,
) -> AppResult<Json<Value>> {
    let platform = q.get("platform").map(String::as_str).unwrap_or(DEFAULT_PLATFORM);
    let list: Vec<Value> = state
        .i18n
        .languages(platform)
        .iter()
        .map(|catalog| {
            json!({
                "code": catalog.code,
                "endonym": catalog.endonym,
                "rev": catalog.rev,
            })
        })
        .collect();
    Ok(Json(json!(list)))
}

async fn catalog(
    State(state): State<AppState>,
    Query(q): Query<HashMap<String, String>>,
) -> Result<axum::response::Response, AppError> {
    let platform = q.get("platform").map(String::as_str).unwrap_or(DEFAULT_PLATFORM);
    let code = q.get("lang").map(String::as_str).unwrap_or_default();
    let Some(catalog) = state.i18n.catalog(platform, code) else {
        return Err(AppError::NotFound(format!("no catalog for {code}")));
    };
    if q.get("rev").map(String::as_str) == Some(catalog.rev.as_str()) {
        return Ok(StatusCode::NOT_MODIFIED.into_response());
    }
    Ok(Json(json!({
        "code": catalog.code,
        "endonym": catalog.endonym,
        "rev": catalog.rev,
        "strings": catalog.strings,
    }))
    .into_response())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn store_loads_only_the_requested_platform() {
        let dir = std::env::temp_dir().join(format!("orangchat-i18n-test-{}", std::process::id()));
        let platform_dir = dir.join("android");
        std::fs::create_dir_all(&platform_dir).unwrap();
        std::fs::write(
            platform_dir.join("de.json"),
            r#"{"code":"de","endonym":"Deutsch","rev":"abc","strings":{"catalog_x":"Hallo"}}"#,
        )
        .unwrap();
        std::fs::write(
            platform_dir.join("not-journal.json.bak"),
            r#"{"code":"zz"}"#,
        )
        .unwrap();

        let store = I18nStore::load(dir.to_str().unwrap());
        assert_eq!(store.languages("android").len(), 1);
        assert_eq!(store.languages("web").len(), 0);
        let de = store.catalog("android", "de").expect("de loaded");
        assert_eq!(de.endonym, "Deutsch");
        assert_eq!(de.strings.get("catalog_x").map(String::as_str), Some("Hallo"));

        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn missing_root_yields_an_empty_store() {
        let store = I18nStore::load("/nonexistent/i18n-dir");
        assert!(store.languages("android").is_empty());
        assert!(store.catalog("android", "en").is_none());
    }
}
