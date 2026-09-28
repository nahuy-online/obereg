//! Извлечение потоков: разбор ответа видеосервиса.
//!
//! Логика разбора живёт в ядре, а не в оболочке, по двум причинам:
//! * её можно проверить нативно (`cargo test`), без Gradle и устройства;
//! * она переиспользуется на любой платформе — оболочка отвечает только за сам
//!   сетевой запрос (у неё уже есть HTTPS-клиент с маршрутом через туннель).
//!
//! Формат ответа: `streamingData.formats` — потоки, где видео и звук уже вместе;
//! `adaptiveFormats` — раздельные потоки (для них нужна склейка, её пока нет).

use serde::{Deserialize, Serialize};
use serde_json::Value;

use crate::error::{CoreError, Result};

/// Один доступный поток.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct StreamFormat {
    /// Идентификатор формата у видеосервиса.
    pub itag: i64,
    /// MIME-тип с кодеками.
    pub mime_type: String,
    /// Ссылка на поток.
    pub url: String,
    /// Метка качества («720p», «medium»).
    pub quality_label: Option<String>,
    /// Битрейт, бит/с.
    pub bitrate: i64,
    /// Есть ли звуковая дорожка.
    pub has_audio: bool,
    /// Есть ли видеодорожка.
    pub has_video: bool,
}

impl StreamFormat {
    /// Высота кадра из метки качества («720p» → 720).
    pub fn height(&self) -> u32 {
        self.quality_label
            .as_deref()
            .and_then(|label| {
                let digits: String = label.chars().take_while(|c| c.is_ascii_digit()).collect();
                digits.parse::<u32>().ok()
            })
            .unwrap_or(0)
    }

    /// Поток готов к воспроизведению сразу: видео и звук вместе.
    pub fn is_muxed(&self) -> bool {
        self.has_audio && self.has_video
    }
}

/// Итог разбора ответа.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "status", rename_all = "snake_case")]
pub enum ExtractOutcome {
    /// Найден поток, который можно отдать плееру сразу.
    Success {
        /// Выбранный поток.
        format: StreamFormat,
        /// Сколько всего потоков было в ответе.
        total: usize,
    },
    /// Разобрали, но подходящего потока нет: сервис отдал только раздельные.
    NoMuxedStream {
        /// Сколько раздельных потоков было в ответе.
        adaptive: usize,
    },
    /// Сервис отдал раздельно видео и звук — играем их вместе (склейка на лету плеером).
    SeparateStreams {
        /// Поток с видео (без звука).
        video: StreamFormat,
        /// Поток со звуком (без видео).
        audio: StreamFormat,
        /// Сколько всего потоков было в ответе.
        total: usize,
    },
    /// Сервис отказал (видео недоступно, ограничение региона и т. п.).
    Refused {
        /// Причина от сервиса.
        reason: String,
    },
    /// Ответ не разобран: новый формат, обрезанный ответ и т. п.
    Malformed {
        /// Что именно не получилось.
        reason: String,
    },
}

/// Максимальная высота, которую берём без склейки: выше — тяжело и не нужно для телефона.
pub const DEFAULT_MAX_HEIGHT: u32 = 720;

/// Разобрать массив потоков из ответа.
pub fn parse_formats(array: Option<&Value>) -> Result<Vec<StreamFormat>> {
    let Some(array) = array else {
        return Ok(Vec::new());
    };
    let items = array
        .as_array()
        .ok_or_else(|| CoreError::Config("streaming data: expected an array".into()))?;

    let mut formats = Vec::with_capacity(items.len());
    for item in items {
        let url = item.get("url").and_then(Value::as_str).unwrap_or_default();
        if url.is_empty() {
            continue; // без ссылки поток бесполезен (например, защищённый DRM)
        }
        let mime_type = item
            .get("mimeType")
            .and_then(Value::as_str)
            .unwrap_or_default()
            .to_string();
        // Как определяются дорожки:
        // * контейнер audio/* — точно только звук, что бы ни стояло в метке качества
        //   (у звуковых потоков бывает qualityLabel:"tiny");
        // * в готовом потоке «видео+звук» звук виден по аудиокодеку в MIME
        //   (например, codecs="avc1..., mp4a..."), поле audioQuality бывает не всегда;
        // * audioQuality — запасной признак звука, если MIME неполный.
        let audio_container = mime_type.starts_with("audio/");
        let audio_codec = mime_type.contains("mp4a")
            || mime_type.contains("opus")
            || mime_type.contains("vorbis")
            || mime_type.contains("ac-3")
            || mime_type.contains("ec-3");
        let has_audio = audio_container || audio_codec || item.get("audioQuality").is_some();
        let has_video = !audio_container && mime_type.contains("video/");
        formats.push(StreamFormat {
            itag: item.get("itag").and_then(Value::as_i64).unwrap_or(0),
            mime_type,
            url: url.to_string(),
            quality_label: item
                .get("qualityLabel")
                .and_then(Value::as_str)
                .map(str::to_string),
            bitrate: item.get("bitrate").and_then(Value::as_i64).unwrap_or(0),
            has_audio,
            has_video,
        });
    }
    Ok(formats)
}

/// Выбрать лучший поток, где видео и звук уже вместе.
pub fn select_muxed(formats: &[StreamFormat], max_height: u32) -> Option<StreamFormat> {
    let mp4: Vec<&StreamFormat> = formats
        .iter()
        .filter(|format| format.is_muxed() && format.mime_type.contains("video/mp4"))
        .collect();
    if mp4.is_empty() {
        return formats
            .iter()
            .filter(|format| format.is_muxed())
            .max_by_key(|format| format.bitrate)
            .cloned();
    }
    let within_limit: Vec<&&StreamFormat> = mp4
        .iter()
        .filter(|format| format.height() > 0 && format.height() <= max_height)
        .collect();
    let pool: Vec<&&StreamFormat> = if within_limit.is_empty() {
        mp4.iter().collect()
    } else {
        within_limit
    };
    pool.into_iter()
        .max_by_key(|format| (format.height(), format.bitrate))
        .map(|format| (*format).clone())
}

/// Выбрать пару «видео без звука» + «звук без видео» для совместного воспроизведения.
///
/// Нужна, когда сервис не отдаёт готовый поток: плеер умеет играть две дорожки
/// одновременно, поэтому внешний муксер не требуется.
pub fn select_adaptive_pair(
    formats: &[StreamFormat],
    max_height: u32,
) -> Option<(StreamFormat, StreamFormat)> {
    let video_only = |format: &&StreamFormat| {
        format.has_video && !format.has_audio && format.mime_type.contains("video/")
    };
    let video = formats
        .iter()
        .filter(video_only)
        .filter(|format| format.height() > 0 && format.height() <= max_height)
        .max_by_key(|format| (format.height(), format.bitrate))
        .or_else(|| {
            formats
                .iter()
                .filter(video_only)
                .max_by_key(|format| (format.height(), format.bitrate))
        })?;

    let audio = formats
        .iter()
        .filter(|format| format.has_audio && !format.has_video)
        .max_by_key(|format| format.bitrate)?;

    Some((video.clone(), audio.clone()))
}

/// Разобрать ответ сервиса целиком.
pub fn analyze_player_response(json: &str) -> ExtractOutcome {
    let root: Value = match serde_json::from_str(json) {
        Ok(value) => value,
        Err(error) => {
            return ExtractOutcome::Malformed {
                reason: format!("не разобран JSON: {error}"),
            }
        }
    };

    if let Some(status) = root.get("playabilityStatus") {
        let state = status.get("status").and_then(Value::as_str).unwrap_or("OK");
        if state != "OK" {
            let reason = status
                .get("reason")
                .and_then(Value::as_str)
                .unwrap_or(state)
                .to_string();
            return ExtractOutcome::Refused { reason };
        }
    }

    let Some(streaming) = root.get("streamingData") else {
        return ExtractOutcome::Malformed {
            reason: "в ответе нет блока streamingData".into(),
        };
    };

    let muxed = match parse_formats(streaming.get("formats")) {
        Ok(formats) => formats,
        Err(error) => {
            return ExtractOutcome::Malformed {
                reason: error.to_string(),
            }
        }
    };
    let adaptive = parse_formats(streaming.get("adaptiveFormats")).unwrap_or_default();

    match select_muxed(&muxed, DEFAULT_MAX_HEIGHT) {
        Some(format) => ExtractOutcome::Success {
            format,
            total: muxed.len() + adaptive.len(),
        },
        None => match select_adaptive_pair(&adaptive, DEFAULT_MAX_HEIGHT) {
            Some((video, audio)) => ExtractOutcome::SeparateStreams {
                video,
                audio,
                total: muxed.len() + adaptive.len(),
            },
            None => ExtractOutcome::NoMuxedStream {
                adaptive: adaptive.len(),
            },
        },
    }
}

/// Сериализовать итог для передачи в оболочку.
pub fn outcome_to_json(outcome: &ExtractOutcome) -> String {
    serde_json::to_string(outcome).unwrap_or_else(|_| "{\"status\":\"malformed\"}".to_string())
}
