use std::sync::{Arc, OnceLock};

use redis::aio::ConnectionManager;
use socketioxide::SocketIo;
use sqlx::PgPool;

use crate::config::Config;
use crate::services::attachment_crypto::AttachmentCipher;
use crate::services::cloudinary::Cloudinary;
use crate::services::image_moderation::ImageModeration;
use crate::services::push::Push;

/// Shared application state handed to both the axum routes and the Socket.IO
/// handlers. All fields are cheap to clone (Arc / pool handles).
#[derive(Clone)]
pub struct AppState {
    pub pool: PgPool,
    pub redis: ConnectionManager,
    pub config: Arc<Config>,
    /// None when Cloudinary is unconfigured; uploads then go to local disk.
    pub cloudinary: Option<Arc<Cloudinary>>,
    /// Present alongside Cloudinary; message attachments are encrypted with it.
    pub attachment_cipher: Option<Arc<AttachmentCipher>>,
    /// None when OPENAI_API_KEY is unset; images are then never flagged.
    pub image_moderation: Option<Arc<ImageModeration>>,
    /// None when neither Web Push nor FCM credentials are configured.
    pub push: Option<Arc<Push>>,
    io: Arc<OnceLock<SocketIo>>,
}

impl AppState {
    pub fn new(pool: PgPool, redis: ConnectionManager, config: Config) -> Self {
        let cloudinary = Cloudinary::from_config(&config).map(Arc::new);
        let attachment_cipher = AttachmentCipher::from_config(&config).map(Arc::new);
        let image_moderation = ImageModeration::from_config(&config).map(Arc::new);
        let push = Push::from_config(&config).map(Arc::new);
        AppState {
            pool,
            redis,
            config: Arc::new(config),
            cloudinary,
            attachment_cipher,
            image_moderation,
            push,
            io: Arc::new(OnceLock::new()),
        }
    }

    /// Called once, after the Socket.IO layer is built, so routes can emit.
    pub fn set_io(&self, io: SocketIo) {
        let _ = self.io.set(io);
    }

    /// Process-wide Socket.IO handle. Mirrors sockets/io.ts getIO().
    pub fn io(&self) -> &SocketIo {
        self.io.get().expect("Socket.IO server not initialized yet")
    }

    /// A fresh clone of the multiplexed redis connection for issuing commands.
    pub fn rd(&self) -> ConnectionManager {
        self.redis.clone()
    }
}
