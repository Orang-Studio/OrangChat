
use std::io::Cursor;
use std::path::PathBuf;

use axum::extract::{DefaultBodyLimit, Multipart, Query, State};
use axum::routing::post;
use axum::{Json, Router};
use image::codecs::gif::{GifDecoder, GifEncoder, Repeat};
use image::codecs::jpeg::JpegEncoder;
use image::imageops::FilterType;
use image::{AnimationDecoder, DynamicImage, Frame, ImageFormat};
use serde::Deserialize;
use serde_json::{json, Value};

use crate::error::{AppError, AppResult};
use crate::http::AuthUser;
use crate::ids::cuid;
use crate::services::rate_limit;
use crate::state::AppState;

const MAX_UPLOAD: usize = 12 * 1024 * 1024;
const MAX_FRAMES: usize = 300;

pub fn routes() -> Router<AppState> {
    Router::new()
        .route("/uploads/image", post(upload_image))
        .layer(DefaultBodyLimit::max(MAX_UPLOAD + 1024 * 1024))
}

fn uploads_dir() -> PathBuf {
    std::env::var("UPLOAD_DIR")
        .unwrap_or_else(|_| "/var/lib/orangchat/uploads".into())
        .into()
}

fn max_dim(kind: &str) -> (u32, u32) {
    match kind {
        "banner" => (1200, 480),
        "chat-background" => (1600, 900),
        "emoji" => (128, 128),
        "app-icon" => (256, 256),
        _ => (512, 512),
    }
}

#[derive(Deserialize)]
struct UploadQuery {
    kind: Option<String>,
}

async fn upload_image(
    user: AuthUser,
    Query(q): Query<UploadQuery>,
    State(state): State<AppState>,
    mut multipart: Multipart,
) -> AppResult<Json<Value>> {
    rate_limit::check(
        &state,
        "upload:image",
        &user.user_id,
        rate_limit::UPLOAD_IMAGE_PER_USER,
    )
    .await?;

    let kind = q.kind.unwrap_or_else(|| "avatar".into());

    let mut bytes: Option<Vec<u8>> = None;
    while let Some(field) = multipart
        .next_field()
        .await
        .map_err(|_| AppError::BadRequest("Invalid upload".into()))?
    {
        if field.name() == Some("file") {
            let data = field
                .bytes()
                .await
                .map_err(|_| AppError::BadRequest("Upload too large".into()))?;
            if data.len() > MAX_UPLOAD {
                return Err(AppError::BadRequest(
                    "Image is too large (max 12 MB)".into(),
                ));
            }
            bytes = Some(data.to_vec());
            break;
        }
    }
    let bytes = bytes.ok_or_else(|| AppError::BadRequest("No file provided".into()))?;

    let (out, ext) = tokio::task::spawn_blocking(move || process_image(&bytes, &kind))
        .await
        .map_err(|_| AppError::Internal("Image processing failed".into()))??;

    let url = store_image(&state, out, ext).await?;
    Ok(Json(json!({ "url": url })))
}

pub(crate) async fn store_image(state: &AppState, out: Vec<u8>, ext: &str) -> AppResult<String> {
    store_media(state, out, ext, "images", "image").await
}

pub(crate) async fn store_audio(state: &AppState, out: Vec<u8>, ext: &str) -> AppResult<String> {
    store_media(state, out, ext, "sounds", "video").await
}

async fn store_media(
    state: &AppState,
    out: Vec<u8>,
    ext: &str,
    folder: &str,
    resource_type: &str,
) -> AppResult<String> {
    let id = cuid();

    if let Some(cloudinary) = &state.cloudinary {
        let uploaded = cloudinary
            .upload(out, &format!("orangchat/{folder}/{id}"), resource_type)
            .await?;
        return Ok(uploaded.url);
    }

    let name = format!("{id}.{ext}");
    let dir = uploads_dir();
    tokio::fs::create_dir_all(&dir).await.ok();
    tokio::fs::write(dir.join(&name), &out)
        .await
        .map_err(|_| AppError::Internal("Failed to store upload".into()))?;

    Ok(format!("/uploads/{name}"))
}

fn resize_to_fit(img: DynamicImage, mw: u32, mh: u32) -> DynamicImage {
    if img.width() <= mw && img.height() <= mh {
        img
    } else {
        img.resize(mw, mh, FilterType::Lanczos3)
    }
}

pub(crate) fn process_image(bytes: &[u8], kind: &str) -> AppResult<(Vec<u8>, &'static str)> {
    let (mw, mh) = max_dim(kind);
    let format =
        image::guess_format(bytes).map_err(|_| AppError::BadRequest("Unsupported image".into()))?;

    if format == ImageFormat::Gif {
        let decoder = GifDecoder::new(Cursor::new(bytes))
            .map_err(|_| AppError::BadRequest("Invalid GIF".into()))?;
        let frames: Vec<Frame> = decoder
            .into_frames()
            .take(MAX_FRAMES + 1)
            .collect::<Result<_, _>>()
            .map_err(|_| AppError::BadRequest("Invalid GIF".into()))?;

        if frames.len() > 1 {
            let mut out = Vec::new();
            {
                let mut encoder = GifEncoder::new(&mut out);
                encoder.set_repeat(Repeat::Infinite).ok();
                for frame in frames.into_iter().take(MAX_FRAMES) {
                    let delay = frame.delay();
                    let resized =
                        resize_to_fit(DynamicImage::ImageRgba8(frame.into_buffer()), mw, mh);
                    let new_frame = Frame::from_parts(resized.to_rgba8(), 0, 0, delay);
                    encoder
                        .encode_frame(new_frame)
                        .map_err(|_| AppError::Internal("GIF encode failed".into()))?;
                }
            }
            if out.len() >= bytes.len() {
                return Ok((bytes.to_vec(), "gif"));
            }
            return Ok((out, "gif"));
        }
    }

    let img = image::load_from_memory(bytes)
        .map_err(|_| AppError::BadRequest("Could not read image".into()))?;
    let resized = resize_to_fit(img, mw, mh);
    let mut out = Vec::new();
    if resized.color().has_alpha() {
        resized
            .write_to(&mut Cursor::new(&mut out), ImageFormat::Png)
            .map_err(|_| AppError::Internal("PNG encode failed".into()))?;
        Ok((out, "png"))
    } else {
        let mut encoder = JpegEncoder::new_with_quality(&mut out, 82);
        encoder
            .encode_image(&resized)
            .map_err(|_| AppError::Internal("JPEG encode failed".into()))?;
        Ok((out, "jpg"))
    }
}
