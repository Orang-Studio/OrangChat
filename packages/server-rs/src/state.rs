use std::sync::{Arc, OnceLock};

use redis::aio::ConnectionManager;
use socketioxide::SocketIo;
use sqlx::PgPool;

use crate::config::Config;
use crate::http::i18n::I18nStore;
use crate::services::attachment_crypto::AttachmentCipher;
use crate::services::cloudinary::Cloudinary;
use crate::services::image_moderation::ImageModeration;
use crate::services::push::Push;

#[derive(Clone)]
pub struct AppState {
    pub pool: PgPool,
    pub redis: ConnectionManager,
    pub config: Arc<Config>,
    pub cloudinary: Option<Arc<Cloudinary>>,
    pub attachment_cipher: Option<Arc<AttachmentCipher>>,
    pub image_moderation: Option<Arc<ImageModeration>>,
    pub push: Option<Arc<Push>>,
    pub i18n: Arc<I18nStore>,
    io: Arc<OnceLock<SocketIo>>,
}

impl AppState {
    pub fn new(pool: PgPool, redis: ConnectionManager, config: Config) -> Self {
        let cloudinary = Cloudinary::from_config(&config).map(Arc::new);
        let attachment_cipher = AttachmentCipher::from_config(&config).map(Arc::new);
        let image_moderation = ImageModeration::from_config(&config).map(Arc::new);
        let push = Push::from_config(&config).map(Arc::new);
        let i18n = Arc::new(I18nStore::load(&config.i18n_dir));
        AppState {
            pool,
            redis,
            config: Arc::new(config),
            cloudinary,
            attachment_cipher,
            image_moderation,
            push,
            i18n,
            io: Arc::new(OnceLock::new()),
        }
    }

    pub fn set_io(&self, io: SocketIo) {
        let _ = self.io.set(io);
    }

    pub fn io(&self) -> &SocketIo {
        self.io.get().expect("Socket.IO server not initialized yet")
    }

    pub fn rd(&self) -> ConnectionManager {
        self.redis.clone()
    }
}
