//! Разбор ответа видеосервиса и выбор потока для своего плеера.
//!
//! Фикстуры синтетические: структура повторяет настоящий ответ `player`
//! (`streamingData.formats` — потоки со звуком и видео, `adaptiveFormats` — раздельные),
//! но ссылки вымышленные. Так проверяется логика, а не доступность сети.

use obereg_core::extract::{analyze_player_response, parse_formats, select_muxed, ExtractOutcome};

fn response(formats: &str, adaptive: &str) -> String {
    format!(
        r#"{{
            "playabilityStatus": {{"status": "OK"}},
            "streamingData": {{
                "formats": [{formats}],
                "adaptiveFormats": [{adaptive}]
            }}
        }}"#
    )
}

const MUXED_360: &str = r#"{"itag":18,"mimeType":"video/mp4; codecs=\"avc1.42001E, mp4a.40.2\"","bitrate":500000,"qualityLabel":"360p","audioQuality":"AUDIO_QUALITY_LOW","url":"https://example.invalid/v/18"}"#;
const MUXED_720: &str = r#"{"itag":22,"mimeType":"video/mp4; codecs=\"avc1.64001F, mp4a.40.2\"","bitrate":2000000,"qualityLabel":"720p","audioQuality":"AUDIO_QUALITY_MEDIUM","url":"https://example.invalid/v/22"}"#;
const VIDEO_ONLY_1080: &str = r#"{"itag":137,"mimeType":"video/mp4; codecs=\"avc1.640028\"","bitrate":4000000,"qualityLabel":"1080p","url":"https://example.invalid/v/137"}"#;
const AUDIO_ONLY: &str = r#"{"itag":140,"mimeType":"audio/mp4; codecs=\"mp4a.40.2\"","bitrate":128000,"audioQuality":"AUDIO_QUALITY_MEDIUM","url":"https://example.invalid/a/140"}"#;

#[test]
fn picks_highest_muxed_stream_within_limit() {
    let outcome = analyze_player_response(&response(&format!("{MUXED_360},{MUXED_720}"), ""));
    match outcome {
        ExtractOutcome::Success { format, total } => {
            assert_eq!(format.itag, 22);
            assert_eq!(format.height(), 720);
            assert!(format.is_muxed());
            assert_eq!(total, 2);
        }
        other => panic!("ожидался Success, получено {other:?}"),
    }
}

#[test]
fn ignores_video_only_streams_when_muxed_exists() {
    let outcome = analyze_player_response(&response(
        MUXED_360,
        &format!("{VIDEO_ONLY_1080},{AUDIO_ONLY}"),
    ));
    match outcome {
        ExtractOutcome::Success { format, .. } => {
            assert_eq!(
                format.itag, 18,
                "нужен поток со звуком, а не 1080p без звука"
            );
        }
        other => panic!("ожидался Success, получено {other:?}"),
    }
}

#[test]
fn reports_when_only_adaptive_streams_exist() {
    let outcome =
        analyze_player_response(&response("", &format!("{VIDEO_ONLY_1080},{AUDIO_ONLY}")));
    assert_eq!(outcome, ExtractOutcome::NoMuxedStream { adaptive: 2 });
}

#[test]
fn reports_service_refusal() {
    let json = r#"{"playabilityStatus": {"status": "ERROR", "reason": "Видео недоступно"}}"#;
    match analyze_player_response(json) {
        ExtractOutcome::Refused { reason } => assert!(reason.contains("недоступно")),
        other => panic!("ожидался Refused, получено {other:?}"),
    }
}

#[test]
fn reports_missing_streaming_data() {
    let json = r#"{"playabilityStatus": {"status": "OK"}}"#;
    assert!(matches!(
        analyze_player_response(json),
        ExtractOutcome::Malformed { .. }
    ));
}

#[test]
fn reports_broken_json() {
    assert!(matches!(
        analyze_player_response("{not json}"),
        ExtractOutcome::Malformed { .. }
    ));
}

#[test]
fn skips_formats_without_url() {
    let json = r#"[{"itag":18,"mimeType":"video/mp4","qualityLabel":"360p","audioQuality":"AUDIO_QUALITY_LOW"}]"#;
    let value: serde_json::Value = serde_json::from_str(json).expect("fixture must parse");
    let formats = parse_formats(Some(&value)).expect("parse must work");
    assert!(formats.is_empty(), "поток без ссылки бесполезен");
}

#[test]
fn parses_audio_and_video_flags() {
    let json = format!("[{MUXED_720},{AUDIO_ONLY}]");
    let value: serde_json::Value = serde_json::from_str(&json).expect("fixture must parse");
    let formats = parse_formats(Some(&value)).expect("parse must work");
    assert_eq!(formats.len(), 2);
    assert!(formats[0].is_muxed());
    assert!(!formats[1].has_video);
    assert!(formats[1].has_audio);
    assert_eq!(formats[1].quality_label, None);
}

#[test]
fn selection_prefers_higher_bitrate_at_same_height() {
    let low = MUXED_360.replace("\"bitrate\":500000", "\"bitrate\":100000");
    let high = MUXED_360.replace("\"bitrate\":500000", "\"bitrate\":900000");
    let json = format!("[{low},{high}]");
    let value: serde_json::Value = serde_json::from_str(&json).expect("fixture must parse");
    let formats = parse_formats(Some(&value)).expect("parse must work");
    let chosen = select_muxed(&formats, 720).expect("должен быть выбран поток");
    assert_eq!(chosen.bitrate, 900000);
}

#[test]
fn selection_respects_height_limit() {
    let outcome = analyze_player_response(&response(&format!("{MUXED_360},{MUXED_720}"), ""));
    // Ограничение по умолчанию — 720p, поэтому максимальный из подходящих и берём.
    match outcome {
        ExtractOutcome::Success { format, .. } => assert_eq!(format.height(), 720),
        other => panic!("ожидался Success, получено {other:?}"),
    }
}

#[test]
fn outcome_serializes_for_bindings() {
    let outcome = analyze_player_response(&response(MUXED_360, ""));
    let json = obereg_core::extract::outcome_to_json(&outcome);
    assert!(json.contains("\"status\":\"success\""), "json: {json}");
    assert!(
        json.contains("\"url\":\"https://example.invalid/v/18\""),
        "json: {json}"
    );
}
