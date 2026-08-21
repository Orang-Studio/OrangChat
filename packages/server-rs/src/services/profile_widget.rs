use base64::Engine as _;
use rand::RngCore;
use serde::{Deserialize, Serialize};
use serde_json::{json, Map, Value};
use sha2::{Digest, Sha256};

use crate::error::{AppError, AppResult};
use crate::state::AppState;

pub const MAX_WIDGETS: usize = 24;
pub const MAX_CONFIG_BYTES: usize = 4096;
pub const MAX_FIELDS: usize = 32;
pub const MAX_FIELD_KEY: usize = 64;
pub const MAX_FIELD_VALUE: usize = 200;
pub const MAX_TOKENS_PER_USER: i64 = 10;
const MAX_TOKEN_LABEL: usize = 60;

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(tag = "kind", rename_all = "lowercase")]
pub enum ConfigField {
    String {
        key: String,
        label: String,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        max: Option<usize>,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        multiline: Option<bool>,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        placeholder: Option<String>,
    },
    Url {
        key: String,
        label: String,
    },
    Boolean {
        key: String,
        label: String,
    },
    Select {
        key: String,
        label: String,
        options: Vec<SelectOption>,
    },
    List {
        key: String,
        label: String,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        max: Option<usize>,
        of: Vec<ConfigField>,
    },
}

impl ConfigField {
    fn key(&self) -> &str {
        match self {
            ConfigField::String { key, .. }
            | ConfigField::Url { key, .. }
            | ConfigField::Boolean { key, .. }
            | ConfigField::Select { key, .. }
            | ConfigField::List { key, .. } => key,
        }
    }
}

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct SelectOption {
    pub value: String,
    pub label: String,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct WidgetDefinition {
    #[serde(rename = "type")]
    pub kind: String,
    pub label: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub description: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub icon: Option<String>,
    #[serde(default, skip_serializing_if = "std::ops::Not::not")]
    pub singleton: bool,
    #[serde(default, rename = "default", skip_serializing_if = "std::ops::Not::not")]
    pub in_default_layout: bool,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub config: Vec<ConfigField>,
    pub render: Value,
}

#[derive(Deserialize)]
struct CatalogFile {
    widgets: Vec<WidgetDefinition>,
}

pub struct WidgetCatalog {
    widgets: Vec<WidgetDefinition>,
    rev: String,
}

impl WidgetCatalog {
    pub fn load(path: &str) -> Self {
        let raw = match std::fs::read_to_string(path) {
            Ok(raw) => raw,
            Err(err) => {
                tracing::warn!(%path, %err, "widget catalog missing; profiles fall back to built-ins");
                return WidgetCatalog { widgets: Vec::new(), rev: "0".into() };
            }
        };
        let parsed: CatalogFile = match serde_json::from_str(&raw) {
            Ok(parsed) => parsed,
            Err(err) => {
                tracing::error!(%path, %err, "widget catalog is invalid; serving none");
                return WidgetCatalog { widgets: Vec::new(), rev: "0".into() };
            }
        };
        let rev = hex::encode(Sha256::digest(raw.as_bytes()))[..12].to_string();
        tracing::info!(%path, %rev, count = parsed.widgets.len(), "loaded widget catalog");
        WidgetCatalog { widgets: parsed.widgets, rev }
    }

    pub fn rev(&self) -> &str {
        &self.rev
    }

    pub fn widgets(&self) -> &[WidgetDefinition] {
        &self.widgets
    }

    pub fn find(&self, kind: &str) -> Option<&WidgetDefinition> {
        self.widgets.iter().find(|w| w.kind == kind)
    }

    pub fn default_layout(&self) -> Value {
        let list: Vec<Value> = self
            .widgets
            .iter()
            .filter(|w| w.in_default_layout)
            .enumerate()
            .map(|(i, w)| json!({ "id": format!("w{i}"), "type": w.kind }))
            .collect();
        Value::Array(list)
    }
}

fn strip_unsafe(value: &str) -> String {
    value
        .chars()
        .filter(|c| {
            (!c.is_control() || *c == '\n')
                && !('\u{202a}'..='\u{202e}').contains(c)
                && !('\u{2066}'..='\u{2069}').contains(c)
        })
        .collect()
}

fn clamp(value: &str, max: usize) -> String {
    strip_unsafe(value).trim().chars().take(max).collect()
}

fn widget_id() -> String {
    let mut bytes = [0u8; 8];
    rand::thread_rng().fill_bytes(&mut bytes);
    format!("w_{}", hex::encode(bytes))
}

fn clean_config(fields: &[ConfigField], input: Option<&Map<String, Value>>) -> AppResult<Value> {
    let mut out = Map::new();
    let Some(input) = input else { return Ok(Value::Object(out)) };

    for field in fields {
        let Some(raw) = input.get(field.key()) else { continue };
        if raw.is_null() {
            continue;
        }
        let cleaned = match field {
            ConfigField::String { max, .. } => {
                let text = raw
                    .as_str()
                    .ok_or_else(|| AppError::BadRequest(format!("{} must be text", field.key())))?;
                let text = clamp(text, max.unwrap_or(200));
                if text.is_empty() {
                    continue;
                }
                Value::String(text)
            }
            ConfigField::Url { .. } => {
                let text = raw
                    .as_str()
                    .ok_or_else(|| AppError::BadRequest(format!("{} must be a url", field.key())))?;
                let text = clamp(text, 2048);
                if text.is_empty() {
                    continue;
                }
                crate::services::user::check_image_url(Some(&text), field.key())?;
                Value::String(text)
            }
            ConfigField::Boolean { .. } => Value::Bool(
                raw.as_bool()
                    .ok_or_else(|| AppError::BadRequest(format!("{} must be true or false", field.key())))?,
            ),
            ConfigField::Select { options, .. } => {
                let text = raw.as_str().unwrap_or_default();
                if !options.iter().any(|o| o.value == text) {
                    return Err(AppError::BadRequest(format!("{} is not one of the allowed values", field.key())));
                }
                Value::String(text.to_string())
            }
            ConfigField::List { max, of, .. } => {
                let items = raw
                    .as_array()
                    .ok_or_else(|| AppError::BadRequest(format!("{} must be a list", field.key())))?;
                let mut kept = Vec::new();
                for item in items.iter().take(max.unwrap_or(12)) {
                    let entry = clean_config(of, item.as_object())?;
                    if entry.as_object().is_some_and(|o| !o.is_empty()) {
                        kept.push(entry);
                    }
                }
                if kept.is_empty() {
                    continue;
                }
                Value::Array(kept)
            }
        };
        out.insert(field.key().to_string(), cleaned);
    }
    Ok(Value::Object(out))
}

pub fn validate_widgets(catalog: &WidgetCatalog, input: &Value) -> AppResult<Value> {
    let list = input
        .as_array()
        .ok_or_else(|| AppError::BadRequest("profileWidgets must be a list".into()))?;
    if list.len() > MAX_WIDGETS {
        return Err(AppError::BadRequest(format!(
            "a profile can hold at most {MAX_WIDGETS} widgets"
        )));
    }

    let mut out = Vec::with_capacity(list.len());
    let mut seen_ids = std::collections::HashSet::new();
    let mut seen_singletons = std::collections::HashSet::new();

    for entry in list {
        let obj = entry
            .as_object()
            .ok_or_else(|| AppError::BadRequest("each widget must be an object".into()))?;
        let kind = obj
            .get("type")
            .and_then(Value::as_str)
            .ok_or_else(|| AppError::BadRequest("each widget needs a type".into()))?;
        let Some(def) = catalog.find(kind) else {
            return Err(AppError::BadRequest(format!("unknown widget type: {kind}")));
        };
        if def.singleton && !seen_singletons.insert(kind.to_string()) {
            return Err(AppError::BadRequest(format!(
                "the {kind} widget can only be added once"
            )));
        }

        let mut id = obj
            .get("id")
            .and_then(Value::as_str)
            .map(|s| s.chars().filter(|c| c.is_ascii_alphanumeric() || *c == '_').take(32).collect::<String>())
            .filter(|s| !s.is_empty())
            .unwrap_or_else(widget_id);
        while !seen_ids.insert(id.clone()) {
            id = widget_id();
        }

        let config = clean_config(&def.config, obj.get("config").and_then(Value::as_object))?;
        if serde_json::to_string(&config).map(|s| s.len()).unwrap_or(0) > MAX_CONFIG_BYTES {
            return Err(AppError::BadRequest(format!(
                "the {kind} widget's settings are too large"
            )));
        }

        let mut widget = Map::new();
        widget.insert("id".into(), Value::String(id));
        widget.insert("type".into(), Value::String(kind.to_string()));
        if obj.get("hidden").and_then(Value::as_bool).unwrap_or(false) {
            widget.insert("hidden".into(), Value::Bool(true));
        }
        if config.as_object().is_some_and(|o| !o.is_empty()) {
            widget.insert("config".into(), config);
        }
        out.push(Value::Object(widget));
    }

    Ok(Value::Array(out))
}

pub fn clean_field_key(key: &str) -> Option<String> {
    let key: String = key
        .trim()
        .to_ascii_lowercase()
        .chars()
        .filter(|c| c.is_ascii_alphanumeric() || *c == '_' || *c == '-')
        .take(MAX_FIELD_KEY)
        .collect();
    (!key.is_empty()).then_some(key)
}

pub fn clean_field_value(value: &Value) -> AppResult<String> {
    let text = match value {
        Value::String(s) => s.clone(),
        Value::Number(n) => n.to_string(),
        Value::Bool(b) => b.to_string(),
        _ => {
            return Err(AppError::BadRequest(
                "field values must be a string, number or boolean".into(),
            ))
        }
    };
    Ok(clamp(&text, MAX_FIELD_VALUE))
}

pub fn merge_fields(existing: &Value, incoming: &Map<String, Value>) -> AppResult<Value> {
    let mut out = existing.as_object().cloned().unwrap_or_default();
    for (key, value) in incoming {
        let Some(key) = clean_field_key(key) else {
            return Err(AppError::BadRequest(format!("invalid field name: {key}")));
        };
        if value.is_null() {
            out.remove(&key);
            continue;
        }
        let cleaned = clean_field_value(value)?;
        if cleaned.is_empty() {
            out.remove(&key);
            continue;
        }
        if !out.contains_key(&key) && out.len() >= MAX_FIELDS {
            return Err(AppError::BadRequest(format!(
                "a profile can hold at most {MAX_FIELDS} custom fields"
            )));
        }
        out.insert(key, Value::String(cleaned));
    }
    Ok(Value::Object(out))
}

fn hash_token(token: &str) -> String {
    hex::encode(Sha256::digest(token.as_bytes()))
}

fn generate_token(user_id: &str) -> String {
    let mut secret = [0u8; 32];
    rand::thread_rng().fill_bytes(&mut secret);
    let engine = base64::engine::general_purpose::URL_SAFE_NO_PAD;
    format!("{}.{}", engine.encode(user_id), engine.encode(secret))
}

fn claimed_user_id(token: &str) -> Option<String> {
    let prefix = token.split('.').next()?;
    let raw = base64::engine::general_purpose::URL_SAFE_NO_PAD.decode(prefix).ok()?;
    String::from_utf8(raw).ok()
}

pub async fn authenticate(state: &AppState, token: &str) -> AppResult<String> {
    let denied = || AppError::Unauthorized("Invalid widget token".into());
    let claimed = claimed_user_id(token).ok_or_else(denied)?;
    let hash = hash_token(token);

    let row: Option<(String, String)> = sqlx::query_as(
        r#"SELECT t.id, t."userId"
             FROM "ProfileFieldToken" t
             JOIN "User" u ON u.id = t."userId"
            WHERE t."tokenHash" = $1 AND t."userId" = $2 AND u."deletedAt" IS NULL"#,
    )
    .bind(&hash)
    .bind(&claimed)
    .fetch_optional(&state.pool)
    .await?;

    let (token_id, user_id) = row.ok_or_else(denied)?;
    let _ = sqlx::query(r#"UPDATE "ProfileFieldToken" SET "lastUsedAt" = now() WHERE id = $1"#)
        .bind(&token_id)
        .execute(&state.pool)
        .await;
    Ok(user_id)
}

pub async fn mint_token(state: &AppState, user_id: &str, label: &str) -> AppResult<(String, String, String)> {
    let count: i64 = sqlx::query_scalar(r#"SELECT count(*) FROM "ProfileFieldToken" WHERE "userId" = $1"#)
        .bind(user_id)
        .fetch_one(&state.pool)
        .await?;
    if count >= MAX_TOKENS_PER_USER {
        return Err(AppError::BadRequest(format!(
            "you can hold at most {MAX_TOKENS_PER_USER} widget tokens"
        )));
    }

    let label = clamp(label, MAX_TOKEN_LABEL);
    let label = if label.is_empty() { "Token".to_string() } else { label };
    let token = generate_token(user_id);
    let hint: String = token.chars().rev().take(4).collect::<Vec<_>>().into_iter().rev().collect();
    let id: String = format!("pft_{}", hex::encode({
        let mut b = [0u8; 12];
        rand::thread_rng().fill_bytes(&mut b);
        b
    }));

    sqlx::query(
        r#"INSERT INTO "ProfileFieldToken" (id, "userId", "tokenHash", hint, label)
           VALUES ($1, $2, $3, $4, $5)"#,
    )
    .bind(&id)
    .bind(user_id)
    .bind(hash_token(&token))
    .bind(&hint)
    .bind(&label)
    .execute(&state.pool)
    .await?;

    Ok((id, token, hint))
}

pub async fn revoke_token(state: &AppState, user_id: &str, token_id: &str) -> AppResult<()> {
    let result = sqlx::query(r#"DELETE FROM "ProfileFieldToken" WHERE id = $1 AND "userId" = $2"#)
        .bind(token_id)
        .bind(user_id)
        .execute(&state.pool)
        .await?;
    if result.rows_affected() == 0 {
        return Err(AppError::NotFound("Token not found".into()));
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn catalog() -> WidgetCatalog {
        let raw = r#"{"widgets":[
            {"type":"bio","label":"l","singleton":true,"default":true,"render":{"block":"native","component":"bio"}},
            {"type":"text","label":"l","config":[
                {"kind":"string","key":"body","label":"b","max":10}
            ],"render":{"block":"text","value":"{config.body}"}},
            {"type":"links","label":"l","config":[
                {"kind":"list","key":"items","label":"i","max":2,"of":[
                    {"kind":"string","key":"label","label":"l","max":8},
                    {"kind":"url","key":"url","label":"u"}
                ]}
            ],"render":{"block":"links","from":"config.items"}}
        ]}"#;
        let parsed: CatalogFile = serde_json::from_str(raw).unwrap();
        WidgetCatalog { widgets: parsed.widgets, rev: "test".into() }
    }

    #[test]
    fn an_unknown_widget_type_is_refused() {
        let err = validate_widgets(&catalog(), &json!([{ "type": "nope" }]));
        assert!(err.is_err());
    }

    #[test]
    fn a_singleton_cannot_appear_twice() {
        let err = validate_widgets(&catalog(), &json!([{ "type": "bio" }, { "type": "bio" }]));
        assert!(err.is_err());
    }

    #[test]
    fn a_non_singleton_can_repeat() {
        let ok = validate_widgets(&catalog(), &json!([{ "type": "text" }, { "type": "text" }])).unwrap();
        assert_eq!(ok.as_array().unwrap().len(), 2);
    }

    #[test]
    fn missing_ids_are_generated_and_collisions_broken() {
        let out = validate_widgets(&catalog(), &json!([
            { "type": "text", "id": "same" },
            { "type": "text", "id": "same" }
        ]))
        .unwrap();
        let ids: Vec<&str> = out
            .as_array()
            .unwrap()
            .iter()
            .map(|w| w["id"].as_str().unwrap())
            .collect();
        assert_ne!(ids[0], ids[1]);
    }

    #[test]
    fn config_keys_outside_the_schema_are_dropped() {
        let out = validate_widgets(&catalog(), &json!([
            { "type": "text", "config": { "body": "hi", "sneaky": "x" } }
        ]))
        .unwrap();
        let config = &out[0]["config"];
        assert_eq!(config["body"], "hi");
        assert!(config.get("sneaky").is_none());
    }

    #[test]
    fn overlong_config_text_is_truncated_not_rejected() {
        let out = validate_widgets(&catalog(), &json!([
            { "type": "text", "config": { "body": "0123456789abcdef" } }
        ]))
        .unwrap();
        assert_eq!(out[0]["config"]["body"], "0123456789");
    }

    #[test]
    fn a_widget_url_must_stay_on_a_safe_scheme() {
        let err = validate_widgets(&catalog(), &json!([
            { "type": "links", "config": { "items": [{ "label": "x", "url": "javascript:alert(1)" }] } }
        ]));
        assert!(err.is_err());
    }

    #[test]
    fn lists_are_capped_at_the_declared_maximum() {
        let out = validate_widgets(&catalog(), &json!([
            { "type": "links", "config": { "items": [
                { "label": "a", "url": "https://a.example" },
                { "label": "b", "url": "https://b.example" },
                { "label": "c", "url": "https://c.example" }
            ] } }
        ]))
        .unwrap();
        assert_eq!(out[0]["config"]["items"].as_array().unwrap().len(), 2);
    }

    #[test]
    fn too_many_widgets_is_refused() {
        let many: Vec<Value> = (0..MAX_WIDGETS + 1).map(|_| json!({ "type": "text" })).collect();
        assert!(validate_widgets(&catalog(), &Value::Array(many)).is_err());
    }

    #[test]
    fn the_default_layout_is_the_flagged_widgets() {
        let layout = catalog().default_layout();
        assert_eq!(layout.as_array().unwrap().len(), 1);
        assert_eq!(layout[0]["type"], "bio");
    }

    #[test]
    fn the_shipped_catalog_parses_and_matches_the_built_in_card_order() {
        let shipped = WidgetCatalog::load(concat!(env!("CARGO_MANIFEST_DIR"), "/widgets.json"));
        assert!(!shipped.widgets().is_empty());
        assert_ne!(shipped.rev(), "0");

        let layout = shipped.default_layout();
        let order: Vec<&str> =
            layout.as_array().unwrap().iter().map(|w| w["type"].as_str().unwrap()).collect();
        assert_eq!(
            order,
            ["pronouns", "now-playing", "badges", "bio", "connections", "member-since"],
        );

        for widget in shipped.widgets() {
            assert!(widget.label.starts_with("widget."), "{}", widget.kind);
            assert!(validate_widgets(&shipped, &json!([{ "type": widget.kind }])).is_ok());
        }
    }

    #[test]
    fn a_token_carries_its_owner_id() {
        let token = generate_token("user_abc");
        assert_eq!(claimed_user_id(&token).as_deref(), Some("user_abc"));
    }

    #[test]
    fn tokens_are_unique_and_hashed_beyond_recovery() {
        let a = generate_token("user_abc");
        let b = generate_token("user_abc");
        assert_ne!(a, b);
        assert_eq!(hash_token(&a).len(), 64);
        assert!(!hash_token(&a).contains(&a));
    }

    #[test]
    fn malformed_tokens_claim_nothing() {
        assert_eq!(claimed_user_id("!!!not base64!!!.xyz"), None);
        assert_eq!(claimed_user_id("no-dot-at-all"), None);
    }

    #[test]
    fn pushed_values_are_coerced_and_trimmed() {
        let merged = merge_fields(&json!({}), json!({ "a": 42, "b": true, "c": "  hi  " }).as_object().unwrap()).unwrap();
        assert_eq!(merged["a"], "42");
        assert_eq!(merged["b"], "true");
        assert_eq!(merged["c"], "hi");
    }

    #[test]
    fn a_null_push_clears_the_field() {
        let merged = merge_fields(&json!({ "a": "1" }), json!({ "a": null }).as_object().unwrap()).unwrap();
        assert!(merged.get("a").is_none());
    }

    #[test]
    fn bidi_overrides_never_survive_a_push() {
        let merged = merge_fields(&json!({}), json!({ "a": "safe\u{202e}evil" }).as_object().unwrap()).unwrap();
        assert_eq!(merged["a"], "safeevil");
    }

    #[test]
    fn structured_values_are_refused() {
        assert!(merge_fields(&json!({}), json!({ "a": { "nested": 1 } }).as_object().unwrap()).is_err());
    }

    #[test]
    fn field_names_are_normalised() {
        assert_eq!(clean_field_key("  My Field! "), Some("myfield".into()));
        assert_eq!(clean_field_key("!!!"), None);
    }

    #[test]
    fn the_field_bag_is_capped() {
        let mut existing = Map::new();
        for i in 0..MAX_FIELDS {
            existing.insert(format!("k{i}"), Value::String("v".into()));
        }
        let existing = Value::Object(existing);
        assert!(merge_fields(&existing, json!({ "k0": "changed" }).as_object().unwrap()).is_ok());
        assert!(merge_fields(&existing, json!({ "brand_new": "v" }).as_object().unwrap()).is_err());
    }
}
