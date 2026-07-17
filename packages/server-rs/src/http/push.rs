use axum::{
    extract::State,
    http::StatusCode,
    routing::{get, put},
    Json, Router,
};
use serde::{Deserialize, Serialize};

use crate::{
    error::AppError,
    http::AuthUser,
    services::push::{self, NewSubscription},
    state::AppState,
};

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct PushConfig {
    web_push_public_key: Option<String>,
    fcm_enabled: bool,
}

#[derive(Deserialize)]
struct DeleteSubscription {
    endpoint: String,
}

pub fn routes() -> Router<AppState> {
    Router::new()
        .route("/push/config", get(config))
        .route("/push/subscriptions", put(save).delete(remove))
}

async fn config(State(state): State<AppState>) -> Json<PushConfig> {
    Json(PushConfig {
        web_push_public_key: push::web_public_key(&state),
        fcm_enabled: state.push.as_ref().is_some_and(|p| p.fcm_enabled()),
    })
}

async fn save(
    State(state): State<AppState>,
    user: AuthUser,
    Json(sub): Json<NewSubscription>,
) -> Result<StatusCode, AppError> {
    let valid = match sub.kind.as_str() {
        "webpush" => {
            sub.p256dh.as_deref().is_some_and(|v| !v.is_empty())
                && sub.auth.as_deref().is_some_and(|v| !v.is_empty())
        }
        "fcm" => true,
        _ => false,
    };
    if !valid || sub.endpoint.is_empty() {
        return Err(AppError::BadRequest("Invalid push subscription".into()));
    }
    push::save_subscription(&state, &user.user_id, &sub).await?;
    Ok(StatusCode::NO_CONTENT)
}

async fn remove(
    State(state): State<AppState>,
    user: AuthUser,
    Json(body): Json<DeleteSubscription>,
) -> Result<StatusCode, AppError> {
    push::delete_subscription(&state, &user.user_id, &body.endpoint).await?;
    Ok(StatusCode::NO_CONTENT)
}
