//! Контракт публичного API ядра: жизненный цикл сессии, конфигурация, версии.

use obereg_core::config::{CoreConfig, TransportMode};
use obereg_core::error::CoreError;
use obereg_core::session::CoreSession;
use obereg_core::state::ConnectionState;

#[test]
fn config_json_roundtrip_preserves_everything() {
    let mut config = CoreConfig::default_for("device-roundtrip", ":memory:");
    config.locale = "ru".to_string();
    config.tunnel.mode = TransportMode::Tunnel;
    config.tunnel.handshake_timeout_ms = 7_777;
    config.tunnel.idle_timeout_ms = 33_333;

    let json = config.to_json();
    let parsed = CoreConfig::from_json(&json).expect("config json must parse back");
    assert_eq!(parsed, config, "roundtrip must be lossless");
}

#[test]
fn config_rejects_unknown_schema_version() {
    let mut config = CoreConfig::default_for("device-old", ":memory:");
    config.config_version = CoreConfig::CURRENT_CONFIG_VERSION + 5;
    assert!(
        CoreConfig::from_json(&config.to_json()).is_err(),
        "config from the future must be rejected"
    );
}

#[test]
fn config_rejects_bad_listen_addr() {
    let mut config = CoreConfig::default_for("device-bad", ":memory:");
    config.tunnel.listen_addr = "not-an-address".to_string();
    assert!(CoreConfig::from_json(&config.to_json()).is_err());
}

#[test]
fn config_json_uses_stable_snake_case_names() {
    let config = CoreConfig::default_for("device-keys", ":memory:");
    let json = config.to_json();
    for key in [
        "\"config_version\"",
        "\"device_id\"",
        "\"locale\"",
        "\"log_level\"",
        "\"journal_path\"",
        "\"listen_addr\"",
        "\"handshake_timeout_ms\"",
        "\"idle_timeout_ms\"",
    ] {
        assert!(json.contains(key), "json must contain {key}: {json}");
    }
}

#[test]
fn session_lifecycle_direct_mode() {
    let session = CoreSession::new(CoreConfig::default_for("device-lifecycle", ":memory:"))
        .expect("session must be created");
    assert_eq!(session.status().state, ConnectionState::Idle);

    let snapshot = session.start().expect("start must succeed");
    assert!(
        matches!(
            snapshot.state,
            ConnectionState::Connected | ConnectionState::Degraded
        ),
        "direct start must end connected or degraded, got {snapshot:?}"
    );
    assert_eq!(snapshot.mode, TransportMode::Direct);

    let stopped = session.stop().expect("stop must succeed");
    assert_eq!(stopped.state, ConnectionState::Idle);
    assert!(
        matches!(session.stop(), Err(CoreError::NotRunning)),
        "second stop must report NotRunning"
    );
}

#[test]
fn double_start_is_rejected() {
    let session = CoreSession::new(CoreConfig::default_for("device-double", ":memory:"))
        .expect("session must be created");
    session.start().expect("first start must succeed");
    assert!(matches!(session.start(), Err(CoreError::AlreadyRunning)));
    session.stop().expect("stop must succeed");
}

#[test]
fn transport_mode_switch_before_start_only_records_desire() {
    let session = CoreSession::new(CoreConfig::default_for("device-desired", ":memory:"))
        .expect("session must be created");
    let snapshot = session
        .set_transport_mode(TransportMode::Direct)
        .expect("switching before start must not fail");
    assert_eq!(snapshot.state, ConnectionState::Idle);
    assert_eq!(session.desired_mode(), TransportMode::Direct);
}

#[test]
fn api_version_is_stable_and_exposed() {
    assert_eq!(CoreSession::version(), "0.1.0");
    assert_eq!(obereg_core::CORE_API_VERSION, "0.1.0");
    assert_eq!(obereg_core::CORE_API_MAJOR, 0);
}
