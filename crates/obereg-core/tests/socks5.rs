//! Сырой SOCKS5-клиент против локального прокси ядра: контракт протокола,
//! по которому к туннелю подключаются оболочки и внешние инструменты.

use std::io::{Read, Write};
use std::net::{TcpListener, TcpStream};
use std::thread;

use obereg_core::config::CoreConfig;
use obereg_core::session::CoreSession;

/// Эхо-сервер: возвращает всё, что получил.
fn echo_server() -> String {
    let listener = TcpListener::bind("127.0.0.1:0").expect("echo listener must bind");
    let addr = listener.local_addr().expect("local addr").to_string();
    thread::spawn(move || {
        let Ok((mut stream, _)) = listener.accept() else {
            return;
        };
        let mut buffer = [0_u8; 1024];
        loop {
            match stream.read(&mut buffer) {
                Ok(0) | Err(_) => break,
                Ok(read) => {
                    if stream.write_all(&buffer[..read]).is_err() {
                        break;
                    }
                    let _ = stream.flush();
                }
            }
        }
    });
    addr
}

#[test]
fn raw_socks5_roundtrip_through_tunnel() {
    let echo_addr = echo_server();
    let (host, port_str) = echo_addr.rsplit_once(':').expect("host:port");
    let port: u16 = port_str.parse().expect("port");

    let session = CoreSession::new(CoreConfig::default_tunneled("device-socks", ":memory:"))
        .expect("session must be created");
    session.start().expect("tunnel must start");
    let proxy_addr = session
        .local_proxy_addr()
        .expect("local proxy must be listening");

    let mut stream = TcpStream::connect(&proxy_addr).expect("proxy must accept");

    // 1. Приветствие: версия 5, один метод — без аутентификации.
    stream
        .write_all(&[0x05, 0x01, 0x00])
        .expect("greeting must be sent");
    let mut method = [0_u8; 2];
    stream.read_exact(&mut method).expect("method reply");
    assert_eq!(method, [0x05, 0x00], "no-auth method must be selected");

    // 2. CONNECT к эхо-серверу в форме «домен».
    let mut request = vec![0x05_u8, 0x01, 0x00, 0x03, host.len() as u8];
    request.extend_from_slice(host.as_bytes());
    request.push((port >> 8) as u8);
    request.push((port & 0xff) as u8);
    stream
        .write_all(&request)
        .expect("connect request must be sent");

    let mut reply = [0_u8; 4];
    stream.read_exact(&mut reply).expect("connect reply");
    assert_eq!(reply[0], 0x05, "reply version");
    assert_eq!(
        reply[1], 0x00,
        "connect must succeed, reply code {}",
        reply[1]
    );
    let mut bind = [0_u8; 6];
    stream.read_exact(&mut bind).expect("bind address and port");

    // 3. Обмен данными через туннель.
    stream.write_all(b"ping").expect("write ping");
    let mut buffer = [0_u8; 4];
    stream.read_exact(&mut buffer).expect("read echo");
    assert_eq!(&buffer, b"ping");
    stream.write_all(b"pong").expect("write pong");
    let mut buffer2 = [0_u8; 4];
    stream.read_exact(&mut buffer2).expect("read echo 2");
    assert_eq!(&buffer2, b"pong");

    // 4. Счётчики трафика должны что-то насчитать.
    let snapshot = session.status();
    assert!(
        snapshot.bytes_up >= 4 && snapshot.bytes_down >= 4,
        "tunnel must count transferred bytes, got {snapshot:?}"
    );
    assert!(
        snapshot.sessions_served >= 1,
        "tunnel must count served sessions"
    );

    session.stop().expect("session must stop");
}
