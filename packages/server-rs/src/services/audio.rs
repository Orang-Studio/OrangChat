
use std::io::Cursor;

use symphonia::core::codecs::CODEC_TYPE_NULL;
use symphonia::core::formats::FormatOptions;
use symphonia::core::io::MediaSourceStream;
use symphonia::core::meta::MetadataOptions;
use symphonia::core::probe::Hint;

use crate::error::{AppError, AppResult};

pub const MAX_SOUND_SECS: f64 = 3.0;

const TOLERANCE_SECS: f64 = 0.15;

pub fn probe_duration(bytes: Vec<u8>, extension: Option<&str>) -> AppResult<f64> {
    let source = MediaSourceStream::new(Box::new(Cursor::new(bytes)), Default::default());
    let mut hint = Hint::new();
    if let Some(ext) = extension {
        hint.with_extension(ext);
    }

    let probed = symphonia::default::get_probe()
        .format(
            &hint,
            source,
            &FormatOptions::default(),
            &MetadataOptions::default(),
        )
        .map_err(|_| AppError::BadRequest("Unsupported or corrupt audio file".into()))?;

    let mut format = probed.format;
    let track = format
        .tracks()
        .iter()
        .find(|t| t.codec_params.codec != CODEC_TYPE_NULL)
        .ok_or_else(|| AppError::BadRequest("That file has no audio track".into()))?;

    let track_id = track.id;
    let sample_rate = track
        .codec_params
        .sample_rate
        .ok_or_else(|| AppError::BadRequest("That file has no audio track".into()))?
        as f64;

    if let Some(frames) = track.codec_params.n_frames {
        return Ok(frames as f64 / sample_rate);
    }

    let mut frames: u64 = 0;
    while let Ok(packet) = format.next_packet() {
        if packet.track_id() == track_id {
            frames += packet.dur();
        }
    }
    Ok(frames as f64 / sample_rate)
}

pub fn require_short_enough(bytes: Vec<u8>, extension: Option<&str>) -> AppResult<f64> {
    let seconds = probe_duration(bytes, extension)?;
    if seconds <= 0.0 {
        return Err(AppError::BadRequest("That file has no audio in it".into()));
    }
    if seconds > MAX_SOUND_SECS + TOLERANCE_SECS {
        return Err(AppError::BadRequest(format!(
            "Sounds must be {MAX_SOUND_SECS:.0} seconds or shorter (that one is {seconds:.1}s)"
        )));
    }
    Ok(seconds)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn wav(seconds: f64) -> Vec<u8> {
        const RATE: u32 = 44_100;
        let frames = (RATE as f64 * seconds) as u32;
        let data_len = frames * 2;
        let mut out = Vec::with_capacity(44 + data_len as usize);
        out.extend(b"RIFF");
        out.extend((36 + data_len).to_le_bytes());
        out.extend(b"WAVEfmt ");
        out.extend(16u32.to_le_bytes());
        out.extend(1u16.to_le_bytes());
        out.extend(1u16.to_le_bytes());
        out.extend(RATE.to_le_bytes());
        out.extend((RATE * 2).to_le_bytes());
        out.extend(2u16.to_le_bytes());
        out.extend(16u16.to_le_bytes());
        out.extend(b"data");
        out.extend(data_len.to_le_bytes());
        out.extend(std::iter::repeat_n(0u8, data_len as usize));
        out
    }

    #[test]
    fn measures_duration() {
        let seconds = probe_duration(wav(1.0), Some("wav")).unwrap();
        assert!((seconds - 1.0).abs() < 0.01, "got {seconds}");
    }

    #[test]
    fn accepts_a_clip_at_the_limit() {
        assert!(require_short_enough(wav(3.0), Some("wav")).is_ok());
    }

    #[test]
    fn rejects_a_clip_over_the_limit() {
        assert!(require_short_enough(wav(30.0), Some("wav")).is_err());
    }

    #[test]
    fn rejects_a_file_that_is_not_audio() {
        assert!(require_short_enough(b"not audio at all".to_vec(), Some("wav")).is_err());
    }

    #[test]
    fn ignores_a_wrong_extension_hint() {
        let seconds = probe_duration(wav(1.0), Some("mp3")).unwrap();
        assert!((seconds - 1.0).abs() < 0.01, "got {seconds}");
    }
}
