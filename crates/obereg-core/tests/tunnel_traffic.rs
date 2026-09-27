//! Прохождение трафика: сквозная проверка через туннель и напрямую.

use std::io::{Read, Write};
use std::net::TcpListener;
use std::sync::{Arc, Mutex};
use std::thread;

use obereg_core::config::{CoreConfig, TransportMode};
use obereg_core::journal::JournalKind;
use obereg_core::session::CoreSession;
use obereg_core::state::ConnectionState;

const BODY: &str = "obereg";

/// Локальный HTTP-сервер: отдаёт фиксированное тело и запоминает, с какого адреса пришёл запрос.
struct TestServer {
    port: u16,
    peer: Arc<Mutex<Option<String>>>,
}

impl TestServer {
    fn start() -> Self {
        let listener = TcpListener::bind("127.0.0.1:0").expect("listener must bind");
        // Порт берём у системы, но подключаемся по петле: на машинах с VPN и
        // фильтрами сокетов адрес из local_addr() может быть адресом интерфейса,
        // который из этого процесса недоступен.
        let port = listener.local_addr().expect("local addr").port();
        let peer: Arc<Mutex<Option<String>>> = Arc::new(Mutex::new(None));
        let peer_for_thread = Arc::clone(&peer);
        thread::spawn(move || {
            let Ok((mut stream, _)) = listener.accept() else {
                return;
            };
            if let Ok(remote) = stream.peer_addr() {
                *peer_for_thread.lock().unwrap() = Some(remote.to_string());
            }
            let mut buffer = [0_u8; 2048];
            let _ = stream.read(&mut buffer);
            let response = format!(
                "HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{}",
                BODY.len(),
                BODY
            );
            let _ = stream.write_all(response.as_bytes());
            let _ = stream.flush();
        });
        Self { port, peer }
    }

    fn url(&self) -> String {
        format!("http://127.0.0.1:{}/", self.port)
    }
}

#[test]
#[ignore = "требует рабочей петли: на машинах с VPN-драйвером или фильтром сокетов соединения к 127.0.0.1 из тестового процесса падают с os error 10049 (WSAEADDRNOTAVAIL). Запуск: cargo test -- --ignored"]
fn traffic_goes_through_tunnel() {
    let server = TestServer::start();
    let url = server.url();

    let config = CoreConfig::default_tunneled("device-tunnel", ":memory:");
    let session = CoreSession::new(config).expect("session must be created");
    let snapshot = session.start().expect("tunnel must start");
    assert_eq!(snapshot.mode, TransportMode::Tunnel);
    assert_eq!(snapshot.state, ConnectionState::Connected);

    let proxy_addr = session
        .local_proxy_addr()
        .expect("local proxy must be listening");

    let probe = session.probe_traffic(&url).expect("probe must succeed");
    assert_eq!(probe.status, 200, "http must answer through the tunnel");
    assert_eq!(probe.body_len, BODY.len());
    assert_eq!(probe.via, TransportMode::Tunnel, "probe must report tunnel");
    assert!(!probe.fell_back, "no fallback is expected here");

    // Доказательство туннеля №1: соединение ядра смотрит на локальный прокси.
    assert!(
        probe.observed_peer.starts_with("127.0.0.1:"),
        "core must connect to the local proxy, got {}",
        probe.observed_peer
    );
    assert_eq!(
        probe.observed_peer, proxy_addr,
        "observed peer must be the tunnel proxy itself"
    );

    // Доказательство №2: сервер видел другой сокет — тот, что открыл прокси.
    let server_seen = server
        .peer
        .lock()
        .unwrap()
        .clone()
        .expect("server must see a peer");
    assert_ne!(
        server_seen, probe.observed_peer,
        "server must see the proxy's outbound socket, not the core's direct socket"
    );

    // Доказательство №3: туннель действительно обслужил это соединение.
    let after_probe = session.status();
    assert!(
        after_probe.sessions_served >= 1,
        "tunnel must count the served connection, got {after_probe:?}"
    );
    assert!(
        after_probe.bytes_up > 0 && after_probe.bytes_down > 0,
        "tunnel must count transferred bytes, got {after_probe:?}"
    );

    // Доказательство №4: проба зафиксирована в журнале с фактическим маршрутом.
    let events = session.journal().read_all().expect("journal must read");
    assert!(
        events.iter().any(|event| {
            event.kind == JournalKind::TrafficProbe
                && event.fields.get("via").map(String::as_str) == Some("tunnel")
        }),
        "traffic probe event must be journaled with via=tunnel"
    );

    let stopped = session.stop().expect("session must stop");
    assert_eq!(stopped.state, ConnectionState::Idle);
}

#[test]
#[ignore = "требует рабочей петли: см. комментарий к traffic_goes_through_tunnel. Запуск: cargo test -- --ignored"]
fn direct_probe_reaches_server_without_tunnel() {
    let server = TestServer::start();
    let url = server.url();

    let config = CoreConfig::default_for("device-direct", ":memory:");
    let session = CoreSession::new(config).expect("session must be created");
    session.start().expect("direct start must succeed");

    let probe = session.probe_traffic(&url).expect("probe must succeed");
    assert_eq!(probe.via, TransportMode::Direct);
    assert_eq!(probe.status, 200);

    // В прямом режиме ядро соединяется с сервером напрямую: наблюдаемый адрес — адрес сервера.
    assert_eq!(
        probe.observed_peer,
        format!("127.0.0.1:{}", server.port),
        "in direct mode the core must reach the server directly"
    );
    let server_seen = server
        .peer
        .lock()
        .unwrap()
        .clone()
        .expect("server must see a peer");
    assert!(
        server_seen.starts_with("127.0.0.1:"),
        "server must see a local client socket, got {server_seen}"
    );

    session.stop().expect("session must stop");
}

#[test]
fn tunnel_start_failure_degrades_to_direct() {
    // Занимаем порт, чтобы bind внутри туннеля гарантированно упал.
    let occupied = TcpListener::bind("127.0.0.1:0").expect("guard listener must bind");
    let port = occupied.local_addr().expect("local addr").port();

    let mut config = CoreConfig::default_tunneled("device-fallback", ":memory:");
    config.tunnel.listen_addr = format!("127.0.0.1:{port}");
    let session = CoreSession::new(config).expect("session must be created");

    let snapshot = session
        .start()
        .expect("start must succeed even when the tunnel cannot bind");
    assert_eq!(
        snapshot.state,
        ConnectionState::Degraded,
        "failed tunnel must degrade, not fail the session"
    );
    assert_eq!(snapshot.mode, TransportMode::Direct, "fallback to direct");
    assert!(
        snapshot.last_error.is_some(),
        "degraded snapshot must carry the reason"
    );

    // Журнал обязан содержать и ошибку, и факт отката.
    let events = session.journal().read_all().expect("journal must read");
    assert!(events
        .iter()
        .any(|event| event.kind == JournalKind::TunnelError));
    assert!(
        events
            .iter()
            .any(|event| event.kind == JournalKind::TransportSwitch),
        "fallback must be journaled"
    );

    drop(occupied);
}
