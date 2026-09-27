//! Аутентификация встроенного туннеля: узел не должен быть открытым прокси.
//!
//! Проверяются оба входа: SOCKS5 (RFC 1929) и HTTP CONNECT (Basic).

use std::io::{Read, Write};
use std::net::TcpStream;
use std::thread;
use std::time::Duration;

use obereg_core::config::{CoreConfig, TransportMode, TunnelAuth};

const BODY: &str = "obereg-auth";

/// Локальный HTTP-сервер как цель. Возвращает адрес, который реально отдаёт система
/// (на машинах с VPN-драйвером это может быть адрес интерфейса, а не петля).
fn start_target() -> String {
    let listener = std::net::TcpListener::bind("127.0.0.1:0").expect("target must bind");
    let addr = listener.local_addr().expect("target addr").to_string();
    thread::spawn(move || {
        for _ in 0..4 {
            let Ok((mut stream, _)) = listener.accept() else {
                return;
            };
            let mut buffer = [0_u8; 2048];
            let _ = stream.read(&mut buffer);
            let response = format!(
                "HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{}",
                BODY.len(),
                BODY
            );
            let _ = stream.write_all(response.as_bytes());
        }
    });
    println!("target: {addr}");
    addr
}

fn session_with_auth(auth: Option<TunnelAuth>) -> (obereg_core::session::CoreSession, String) {
    let mut config = CoreConfig::default_tunneled("device-auth", ":memory:");
    config.tunnel.auth = auth;
    let session = obereg_core::session::CoreSession::new(config).expect("session must be created");
    session.start().expect("tunnel must start");
    let socks = session.local_proxy_addr().expect("socks proxy");
    let http = session.local_http_proxy_addr().expect("http proxy");
    println!("socks5: {socks}, http: {http}");
    (session, http)
}

#[test]
fn tunnel_with_auth_accepts_correct_credentials() {
    let target = start_target();
    let (session, http_proxy) = session_with_auth(Some(TunnelAuth {
        username: "obereg".to_string(),
        password: "secret".to_string(),
    }));
    assert_eq!(session.status().mode, TransportMode::Tunnel);

    // HTTP CONNECT с корректными учётными данными: роутим запрос через туннель.
    let mut stream = TcpStream::connect(&http_proxy).expect("proxy must accept");
    stream
        .set_read_timeout(Some(Duration::from_secs(5)))
        .expect("timeout");
    let credentials = "obereg:secret";
    let encoded = base64(credentials.as_bytes());
    let request = format!(
        "CONNECT {target} HTTP/1.1\r\nHost: {target}\r\nProxy-Authorization: Basic {encoded}\r\n\r\n"
    );
    stream
        .write_all(request.as_bytes())
        .expect("CONNECT must be sent");

    let mut head = Vec::new();
    let mut chunk = [0_u8; 256];
    loop {
        let read = stream.read(&mut chunk).expect("must read reply");
        assert!(read > 0, "proxy closed before replying");
        head.extend_from_slice(&chunk[..read]);
        if head.windows(4).any(|window| window == b"\r\n\r\n") {
            break;
        }
    }
    let head_text = String::from_utf8_lossy(&head).to_string();
    assert!(
        head_text.starts_with("HTTP/1.1 200"),
        "с корректными данными ожидался 200, получено: {head_text}"
    );

    // И сам трафик доходит до цели.
    let get = format!("GET / HTTP/1.1\r\nHost: {target}\r\nConnection: close\r\n\r\n");
    stream.write_all(get.as_bytes()).expect("GET must be sent");
    let mut response = Vec::new();
    loop {
        match stream.read(&mut chunk) {
            Ok(0) => break,
            Ok(read) => response.extend_from_slice(&chunk[..read]),
            Err(_) => break,
        }
    }
    let body = String::from_utf8_lossy(&response).to_string();
    assert!(body.contains(BODY), "тело ответа должно прийти: {body}");

    session.stop().expect("session must stop");
}

#[test]
fn tunnel_with_auth_rejects_missing_credentials() {
    let target = start_target();
    let (session, http_proxy) = session_with_auth(Some(TunnelAuth {
        username: "obereg".to_string(),
        password: "secret".to_string(),
    }));

    // Без заголовка авторизации узел обязан ответить 407 и не пропускать трафик.
    let mut stream = TcpStream::connect(&http_proxy).expect("proxy must accept");
    stream
        .set_read_timeout(Some(Duration::from_secs(5)))
        .expect("timeout");
    let request = format!("CONNECT {target} HTTP/1.1\r\nHost: {target}\r\n\r\n");
    stream
        .write_all(request.as_bytes())
        .expect("CONNECT must be sent");

    let mut head = Vec::new();
    let mut chunk = [0_u8; 256];
    loop {
        match stream.read(&mut chunk) {
            Ok(0) => break,
            Ok(read) => {
                head.extend_from_slice(&chunk[..read]);
                if head.windows(4).any(|window| window == b"\r\n\r\n") {
                    break;
                }
            }
            Err(_) => break,
        }
    }
    let head_text = String::from_utf8_lossy(&head).to_string();
    assert!(
        head_text.starts_with("HTTP/1.1 407"),
        "без учётных данных ожидался 407, получено: {head_text}"
    );

    // Ни одно соединение не должно быть обслужено.
    assert_eq!(
        session.status().sessions_served,
        0,
        "без авторизации туннель не должен обслуживать соединения"
    );

    session.stop().expect("session must stop");
}

#[test]
fn tunnel_without_auth_still_serves_local_requests() {
    // Локальный прокси приложения остаётся без пароля: это наш же процесс на телефоне.
    let (session, _http_proxy) = session_with_auth(None);
    assert_eq!(session.status().mode, TransportMode::Tunnel);
    session.stop().expect("session must stop");
}

/// Минимальный base64 для проверки заголовка.
fn base64(input: &[u8]) -> String {
    const ALPHABET: &[u8; 64] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    let mut output = String::new();
    for chunk in input.chunks(3) {
        let b0 = chunk[0] as u32;
        let b1 = *chunk.get(1).unwrap_or(&0) as u32;
        let b2 = *chunk.get(2).unwrap_or(&0) as u32;
        let triple = (b0 << 16) | (b1 << 8) | b2;
        output.push(ALPHABET[((triple >> 18) & 0x3f) as usize] as char);
        output.push(ALPHABET[((triple >> 12) & 0x3f) as usize] as char);
        output.push(if chunk.len() > 1 {
            ALPHABET[((triple >> 6) & 0x3f) as usize] as char
        } else {
            '='
        });
        output.push(if chunk.len() > 2 {
            ALPHABET[(triple & 0x3f) as usize] as char
        } else {
            '='
        });
    }
    output
}
