//! Автооткат tunnel → direct: фактический маршрут должен совпадать с тем,
//! который сессия сообщает наружу.

use std::net::TcpListener;
use std::thread;

use obereg_core::config::TransportMode;
use obereg_core::transport::{DirectTransport, TransportRouter, TunnelTransport};

/// Порт, на котором заведомо никто не слушает (discard), — соединение обязано упасть.
const CLOSED_PORT: &str = "127.0.0.1:9";

#[test]
fn tunnel_failure_falls_back_and_reports_truthful_mode() {
    // Цель, к которой должен прийти трафик после отката.
    let server = TcpListener::bind("127.0.0.1:0").expect("server must bind");
    let server_addr = server.local_addr().expect("server addr");
    thread::spawn(move || {
        let _ = server.accept();
    });

    let tunnel = TunnelTransport::new();
    tunnel.set_proxy_addr(Some(CLOSED_PORT.to_string()));
    let direct = DirectTransport::with_probe_target(server_addr.to_string());
    let router = TransportRouter::new(direct, tunnel, TransportMode::Tunnel);

    let routed = router
        .connect(&server_addr.ip().to_string(), server_addr.port())
        .expect("fallback must still deliver the connection");

    assert_eq!(
        routed.mode,
        TransportMode::Direct,
        "фактический маршрут после отката — прямой"
    );
    assert!(routed.fell_back, "откат должен быть зафиксирован");
    assert!(
        routed.tunnel_error.is_some(),
        "причина отката должна сохраняться для журнала"
    );
    assert_eq!(
        router.active_mode(),
        TransportMode::Direct,
        "активный режим обязан совпадать с фактическим маршрутом"
    );
    assert_eq!(router.fallback_count(), 1, "откат должен быть посчитан");
}

#[test]
fn tunnel_mode_requires_running_tunnel() {
    let router = TransportRouter::new(
        DirectTransport::new(),
        TunnelTransport::new(),
        TransportMode::Direct,
    );

    assert!(
        router.set_mode(TransportMode::Tunnel).is_err(),
        "без поднятого туннеля режим tunnel включаться не должен"
    );
    assert_eq!(router.active_mode(), TransportMode::Direct);
}

#[test]
fn no_silent_fallback_when_auto_fallback_is_off() {
    let tunnel = TunnelTransport::new();
    tunnel.set_proxy_addr(Some(CLOSED_PORT.to_string()));
    let router = TransportRouter::new(DirectTransport::new(), tunnel, TransportMode::Tunnel);
    router.set_auto_fallback(false);

    assert!(
        router.connect("127.0.0.1", 80).is_err(),
        "при выключенном откате ошибка туннеля должна доходить до вызывающего"
    );
    assert_eq!(
        router.fallback_count(),
        0,
        "счётчик откатов не должен увеличиваться"
    );
}
