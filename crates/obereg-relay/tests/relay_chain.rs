//! Сквозная проверка внешнего узла: клиент → локальный прокси → релей → цель.
//!
//! Тест запускает настоящий бинарник `obereg-relay` отдельным процессом, направляет
//! на него upstream клиента и проверяет, что запрос дошёл до цели и что релей посчитал
//! обслуженное соединение.

use std::io::{Read, Write};
use std::net::{TcpListener, TcpStream};
use std::path::PathBuf;
use std::process::{Child, Command, Stdio};
use std::thread;
use std::time::{Duration, Instant};

use obereg_core::config::{CoreConfig, TransportMode, UpstreamConfig, UpstreamKind};
use obereg_core::journal::{now_ms, JournalKind};
use obereg_core::session::CoreSession;

const BODY: &str = "obereg-through-relay";

/// Простой HTTP-сервер как «цель» за релеем.
fn start_target() -> String {
    let listener = TcpListener::bind("127.0.0.1:0").expect("target must bind");
    let addr = listener.local_addr().expect("target addr").to_string();
    thread::spawn(move || {
        // Обслуживаем несколько запросов: цепочка может подключаться дважды.
        for _ in 0..3 {
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
            let _ = stream.flush();
        }
    });
    addr
}

/// Свободный порт для релея.
fn free_port() -> u16 {
    let listener = TcpListener::bind("127.0.0.1:0").expect("probe bind");
    let port = listener.local_addr().expect("probe addr").port();
    drop(listener);
    port
}

struct RelayProcess {
    child: Child,
    status_path: PathBuf,
}

impl RelayProcess {
    fn start(port: u16, status_path: PathBuf) -> Self {
        let child = Command::new(env!("CARGO_BIN_EXE_obereg-relay"))
            .arg("--listen")
            .arg(format!("127.0.0.1:{port}"))
            .arg("--journal")
            .arg(":memory:")
            .arg("--status-file")
            .arg(&status_path)
            .stdout(Stdio::null())
            .stderr(Stdio::null())
            .spawn()
            .expect("relay must start");
        Self { child, status_path }
    }

    /// Дождаться, пока релей напишет статус и обслужит хотя бы одно соединение.
    fn wait_for(&self, predicate: impl Fn(&str) -> bool, timeout: Duration) -> String {
        let deadline = Instant::now() + timeout;
        loop {
            if let Ok(text) = std::fs::read_to_string(&self.status_path) {
                if predicate(&text) {
                    return text;
                }
            }
            if Instant::now() >= deadline {
                panic!(
                    "relay status timeout; last content: {:?}",
                    std::fs::read_to_string(&self.status_path).unwrap_or_default()
                );
            }
            thread::sleep(Duration::from_millis(100));
        }
    }
}

impl Drop for RelayProcess {
    fn drop(&mut self) {
        let _ = self.child.kill();
        let _ = self.child.wait();
    }
}

#[test]
fn traffic_reaches_target_through_relay() {
    let target = start_target();
    let status_path = std::env::temp_dir().join(format!("obereg-relay-status-{}.json", now_ms()));
    let relay_port = free_port();
    let relay = RelayProcess::start(relay_port, status_path);
    let relay_addr = format!("127.0.0.1:{relay_port}");

    // 1. Релей готов и слушает.
    relay.wait_for(
        |text| text.contains("\"listen_addr\""),
        Duration::from_secs(15),
    );

    // 2. Клиент направляет свой upstream на релей.
    let mut config = CoreConfig::default_tunneled("client-with-relay", ":memory:");
    config.tunnel.upstream = Some(UpstreamConfig {
        kind: UpstreamKind::Socks5,
        addr: relay_addr.clone(),
        username: None,
        password: None,
    });
    let session = CoreSession::new(config).expect("session must be created");
    let snapshot = session.start().expect("tunnel must start");
    assert_eq!(snapshot.mode, TransportMode::Tunnel);

    // 3. Запрос через всю цепочку: клиент → локальный прокси → релей → цель.
    let probe = session
        .probe_traffic(&format!("http://{target}/"))
        .expect("probe must succeed through the relay");
    assert_eq!(probe.status, 200, "цель должна ответить через релей");
    assert_eq!(probe.body_len, BODY.len());

    // 4. Релей действительно обслужил соединение.
    let status = relay.wait_for(
        |text| text.contains("\"sessions_served\":1") || !text.contains("\"sessions_served\":0"),
        Duration::from_secs(10),
    );
    assert!(
        status.contains("\"sessions_served\""),
        "в статусе релея должен быть счётчик: {status}"
    );
    assert!(
        !status.contains("\"sessions_served\":0"),
        "релей должен обслужить хотя бы одно соединение: {status}"
    );

    // 5. Журнал клиента фиксирует маршрут.
    let events = session.journal().read_all().expect("journal must read");
    assert!(
        events
            .iter()
            .any(|event| event.kind == JournalKind::TunnelStart),
        "запуск туннеля должен быть записан"
    );

    // 6. Клиентский SOCKS5-прокси остаётся локальным, а релей — внешним адресом upstream.
    let local = session.local_proxy_addr().expect("local proxy");
    assert!(local.starts_with("127.0.0.1:"), "локальный прокси: {local}");
    assert_ne!(
        local, relay_addr,
        "локальный прокси и релей — разные адреса"
    );

    session.stop().expect("session must stop");
    drop(relay);
}

#[test]
fn relay_prints_client_configuration() {
    let output = Command::new(env!("CARGO_BIN_EXE_obereg-relay"))
        .arg("--print-config")
        .arg("--listen")
        .arg("198.51.100.7:1080")
        .output()
        .expect("relay must run");
    assert!(output.status.success());
    let text = String::from_utf8_lossy(&output.stdout).to_string();
    assert!(
        text.contains("\"kind\":\"socks5\""),
        "подсказка должна содержать upstream socks5: {text}"
    );
    assert!(
        text.contains("198.51.100.7:1080"),
        "подсказка должна содержать адрес релея: {text}"
    );
}

/// Держим TCP-соединение живым, пока тест работает: проверка отсутствия утечки соединений.
#[allow(dead_code)]
fn keep_alive(addr: &str) -> TcpStream {
    TcpStream::connect(addr).expect("connect")
}
