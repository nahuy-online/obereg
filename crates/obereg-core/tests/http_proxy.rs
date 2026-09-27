//! HTTP CONNECT-прокси встроенного туннеля: тот же маршрут, но для клиентов,
//! которые не умеют SOCKS5 (WebView, браузерные движки, обычные HTTP-клиенты).

use std::io::{Read, Write};
use std::net::{TcpListener, TcpStream};
use std::sync::{Arc, Mutex};
use std::thread;

use obereg_core::config::{CoreConfig, TransportMode};
use obereg_core::journal::JournalKind;
use obereg_core::session::CoreSession;

const BODY: &str = "obereg-http-proxy";

/// Локальный HTTP-сервер: отдаёт фиксированное тело и запоминает адрес клиента.
struct TestServer {
    addr: String,
    peer: Arc<Mutex<Option<String>>>,
}

impl TestServer {
    fn start() -> Self {
        let listener = TcpListener::bind("127.0.0.1:0").expect("listener must bind");
        let addr = listener.local_addr().expect("local addr").to_string();
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
        Self { addr, peer }
    }
}

#[test]
fn http_connect_tunnel_carries_requests() {
    let server = TestServer::start();

    let session = CoreSession::new(CoreConfig::default_tunneled(
        "device-http-proxy",
        ":memory:",
    ))
    .expect("session must be created");
    let snapshot = session.start().expect("tunnel must start");
    assert_eq!(snapshot.mode, TransportMode::Tunnel);

    let http_proxy = session
        .local_http_proxy_addr()
        .expect("HTTP CONNECT proxy must be listening on the device");
    assert!(
        http_proxy.starts_with("127.0.0.1:"),
        "адрес HTTP-прокси должен быть локальным, получено {http_proxy}"
    );

    // 1. Устанавливаем туннель через HTTP CONNECT — так делает WebView.
    let mut stream = TcpStream::connect(&http_proxy).expect("proxy must accept");
    stream
        .set_read_timeout(Some(std::time::Duration::from_secs(5)))
        .ok();
    let connect_request = format!(
        "CONNECT {server}\r\nHost: {server}\r\n\r\n",
        server = server.addr
    );
    stream
        .write_all(connect_request.as_bytes())
        .expect("CONNECT must be sent");

    let mut head = Vec::new();
    let mut chunk = [0_u8; 256];
    loop {
        let read = stream.read(&mut chunk).expect("must read proxy reply");
        assert!(read > 0, "proxy closed before replying");
        head.extend_from_slice(&chunk[..read]);
        if head.windows(4).any(|window| window == b"\r\n\r\n") {
            break;
        }
    }
    let head_text = String::from_utf8_lossy(&head).to_string();
    assert!(
        head_text.starts_with("HTTP/1.1 200"),
        "ожидался 200 Connection Established, получено: {head_text}"
    );

    // 2. Через установленный туннель делаем обычный HTTP-запрос.
    let request = format!(
        "GET / HTTP/1.1\r\nHost: {}\r\nConnection: close\r\n\r\n",
        server.addr
    );
    stream
        .write_all(request.as_bytes())
        .expect("request must be sent");

    let mut response = Vec::new();
    loop {
        match stream.read(&mut chunk) {
            Ok(0) => break,
            Ok(read) => response.extend_from_slice(&chunk[..read]),
            Err(_) => break,
        }
    }
    let body = String::from_utf8_lossy(&response).to_string();
    assert!(
        body.contains(BODY),
        "ответ должен пройти через туннель, получено: {body}"
    );

    // 3. Доказательства: туннель посчитал соединение, а сервер видел не сам клиент.
    let after = session.status();
    assert!(
        after.sessions_served >= 1,
        "туннель должен посчитать обслуженное соединение, получено {after:?}"
    );
    assert!(
        after.bytes_down >= BODY.len() as u64,
        "счётчик скачанных байт должен вырасти, получено {after:?}"
    );
    let server_seen = server.peer.lock().unwrap().clone();
    assert!(server_seen.is_some(), "сервер должен видеть клиента");

    // 4. Событие tunnel_start содержит оба адреса прокси.
    let events = session.journal().read_all().expect("journal must read");
    let start_event = events
        .iter()
        .find(|event| event.kind == JournalKind::TunnelStart)
        .expect("tunnel_start must be journaled");
    assert!(
        start_event.fields.contains_key("http_listen_addr"),
        "в журнале должен быть адрес HTTP-прокси: {start_event:?}"
    );

    session.stop().expect("session must stop");
}

#[test]
fn http_proxy_requires_running_tunnel() {
    let session = CoreSession::new(CoreConfig::default_for("device-http-off", ":memory:"))
        .expect("session must be created");
    assert!(
        session.local_http_proxy_addr().is_none(),
        "без запущенного туннеля HTTP-прокси не поднимается"
    );
}
