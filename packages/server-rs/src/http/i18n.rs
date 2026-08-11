//! Server-served string catalogs.
//!
//! The Android APK bakes ten locales in at build time, which means a typo
//! fix or a brand-new language costs an APK release - and the release channel
//! is a full download plus the system installer's confirmation. This is the
//! lightweight path: the server serves the same catalogs the app already
//! ships, keyed by resource name, so a fix is a commit of a JSON file and the
//! app picks it up on its next poll. The bundled resources stay the offline
//! fallback; the fetched catalog only overrides individual keys.
//!
//! Catalogs live in the repo under `<dir>/<platform>/<code>.json`, authored by
//! `android/tools/export_server_catalogs.py` from `res/values-*/strings.xml`
//! and loaded once at startup. Adding a language is adding a `values-xx/`
//! directory, exporting, and restarting - no database, no admin surface.
//!
//! Deliberately unauthenticated and exempt from the version wall (merged after
//! it, like `/updates/policy`): a retired build still gets string fixes, and
//! the request is only ever three small GETs per device per hour.

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

/// One language's full catalogue: every bundled string key plus its
/// translation, as shipped by the export script. `rev` is a content hash, so
/// clients can short-circuit the fetch and the hash never needs manual
/// bookkeeping when a translator edits a file.
#[derive(Clone, Serialize, Deserialize)]
pub struct Catalog {
    pub code: String,
    pub endonym: String,
    pub rev: String,
    pub strings: HashMap<String, String>,
}

/// All catalogs the server can serve, laid out as platform → code → catalog so
/// a future web client can hold its own key space (`i18n/web/`) without
/// changing the Android contract.
pub struct I18nStore {
    platforms: HashMap<String, HashMap<String, Arc<Catalog>>>,
}

impl I18nStore {
    /// Reads every `<dir>/<platform>/*.json`. A missing or unreadable entry
    /// logs and is skipped; a missing root logs once and yields an empty
    /// store, so an unconfigured deployment keeps serving with resources-only
    /// fallback rather than failing to boot.
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

    /// The catalogs for `platform`, in language-code order so the picker list
    /// is deterministic across restarts.
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

/// Every language the server can serve for a platform. The client merges this
/// with what it shipped, which is what lets a language appear in the picker
/// without an app update.
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

/// A language's full catalogue. The client sends the `rev` it has cached; a
/// match answers 304 and the client keeps what it has.
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
