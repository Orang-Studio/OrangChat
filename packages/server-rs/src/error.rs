use axum::http::StatusCode;
use axum::response::{IntoResponse, Response};
use axum::Json;
use serde_json::json;

/// Application error, mapped to HTTP status codes the same way app.ts does.
#[derive(Debug, thiserror::Error)]
pub enum AppError {
    /// PermissionError in the TS server → 403.
    #[error("{0}")]
    Permission(String),
    /// UsernameTakenError → 409.
    #[error("{0}")]
    UsernameTaken(String),
    #[error("{0}")]
    BadRequest(String),
    #[error("{0}")]
    Unauthorized(String),
    /// Credentials were correct but the account has 2FA on and no valid code
    /// was supplied. Carries a `code` in the body so clients can tell this
    /// apart from a plain bad-password 401 and prompt for the second factor.
    #[error("{0}")]
    TwoFactorRequired(String),
    #[error("{0}")]
    NotFound(String),
    #[error("{0}")]
    Conflict(String),
    /// Carries the seconds until the window resets, sent both as a `Retry-After`
    /// header and in the body so socket acks can surface it too.
    #[error("{message}")]
    TooManyRequests { message: String, retry_after: u64 },
    #[error("{0}")]
    Internal(String),
}

impl AppError {
    pub fn status(&self) -> StatusCode {
        match self {
            AppError::Permission(_) => StatusCode::FORBIDDEN,
            AppError::UsernameTaken(_) | AppError::Conflict(_) => StatusCode::CONFLICT,
            AppError::BadRequest(_) => StatusCode::BAD_REQUEST,
            AppError::Unauthorized(_) | AppError::TwoFactorRequired(_) => StatusCode::UNAUTHORIZED,
            AppError::NotFound(_) => StatusCode::NOT_FOUND,
            AppError::TooManyRequests { .. } => StatusCode::TOO_MANY_REQUESTS,
            AppError::Internal(_) => StatusCode::INTERNAL_SERVER_ERROR,
        }
    }

    /// Message string used both for HTTP bodies and socket ack errors.
    pub fn message(&self) -> String {
        self.to_string()
    }
}

impl IntoResponse for AppError {
    fn into_response(self) -> Response {
        if let AppError::Internal(ref msg) = self {
            tracing::error!("request failed: {msg}");
        }
        let mut body = json!({ "error": self.to_string() });
        if let AppError::TwoFactorRequired(_) = self {
            body["code"] = json!("2fa_required");
        }
        if let AppError::TooManyRequests { retry_after, .. } = self {
            body["code"] = json!("rate_limited");
            body["retryAfter"] = json!(retry_after);
            return (
                self.status(),
                [(axum::http::header::RETRY_AFTER, retry_after.to_string())],
                Json(body),
            )
                .into_response();
        }
        (self.status(), Json(body)).into_response()
    }
}

impl From<sqlx::Error> for AppError {
    fn from(e: sqlx::Error) -> Self {
        AppError::Internal(format!("database error: {e}"))
    }
}

impl From<redis::RedisError> for AppError {
    fn from(e: redis::RedisError) -> Self {
        AppError::Internal(format!("redis error: {e}"))
    }
}

pub type AppResult<T> = Result<T, AppError>;
